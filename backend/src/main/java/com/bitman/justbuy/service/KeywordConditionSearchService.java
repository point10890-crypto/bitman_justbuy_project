package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.KeywordSearchResponse;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import com.bitman.justbuy.service.KeywordSearchQueryParser.Filter;
import com.bitman.justbuy.service.KeywordSearchQueryParser.Spec;
import com.bitman.justbuy.service.KeywordSearchDataService.Quote;
import com.bitman.justbuy.service.KeywordSearchDataService.Flow;
import com.bitman.justbuy.service.KeywordSearchDataService.Snapshot;
import com.bitman.justbuy.dto.KeywordSearchResponse.*;

@Service
public class KeywordConditionSearchService {
    private final KeywordSearchQueryParser parser;
    private final KeywordSearchDataService data;
    private KisResearchEvidenceService researchEvidence;
    private final Semaphore slots = new Semaphore(4);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Clock clock;
    private final LinkedHashMap<UUID,PendingSearch> pendingSearches=new LinkedHashMap<>();
    private record Prepared(String query,Spec spec,Snapshot snapshot,List<Quote> eligible,int distinctCount,boolean rawEmpty,
        int priceEligibleCount,int missingRank,int crossLimit,boolean secondaryEnabled,int secondaryPending,
        boolean flowLimited,boolean researchLimited,Map<String,KisResearchEvidenceService.CandidateEvidence> research,
        Map<String,Quote> kisCrossQuotes,Map<String,Object> secondary,Map<String,Flow> primaryFlows,
        List<String> warnings,List<ProviderStatus> providers,LocalDate investorDate,Instant validationAsOf) {}
    private record PendingSearch(UUID owner,Prepared input,Instant expiresAt,Map<String,Flow> confirmedFlows) {}
    @org.springframework.beans.factory.annotation.Autowired
    public KeywordConditionSearchService(KeywordSearchQueryParser parser, KeywordSearchDataService data) {
        this(parser,data,Clock.systemUTC());
    }
    KeywordConditionSearchService(KeywordSearchQueryParser parser,KeywordSearchDataService data,Clock clock) {
        this.parser=parser;this.data=data;this.clock=clock;
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureResearchEvidence(KisResearchEvidenceService research) { this.researchEvidence=research; }
    public KeywordSearchResponse search(String query) {
        return search(query,null);
    }
    public KeywordSearchResponse search(String query,UUID owner) {
        Spec spec = parser.parse(query);
        if (!spec.unsupportedTerms().isEmpty()) return response(query,"UNSUPPORTED",spec,
            new Scope("지원 조건을 확인한 후 조회합니다",0,0,0,false),null,"NOT_QUERIED",
            List.of("미지원 조건은 생략하지 않습니다. 가격·등락률·거래량·거래대금·순매수 조건으로 조회해 주세요."),List.of());
        if (!slots.tryAcquire()) return response(query,"UNAVAILABLE",spec,new Scope("조회 대기",0,0,0,false),
            null,"UNAVAILABLE",List.of("다른 검색이 진행 중입니다. 잠시 후 다시 조회해 주세요."),List.of());
        try { return select(query,spec,owner); }
        catch (RuntimeException failure) {
            return response(query,"UNAVAILABLE",spec,new Scope("실데이터 조회 실패",0,0,0,false),null,
                "UNAVAILABLE",List.of("실제 시세를 확인하지 못했습니다. 가상 종목을 대신 표시하지 않습니다."),List.of());
        } finally { slots.release(); }
    }
    public KeywordSearchResponse progress(UUID owner,String progressId) {
        UUID id;try{id=UUID.fromString(progressId);}catch(RuntimeException ignored){throw missingProgress();}
        PendingSearch pending;
        synchronized(pendingSearches){pending=pendingSearches.get(id);}
        if(pending==null||owner==null||!owner.equals(pending.owner()))throw missingProgress();
        if(!clock.instant().isBefore(pending.expiresAt()) || !pending.input().investorDate().equals(
            com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(LocalDate.now(clock.withZone(KST)))))
            throw new com.bitman.justbuy.controller.ApiException(org.springframework.http.HttpStatus.GONE,"수급 확인 대기가 만료되었습니다.","FLOW_PROGRESS_EXPIRED");
        Map<String,Object> secondary=currentInvestors(pending.input());
        Map<String,Flow> confirmed=confirmedFlows(pending.input(),secondary,pending.confirmedFlows());
        synchronized(pendingSearches){
            PendingSearch current=pendingSearches.get(id);
            if(current!=null){var merged=new LinkedHashMap<>(current.confirmedFlows());merged.putAll(confirmed);confirmed=Map.copyOf(merged);
                pendingSearches.put(id,new PendingSearch(pending.owner(),pending.input(),pending.expiresAt(),confirmed));}
        }
        KeywordSearchResponse result=evaluate(pending.input(),secondary,confirmed);
        if(result.flowCoverage().pendingCount()>0)return withProgress(result,new Progress(id.toString(),pending.expiresAt(),10000));
        synchronized(pendingSearches){pendingSearches.remove(id);}
        return result;
    }
    private static com.bitman.justbuy.controller.ApiException missingProgress() {
        return new com.bitman.justbuy.controller.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"수급 확인 요청을 찾지 못했습니다.","FLOW_PROGRESS_NOT_FOUND");
    }

