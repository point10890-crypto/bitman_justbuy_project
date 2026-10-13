package com.bitman.justbuy.research;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.channels.FileChannel;

/** Explicit offline entrypoint. No Spring registration, market request, model request or order. */
public final class ResearchCoreCli {
    private static final String VERSION="profit-research-core-v1";
    private static final Object PUBLICATION_LOCK=new Object();
    private static final ObjectMapper JSON=JsonMapper.builder().addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    public static void main(String[] args) {
        try {
            if(args.length!=4||!"--snapshot".equals(args[0])||!"--workspace".equals(args[2]))throw new IllegalArgumentException("Expected snapshot and workspace");
            var report=execute(Path.of(args[1]),Path.of(args[3]),Clock.systemUTC());
            System.out.println(JSON.writeValueAsString(Map.of("savedSlots",report.get("savedSlots"),"uniqueStocks",report.get("uniqueStocks"),
                "measuredOutcomes",report.get("measuredOutcomes"),"profitabilityStatus",report.get("profitabilityStatus"),"productionActivationAllowed",false)));
        } catch(Exception ex){System.err.println("Research run failed: verify the public input and owned research workspace.");System.exit(1);}
    }
    public static Map<String,Object> execute(Path snapshot,Path workspace,Clock clock) {
        try {
            var input=new PublicPickSnapshotAdapter().read(snapshot,clock.instant(),Duration.ofMinutes(30));
            var request=new ResearchConditionEngine.Request(new ResearchConditionEngine.Compare(ResearchConditionEngine.Metric.REWARD_RISK,
                ResearchConditionEngine.Operator.GTE,BigDecimal.ONE,ResearchConditionEngine.Unit.MULTIPLE),ResearchConditionEngine.Metric.REWARD_RISK,
                ResearchConditionEngine.Direction.DESC,ResearchConditionEngine.SelectionOrder.FILTER_THEN_RANK,20);
            String fingerprint=hash(VERSION+"|"+input.inputDigest()+"|"+input.snapshot().cutoff()+"|max-age=30m|scenario-distance>=1");
            String runId,payload;boolean reused;
            try(var store=new ResearchRunStore(workspace,clock)) {
                runId=store.createRun("PUBLIC_RESEARCH",fingerprint);
                var cached=store.reusedOutput(runId,"PUBLIC_PICK_REVIEW",fingerprint);
                reused=cached.isPresent();
                if(reused)payload=cached.orElseThrow();
                else {
                    var lease=store.reserveStage(runId,"PUBLIC_PICK_REVIEW",fingerprint,0);
                    var condition=new ResearchConditionEngine().evaluate(input.snapshot(),request);
                    var policy=new ResearchLearningService.Policy("BASELINE_V1","CHALLENGER_V1",5,"NEXT_KRX_OPEN_5_CLOSE_V1","UNCONFIGURED",VERSION,Map.of());
                    // A quote archive is not a publication ledger. Missing publication/venue/calendar
                    // metadata is sent through the evaluator and remains explicitly unmeasurable.
                    var outcomes=new ArrayList<ResearchOutcomeEvaluator.Outcome>();
                    var evaluator=new ResearchOutcomeEvaluator();
                    var evaluationPolicy=new ResearchOutcomeEvaluator.Policy("NEXT_KRX_OPEN_5_CLOSE_V1",5,
                        ResearchOutcomeEvaluator.Measurement.NEXT_SESSION_OPEN_SIMULATION,null,null,null);
                    for(var candidate:input.snapshot().candidates()) {
                        var quote=candidate.facts().getFirst();
                        var signal=new ResearchOutcomeEvaluator.Signal("archive-"+candidate.id(),candidate.id(),null,quote.observedAt(),quote.value(),null,null,
                            quote.environment()==ResearchConditionEngine.Environment.MOCK?ResearchOutcomeEvaluator.Provenance.MOCK:ResearchOutcomeEvaluator.Provenance.REAL,ResearchOutcomeEvaluator.Adjustment.RAW);
                        outcomes.add(evaluator.evaluate(signal,List.of(),List.of(),evaluationPolicy,clock.instant(),null));
                    }
                    var learning=new ResearchLearningService().compare(List.of(),policy);
                    var result=new LinkedHashMap<String,Object>();
                    result.put("schemaVersion",VERSION);result.put("dataOrigin","ACTUAL_PUBLIC_SAVED_PICKS");
                    result.put("decisionCutoff",input.snapshot().cutoff());result.put("runId",runId);result.put("inputDigest",input.inputDigest());
                    result.put("savedSlots",input.slotCount());result.put("uniqueStocks",input.distinctStocks());result.put("priceDistanceWarnings",input.rewardRiskBelowOne());
                    result.put("conditionDefinition","saved-price-distance >= 1; CURRENT_PRICE valid within 30 minutes; known subset only");
                    result.put("conditionResult",condition);result.put("outcomes",outcomes);
                    result.put("measuredOutcomes",(int)outcomes.stream().filter(o->o.state()==ResearchOutcomeEvaluator.State.COMPLETE).count());result.put("profitabilityStatus",learning.status().name());
                    result.put("learningReview",learning);result.put("productionActivationAllowed",false);result.put("limitations",input.limitations());
                    payload=JSON.writeValueAsString(result);
                    store.completeStage(lease,payload,true);
                }
            }
            @SuppressWarnings("unchecked") Map<String,Object> report=JSON.readValue(payload,LinkedHashMap.class);
            report.put("resultDigest",hash(payload));report.put("reusedValidatedStage",reused);
            String output=JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n";
            publishReport(workspace,report,output);
            return Collections.unmodifiableMap(report);
        } catch(IllegalArgumentException|IllegalStateException ex){throw ex;}
        catch(Exception ex){throw new IllegalStateException("Research execution did not complete");}
    }
    private static String hash(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static void publishReport(Path workspace,Map<String,Object> report,String output)throws Exception {
        synchronized(PUBLICATION_LOCK) {
            Path lockPath=workspace.resolve(".report-publication.lock");
            if(Files.isSymbolicLink(lockPath))throw new IllegalArgumentException("Invalid publication lock boundary");
            try(var channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.lock()) {
                Path runs=workspace.resolve("runs");
                if(Files.isSymbolicLink(runs))throw new IllegalArgumentException("Invalid report boundary");
                Files.createDirectories(runs);
                if(!runs.toRealPath().equals(runs.toAbsolutePath().normalize()))throw new IllegalArgumentException("Redirected report boundary");
                Path archived=runs.resolve(report.get("runId")+".json");
                if(!Files.exists(archived,LinkOption.NOFOLLOW_LINKS))atomicWrite(archived,output);
                Path current=workspace.resolve("report.json");
                if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                    if(!Files.isRegularFile(current,LinkOption.NOFOLLOW_LINKS)||Files.size(current)>ResearchRunStore.MAX_JSON_BYTES)throw new IllegalArgumentException("Invalid current report");
                    JsonNode prior=JSON.readTree(Files.readAllBytes(current));
                    Instant oldCutoff=Instant.parse(prior.path("decisionCutoff").asText());
                    Instant newCutoff=Instant.parse(report.get("decisionCutoff").toString());
                    // Equal cutoff/different input has no proven recency; preserve the first committed pointer.
                    if(newCutoff.isBefore(oldCutoff)||(newCutoff.equals(oldCutoff)&&!Objects.equals(report.get("runId"),prior.path("runId").asText())))return;
                }
                atomicWrite(current,output);
                atomicWrite(workspace.resolve("report.md"),markdown(report));
            }
        }
    }
    private static void atomicWrite(Path target,String value)throws Exception {
        Path temporary=Files.createTempFile(target.getParent(),".report-",".tmp");
        try{Files.writeString(temporary,value,StandardCharsets.UTF_8);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(temporary);}
    }
    private static String markdown(Map<String,Object> r){
        return "# BitMan 수익성 연구 핵심 실행 결과\n\n"+
            "실제 공개 저장본을 읽어 조건 판정과 개선안 비교 기록을 실행했습니다.\n\n"+
            "- 저장 표시 항목: "+r.get("savedSlots")+"건\n- 고유 종목: "+r.get("uniqueStocks")+"개\n"+
            "- 목표/손절 가격 거리 비율 1 미만: "+r.get("priceDistanceWarnings")+"건\n"+
            "- 완료된 실제 미래 성과: 0건\n- 수익성 판단: **실증 부족**\n- 운영 자동 활성화: 허용 안 됨\n\n"+
            "원본은 이전 공개 픽 스냅샷입니다. 오래된 시세는 현재 검색을 통과하지 않습니다. 전체 시장 자료·영구 종목 마스터·미래 시가/종가가 없어 수익률을 생성하지 않습니다. 가격 거리 비율은 기대수익이 아닙니다.\n\n"+
            "같은 시점·입력·버전의 검증 완료 단계를 재사용합니다. 시각 또는 원본이 바뀌면 새 실행입니다.\n\n"+
            "실행: `"+r.get("runId")+"`\n결과 SHA-256: `"+r.get("resultDigest")+"`\n";
    }
}
