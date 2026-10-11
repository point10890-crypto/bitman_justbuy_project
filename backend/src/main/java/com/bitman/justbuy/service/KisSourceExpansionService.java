package com.bitman.justbuy.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;
import java.time.Instant;
@Service
public class KisSourceExpansionService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(KisSourceExpansionService.class);
    public record Enrichment(String prompt,Map<String,Object> metadata) {}
    private final KisSourceGateway gateway;
    private final StockUniverseService universe;
    private final KisCandidateSourceService candidates;
    private final KisResearchEvidenceService research;
    private final KisRealtimeFeedService realtime;
    private final ObjectMapper mapper;
    private KisSupplementalEvidenceService supplementalResearch;
    private KisRealtimeSupplementService supplementalRealtime;
    private volatile Map<String,Object> latestCalendar=Map.of();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureSupplementalResearch(KisSupplementalEvidenceService service) { this.supplementalResearch=service; }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureSupplementalRealtime(KisRealtimeSupplementService service) { this.supplementalRealtime=service; }
    public KisSourceExpansionService(KisSourceGateway gateway,StockUniverseService universe,KisCandidateSourceService candidates,KisResearchEvidenceService research,KisRealtimeFeedService realtime,ObjectMapper mapper) {
        this.gateway=gateway;this.universe=universe;this.candidates=candidates;this.research=research;this.realtime=realtime;this.mapper=mapper;
    }
    @jakarta.annotation.PostConstruct
    public void reportReadiness() { log.info("[KIS Source Expansion] v2 read-only adapters=5 status={}; delivery KST=08:50,11:50,14:50",gateway.readiness().get("status")); }
    /** Explicit scheduler preparation only; disabled sources never acquire tokens or download masters. */
    public void prepare() {
        if (!gateway.isEnabled()) return;
        if (!Set.of("AVAILABLE","PARTIAL").contains(universe.status())) universe.refresh();
        var snapshot=candidates.snapshot();
        var selected=snapshot.quotes().stream().limit(20).map(KeywordSearchDataService.Quote::code).toList();
        if(supplementalRealtime!=null) supplementalRealtime.watch(selected);
        realtime.watch(selected);
        // Prime bounded facts before the delivery slot; existing caches retain original capture times.
        research.fetchEvidence(selected.stream().limit(5).toList());
        if(supplementalResearch!=null) rememberCalendar(supplementalResearch.fetchEvidence(selected));
        if(supplementalRealtime!=null) supplementalRealtime.historicalEvidence(selected);
    }
    public KeywordSearchDataService.Snapshot candidateSnapshot() {
        if (!gateway.isEnabled()) return new KeywordSearchDataService.Snapshot("UNAVAILABLE",List.of(),Instant.now(),"KIS 신규 소스 비활성",List.of());
        return candidates.snapshot();
    }
    public Enrichment enrich(List<String>codes) {
        if (!gateway.isEnabled()) return new Enrichment("",Map.of("status",gateway.readiness().get("status"),"requestedCodes",0,"enrichedCodes",0,"availableFacts",0,"realtimeFreshQuotes",0));
        var selected=codes==null?List.<String>of():codes.stream().filter(Objects::nonNull).filter(c->c.matches("[0-9]{6}")).distinct().limit(5).toList();
        if(supplementalRealtime!=null) supplementalRealtime.watch(selected);
        realtime.watch(selected);
        var batch=research.fetchEvidence(selected);
        var supplemental=supplementalResearch==null?Map.<String,Object>of("status","NOT_CONFIGURED"):supplementalResearch.fetchEvidence(selected);
        rememberCalendar(supplemental);
        var historical=supplementalRealtime==null?Map.<String,Object>of("status","NOT_CONFIGURED"):supplementalRealtime.historicalEvidence(selected);
        var supplementalLive=new LinkedHashMap<String,Map<String,Object>>();
        var states=new LinkedHashMap<String,Object>();
        var master=new ArrayList<StockUniverseService.Stock>();
        var live=new LinkedHashMap<String,Map<String,Object>>();
        int available=0,enriched=0,fresh=0;
        for (String code:selected) {
            universe.find(code).ifPresent(master::add);
            var facts=realtime.facts(code);live.put(code,facts);
            if(supplementalRealtime!=null) supplementalLive.put(code,supplementalRealtime.facts(code));
            if ("AVAILABLE".equals(facts.get("status"))) fresh++;
        }
        for(var item:batch.candidates()) {
            int count=0;
            var sourceStates=new LinkedHashMap<String,Object>();
            var groups=Map.of("technical",item.technical(),"valuation",item.valuation(),"financial",item.financial(),"investorEstimate",item.investorEstimate(),"shortSale",item.shortSale(),"loan",item.loan());
            for(var entry:groups.entrySet()) {
                sourceStates.put(entry.getKey(),entry.getValue().getOrDefault("status","UNAVAILABLE"));
                if("AVAILABLE".equals(entry.getValue().get("status"))) count++;
            }
            available+=count;if(count>0) enriched++;
            sourceStates.put("realtime",live.getOrDefault(item.stockCode(),Map.of()).getOrDefault("status","UNAVAILABLE"));
            states.put(item.stockCode(),sourceStates);
        }
        if(supplemental.get("candidates") instanceof Map<?,?> extraCandidates) {
            for(String code:selected) if(extraCandidates.get(code) instanceof Map<?,?> extraFacts) {
                int extraCount=(int)extraFacts.values().stream().filter(value -> value instanceof Map<?,?> facts && "AVAILABLE".equals(facts.get("status"))).count();
                available+=extraCount;
                if(extraCount>0 && batch.candidates().stream().filter(item->code.equals(item.stockCode())).noneMatch(item ->
                    List.of(item.technical(),item.valuation(),item.financial(),item.investorEstimate(),item.shortSale(),item.loan()).stream().anyMatch(facts -> "AVAILABLE".equals(facts.get("status"))))) enriched++;
                states.put(code+"Supplemental",extraFacts.entrySet().stream().collect(java.util.stream.Collectors.toMap(entry->String.valueOf(entry.getKey()),entry->entry.getValue() instanceof Map<?,?> facts?Objects.toString(facts.get("status"),"UNAVAILABLE"):"UNAVAILABLE",(a,b)->a,LinkedHashMap::new)));
            }
        }
        states.put("supplementalResearch",supplemental.getOrDefault("status","UNAVAILABLE"));
        states.put("historicalMinutes",historical.getOrDefault("status","UNAVAILABLE"));
        String combinedStatus=batch.status();
        if("UNAVAILABLE".equals(combinedStatus) && enriched>0) combinedStatus="PARTIAL";
        if("AVAILABLE".equals(combinedStatus) && (supplementalResearch!=null && !"AVAILABLE".equals(supplemental.get("status"))
            || supplementalRealtime!=null && (!"AVAILABLE".equals(historical.get("status")) || fresh<selected.size()))) combinedStatus="PARTIAL";
        var metadata=Map.<String,Object>of("status",combinedStatus,"requestedCodes",selected.size(),"enrichedCodes",enriched,"availableFacts",available,"realtimeFreshQuotes",fresh,"sourceStates",states);
        try {
            String prompt="\n[KIS 신규 소스 검증 근거] 미확인 항목은 판단 근거로 사용하지 않는다. 장중 수급 ESTIMATE_UNDATED는 날짜 미확인 추정치이며 확정 수급이 아니다. 재무 units UNCONFIRMED를 점수·금액으로 환산하지 않는다. 일봉 observedDate는 날짜이며 실시간 체결시각이 아니다. 테마 소속은 마스터 분류이며 현재 뉴스·호재를 의미하지 않는다. 업종이 숫자 코드이면 정식 업종명 미확인이다. 마스터는 숫자 6자리 코드만 포함하므로 전체 상장시장이라고 간주하지 않는다.\n"
                +mapper.writeValueAsString(Map.of("master",master,"research",batch,"realtime",live,"supplementalResearch",supplemental,"historicalMinutes",historical,"marketSeparatedRealtime",supplementalLive));
            return new Enrichment(prompt,metadata);
        } catch(Exception ignored) { return new Enrichment("\n[KIS 신규 근거 표현 변환 실패]\n",metadata); }
    }
    public Map<String,Object> readiness() {
        return Map.of("gateway",gateway.readiness(),"master",universe.status(),"candidate",candidates.readiness(),"research",research.readiness(),"realtime",realtime.readiness(),"supplementalResearch",supplementalResearch==null?Map.of("status","NOT_CONFIGURED"):supplementalResearch.readiness(),"supplementalRealtime",supplementalRealtime==null?Map.of("status","NOT_CONFIGURED"):supplementalRealtime.readiness(),"scheduledTimesKst",List.of("08:50","11:50","14:50"));
    }
    private void rememberCalendar(Map<String,Object> evidence) {
        if(evidence!=null && evidence.get("calendar") instanceof Map<?,?> calendar && "AVAILABLE".equals(calendar.get("status"))) {
            var copy=new LinkedHashMap<String,Object>();calendar.forEach((key,value)->copy.put(String.valueOf(key),value));latestCalendar=Map.copyOf(copy);
        }
    }
    /** A validated official closure can veto delivery; it never opens a day blocked by the existing calendar. */
    public boolean isOfficiallyClosedToday() {
        if(!gateway.isEnabled()) return false;
        String today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
        var calendar=latestCalendar;
        if(!today.equals(calendar.get("requestDate")) || !(calendar.get("calendar") instanceof List<?> rows)) return false;
        return rows.stream().anyMatch(row -> row instanceof Map<?,?> entry && today.equals(entry.get("calendarDate")) && "N".equals(entry.get("opnd_yn")));
    }
}
