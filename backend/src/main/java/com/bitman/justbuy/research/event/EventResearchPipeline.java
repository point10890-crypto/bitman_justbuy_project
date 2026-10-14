package com.bitman.justbuy.research.event;

import com.bitman.justbuy.service.*;
import com.bitman.justbuy.research.ResearchOutcomeEvaluator;
import com.bitman.justbuy.research.ResearchLearningService;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Versioned public evidence research, independent of production pick rankings and member data. */
@Service
public class EventResearchPipeline implements AutoCloseable {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final String VERSION="event-research-v1";
    public interface Bars { List<ResearchOutcomeEvaluator.SessionBar> read(String code,LocalDate from,LocalDate to); }
    public record Ports(Supplier<DartSupplyContractSource.Snapshot> contracts,Supplier<KindBuybackSource.Snapshot> buybacks,
        Function<List<String>,DartFinancialEvidenceService.EvidenceSnapshot> financial,
        Function<List<String>,KisResearchEvidenceService.EvidenceBatch> kis,
        Function<List<String>,Map<String,Object>> kiwoom,Bars bars,Supplier<List<DartSupplyContractSource.Filing>> filings) {
        public Ports(Supplier<DartSupplyContractSource.Snapshot> contracts,Supplier<KindBuybackSource.Snapshot> buybacks,
            Function<List<String>,DartFinancialEvidenceService.EvidenceSnapshot> financial,Function<List<String>,KisResearchEvidenceService.EvidenceBatch> kis,
            Function<List<String>,Map<String,Object>> kiwoom,Bars bars){this(contracts,buybacks,financial,kis,kiwoom,bars,List::of);}
    }
    private final Ports ports;
    private final EventEvidenceVerifier verifier;
    private final EventResearchStore store;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;
    private final CashImpactCalculator calculator=new CashImpactCalculator();
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"event-research");t.setDaemon(true);return t;});
    private volatile String runId,lastError;
    private volatile Instant lastCompletedAt;
    private Supplier<AnalysisResponse> baseline=()->null;
    private BigDecimal roundTripCostPct;
    private boolean costsVerified;
    @Autowired(required=false) public void configureBaseline(AnalysisService service){baseline=()->service.getStoredPrecomputed("REVERSAL_EDGE");}
    @Autowired public void configureCost(@Value("${bitman.research.events.round-trip-cost-pct:}") String cost,@Value("${bitman.research.events.costs-verified:false}") boolean verified){
        try{BigDecimal value=new BigDecimal(cost);if(value.signum()>=0&&value.compareTo(BigDecimal.TEN)<=0){roundTripCostPct=value;costsVerified=verified;}}catch(RuntimeException ignored){}
    }
    @Autowired
    public EventResearchPipeline(DartSupplyContractSource source,KindBuybackSource buyback,DartFinancialEvidenceService financial,
        KisResearchEvidenceService kis,KiwoomReadOnlyService kiwoom,KisSourceGateway gateway,EventEvidenceVerifier verifier,ObjectMapper mapper,
        @Value("${bitman.storage.data-dir:./data}") String dataDir,@Value("${bitman.research.events.enabled:true}") boolean enabled) {
        this(new Ports(source::fetchRecent,buyback::fetchRecent,financial::fetchEvidence,kis::fetchEvidence,kiwoom::fetchEvidence,
            (code,from,to)->readBars(gateway,mapper,code,from,to),source::recentFilings),verifier,
            new EventResearchStore(Path.of(dataDir).resolve("event-research"),mapper),mapper,Clock.systemUTC(),enabled);
    }
    EventResearchPipeline(Ports ports,EventEvidenceVerifier verifier,EventResearchStore store,ObjectMapper mapper,Clock clock,boolean enabled) {
        this.ports=ports;this.verifier=verifier;this.store=store;this.mapper=mapper.copy().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);this.clock=clock;this.enabled=enabled;
        store.load().ifPresent(n->{runId=n.path("runId").asText(null);try{lastCompletedAt=Instant.parse(n.path("asOf").asText());}catch(RuntimeException ignored){}});
    }
    @Scheduled(initialDelayString="${bitman.research.events.initial-delay-ms:90000}",fixedDelayString="${bitman.research.events.refresh-ms:1800000}")
    public void scheduledRefresh(){requestRefresh();}
    public Map<String,Object> requestRefresh() {
        if(!enabled)return Map.of("accepted",false,"status","DISABLED","runId",Objects.toString(runId,""));
        if(!running.compareAndSet(false,true))return Map.of("accepted",false,"status","RUNNING","runId",Objects.toString(runId,""));
        runId=UUID.randomUUID().toString();String id=runId;
        try{worker.submit(()->{try{execute();}finally{running.set(false);}});}catch(RejectedExecutionException e){running.set(false);lastError="WORKER_UNAVAILABLE";return Map.of("accepted",false,"status","FAILED","runId",id);}
        return Map.of("accepted",true,"status","RUNNING","runId",id);
    }
    void refreshNow(){if(!running.compareAndSet(false,true))return;runId=UUID.randomUUID().toString();try{execute();}finally{running.set(false);}}
    public ObjectNode snapshot() {
        ObjectNode result=store.load().map(n->(ObjectNode)n).orElseGet(this::empty);
        Instant asOf=parseInstant(result.path("asOf").asText());boolean stale=lastError!=null||asOf==null||asOf.plusSeconds(5400).isBefore(clock.instant());
        result.put("stale",stale);if(lastError!=null)((ArrayNode)result.withArray("issues")).add(lastError);
        return result;
    }
    /** Metadata only: this public operational surface must never return stock/customer/source facts. */
    public Map<String,Object> status() {
        var snapshot=snapshot();var result=new LinkedHashMap<String,Object>();result.put("status",!enabled?"DISABLED":running.get()?"RUNNING":lastError!=null?"FAILED":snapshot.path("status").asText());
        result.put("runId",runId);result.put("running",running.get());result.put("lastCompletedAt",lastCompletedAt==null?null:lastCompletedAt.toString());result.put("lastError",lastError);
        result.put("asOf",snapshot.path("asOf").asText(null));result.put("candidateCount",snapshot.path("candidates").size());result.put("stale",snapshot.path("stale").asBoolean());
        result.put("issues",mapper.convertValue(snapshot.path("issues"),List.class));result.put("coverage",mapper.convertValue(snapshot.path("coverage"),Map.class));return result;
    }
    private void execute() {
        long deadline=System.nanoTime()+Duration.ofMinutes(4).toNanos();
        try {
            var source=ports.contracts().get();if(source==null||!Set.of("COMPLETE","PARTIAL","EMPTY").contains(source.status()))throw new IllegalStateException("CONTRACT_SOURCE_UNAVAILABLE");
            var issues=new LinkedHashSet<>(source.issues());List<DartSupplyContractSource.Contract> contracts=source.contracts().stream().filter(c->c!=null&&c.supplierCode()!=null&&c.supplierCode().matches("[0-9]{6}")
                &&c.receiptNo()!=null&&c.receiptNo().matches("[0-9]{14}")&&c.firstSeenAt()!=null&&!c.firstSeenAt().isAfter(clock.instant())).limit(12).toList();
            JsonNode lastCommitted=store.load().orElse(null);
            List<DartSupplyContractSource.Contract> relationshipEvidence=new ArrayList<>();
            // Fresh amendments/withdrawals must precede the bounded archive inspection.
            for(var c:contracts)relationshipEvidence.add(withFirstSeen(c,store.firstSeen(c.receiptNo(),c.firstSeenAt())));
            for(JsonNode c:store.relationshipHistory())if(!"CUSTOMER_EVENT_PROPAGATION".equals(c.path("origin").asText()))relationshipEvidence.add(relationshipContract(c));
            var graph=new EconomicLinkService();var links=graph.relationships(relationshipEvidence,clock.instant());
            List<DartSupplyContractSource.Filing> filings=new ArrayList<>();
            for(var f:ports.filings().get())filings.add(new DartSupplyContractSource.Filing(f.stockCode(),f.corpName(),f.receiptNo(),f.title(),f.filingDate(),store.firstSeen(f.receiptNo(),f.firstSeenAt()),f.sourceUrl()));
            var propagated=graph.propagate(links,filings,clock.instant()).stream().limit(4).toList();
            var requestedCodes=new LinkedHashSet<String>();contracts.forEach(c->requestedCodes.add(c.supplierCode()));propagated.forEach(p->requestedCodes.add(p.link().supplierCode()));
            List<String> codes=List.copyOf(requestedCodes);
            Map<String,DartFinancialEvidenceService.StockEvidence> finances=new LinkedHashMap<>();Map<String,KisResearchEvidenceService.CandidateEvidence> markets=new LinkedHashMap<>();Map<String,Object> kiwoom=new LinkedHashMap<>();
            for(int i=0;i<codes.size();i+=5) {
                budget(deadline);List<String> batch=codes.subList(i,Math.min(i+5,codes.size()));
                try{var f=ports.financial().apply(batch);if(f!=null&&f.stocks()!=null)for(var stock:f.stocks())if(batch.contains(stock.stockCode()))finances.put(stock.stockCode(),stock);}catch(RuntimeException e){issues.add("FINANCIAL_SOURCE_UNAVAILABLE");}
                budget(deadline);try{var k=ports.kis().apply(batch);if(k!=null&&k.candidates()!=null)for(var stock:k.candidates())if(batch.contains(stock.stockCode()))markets.put(stock.stockCode(),stock);}catch(RuntimeException e){issues.add("KIS_SOURCE_UNAVAILABLE");}
                budget(deadline);try{var k=ports.kiwoom().apply(batch);if(k!=null&&k.get("candidates") instanceof Map<?,?> candidates)for(String code:batch)if(candidates.get(code) instanceof Map<?,?> facts){var factsWithSource=new LinkedHashMap<String,Object>();facts.forEach((key,value)->factsWithSource.put(key.toString(),value));factsWithSource.put("source",k.get("source"));factsWithSource.put("environment",k.get("environment"));factsWithSource.put("status",k.get("status"));kiwoom.put(code,factsWithSource);}}catch(RuntimeException e){issues.add("KIWOOM_SOURCE_UNAVAILABLE");}
            }
            long financeAvailable=finances.values().stream().filter(f->f.facts()!=null&&"KRW".equals(f.facts().currency())&&f.facts().revenue()!=null).count();
            long kisAvailable=markets.values().stream().filter(m->"AVAILABLE".equals(m.valuation().get("status"))).count();
            if(financeAvailable<codes.size())issues.add("FINANCIAL_COVERAGE_PARTIAL");if(kisAvailable<codes.size())issues.add("KIS_PRICE_COVERAGE_PARTIAL");
            Instant cutoff=clock.instant();ArrayNode candidates=mapper.createArrayNode();var aiBudget=new AiRunBudget();
            for(var original:contracts) {
                budget(deadline);Instant first=store.firstSeen(original.receiptNo(),original.firstSeenAt());var contract=withFirstSeen(original,first);
                var financial=finances.get(contract.supplierCode());String financialReceipt=financial==null||financial.facts()==null?"UNKNOWN":financial.facts().receiptNo();
                String id=hash(VERSION+":"+contract.receiptNo()+":"+contract.documentHash()+":"+financialReceipt);
                ObjectNode prior=store.caseById(id).map(n->(ObjectNode)n).orElse(null);
                if(prior!=null) {
                    prior.set("currentMarket",marketContext(contract.supplierCode(),markets,kiwoom));
                    retryVerification(prior,contract,aiBudget);
                    candidates.add(prior);continue; // Facts/scenarios and original price context remain frozen.
                }
                var calculation=calculator.calculate(contract,financial,cutoff);
                var checked=checkVerification(contract,calculation,cutoff,0,aiBudget);
                var candidate=mapper.createObjectNode();candidate.put("caseId",id);candidate.put("stockCode",contract.supplierCode());candidate.put("stockName",contract.supplierName());
                candidate.put("customerName",contract.customerName());candidate.put("product",contract.product());candidate.put("contractAmountWon",decimal(contract.amountWon()));candidate.put("contractDeltaWon",decimal(calculation.incrementalContractAmountWon()));
                candidate.put("sourceUrl",contract.sourceUrl());candidate.put("receiptNo",contract.receiptNo());candidate.put("firstSeenAt",first.toString());candidate.put("publishedAt",cutoff.toString());candidate.put("filingDate",Objects.toString(contract.filingDate(),null));
                candidate.put("amendmentStatus",contract.amendmentStatus());candidate.put("evidenceText",contract.evidenceText());candidate.put("documentHash",contract.documentHash());candidate.put("policyVersion",VERSION);
                candidate.put("contractStartDate",Objects.toString(contract.startDate(),null));candidate.put("contractEndDate",Objects.toString(contract.endDate(),null));
                candidate.set("financial",exactNumbers(mapper.valueToTree(financial)));candidate.set("scenario",exactNumbers(mapper.valueToTree(calculation)));
                writeVerification(candidate,checked);
                var market=marketContext(contract.supplierCode(),markets,kiwoom);candidate.set("market",market);candidate.set("currentMarket",market.deepCopy());
                candidate.put("publicationPolicy","INFORMATIONAL_OBSERVATION");candidate.put("profitabilityProven",false);candidates.add(candidate);
                candidate.put("origin","SUPPLIER_CONTRACT");
            }
            for(var propagation:propagated) {
                var link=propagation.link();var event=propagation.event();
                String id=hash(VERSION+":propagation:"+event.receiptNo()+":"+link.receiptNo());
                var existing=store.caseById(id);if(existing.isPresent()){var candidate=(ObjectNode)existing.get();candidate.set("currentMarket",marketContext(link.supplierCode(),markets,kiwoom));candidates.add(candidate);continue;}
                var candidate=mapper.createObjectNode().put("caseId",id).put("stockCode",link.supplierCode()).put("stockName",link.supplierName()).put("customerName",link.customerName()).put("product",link.product());
                candidate.putNull("contractAmountWon");candidate.putNull("contractDeltaWon");candidate.put("sourceUrl",link.sourceUrl());candidate.put("receiptNo",link.receiptNo());candidate.put("firstSeenAt",event.firstSeenAt().toString());
                candidate.put("relationshipKnownAt",link.knownAt().toString());candidate.put("publishedAt",cutoff.toString());candidate.put("filingDate",Objects.toString(event.filingDate(),null));candidate.put("amendmentStatus","CUSTOMER_EVENT");candidate.put("evidenceText","공급관계는 기존 공급계약 원문으로 확인됩니다. 새 고객 공시는 공급 물량이나 이익 증가를 증명하지 않습니다.");
                candidate.set("financial",exactNumbers(mapper.valueToTree(finances.get(link.supplierCode()))));var scenario=candidate.putObject("scenario").put("status","UNKNOWN");scenario.putNull("cashFlowWon");scenario.putNull("operatingProfitWon");scenario.putNull("recognizedRevenueWon");scenario.putArray("issues").add("CUSTOMER_EVENT_ALLOCATION_UNKNOWN");
                var verification=candidate.putObject("verification").put("status","RULE_ONLY");verification.putArray("risks").add("CUSTOMER_EVENT_ALLOCATION_UNKNOWN");verification.putArray("counterArguments").add("고객사 사건이 해당 제품의 실제 주문 변화로 이어지는지 추가 원문이 필요합니다.");verification.putArray("invalidationConditions").add("MISSING_SOURCE_EVIDENCE");
                var market=marketContext(link.supplierCode(),markets,kiwoom);candidate.set("market",market);candidate.set("currentMarket",market.deepCopy());
                var related=candidate.putObject("relatedCustomerEvent").put("receiptNo",event.receiptNo()).put("title",event.title()).put("sourceUrl",event.sourceUrl()).put("firstSeenAt",event.firstSeenAt().toString()).put("filingDate",Objects.toString(event.filingDate(),null)).put("status",propagation.status());related.set("issues",mapper.valueToTree(propagation.issues()));
                candidate.put("origin","CUSTOMER_EVENT_PROPAGATION").put("publicationPolicy","INFORMATIONAL_OBSERVATION").put("profitabilityProven",false).put("policyVersion",VERSION);candidates.add(candidate);
            }
            budget(deadline);KindBuybackSource.Snapshot buybacks;
            try{buybacks=ports.buybacks().get();}catch(RuntimeException e){buybacks=new KindBuybackSource.Snapshot("UNAVAILABLE",clock.instant(),List.of(),List.of("KIND_SOURCE_UNAVAILABLE"));}
            // Mature evaluations are collected only after the real close. Future prices remain absent.
            for(JsonNode node:candidates)updateOutcomes((ObjectNode)node,deadline);
            ObjectNode result=empty();result.put("status",candidates.isEmpty()?"EMPTY":"COMPLETE".equals(source.status())&&issues.isEmpty()?"COMPLETE":"PARTIAL");result.put("asOf",clock.instant().toString());result.put("runId",runId);
            if(lastCommitted!=null)for(String field:List.of("evaluationCursor","portfolioEvaluationCursor"))if(lastCommitted.path(field).canConvertToInt()&&lastCommitted.path(field).asInt()>=0)result.set(field,lastCommitted.path(field).deepCopy());
            result.set("candidates",candidates);result.set("buybacks",mapper.valueToTree(buybacks));result.set("issues",mapper.valueToTree(issues));
            var coverage=result.putObject("coverage");coverage.put("contracts",contracts.size());coverage.put("namedRelationships",contracts.stream().filter(c->c.customerName()!=null&&!c.customerName().isBlank()).count());
            coverage.put("financialReferences",financeAvailable);coverage.put("kisCandidates",kisAvailable);coverage.put("kiwoomCandidates",kiwoom.size());coverage.put("maxContracts",12);coverage.put("aiCallsMax",2);coverage.put("aiChecked",candidates.findValuesAsText("status").stream().filter("AI_CHECKED"::equals).count());
            coverage.put("rankingsPromoted",false);coverage.put("futureProfitabilityMeasured",false);coverage.put("kindRows",buybacks==null?0:buybacks.rows().size());
            coverage.put("economicLinks",links.size());coverage.put("customerFilings",filings.size());coverage.put("propagationObservations",propagated.size());
            coverage.put("historyWindowLimited",store.historyWindowLimited());coverage.put("aiProviderCalls",aiBudget.providerCalls);coverage.put("aiAttemptsMaxPerCase",3);
            store.save(result);updateArchived(candidates,deadline);updateComparisons(candidates,markets,deadline);lastCompletedAt=clock.instant();lastError=null;
        }catch(RuntimeException e){lastError=Set.of("CONTRACT_SOURCE_UNAVAILABLE","RESEARCH_BUDGET_EXCEEDED","RESEARCH_ATOMIC_WRITE_FAILED").contains(Objects.toString(e.getMessage(),""))?e.getMessage():"RESEARCH_REFRESH_FAILED";}
    }
    private ObjectNode marketContext(String code,Map<String,KisResearchEvidenceService.CandidateEvidence> markets,Map<String,Object> kiwoom) {
        var market=mapper.createObjectNode();market.set("kis",exactNumbers(mapper.valueToTree(markets.get(code))));market.set("kiwoom",exactNumbers(mapper.valueToTree(kiwoom.get(code))));return market;
    }
    private static final class AiRunBudget {private int providerCalls;}
    private record VerificationCheck(EventEvidenceVerifier.Verdict verdict,int providerAttempts,boolean providerAttempted) {}
    private VerificationCheck checkVerification(DartSupplyContractSource.Contract contract,CashImpactCalculator.Result calculation,Instant cutoff,int attempts,AiRunBudget budget) {
        var input=verifier.input(contract,calculation,cutoff);
        if(budget.providerCalls>=2)return new VerificationCheck(verifier.ruleOnly(input,"RUN_AI_BUDGET"),attempts,false);
        var verdict=verifier.verify(input);boolean attempted=providerAttempted(verdict.issues());
        if(attempted)budget.providerCalls++;
        return new VerificationCheck(verdict,attempts+(attempted?1:0),attempted);
    }
    private static boolean providerAttempted(List<String> issues) {
        // These verifier exits occur before executor.submit; all ambiguous failures count conservatively.
        return issues==null||issues.stream().noneMatch(Set.of("AI_UNAVAILABLE","INVALID_EVIDENCE_INPUT","INPUT_TOO_LARGE","AI_BUSY","RUN_AI_BUDGET")::contains);
    }
    private void writeVerification(ObjectNode candidate,VerificationCheck checked) {
        var verdict=checked.verdict();var verification=candidate.putObject("verification").put("status",verdict.status()).put("provider",verdict.provider()).put("verdict",verdict.verdict());
        verification.set("claims",exactNumbers(mapper.valueToTree(verdict.claims())));verification.set("risks",mapper.valueToTree(verdict.risks()));verification.set("counterArguments",mapper.valueToTree(counterArguments(verdict.risks())));
        verification.set("invalidationConditions",mapper.valueToTree(List.of(verdict.invalidation())));verification.set("issues",mapper.valueToTree(verdict.issues()));
        verification.put("providerAttempts",checked.providerAttempts()).put("retryExhausted",checked.providerAttempts()>=3&&!"AI_CHECKED".equals(verdict.status()));
        if(checked.providerAttempted())verification.put("lastProviderAttemptAt",clock.instant().toString());
        if("AI_CHECKED".equals(verdict.status()))verification.put("checkedAt",clock.instant().toString());
    }
    private void retryVerification(ObjectNode candidate,DartSupplyContractSource.Contract contract,AiRunBudget budget) {
        JsonNode prior=candidate.path("verification");if(!"RULE_ONLY".equals(prior.path("status").asText()))return;
        List<String> issues=new ArrayList<>();prior.path("issues").forEach(n->issues.add(n.asText()));
        Set<String> retryable=Set.of("AI_UNAVAILABLE","AI_BUSY","AI_TIMEOUT","AI_RESULT_UNAVAILABLE","AI_RESULT_INVALID","AI_CLAIMS_INVALID","AI_INTERRUPTED","RUN_AI_BUDGET");
        if(issues.stream().noneMatch(retryable::contains))return;
        int attempts=prior.has("providerAttempts")?Math.max(0,prior.path("providerAttempts").asInt()):providerAttempted(issues)?1:0;
        if(attempts>=3){((ObjectNode)prior).put("retryExhausted",true);return;}
        Instant cutoff=parseInstant(candidate.path("publishedAt").asText());if(cutoff==null||cutoff.isAfter(clock.instant()))return;
        try {
            var calculation=mapper.treeToValue(candidate.path("scenario"),CashImpactCalculator.Result.class);var observed=calculation.observed();
            if(observed==null||!contract.supplierCode().equals(observed.supplierCode())||!contract.receiptNo().equals(observed.receiptNo())
                ||!Objects.equals(contract.amendmentStatus(),observed.amendmentStatus())||!Objects.equals(contract.startDate(),observed.startDate())||!Objects.equals(contract.endDate(),observed.endDate())
                ||!Objects.equals(contract.firstSeenAt(),observed.firstSeenAt())||!sameDecimal(contract.amountWon(),observed.contractAmountWon())||!sameDecimal(contract.previousAmountWon(),observed.previousAmountWon()))return;
            writeVerification(candidate,checkVerification(contract,calculation,cutoff,attempts,budget));
        }catch(Exception ignored){} // A damaged stored packet cannot be repaired using future financial evidence.
    }
    private static boolean sameDecimal(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null&&a.compareTo(b)==0;}
    private void updateArchived(ArrayNode visible,long deadline) {
        Set<String> current=new HashSet<>();visible.forEach(c->current.add(c.path("caseId").asText()));
        List<JsonNode> archived=store.cases().stream().filter(c->!current.contains(c.path("caseId").asText())).toList();if(archived.isEmpty())return;
        int start=store.load().map(n->n.path("evaluationCursor").asInt()).orElse(0)%archived.size();
        int visited=0;for(;visited<Math.min(4,archived.size());visited++) {
            if(System.nanoTime()+Duration.ofSeconds(13).toNanos()>deadline)break;
            ObjectNode candidate=(ObjectNode)archived.get((start+visited)%archived.size()).deepCopy();updateOutcomes(candidate,deadline);store.updateCase(candidate);
        }
        ObjectNode latest=store.load().map(n->(ObjectNode)n).orElse(null);if(latest!=null){latest.put("evaluationCursor",(start+visited)%archived.size());store.save(latest);}
    }
    private void updateComparisons(ArrayNode candidates,Map<String,KisResearchEvidenceService.CandidateEvidence> markets,long deadline) {
        Instant now=clock.instant();var local=now.atZone(KST);String baselineId="existing-swing-v1";
        String captureStatus="WAITING_FOR_AFTER_CLOSE_CAPTURE";
        if(local.toLocalTime().compareTo(LocalTime.of(15,30))>=0&&KoreanMarketCalendar.isTradingDay(local.toLocalDate())) {
            AnalysisResponse old=null;try{old=baseline.get();}catch(RuntimeException ignored){}
            Instant baselineAt=old==null?null:parseInstant(old.updatedAt());
            if(old!=null&&old.stockPicks()!=null&&baselineAt!=null&&!baselineAt.isAfter(now)&&baselineAt.atZone(KST).toLocalDate().equals(local.toLocalDate())) {
                List<String> baselineCodes=old.stockPicks().stream().map(p->p.code()).filter(c->c!=null&&c.matches("[0-9]{6}")).distinct().limit(3).toList();
                List<String> challengerCodes=new ArrayList<>();for(JsonNode c:candidates)try {
                    String code=c.path("stockCode").asText();var fresh=markets.get(code);
                    if(c.path("customerName").asText("").isBlank()||!Set.of("PARTIAL","AVAILABLE").contains(c.path("scenario").path("status").asText())||new BigDecimal(c.path("scenario").path("operatingProfitWon").path("upper").asText("0")).signum()<=0||fresh==null||!"AVAILABLE".equals(fresh.valuation().get("status")))continue;
                    if(!challengerCodes.contains(code))challengerCodes.add(code);if(challengerCodes.size()==3)break;
                }catch(RuntimeException ignored){}
                if(!baselineCodes.isEmpty()&&!challengerCodes.isEmpty()) {
                    var capture=mapper.createObjectNode().put("captureId",hash(local.toLocalDate()+":"+VERSION)).put("decisionAt",now.toString()).put("decisionDate",local.toLocalDate().toString()).put("baselinePolicyId",baselineId).put("challengerPolicyId",VERSION).put("inputVersion",VERSION);
                    capture.put("sourceBaselineAt",baselineAt.toString());capture.set("baselineWeights",exactNumbers(mapper.valueToTree(equalWeights(baselineCodes))));capture.set("challengerWeights",exactNumbers(mapper.valueToTree(equalWeights(challengerCodes))));capture.put("roundTripCostPct",decimal(roundTripCostPct));capture.put("costsVerified",costsVerified);capture.putArray("outcomes");
                    store.capturePortfolio(capture);captureStatus="CAPTURED";
                }else captureStatus="SELECTION_EVIDENCE_INSUFFICIENT";
            }else captureStatus="CURRENT_BASELINE_UNAVAILABLE";
        }
        List<ResearchLearningService.Observation> learning=new ArrayList<>();Map<LocalDate,Set<String>> expected=new LinkedHashMap<>();int mature=0;JsonNode latestOutcomes=mapper.createArrayNode();
        List<JsonNode> portfolioCaptures=store.portfolios();int cursor=portfolioCaptures.isEmpty()?0:store.load().map(n->n.path("portfolioEvaluationCursor").asInt()).orElse(0)%portfolioCaptures.size();
        Set<String> selectedCaptures=new HashSet<>();int advanced=0;
        for(;advanced<portfolioCaptures.size()&&selectedCaptures.size()<2;advanced++) {
            JsonNode capture=portfolioCaptures.get((cursor+advanced)%portfolioCaptures.size());
            long completed=0;for(JsonNode outcome:capture.path("outcomes"))if(Set.of("COMPLETE","GROSS_ONLY").contains(outcome.path("state").asText()))completed++;
            if(completed<4)selectedCaptures.add(capture.path("decisionDate").asText());
        }
        for(JsonNode node:portfolioCaptures) {
            ObjectNode capture=(ObjectNode)node.deepCopy();LocalDate date=LocalDate.parse(capture.path("decisionDate").asText());var base=new EventResearchPortfolioEvaluator.Selection(baselineId,weights(capture.path("baselineWeights")));var challenger=new EventResearchPortfolioEvaluator.Selection(VERSION,weights(capture.path("challengerWeights")));
            expected.put(date,Set.of(EventResearchPortfolioEvaluator.INSTRUMENT));
            LocalDate entry=KoreanMarketCalendar.nextTradingDay(date);if(entry==null||now.isBefore(entry.atTime(15,30).atZone(KST).toInstant())||!selectedCaptures.contains(date.toString())||System.nanoTime()+Duration.ofSeconds(13).toNanos()>deadline) {
                latestOutcomes=capture.path("outcomes");mature+=appendStoredLearning(capture,base,challenger,learning,now);continue;
            }
            var union=new LinkedHashSet<>(base.weights().keySet());union.addAll(challenger.weights().keySet());Map<String,List<ResearchOutcomeEvaluator.SessionBar>> bars=new LinkedHashMap<>();
            for(String code:union){if(System.nanoTime()+Duration.ofSeconds(13).toNanos()>deadline)break;try{bars.put(code,ports.bars().read(code,entry,local.toLocalDate()));}catch(RuntimeException ignored){}}
            JsonNode previousOutcomes=capture.path("outcomes").deepCopy();ArrayNode comparisons=capture.putArray("outcomes");var evaluator=new ResearchOutcomeEvaluator();List<LocalDate> calendar=new ArrayList<>();for(LocalDate d=date;d.isBefore(date.plusDays(60));d=d.plusDays(1))if(KoreanMarketCalendar.isTradingDay(d))calendar.add(d);
            for(int horizon:new int[]{1,3,5,20}) {
                JsonNode completed=null;for(JsonNode prior:previousOutcomes)if(prior.path("horizonSessions").asInt()==horizon&&Set.of("COMPLETE","GROSS_ONLY").contains(prior.path("state").asText())){completed=prior;break;}
                if(completed!=null){comparisons.add(completed);continue;}
                List<LocalDate> horizonCalendar=calendar.stream().filter(d->!d.isBefore(entry)).limit(horizon).toList();
                if(horizonCalendar.size()<horizon||date.getYear()!=2026||horizonCalendar.getLast().getYear()!=2026){var absent=comparisons.addObject().put("state","UNMEASURABLE").put("horizonSessions",horizon);absent.putArray("reasons").add("OFFICIAL_CALENDAR_COVERAGE_UNCONFIRMED");continue;}
                if(now.isBefore(horizonCalendar.getLast().atTime(15,30).atZone(KST).toInstant())){var pending=comparisons.addObject().put("state","PENDING").put("horizonSessions",horizon);pending.putArray("reasons").add("HORIZON_NOT_MATURE");continue;}
                List<EventResearchPortfolioEvaluator.StockReturn> returns=new ArrayList<>();
                for(String code:union) {
                    var signal=new ResearchOutcomeEvaluator.Signal(capture.path("captureId").asText()+":"+code,code,parseInstant(capture.path("decisionAt").asText()),null,null,"KRX","REGULAR",ResearchOutcomeEvaluator.Provenance.REAL,ResearchOutcomeEvaluator.Adjustment.ADJUSTED);
                    var outcome=evaluator.evaluate(signal,calendar,bars.getOrDefault(code,List.of()),new ResearchOutcomeEvaluator.Policy("AFTER_CLOSE_NEXT_SESSION_OPEN_V1",horizon,ResearchOutcomeEvaluator.Measurement.NEXT_SESSION_OPEN_SIMULATION,null,null,null),now,null);
                    returns.add(new EventResearchPortfolioEvaluator.StockReturn(code,outcome.state().name(),outcome.grossReturnPct(),true));
                }
                BigDecimal cost=null;try{cost=new BigDecimal(capture.path("roundTripCostPct").asText());}catch(RuntimeException ignored){}
                var compared=new EventResearchPortfolioEvaluator().compare(base,challenger,returns,horizon,cost,capture.path("costsVerified").asBoolean());comparisons.add(exactNumbers(mapper.valueToTree(compared)));
            }
            store.updatePortfolio(capture);latestOutcomes=comparisons;mature+=appendStoredLearning(capture,base,challenger,learning,now);
        }
        var policy=new ResearchLearningService.Policy(baselineId,VERSION,5,EventResearchPortfolioEvaluator.EVALUATION_POLICY_VERSION,EventResearchPortfolioEvaluator.COST_POLICY_VERSION,VERSION,expected);
        var review=new ResearchLearningService().compare(learning,policy);ObjectNode latest=store.load().map(n->(ObjectNode)n).orElse(null);if(latest!=null){var comparison=latest.putObject("comparison").put("status",review.status().name()).put("captureStatus",captureStatus).put("capturedDates",expected.size()).put("measuredDates",mature).put("independentDates",review.independentDates()).put("productionActivationAllowed",false).put("baselinePolicyId",baselineId).put("challengerPolicyId",VERSION).put("costsVerified",costsVerified);
            comparison.set("latestOutcomes",latestOutcomes);comparison.put("meanPairedDeltaPct",decimal(review.meanPairedDelta()==null?null:review.meanPairedDelta().multiply(new BigDecimal("100"))));latest.put("portfolioEvaluationCursor",portfolioCaptures.isEmpty()?0:(cursor+advanced)%portfolioCaptures.size());store.save(latest);}
    }
    private int appendStoredLearning(JsonNode capture,EventResearchPortfolioEvaluator.Selection base,EventResearchPortfolioEvaluator.Selection challenger,List<ResearchLearningService.Observation> observations,Instant now) {
        LocalDate date=LocalDate.parse(capture.path("decisionDate").asText()),end=date;for(int i=0;i<5;i++){end=KoreanMarketCalendar.nextTradingDay(end);if(end==null)return 0;}
        if(date.getYear()!=2026||end.getYear()!=2026||now.isBefore(end.atTime(15,30).atZone(KST).toInstant()))return 0;
        for(JsonNode value:capture.path("outcomes"))if(value.path("horizonSessions").asInt()==5&&Set.of("COMPLETE","GROSS_ONLY").contains(value.path("state").asText())) {
            var compared=new EventResearchPortfolioEvaluator.Comparison(value.path("state").asText(),5,number(value.path("baselineGross")),number(value.path("challengerGross")),number(value.path("baselineNet")),number(value.path("challengerNet")),number(value.path("netDelta")),mapper.convertValue(value.path("reasons"),List.class));
            observations.addAll(EventResearchPortfolioEvaluator.learningObservations(compared,date,end,base,challenger,VERSION));return 1;
        }return 0;
    }
    private static BigDecimal number(JsonNode value){try{return value==null||value.isNull()?null:new BigDecimal(value.asText());}catch(RuntimeException e){return null;}}
    private static Map<String,BigDecimal> equalWeights(List<String> codes){Map<String,BigDecimal> weights=new LinkedHashMap<>();if(!codes.isEmpty()){BigDecimal weight=BigDecimal.ONE.divide(BigDecimal.valueOf(codes.size()),18,java.math.RoundingMode.DOWN);codes.forEach(c->weights.put(c,weight));}return weights;}
    private static Map<String,BigDecimal> weights(JsonNode node){Map<String,BigDecimal> result=new LinkedHashMap<>();node.fields().forEachRemaining(e->result.put(e.getKey(),new BigDecimal(e.getValue().asText())));return result;}
    private void updateOutcomes(ObjectNode candidate,long deadline) {
        Instant now=clock.instant();Instant published=parseInstant(candidate.path("evaluationPublishedAt").asText());var local=now.atZone(KST);
        JsonNode previousOutcomes=published==null?mapper.createArrayNode():candidate.path("outcomes").deepCopy();
        if(published==null&&local.toLocalTime().compareTo(LocalTime.of(15,30))>=0&&KoreanMarketCalendar.isTradingDay(local.toLocalDate())) {
            published=now;candidate.put("evaluationPublishedAt",published.toString());candidate.put("evaluationPolicy","AFTER_CLOSE_NEXT_SESSION_OPEN_V1");
        }
        ArrayNode outcomes=candidate.putArray("outcomes");
        if(published==null){for(int horizon:new int[]{1,3,5,20})outcomes.add(pending(horizon,"WAITING_FOR_AFTER_CLOSE_OBSERVATION"));return;}
        LocalDate date=published.atZone(KST).toLocalDate();List<LocalDate> calendar=new ArrayList<>();for(LocalDate d=date;d.isBefore(date.plusDays(60));d=d.plusDays(1))if(KoreanMarketCalendar.isTradingDay(d))calendar.add(d);
        LocalDate entry=KoreanMarketCalendar.nextTradingDay(date);if(entry==null||now.isBefore(entry.atTime(15,30).atZone(KST).toInstant())) {for(int horizon:new int[]{1,3,5,20})outcomes.add(pending(horizon,"HORIZON_NOT_MATURE"));return;}
        if(date.getYear()!=2026){for(int horizon:new int[]{1,3,5,20}){var node=pending(horizon,"OFFICIAL_CALENDAR_COVERAGE_UNCONFIRMED");node.put("state","UNMEASURABLE");outcomes.add(node);}return;}
        budget(deadline);List<ResearchOutcomeEvaluator.SessionBar> bars;
        try{bars=ports.bars().read(candidate.path("stockCode").asText(),entry,local.toLocalDate());}catch(RuntimeException e){bars=List.of();}
        var signal=new ResearchOutcomeEvaluator.Signal(candidate.path("caseId").asText(),candidate.path("stockCode").asText(),published,null,null,"KRX","REGULAR",ResearchOutcomeEvaluator.Provenance.REAL,ResearchOutcomeEvaluator.Adjustment.ADJUSTED);
        var evaluator=new ResearchOutcomeEvaluator();
        for(int horizon:new int[]{1,3,5,20}) {
            List<LocalDate> evaluationCalendar=calendar.stream().filter(d->!d.isBefore(entry)).limit(horizon).toList();
            if(evaluationCalendar.size()<horizon||evaluationCalendar.getLast().getYear()!=2026){var node=pending(horizon,"OFFICIAL_CALENDAR_COVERAGE_UNCONFIRMED");node.put("state","UNMEASURABLE");outcomes.add(node);continue;}
            JsonNode completed=null;for(JsonNode prior:previousOutcomes)if(prior.path("horizonSessions").asInt()==horizon&&"COMPLETE".equals(prior.path("state").asText())
                &&candidate.path("caseId").asText().equals(prior.path("signalId").asText())&&"AFTER_CLOSE_NEXT_SESSION_OPEN_V1".equals(prior.path("policyVersion").asText())
                &&"NEXT_SESSION_OPEN_SIMULATION".equals(prior.path("measurement").asText())&&entry.toString().equals(prior.path("entryDate").asText())
                &&evaluationCalendar.getLast().toString().equals(prior.path("exitDate").asText())){completed=prior;break;}
            if(completed!=null){outcomes.add(completed);continue;}
            var outcome=evaluator.evaluate(signal,calendar,bars,new ResearchOutcomeEvaluator.Policy("AFTER_CLOSE_NEXT_SESSION_OPEN_V1",horizon,ResearchOutcomeEvaluator.Measurement.NEXT_SESSION_OPEN_SIMULATION,null,null,null),now,null);
            ObjectNode node=mapper.valueToTree(outcome);node.put("grossReturnPct",decimal(outcome.grossReturnPct()));node.put("netReturnPct",decimal(outcome.netReturnPct()));node.put("excessReturnPct",decimal(outcome.excessReturnPct()));outcomes.add(node);
        }
    }
    private ObjectNode pending(int horizon,String reason){var node=mapper.createObjectNode().put("horizonSessions",horizon).put("state","PENDING");node.putNull("grossReturnPct");node.putNull("netReturnPct");node.putNull("excessReturnPct");node.putArray("reasons").add(reason);return node;}
    private ObjectNode empty(){var node=mapper.createObjectNode().put("status",enabled?"WAITING":"DISABLED").putNull("asOf").putNull("runId").put("stale",true);node.putArray("issues");node.putObject("coverage");node.putArray("candidates");var buybacks=node.putObject("buybacks").put("status","WAITING").putNull("asOf");buybacks.putArray("rows");buybacks.putArray("issues");return node;}
    private static List<String> counterArguments(List<String> risks){var result=new ArrayList<String>();result.add("공급계약이 회사 전체의 이익 증가로 이어지는지는 납품·원가·다음 실적 확인이 필요합니다.");if(risks.contains("HISTORICAL_MARGIN_PROXY"))result.add("과거 회사 전체 마진은 이번 제품의 마진을 보장하지 않습니다.");if(risks.contains("CASH_FLOW_UNKNOWN"))result.add("회수 시기·운전자본·설비투자 미확인으로 현금흐름 증가를 확정할 수 없습니다.");if(risks.contains("AMENDMENT"))result.add("기존 계약 변경을 신규 수주로 중복 계산할 수 없습니다.");return result;}
    private static DartSupplyContractSource.Contract withFirstSeen(DartSupplyContractSource.Contract c,Instant first){return new DartSupplyContractSource.Contract(c.supplierCode(),c.supplierName(),c.customerName(),c.product(),c.amountWon(),c.previousAmountWon(),c.recentSalesWon(),c.startDate(),c.endDate(),c.filingDate(),c.receiptNo(),c.sourceUrl(),first,c.amendmentStatus(),c.evidenceText(),c.documentHash());}
    private static DartSupplyContractSource.Contract relationshipContract(JsonNode c){return new DartSupplyContractSource.Contract(c.path("stockCode").asText(),c.path("stockName").asText(),c.path("customerName").asText(null),c.path("product").asText(null),null,null,null,parseDate(c.path("contractStartDate").asText()),parseDate(c.path("contractEndDate").asText()),parseDate(c.path("filingDate").asText()),c.path("receiptNo").asText(),c.path("sourceUrl").asText(),parseInstant(c.path("firstSeenAt").asText()),c.path("amendmentStatus").asText(),c.path("evidenceText").asText(),c.path("documentHash").asText());}
    private static LocalDate parseDate(String value){try{return LocalDate.parse(value);}catch(RuntimeException e){return null;}}
    private static List<ResearchOutcomeEvaluator.SessionBar> readBars(KisSourceGateway gateway,ObjectMapper mapper,String code,LocalDate from,LocalDate to) {
        var completed=Instant.now().atZone(KST);LocalDate last=completed.toLocalTime().isBefore(LocalTime.of(15,30))?KoreanMarketCalendar.previousTradingDay(completed.toLocalDate()):completed.toLocalDate();
        if(last==null||last.isBefore(from))return List.of();if(to.isAfter(last))to=last;
        var format=java.time.format.DateTimeFormatter.BASIC_ISO_DATE;var response=gateway.fetch("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice","FHKST03010100",
            Map.of("FID_COND_MRKT_DIV_CODE","J","FID_INPUT_ISCD",code,"FID_INPUT_DATE_1",from.format(format),"FID_INPUT_DATE_2",to.format(format),"FID_PERIOD_DIV_CODE","D","FID_ORG_ADJ_PRC","0"),System.nanoTime()+Duration.ofSeconds(12).toNanos());
        if(!"AVAILABLE".equals(response.status())||!code.equals(response.body().path("output1").path("stck_shrn_iscd").asText())||!response.body().path("output2").isArray())return List.of();List<ResearchOutcomeEvaluator.SessionBar> bars=new ArrayList<>();
        for(JsonNode row:response.body().path("output2"))try {
            LocalDate date=LocalDate.parse(row.path("stck_bsop_date").asText(),format);if(date.isAfter(last)||date.isBefore(from)||date.isAfter(to))continue;
            BigDecimal open=new BigDecimal(row.path("stck_oprc").asText()),high=new BigDecimal(row.path("stck_hgpr").asText()),low=new BigDecimal(row.path("stck_lwpr").asText()),close=new BigDecimal(row.path("stck_clpr").asText());
            bars.add(new ResearchOutcomeEvaluator.SessionBar(code,date,response.fetchedAt(),open,high,low,close,row.path("acml_vol").asLong()>0,false,"KRX","REGULAR",ResearchOutcomeEvaluator.Provenance.REAL,ResearchOutcomeEvaluator.Adjustment.ADJUSTED));
        }catch(RuntimeException ignored){}return List.copyOf(bars);
    }
    private static String decimal(BigDecimal n){return n==null?null:n.toPlainString();}
    private static JsonNode exactNumbers(JsonNode node) {
        if(node==null)return NullNode.instance;
        if(node.isFloatingPointNumber()||(node.isIntegralNumber()&&node.bigIntegerValue().abs().compareTo(java.math.BigInteger.valueOf(9_007_199_254_740_991L))>0))return TextNode.valueOf(node.decimalValue().toPlainString());
        if(node.isObject()){ObjectNode result=((ObjectNode)node).deepCopy();node.fields().forEachRemaining(e->result.set(e.getKey(),exactNumbers(e.getValue())));return result;}
        if(node.isArray()){ArrayNode result=JsonNodeFactory.instance.arrayNode();node.forEach(n->result.add(exactNumbers(n)));return result;}return node;
    }
    private static Instant parseInstant(String value){try{return Instant.parse(value);}catch(RuntimeException e){return null;}}
    private static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("HASH_UNAVAILABLE");}}
    private static void budget(long deadline){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IllegalStateException("RESEARCH_BUDGET_EXCEEDED");}
    @PreDestroy @Override public void close(){worker.shutdownNow();}
}