    private KeywordSearchResponse select(String query, Spec spec,UUID owner) {
        LocalDate investorDate=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(LocalDate.now(clock.withZone(KST)));
        List<String> requestedParts=requestedParts(spec);
        Snapshot snapshot;
        if (spec.stockCode()!=null && !spec.stockCode().isBlank()) {
            Quote q=data.lookup(spec.stockCode());
            snapshot=new Snapshot(q==null ? "UNAVAILABLE":"COMPLETE",q==null ? List.of():List.of(q),
                q==null ? null:q.fetchedAt(),"종목코드 직접 조회",q==null ? List.of("해당 코드의 실제 시세를 확인하지 못했습니다."):List.of());
        } else snapshot=data.snapshot();
        if (snapshot==null || !Set.of("COMPLETE","PARTIAL").contains(snapshot.status())) return response(query,"UNAVAILABLE",spec,
            new Scope(snapshot==null ? "실데이터 조회":snapshot.scope(),0,0,0,false),snapshot==null ? null:snapshot.fetchedAt(),
            "UNAVAILABLE",snapshot==null ? List.of("시세 조회 실패"):snapshot.warnings(),List.of());
        var warnings=new LinkedHashSet<String>(snapshot.warnings()==null ? List.of():snapshot.warnings());
        List<Quote> raw=snapshot.quotes()==null ? List.of():snapshot.quotes();
        var distinct=new LinkedHashMap<String,Quote>();
        for (Quote q:raw) {
            if (!validQuote(q)) { warnings.add("코드·가격·출처·조회 시각을 검증하지 못한 시세는 제외했습니다."); continue; }
            if (spec.stockCode()!=null && !spec.stockCode().isBlank() && !spec.stockCode().equals(q.code())) continue;
            if (q.observedAt()==null) warnings.add("거래 시각 미확인 시세가 있습니다. 조회 시각을 거래 시각으로 대신 표시하지 않습니다.");
            else if (Duration.between(q.observedAt(),q.fetchedAt()).compareTo(Duration.ofDays(7))>0)
                warnings.add("7일 이상 지난 관측 시각의 종목이 포함되어 있습니다.");
            distinct.putIfAbsent(q.code(),q);
        }
        var eligible=new ArrayList<Quote>();
        int missingRank=0;
        for (Quote q:distinct.values()) {
            if (spec.market()!=null && !spec.market().isBlank() && !spec.market().equalsIgnoreCase(q.market())) continue;
            if (spec.nameContains()!=null && !spec.nameContains().isBlank()
                && !normalize(q.name()).contains(normalize(spec.nameContains()))) continue;
            if (spec.todayOnly() && (q.observedAt()==null || !q.observedAt().atZone(KST).toLocalDate().equals(LocalDate.now(KST)))) {
                warnings.add("오늘 조건은 오늘의 실제 관측만 사용합니다. 이전 거래일 시세는 제외했습니다."); continue;
            }
            boolean matches=true;
            for (Filter f:spec.filters()) {
                if (isFlow(f.field()) || isResearch(f.field())) continue;
                BigDecimal v=metric(q,f.field());
                if (v==null) { warnings.add(f.label()+"에 필요한 실측값 미확인 종목은 제외했습니다."); matches=false; }
                else if (!compare(v,f)) matches=false;
            }
            if (matches && spec.rankingExplicit() && metric(q,spec.sortBy())==null) {
                missingRank++;
                warnings.add("요청한 순위 기준의 실측값 미확인 종목은 순위에서 제외했습니다.");
                matches=false;
            }
            if (matches) eligible.add(q);
        }
        eligible.sort(order(spec));
        int priceEligibleCount=eligible.size();
        var providers=new ArrayList<ProviderStatus>();
        distinct.values().stream().map(Quote::source).distinct().forEach(source->providers.add(new ProviderStatus(source,
            environment(source),snapshot.status(),snapshot.fetchedAt(),snapshot.scope())));
        Map<String,Object> secondary=Map.of();
        Map<String,Quote> kisCrossQuotes=Map.of();
        Map<String,Object> readiness=data.kiwoomReadiness();
        boolean secondaryEnabled=readiness!=null && Boolean.TRUE.equals(readiness.get("enabled"));
        int crossLimit=readiness==null?20:positiveLimit(readiness.get("candidateLimit"));
        List<String> crossCodes=eligible.stream().limit(crossLimit).map(Quote::code).toList();
        int secondaryPending=0;
        if(secondaryEnabled && !eligible.isEmpty()) {
            try {
                Map<String,Quote> quotes=data.kisQuotes(crossCodes);
                if(quotes!=null)kisCrossQuotes=quotes;
            }catch(RuntimeException ignored){/* Preserve primary and Kiwoom observations. */}
            int checked=0,missingNames=0;Instant latest=null;
            for(Quote primary:eligible.stream().limit(crossLimit).toList()) {
                Quote cross=kisCrossQuotes.get(primary.code());
                if(validCrossQuote(primary,cross) && "KIS OpenAPI".equals(cross.source())) {
                    checked++;if(cross.name()==null||cross.name().isBlank())missingNames++;
                    if(latest==null||cross.fetchedAt().isAfter(latest))latest=cross.fetchedAt();
                }
            }
            Map<String,Object> kisProgress=data.kisQuoteProgress(crossCodes);
            int kisPending=kisProgress==null?0:count(kisProgress.get("pendingCodes"),crossCodes.size());
            if(kisPending>0)warnings.add(requestedParts.contains("investorDaily")
                ? "최초 검색에서 KIS 주시세 추가 교차 조회에 대기 상태가 있었습니다. 수급 확인 상태는 별도로 표시합니다."
                : "KIS 주시세 추가 교차 조회가 진행 중입니다. 확인된 결과를 먼저 표시합니다.");
            else if(checked==0)warnings.add("KIS 주시세 교차 조회 미확인: 확인된 다른 공급자의 결과를 유지합니다.");
            else if(checked<crossCodes.size())warnings.add("KIS 주시세 교차 조회는 일부 후보만 확인했습니다.");
            String kisStatus=checked==0?kisPending>0?"PENDING":"UNAVAILABLE":checked<crossCodes.size()||kisPending>0?"PARTIAL":"AVAILABLE";
            providers.add(new ProviderStatus("KIS OpenAPI","REAL",kisStatus,
                latest,"검증된 동일 후보 최대 "+crossLimit+"종목의 기존 KIS 주시세 · 확인되지 않은 나머지는 순차 조회"
                    +(missingNames>0?" · 원응답 종목명 미제공 "+missingNames+"건은 일치하는 검증 종목코드·요청코드로 확인":""),crossCodes.size(),checked,kisPending));
            try {
                Map<String,Object> received=data.kiwoomEvidence(crossCodes,requestedParts);
                if(received!=null) secondary=received;
            } catch(RuntimeException ignored) {warnings.add("키움 REST 교차 조회를 확인하지 못했습니다. 확인된 주시세 결과를 유지합니다.");}
            int usable=0;
            for(Quote quote:eligible.stream().limit(crossLimit).toList()) {
                boolean verified=false;
                for(String part:List.of("quote","technical","valuation","investorDaily"))
                    if(part.equals("quote")?secondaryObservation(quote,secondaryGroup(secondary,quote.code(),part))!=null
                        :!secondaryGroup(secondary,quote.code(),part).isEmpty())verified=true;
                if(verified)usable++;
            }
            secondaryPending=count(secondary.get("pendingCodes"),crossCodes.size());
            if(secondaryPending>0)warnings.add(requestedParts.contains("investorDaily")
                ? "최초 검색에서 키움 REST 추가 교차 근거 일부는 대기 상태였습니다. 수급 확인 상태는 별도로 표시합니다."
                : "키움 REST 추가 근거를 순차 확인하고 있습니다. 확인된 결과를 먼저 표시하며 대기 중인 값을 조건에 사용하지 않습니다.");
            else if(usable==0)warnings.add("키움 REST 교차 근거 미확인: 확인된 주시세와 조건 근거를 유지합니다.");
            String providerSource=string(readiness.get("source")),providerEnvironment=string(readiness.get("environment"));
            String secondaryStatus=usable==0?secondaryPending>0?"PENDING":"UNAVAILABLE":secondaryPending>0||usable<crossCodes.size()
                ||!Set.of("AVAILABLE","COMPLETE").contains(string(secondary.get("status")))?"PARTIAL":"AVAILABLE";
            providers.add(new ProviderStatus(providerSource,providerEnvironment,secondaryStatus,
                instant(secondary.get("fetchedAt")),"동일한 검증 후보 정렬 상위 최대 "+crossLimit+"종목의 "+partsLabel(requestedParts)+" 교차 조회",
                crossCodes.size(),usable,secondaryPending));
        } else if(readiness!=null && !readiness.isEmpty()) providers.add(new ProviderStatus(string(readiness.get("source")),
            string(readiness.get("environment")),secondaryEnabled?"NOT_REQUESTED":string(readiness.get("status")),null,"키움 REST 최대 "+crossLimit+"종목"));
        boolean needsResearch=spec.filters().stream().anyMatch(f -> isResearch(f.field()));
        int researchLimit=secondaryEnabled?Math.max(5,crossLimit):5;
        boolean researchLimited=needsResearch && eligible.size()>researchLimit;
        Map<String,KisResearchEvidenceService.CandidateEvidence> research=new LinkedHashMap<>();
        if(needsResearch && !eligible.isEmpty()) {
            if(researchLimited) warnings.add("기술·PER/PBR 조건은 시세 조건을 통과한 정렬 상위 "+researchLimit+"종목까지만 검증합니다. KIS 최대 5종목, 키움 최대 "+crossLimit+"종목이며 전체 시장 기술 검색이 아닙니다.");
            eligible=new ArrayList<>(eligible.subList(0,Math.min(researchLimit,eligible.size())));
            if(researchEvidence!=null) try {
                var batch=researchEvidence.fetchEvidence(eligible.stream().limit(5).map(Quote::code).toList());
                if(batch!=null && batch.candidates()!=null) for(var candidate:batch.candidates()) {
                    if(candidate!=null && eligible.stream().anyMatch(q -> q.code().equals(candidate.stockCode()))) research.putIfAbsent(candidate.stockCode(),candidate);
                }
            } catch(RuntimeException ignored) { warnings.add("KIS 기술·PER/PBR 자료 미확인: 조건 값을 추정하지 않았습니다."); }
        }
        boolean needsFlow=spec.filters().stream().anyMatch(f -> isFlow(f.field()));
        boolean flowLimited=needsFlow && eligible.size()>20;
        Map<String,Flow> flows=Map.of();
        if (needsFlow && !eligible.isEmpty()) {
            if (flowLimited) warnings.add("수급은 시세 조건을 통과한 정렬 상위 20종목까지만 확인합니다. 전체 시장 수급 검색이 아닙니다.");
            var codes=eligible.stream().limit(20).map(Quote::code).toList();
            try { var observed=data.flows(codes,spec.flowDays()); if(observed!=null) flows=observed; }
            catch (RuntimeException failure) { warnings.add("KIS 수급 조회 미확인: 순매수 값을 추정하지 않았습니다."); }
        }
        if(needsFlow)eligible=new ArrayList<>(eligible.stream().limit(20).toList());
        var verifiedPrimary=new LinkedHashMap<String,Flow>();
        for(Quote quote:eligible)if(validFlow(flows.get(quote.code()),quote.code(),spec))verifiedPrimary.put(quote.code(),flows.get(quote.code()));
        Prepared input=new Prepared(query,spec,new Snapshot(snapshot.status(),List.of(),snapshot.fetchedAt(),snapshot.scope(),snapshot.warnings()),
            List.copyOf(eligible),distinct.size(),raw.isEmpty(),priceEligibleCount,missingRank,crossLimit,secondaryEnabled,secondaryPending,
            flowLimited,researchLimited,Map.copyOf(research),Map.copyOf(kisCrossQuotes),secondary,Map.copyOf(verifiedPrimary),List.copyOf(warnings),List.copyOf(providers),
            investorDate,clock.instant());
        Map<String,Object> latest=needsFlow?currentInvestors(input):secondary;
        Map<String,Flow> confirmed=needsFlow?confirmedFlows(input,latest,confirmedFlows(input,secondary,input.primaryFlows())):Map.of();
        KeywordSearchResponse result=evaluate(input,latest,confirmed);
        if(owner==null||result.flowCoverage()==null||result.flowCoverage().pendingCount()==0)return result;
        UUID id=UUID.randomUUID();Instant expires=clock.instant().plusSeconds(120);
        synchronized(pendingSearches){
            pendingSearches.entrySet().removeIf(entry->!clock.instant().isBefore(entry.getValue().expiresAt()));
            while(pendingSearches.size()>=256)pendingSearches.remove(pendingSearches.keySet().iterator().next());
            pendingSearches.put(id,new PendingSearch(owner,input,expires,confirmed));
        }
        return withProgress(result,new Progress(id.toString(),expires,10000));
    }
    private Map<String,Object> currentInvestors(Prepared input) {
        Map<String,Object> cached;
        try {cached=data.cachedInvestorEvidence(input.eligible().stream().map(Quote::code).toList(),input.investorDate());}
        catch(RuntimeException ignored){cached=Map.of();}
        Map<String,Object> candidates=new LinkedHashMap<>();
        for(Quote quote:input.eligible()) {
            Map<String,Object> facts=new LinkedHashMap<>();
            Object original=secondaryCandidates(input.secondary()).get(quote.code());
            if(original instanceof Map<?,?> map)map.forEach((key,value)->{if(key instanceof String text)facts.put(text,value);});
            Object current=cached==null?null:secondaryCandidates(cached).get(quote.code());
            if(current instanceof Map<?,?> map&&map.containsKey("investorDaily"))facts.put("investorDaily",map.get("investorDaily"));
            candidates.put(quote.code(),Map.copyOf(facts));
        }
        return Map.of("candidates",Map.copyOf(candidates));
    }
    private static KeywordSearchResponse withProgress(KeywordSearchResponse result,Progress progress) {
        return new KeywordSearchResponse(result.query(),result.outcome(),result.appliedConditions(),result.unsupportedTerms(),result.scope(),
            result.fetchedAt(),result.sourceStatus(),result.warnings(),result.matches(),result.providers(),result.flowCoverage(),progress);
    }
    private static Map<String,Flow> confirmedFlows(Prepared input,Map<String,Object> secondary,Map<String,Flow> prior) {
        var confirmed=new LinkedHashMap<String,Flow>();
        for(Quote quote:input.eligible()) {
            Flow accepted=prior.get(quote.code());
            if(accepted==null) {
                Flow candidate=secondaryFlow(quote.code(),secondaryGroup(secondary,quote.code(),"investorDaily"),input.spec().flowDays());
                if(validFlow(candidate,quote.code(),input.spec()))accepted=candidate;
            }
            if(accepted!=null)confirmed.put(quote.code(),accepted);
        }
        return Map.copyOf(confirmed);
    }
    private KeywordSearchResponse evaluate(Prepared input,Map<String,Object> secondary,Map<String,Flow> confirmed) {
        String query=input.query();Spec spec=input.spec();Snapshot snapshot=input.snapshot();List<Quote> eligible=input.eligible();
        int crossLimit=input.crossLimit(),secondaryPending=input.secondaryPending();boolean secondaryEnabled=input.secondaryEnabled();
        boolean needsFlow=spec.filters().stream().anyMatch(f->isFlow(f.field())),needsResearch=spec.filters().stream().anyMatch(f->isResearch(f.field()));
        Map<String,Flow> flows=confirmed;var research=input.research();var kisCrossQuotes=input.kisCrossQuotes();
        var warnings=new LinkedHashSet<>(input.warnings());
        if(needsFlow && !eligible.isEmpty()) {
            if(secondaryEnabled && spec.flowDays()>5) warnings.add("키움 확정 일별 수급은 최대 5거래일까지 확인합니다. 더 긴 요청 기간은 KIS의 확인된 수급만 사용합니다.");
        }
        var matched=new ArrayList<Match>();
        int verifiedFlows=0;
        if(needsFlow) for(Quote q:eligible) if(flows.containsKey(q.code())) verifiedFlows++;
        int pendingFlows=0;
        if(needsFlow && !spec.todayOnly() && spec.flowDays()<=5)for(Quote q:eligible)
            if(!flows.containsKey(q.code()) && rawInvestorPending(secondary,q.code()))pendingFlows++;
        int verifiedResearch=0;
        for (Quote q:eligible) {
            var researchCandidate=selectResearch(q.code(),research.get(q.code()),secondary,spec.filters(),input.validationAsOf());
            boolean researchKnown=true,researchMatches=true;
            for(Filter filter:spec.filters()) if(isResearch(filter.field())) {
                BigDecimal value=researchMetric(researchCandidate,filter.field(),input.validationAsOf());
                if(value==null) {researchKnown=false;warnings.add("KIS·키움 "+filter.label()+" 실측 근거 미확인 종목은 제외했습니다. 이용 권한·자료 범위를 확인해 주세요.");}
                else if(!compare(value,filter)) researchMatches=false;
            }
            if(needsResearch && !researchKnown) continue;
            if(needsResearch) verifiedResearch++;
            if(!researchMatches) continue;
            Flow flow=flows.get(q.code());
            if (needsFlow && flow==null) {
                warnings.add("요청한 수급 기간·실제 관측일·순매수 값 미확인 종목은 제외했습니다."); continue;
            }
            if (needsFlow && spec.filters().stream().filter(f -> isFlow(f.field())).anyMatch(f ->
                !compare(BigDecimal.valueOf("FOREIGN_NET".equals(f.field()) ? flow.foreignNet():flow.institutionNet()),f))) continue;
            List<Evidence> evidence=new ArrayList<>();
            for (Filter f:spec.filters()) {
                if (isFlow(f.field())) evidence.add(new Evidence(f.label(),Long.toString("FOREIGN_NET".equals(f.field()) ? flow.foreignNet():flow.institutionNet()),
                    "주",flow.source(),flow.fromDate()+" ~ "+flow.toDate(),flow.environment(),flow.fetchedAt()));
                else if(isResearch(f.field())) evidence.add(new Evidence(f.label(),researchMetric(researchCandidate,f.field(),input.validationAsOf()).stripTrailingZeros().toPlainString(),
                    Set.of("PER","PBR","VOLUME_RATIO20").contains(f.field())?"배":"충족",string(researchSource(researchCandidate,f.field()).get("source")),researchPeriod(researchCandidate,f.field()),
                    sourceEnvironment(researchSource(researchCandidate,f.field())),instant(researchSource(researchCandidate,f.field()).get("fetchedAt"))));
                else evidence.add(new Evidence(f.label(),metric(q,f.field()).stripTrailingZeros().toPlainString(),unit(f.field()),q.source(),
                    q.observedAt()==null ? null:q.observedAt().toString(),environment(q.source()),q.fetchedAt()));
            }
            if (evidence.isEmpty()) evidence.add(new Evidence("실제 종목 시세 확인",q.price().toPlainString(),"원",q.source(),
                q.observedAt()==null ? null:q.observedAt().toString(),environment(q.source()),q.fetchedAt()));
            var observations=new ArrayList<QuoteObservation>();
            observations.add(new QuoteObservation(q.source(),environment(q.source()),q.price(),q.changePct(),q.volume(),q.tradingValueKrw(),q.fetchedAt(),q.observedAt()));
            Quote kisCross=kisCrossQuotes.get(q.code());
            if(validCrossQuote(q,kisCross,input.validationAsOf()) && "KIS OpenAPI".equals(kisCross.source()) && !q.source().equals(kisCross.source()))
                observations.add(new QuoteObservation(kisCross.source(),"REAL",kisCross.price(),kisCross.changePct(),kisCross.volume(),kisCross.tradingValueKrw(),kisCross.fetchedAt(),kisCross.observedAt()));
            QuoteObservation crossQuote=secondaryObservation(q,secondaryGroup(secondary,q.code(),"quote",input.validationAsOf()));
            if(crossQuote!=null && !q.source().equals(crossQuote.source())) observations.add(crossQuote);
            matched.add(new Match(q.code(),q.name(),q.market(),q.price(),q.changePct(),q.volume(),q.tradingValueKrw(),
                q.source(),q.fetchedAt(),q.observedAt(),List.copyOf(evidence),environment(q.source()),List.copyOf(observations)));
        }
        int count=matched.size();
        int limit=Math.max(1,Math.min(20,spec.limit()));
        var picks=List.copyOf(matched.subList(0,Math.min(limit,count)));
        boolean partial="PARTIAL".equals(snapshot.status()) || !warnings.isEmpty();
        String outcome=partial ? "PARTIAL":count==0 ? "EMPTY":"COMPLETE";
        if (!input.rawEmpty() && input.distinctCount()==0) outcome="UNAVAILABLE";
        if (input.rawEmpty() && !warnings.isEmpty()) outcome="UNAVAILABLE";
        if (eligible.isEmpty() && input.missingRank()>0)outcome="UNAVAILABLE";
        if(needsFlow && !eligible.isEmpty() && verifiedFlows==0)outcome=pendingFlows>0?"PARTIAL":"UNAVAILABLE";
        if(needsResearch && !eligible.isEmpty() && verifiedResearch==0) outcome=secondaryPending>0?"PARTIAL":"UNAVAILABLE";
        String scope=snapshot.scope()+(needsFlow ? " · 최근 "+spec.flowDays()+"개 실제 거래일 수급, 최대 20종목":"")
            +(needsResearch ? " · KIS 완료 일봉·PER/PBR 검증 최대 5종목":"")
            +(secondaryEnabled?" · 키움 REST 동일 후보 교차 근거 최대 "+crossLimit+"종목":"");
        String sourceStatus="UNAVAILABLE".equals(outcome) ? "UNAVAILABLE":partial ? "PARTIAL":"COMPLETE";
        return new KeywordSearchResponse(query,outcome,spec.appliedConditions(),spec.unsupportedTerms(),
            new Scope(scope,input.distinctCount(),input.priceEligibleCount(),count,input.flowLimited() || input.researchLimited() || count>limit),
            snapshot.fetchedAt(),sourceStatus,List.copyOf(warnings),picks,input.providers(),
            needsFlow?new FlowCoverage(eligible.size(),verifiedFlows,pendingFlows,eligible.size()-verifiedFlows-pendingFlows):null,null);
    }
    private static boolean rawInvestorPending(Map<String,Object> secondary,String code) {
        Object candidate=secondaryCandidates(secondary).get(code);
        return candidate instanceof Map<?,?> facts && facts.get("investorDaily") instanceof Map<?,?> flow && "PENDING".equals(flow.get("status"))
            && kiwoomProvider(string(flow.get("source")),string(flow.get("environment")));
    }
    private static List<String> requestedParts(Spec spec) {
        var parts=new LinkedHashSet<String>();parts.add("quote");
        if(spec.filters().stream().anyMatch(filter->isFlow(filter.field())))parts.add("investorDaily");
        for(Filter filter:spec.filters())if(isResearch(filter.field()))parts.add(Set.of("PER","PBR").contains(filter.field())?"valuation":"technical");
        return List.copyOf(parts);
    }
    private static String partsLabel(List<String> parts) {
        return String.join("·",parts.stream().map(part->switch(part){case "valuation"->"PER/PBR";case "technical"->"완료 일봉";case "investorDaily"->"확정 일별 수급";default->"시세";}).toList());
    }

