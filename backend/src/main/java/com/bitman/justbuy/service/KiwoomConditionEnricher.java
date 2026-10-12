package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Independent Kiwoom observations for existing KIS candidates; never replaces or sums primary facts. */
@Service
public class KiwoomConditionEnricher {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final Set<String> USABLE=Set.of("AVAILABLE","PARTIAL_COVERAGE");
    private final KiwoomReadOnlyService adapter;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public KiwoomConditionEnricher(KiwoomReadOnlyService adapter) { this(adapter,Clock.systemUTC()); }
    KiwoomConditionEnricher(KiwoomReadOnlyService adapter,Clock clock) { this.adapter=adapter;this.clock=clock; }

    public record Result(Map<String,Map<String,Object>> facts,Map<String,Object> coverage) {
        public Map<String,Object> forCode(String code) { return facts.getOrDefault(code,Map.of()); }
        public List<String> evidence(String code) {
            Map<String,Object> groups=forCode(code);
            if(groups.isEmpty()) return List.of();
            String prefix=Objects.toString(coverage.get("source"),"KIWOOM_REST")+" ("+coverage.get("environment")+")";
            List<String> lines=new ArrayList<>();
            for(String part:List.of("quote","valuation","technical","investorDaily")) {
                if(!(groups.get(part) instanceof Map<?,?> fact)) continue;
                String status=Objects.toString(fact.get("status"),"UNAVAILABLE");
                if(!USABLE.contains(status)) { lines.add(prefix+" "+label(part)+" 미확인 ("+status+")");continue; }
                String date=Objects.toString(fact.get("observedDate"),"");
                String captured=Objects.toString(fact.get("fetchedAt"),"");
                String detail=switch(part) {
                    case "quote" -> "조회 시세 "+number(fact.get("currentPrice"))+"원 · "+
                        (fact.get("observedAt")==null?"체결 시각 미제공":"관측 "+fact.get("observedAt"));
                    case "valuation" -> "PER "+number(fact.get("per"))+" · PBR "+number(fact.get("pbr"))+
                        " · ROE "+number(fact.get("roe"))+"% · 재무 기준 기간 미제공";
                    case "technical" -> "완료 일봉 "+number(fact.get("bars"))+"개 · "+
                        Objects.toString(fact.get("fromDate"),"시작일 미확인")+" ~ "+date+
                        " · MA20 "+number(fact.get("ma20"))+" · MA60 "+number(fact.get("ma60"))+
                        " · 20일 고가 돌파 "+Objects.toString(fact.get("breakout20"),"미확인");
                    default -> "완료 거래일 수급 "+number(fact.get("days"))+"일 · 기준 "+date+
                        (Integer.valueOf(5).equals(integer(fact.get("days")))?
                            " · 5일 외국인 "+number(fact.get("foreignNetBuy5dShares"))+"주 / 기관 "+number(fact.get("institutionNetBuy5dShares"))+"주":
                            " · 5일 수급 기간 부족, 합계 미확인");
                };
                lines.add(prefix+" "+detail+("PARTIAL_COVERAGE".equals(status)?" · 부분 기간":"")+" · 조회 "+captured);
            }
            return List.copyOf(lines);
        }
        public String prompt() {
            try {return "\n[KIS·키움 동시 조회 근거] 각 공급자의 원자료를 독립적으로 사용한다. 가격을 평균하거나 수급을 합산하지 않는다. "
                +"조회 시각은 거래 시각이 아니며, 재무 기준 기간 미제공·부분 일봉/수급은 전체 기간 검증으로 간주하지 않는다. "
                +"MOCK는 접속 서버 환경이며 응답에 확인된 시장자료의 원 출처·관측일·단위를 유지한다.\n"
                +new ObjectMapper().writeValueAsString(Map.of("coverage",coverage,"candidates",facts));}
            catch(Exception ignored) {return "\n[키움 근거 변환 미확인]\n";}
        }
        public ConditionSignalDto attach(ConditionSignalDto signal) {
            List<String> added=evidence(signal.stockCode());
            if(added.isEmpty()) return signal;
            List<String> evidence=new ArrayList<>(signal.evidence()==null?List.of():signal.evidence());
            for(String line:added) if(!evidence.contains(line)) evidence.add(line);
            return new ConditionSignalDto(signal.section(),signal.mode(),signal.rank(),signal.stockName(),signal.stockCode(),
                signal.capturePrice(),signal.currentPrice(),signal.highPrice(),signal.stopLoss(),signal.maxReturnPct(),signal.status(),
                signal.ruleScore(),signal.aiScore(),signal.finalScore(),signal.summary(),List.copyOf(evidence),signal.riskFlags(),
                signal.invalidation(),signal.capturedAt());
        }
        private static String label(String part) {return switch(part) {case "quote" -> "시세";case "valuation" -> "PER/PBR/ROE";case "technical" -> "일봉";default -> "수급";};}
        private static String number(Object value) {
            if(value==null) return "미확인";
            try {return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();}
            catch(NumberFormatException ignored) {return "미확인";}
        }
    }