    private static boolean validQuote(Quote q) {
        if (q==null || q.code()==null || !q.code().matches("[0-9]{6}") || q.name()==null || q.name().isBlank()
            || q.source()==null || q.source().isBlank() || q.price()==null || q.price().signum()<=0
            || "UNKNOWN".equals(environment(q.source())) || q.price().stripTrailingZeros().scale()>0 || q.fetchedAt()==null) return false;
        Instant now=Instant.now();
        return !q.fetchedAt().isAfter(now.plusSeconds(1)) && !q.fetchedAt().isBefore(now.minusSeconds(180))
            && (q.observedAt()==null || !q.observedAt().isAfter(now.plusSeconds(60)));
    }
    private static boolean validFlow(Flow f,String code,Spec spec) {
        if (f==null || !code.equals(f.code()) || f.days()!=spec.flowDays() || f.fetchedAt()==null
            || f.fetchedAt().isAfter(Instant.now().plusSeconds(1)) || f.fetchedAt().isBefore(Instant.now().minusSeconds(kiwoomProvider(f.source(),f.environment())?600:180))
            || !("KIS OpenAPI".equals(f.source())&&"REAL".equals(f.environment()) || kiwoomProvider(f.source(),f.environment()))) return false;
        try {
            LocalDate from=LocalDate.parse(f.fromDate()),to=LocalDate.parse(f.toDate()),today=LocalDate.now(KST);
            return !from.isAfter(to) && java.time.temporal.ChronoUnit.DAYS.between(from,to)+1>=f.days()
                && !to.isAfter(today) && !to.isBefore(today.minusDays(7))
                && (!spec.todayOnly() || to.equals(today));
        } catch (RuntimeException e) { return false; }
    }
    private static boolean isFlow(String field) { return "FOREIGN_NET".equals(field) || "INSTITUTION_NET".equals(field); }
    private static boolean isResearch(String field) { return Set.of("PER","PBR","MA_ALIGNED","BREAKOUT20","VOLUME_RATIO20").contains(field); }
    private static Map<String,Object> researchSource(KisResearchEvidenceService.CandidateEvidence candidate,String field) {
        if(candidate==null) return Map.of();
        Map<String,Object> source=Set.of("PER","PBR").contains(field)?candidate.valuation():candidate.technical();
        return source==null?Map.of():source;
    }
    private static BigDecimal researchMetric(KisResearchEvidenceService.CandidateEvidence candidate,String field) {
        return researchMetric(candidate,field,Instant.now());
    }
    private static BigDecimal researchMetric(KisResearchEvidenceService.CandidateEvidence candidate,String field,Instant asOf) {
        Map<String,Object> source=researchSource(candidate,field);
        if(!Set.of("AVAILABLE","PARTIAL_COVERAGE").contains(String.valueOf(source.get("status"))) || !validResearchProvider(source)) return null;
        try {
            Instant fetched=Instant.parse(String.valueOf(source.get("fetchedAt")));Instant now=asOf;
            if(fetched.isAfter(now.plusSeconds(1)) || fetched.isBefore(now.minusSeconds(Set.of("PER","PBR").contains(field)?60:600))) return null;
            if(!Set.of("PER","PBR").contains(field)) {
                LocalDate date=LocalDate.parse(String.valueOf(source.get("observedDate"))),today=asOf.atZone(KST).toLocalDate();
                if(date.isAfter(today) || date.isBefore(today.minusDays(7)) || !com.bitman.justbuy.util.KoreanMarketCalendar.isTradingDay(date)) return null;
            }
            String key=switch(field){case "PER"->"per";case "PBR"->"pbr";case "MA_ALIGNED"->"maAligned";case "BREAKOUT20"->"breakout20";default->"volumeRatio20";};
            Object raw=source.get(key);
            if(Set.of("MA_ALIGNED","BREAKOUT20").contains(field)) return raw instanceof Boolean b?b?BigDecimal.ONE:BigDecimal.ZERO:null;
            if(!(raw instanceof BigDecimal) && !(raw instanceof Number) && !(raw instanceof String)) return null;
            String numeric=raw.toString();if(numeric.length()>48 || !numeric.matches("[0-9]+(?:\\.[0-9]+)?")) return null;
            BigDecimal value=new BigDecimal(numeric);
            if(value.abs().compareTo(new BigDecimal("1000000000000000"))>0 || Set.of("PER","PBR").contains(field) && value.signum()<=0) return null;
            return value;
        } catch(RuntimeException ignored) {return null;}
    }
    private static String researchPeriod(KisResearchEvidenceService.CandidateEvidence candidate,String field) {
        Map<String,Object> source=researchSource(candidate,field);
        if(Set.of("PER","PBR").contains(field)) return null;
        try {return LocalDate.parse(String.valueOf(source.get("coverageStart")))+" ~ "+LocalDate.parse(String.valueOf(source.get("coverageEnd")));}
        catch(RuntimeException ignored){return null;}
    }
    private static KisResearchEvidenceService.CandidateEvidence selectResearch(String code,KisResearchEvidenceService.CandidateEvidence primary,
            Map<String,Object> secondary,List<Filter> filters) {
        return selectResearch(code,primary,secondary,filters,Instant.now());
    }
    private static KisResearchEvidenceService.CandidateEvidence selectResearch(String code,KisResearchEvidenceService.CandidateEvidence primary,
            Map<String,Object> secondary,List<Filter> filters,Instant asOf) {
        Map<String,Object> technical=primary==null?Map.of():primary.technical(),valuation=primary==null?Map.of():primary.valuation();
        if(!groupKnown(code,technical,filters,false,asOf)) technical=secondaryGroup(secondary,code,"technical",asOf);
        if(!groupKnown(code,valuation,filters,true,asOf)) valuation=secondaryGroup(secondary,code,"valuation",asOf);
        return new KisResearchEvidenceService.CandidateEvidence(code,technical,valuation,Map.of(),Map.of(),Map.of(),Map.of());
    }
    private static boolean groupKnown(String code,Map<String,Object> group,List<Filter> filters,boolean valuation) {
        return groupKnown(code,group,filters,valuation,Instant.now());
    }
    private static boolean groupKnown(String code,Map<String,Object> group,List<Filter> filters,boolean valuation,Instant asOf) {
        var candidate=new KisResearchEvidenceService.CandidateEvidence(code,valuation?Map.of():group,valuation?group:Map.of(),Map.of(),Map.of(),Map.of(),Map.of());
        return filters.stream().filter(f->isResearch(f.field())&&Set.of("PER","PBR").contains(f.field())==valuation)
            .allMatch(f->researchMetric(candidate,f.field(),asOf)!=null);
    }
    private static boolean validResearchProvider(Map<String,Object> facts) {
        return "KIS OpenAPI".equals(facts.get("source")) || kiwoomProvider(string(facts.get("source")),string(facts.get("environment")));
    }
    private static boolean kiwoomProvider(String source,String environment) {
        return "KIWOOM_REST".equals(source)&&"REAL".equals(environment) || "KIWOOM_REST_MOCK".equals(source)&&"MOCK".equals(environment);
    }
    private static String environment(String source) {
        if("KIWOOM_REST_MOCK".equals(source))return "MOCK";
        return Set.of("KIWOOM_REST","KIS OpenAPI","KIS_INTSTOCK_MULTPRICE_KRX","KIS","NAVER_FINANCE","Naver Finance").contains(source)?"REAL":"UNKNOWN";
    }
    private static String sourceEnvironment(Map<String,Object> source) {
        return "KIS OpenAPI".equals(source.get("source"))?"REAL":string(source.get("environment"));
    }
    private static String string(Object value) {return value instanceof String text?text:"";}
    private static int positiveLimit(Object value) {int limit=count(value,20);return limit>0?limit:20;}
    private static int count(Object value,int maximum) {
        if(!(value instanceof Number number))return 0;
        double raw=number.doubleValue();return Double.isFinite(raw)&&raw==Math.floor(raw)&&raw>=0&&raw<=maximum?(int)raw:0;
    }
    private static Instant instant(Object value) {
        try {return value instanceof Instant at?at:Instant.parse(string(value));}catch(RuntimeException ignored){return null;}
    }
    private static Map<?,?> secondaryCandidates(Map<String,Object> batch) {
        return batch.get("candidates") instanceof Map<?,?> candidates?candidates:Map.of();
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> secondaryGroup(Map<String,Object> batch,String code,String group) {
        return secondaryGroup(batch,code,group,Instant.now());
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> secondaryGroup(Map<String,Object> batch,String code,String group,Instant asOf) {
        if(!(secondaryCandidates(batch).get(code) instanceof Map<?,?> candidate) || !(candidate.get(group) instanceof Map<?,?> raw)) return Map.of();
        Map<String,Object> facts=(Map<String,Object>)raw;
        if(!kiwoomProvider(string(facts.get("source")),string(facts.get("environment")))
            || !Set.of("AVAILABLE","PARTIAL_COVERAGE").contains(string(facts.get("status")))) return Map.of();
        String returned=string(facts.get("requestedStockCode"));
        if(group.equals("quote")) returned=string(facts.get("stockCode"));
        Instant at=instant(facts.get("fetchedAt"));
        long maxAge=switch(group){case "technical","investorDaily"->600;case "valuation"->60;default->180;};
        if(!code.equals(returned) || at==null || at.isAfter(asOf.plusSeconds(1))
            || at.isBefore(asOf.minusSeconds(maxAge))) return Map.of();
        return facts;
    }
    private static Flow secondaryFlow(String code,Map<String,Object> facts,int days) {
        if(days<1 || days>5 || facts.isEmpty() || !"shares".equals(facts.get("quantityUnit"))
            || !"DAILY_NET_BUY_PREVIOUS_TRADING_DAYS".equals(facts.get("flowBasis")) || !(facts.get("observations") instanceof List<?> rows) || rows.size()>5) return null;
        TreeMap<LocalDate,long[]> dated=new TreeMap<>(Comparator.reverseOrder());
        try {
            for(Object value:rows) {
                if(!(value instanceof Map<?,?> row)) return null;
                LocalDate date=LocalDate.parse(string(row.get("observedDate")));
                if(!com.bitman.justbuy.util.KoreanMarketCalendar.isTradingDay(date) || dated.putIfAbsent(date,new long[]{
                    new BigDecimal(String.valueOf(row.get("foreignNetBuyShares"))).longValueExact(),
                    new BigDecimal(String.valueOf(row.get("institutionNetBuyShares"))).longValueExact()})!=null) return null;
            }
            if(dated.size()<days) return null;
            LocalDate last=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(LocalDate.now(KST)),cursor=last,first=last;
            long foreign=0,institution=0;
            for(int i=0;i<days;i++) {
                long[] measured=dated.get(cursor);if(measured==null)return null;
                foreign=Math.addExact(foreign,measured[0]);institution=Math.addExact(institution,measured[1]);first=cursor;
                cursor=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(cursor);
            }
            return new Flow(code,foreign,institution,days,first.toString(),last.toString(),instant(facts.get("fetchedAt")),string(facts.get("source")),string(facts.get("environment")));
        }catch(RuntimeException ignored){return null;}
    }
    private static BigDecimal decimal(Object raw,boolean integer,boolean signed) {
        if(!(raw instanceof Number)&&!(raw instanceof String))return null;
        String value=raw.toString();if(value.length()>48||!value.matches((signed?"[+-]?":"")+"[0-9]+"+(integer?"":"(?:\\.[0-9]+)?")))return null;
        try{return new BigDecimal(value);}catch(RuntimeException ignored){return null;}
    }
    private static QuoteObservation secondaryObservation(Quote primary,Map<String,Object> facts) {
        if(facts.isEmpty() || !normalize(primary.name()).equals(normalize(string(facts.get("stockName"))))
            || Set.of("KOSPI","KOSDAQ").contains(string(facts.get("market"))) && primary.market()!=null
                && !primary.market().equals(string(facts.get("market"))))return null;
        BigDecimal price=decimal(facts.get("currentPrice"),true,false);if(price==null||price.signum()<=0)return null;
        Instant observed=instant(facts.get("observedAt")),fetched=instant(facts.get("fetchedAt"));
        // Match the adapter's documented tolerance for provider/server clock differences.
        if(observed!=null&&observed.isAfter(fetched.plusSeconds(5)))return null;
        return new QuoteObservation(string(facts.get("source")),string(facts.get("environment")),price,decimal(facts.get("changePct"),false,true),
            decimal(facts.get("volume"),true,false),decimal(facts.get("tradingValueKrw"),true,false),fetched,observed);
    }
    private static boolean validCrossQuote(Quote primary,Quote cross) {
        return validCrossQuote(primary,cross,Instant.now());
    }
    private static boolean validCrossQuote(Quote primary,Quote cross,Instant asOf) {
        if(cross==null || cross.code()==null || !primary.code().equals(cross.code()) || cross.source()==null
            || "UNKNOWN".equals(environment(cross.source())) || cross.price()==null || cross.price().signum()<=0
            || cross.price().stripTrailingZeros().scale()>0 || cross.fetchedAt()==null
            || cross.fetchedAt().isAfter(asOf.plusSeconds(1)) || cross.fetchedAt().isBefore(asOf.minusSeconds(180))
            || cross.observedAt()!=null && cross.observedAt().isAfter(asOf.plusSeconds(60))) return false;
        // The KIS client binds its quote to the exact requested code. Missing names remain missing;
        // provided names must agree with the independently verified primary candidate.
        if(cross.name()==null || cross.name().isBlank()) {if(!"KIS OpenAPI".equals(cross.source()))return false;}
        else if(!normalize(primary.name()).equals(normalize(cross.name())))return false;
        return !(Set.of("KOSPI","KOSDAQ").contains(string(cross.market())) && primary.market()!=null && !primary.market().equals(cross.market()));
    }
    private static BigDecimal metric(Quote q,String field) {
        return switch(field) { case "PRICE" -> q.price(); case "CHANGE_PCT" -> q.changePct();
            case "VOLUME" -> q.volume(); case "TRADING_VALUE" -> q.tradingValueKrw(); default -> null; };
    }
    private static boolean compare(BigDecimal v,Filter f) {
        int c=v.compareTo(f.threshold());
        return switch(f.operator()) { case "GTE" -> c>=0; case "LTE" -> c<=0; case "GT" -> c>0; case "LT" -> c<0; case "EQ" -> c==0; default -> false; };
    }
    private static Comparator<Quote> order(Spec spec) {
        Comparator<BigDecimal> numbers=spec.descending() ? Comparator.reverseOrder():Comparator.naturalOrder();
        return Comparator.comparing((Quote q)->metric(q,spec.sortBy()),Comparator.nullsLast(numbers)).thenComparing(Quote::code);
    }
    private static String unit(String field) { return switch(field) { case "CHANGE_PCT" -> "%"; case "VOLUME" -> "주"; default -> "원"; }; }
    private static String normalize(String text) { return text.replaceAll("\\s+","").toLowerCase(Locale.ROOT); }
    private static KeywordSearchResponse response(String query,String outcome,Spec spec,Scope scope,Instant at,String status,List<String> warnings,List<Match>matches) {
        return new KeywordSearchResponse(query,outcome,spec.appliedConditions(),spec.unsupportedTerms(),scope,at,status,
            warnings==null ? List.of():List.copyOf(warnings),matches);
    }
}