    public Result enrich(Map<String,String> candidateNames) {
        if(adapter==null || candidateNames==null || candidateNames.isEmpty()) return empty("NOT_REQUESTED");
        try {
            if(!adapter.isEnabled()) return empty("DISABLED");
            Map<String,Object> ready=adapter.readiness();
            String environment=Objects.toString(ready.get("environment"),"");
            String source="MOCK".equals(environment)?"KIWOOM_REST_MOCK":"REAL".equals(environment)?"KIWOOM_REST":"";
            if(source.isEmpty()) return empty("INVALID_ENVIRONMENT");
            var selected=new LinkedHashMap<String,String>();
            candidateNames.forEach((code,name)->{if(selected.size()<3 && code!=null && code.matches("[0-9]{6}") && name!=null && !name.isBlank()) selected.putIfAbsent(code,name);});
            var requested=KiwoomSupplementPolicy.concurrentParts(new ArrayList<>(selected.keySet()));
            if(requested.isEmpty()) return empty("NOT_REQUESTED");
            Map<String,Object> batch=adapter.fetchEvidence(new ArrayList<>(requested.keySet()),requested);
            Map<?,?> raw=batch!=null && source.equals(batch.get("source")) && environment.equals(batch.get("environment"))
                && batch.get("candidates") instanceof Map<?,?> rows?rows:Map.of();
            Map<String,Map<String,Object>> normalized=new LinkedHashMap<>();
            Map<String,Object> states=new LinkedHashMap<>();
            int available=0,usable=0,enriched=0;
            for(String code:requested.keySet()) {
                Map<?,?> groups=raw.get(code) instanceof Map<?,?> row?row:Map.of();
                Map<String,Object> facts=new LinkedHashMap<>();Map<String,String> partStates=new LinkedHashMap<>();int confirmed=0;
                for(String part:requested.get(code)) {
                    var fact=normalize(groups.get(part),code,selected.get(code),part,source,environment);
                    facts.put(part,fact);String status=Objects.toString(fact.get("status"));partStates.put(part,status);
                    if("AVAILABLE".equals(status)) available++;
                    if(USABLE.contains(status)) {usable++;confirmed++;}
                }
                if(confirmed>0) enriched++;
                normalized.put(code,Collections.unmodifiableMap(facts));states.put(code,Collections.unmodifiableMap(partStates));
            }
            String status=available==requested.size()*4?"AVAILABLE":usable>0?"PARTIAL":"UNAVAILABLE";
            Map<String,Object> coverage=new LinkedHashMap<>();coverage.put("status",status);coverage.put("source",source);coverage.put("environment",environment);
            coverage.put("requestedCodes",requested.size());coverage.put("enrichedCodes",enriched);coverage.put("availableFacts",available);coverage.put("usableFacts",usable);
            coverage.put("sourceStates",Collections.unmodifiableMap(states));coverage.put("requestedParts",requested);coverage.put("candidateLimit",3);
            return new Result(Collections.unmodifiableMap(normalized),Collections.unmodifiableMap(coverage));
        } catch(RuntimeException ignored) {return empty("UNAVAILABLE");}
    }

    private Map<String,Object> normalize(Object value,String code,String name,String part,String source,String environment) {
        if(!(value instanceof Map<?,?> raw)) return rejected("UNAVAILABLE",source,environment);
        String status=Objects.toString(raw.get("status"),"UNAVAILABLE");
        if(!USABLE.contains(status)) return rejected(safeStatus(status),source,environment);
        Object responseCode=raw.get("stockCode"),requestedCode=raw.get("requestedStockCode");
        if(!source.equals(raw.get("source")) || !environment.equals(raw.get("environment"))
            || responseCode!=null && !code.equals(responseCode) || requestedCode!=null && !code.equals(requestedCode)
            || responseCode==null && requestedCode==null) return rejected("INVALID_DATA",source,environment);
        Instant fetched=instant(raw.get("fetchedAt")),now=clock.instant();
        if(fetched==null || fetched.isAfter(now.plusSeconds(1)) || fetched.isBefore(now.minusSeconds("quote".equals(part)?60:600)))
            return rejected("STALE_DATA",source,environment);
        if("quote".equals(part) && (!name.equals(raw.get("stockName")) || !positiveInteger(raw.get("currentPrice")))) return rejected("INVALID_DATA",source,environment);
        if(Set.of("technical","investorDaily").contains(part)) {
            LocalDate observed=date(raw.get("observedDate")),today=LocalDate.now(clock.withZone(KST));
            LocalDate completed="technical".equals(part) && KoreanMarketCalendar.isTradingDay(today)
                && clock.instant().atZone(KST).toLocalTime().isAfter(LocalTime.of(15,35))?today:KoreanMarketCalendar.previousTradingDay(today);
            if(observed==null || !observed.equals(completed)) return rejected("STALE_DATA",source,environment);
            Integer count=integer(raw.get("technical".equals(part)?"bars":"days"));
            if(count==null || count<1 || count>("technical".equals(part)?100:5)) return rejected("INVALID_DATA",source,environment);
            if(count<("technical".equals(part)?60:5)) status="PARTIAL_COVERAGE";
        }
        Map<String,Object> fact=new LinkedHashMap<>();raw.forEach((key,item)->{if(key instanceof String field && item!=null) fact.put(field,item);});
        fact.put("status",status);
        return Collections.unmodifiableMap(fact);
    }
    public static Result empty(String status) {return new Result(Map.of(),Map.of("status",status));}
    private static Map<String,Object> rejected(String status,String source,String environment) {return Map.of("status",status,"source",source,"environment",environment);}
    private static String safeStatus(String status) {return Set.of("EMPTY","UNAVAILABLE","UNDATED","DISABLED","BUDGET_EXCEEDED","NOT_REQUESTED","PROVIDER_UNAVAILABLE","AUTH_UNAVAILABLE","STALE_DATA","INVALID_DATA","INTERRUPTED").contains(status)?status:"UNAVAILABLE";}
    private static Instant instant(Object value) {try {return value==null?null:Instant.parse(value.toString());}catch(DateTimeException ignored) {return null;}}
    private static LocalDate date(Object value) {try {return value==null?null:LocalDate.parse(value.toString());}catch(DateTimeException ignored) {return null;}}
    private static Integer integer(Object value) {try {return value==null?null:new BigDecimal(value.toString()).intValueExact();}catch(ArithmeticException|NumberFormatException ignored) {return null;}}
    private static boolean positiveInteger(Object value) {try {return value!=null && new BigDecimal(value.toString()).longValueExact()>0;}catch(ArithmeticException|NumberFormatException ignored) {return false;}}
}
