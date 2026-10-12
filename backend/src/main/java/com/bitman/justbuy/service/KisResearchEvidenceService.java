package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Optional, read-only source facts. No prices, quantities or dates are supplied by an LLM. */
@Service
public class KisResearchEvidenceService {
    public record CandidateEvidence(String stockCode, Map<String,Object> technical, Map<String,Object> valuation,
        Map<String,Object> financial, Map<String,Object> investorEstimate, Map<String,Object> shortSale, Map<String,Object> loan) {}
    public record EvidenceBatch(String status, Instant fetchedAt, List<CandidateEvidence> candidates) {}
    private final KisSourceGateway gateway;
    private final ObjectMapper mapper;
    private final Clock clock;
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final String QUOTATIONS="/uapi/domestic-stock/v1/quotations/";
    private static final DateTimeFormatter DAY=DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter MONTH=DateTimeFormatter.ofPattern("uuuuMM").withResolverStyle(ResolverStyle.STRICT);
    private static final long BUDGET_NANOS=TimeUnit.SECONDS.toNanos(12);
    private record Cached(KisSourceGateway.Snapshot snapshot,Instant expiresAt) {}
    private record Bar(LocalDate date,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,long volume) {}
    private final Map<String,Cached> cache=new ConcurrentHashMap<>();
    @Autowired
    public KisResearchEvidenceService(KisSourceGateway gateway, ObjectMapper mapper) { this(gateway,mapper,Clock.systemUTC()); }
    KisResearchEvidenceService(KisSourceGateway gateway, ObjectMapper mapper, Clock clock) { this.gateway=gateway;this.mapper=mapper;this.clock=clock; }
    public EvidenceBatch fetchEvidence(List<String> codes) {
        Instant asOf=clock.instant();
        if(!gateway.isEnabled()) return new EvidenceBatch("DISABLED",asOf,List.of());
        LinkedHashSet<String> selected=new LinkedHashSet<>();
        if(codes!=null) for(String code:codes) {
            if(code!=null && code.matches("[0-9]{6}")) selected.add(code);
            if(selected.size()==5) break;
        }
        if(selected.isEmpty()) return new EvidenceBatch("NO_CANDIDATES",asOf,List.of());
        long deadline=System.nanoTime()+BUDGET_NANOS;
        List<CandidateEvidence> evidence=new ArrayList<>();
        for(String code:selected) {
            Map<String,String> base=Map.of("FID_COND_MRKT_DIV_CODE","J","FID_INPUT_ISCD",code);
            LocalDate today=asOf.atZone(KST).toLocalDate();
            Map<String,String> dailyParams=new LinkedHashMap<>(base);
            dailyParams.put("FID_INPUT_DATE_1",today.minusDays(200).format(DAY));
            // A pre-close response must never be reused as a completed bar after the session closes.
            dailyParams.put("FID_INPUT_DATE_2",latestCompletedDate(asOf).format(DAY));
            dailyParams.put("FID_PERIOD_DIV_CODE","D"); dailyParams.put("FID_ORG_ADJ_PRC","0");
            var daily=read(QUOTATIONS+"inquire-daily-itemchartprice","FHKST03010100",dailyParams,Duration.ofMinutes(10),deadline);
            var price=read(QUOTATIONS+"inquire-price","FHKST01010100",base,Duration.ofMinutes(1),deadline);
            Map<String,Object> annual=financial(code,"0",asOf,deadline);
            Map<String,Object> quarterly=financial(code,"1",asOf,deadline);
            Map<String,Object> financial=new LinkedHashMap<>();
            financial.put("annual",annual);financial.put("quarterly",quarterly);
            financial.put("status",bothAvailable(annual,quarterly)?"AVAILABLE":anyAvailable(annual,quarterly)?"PARTIAL":
                "INVALID_RESPONSE".equals(annual.get("status")) || "INVALID_RESPONSE".equals(quarterly.get("status"))?"INVALID_RESPONSE":"UNAVAILABLE");
            var estimate=read(QUOTATIONS+"investor-trend-estimate","HHPTJ04160200",Map.of("MKSC_SHRN_ISCD",code),Duration.ofSeconds(30),deadline);
            Map<String,String> riskDates=new LinkedHashMap<>(base);
            riskDates.put("FID_INPUT_DATE_1",today.minusDays(30).format(DAY));riskDates.put("FID_INPUT_DATE_2",today.format(DAY));
            var shortSale=read(QUOTATIONS+"daily-short-sale","FHPST04830000",riskDates,Duration.ofMinutes(10),deadline);
            var loan=read(QUOTATIONS+"daily-loan-trans","HHPST074500C0",Map.of("MRKT_DIV_CLS_CODE","3","MKSC_SHRN_ISCD",code,
                "START_DATE",today.minusDays(30).format(DAY),"END_DATE",today.format(DAY),"CTS",""),Duration.ofMinutes(10),deadline);
            evidence.add(new CandidateEvidence(code,technical(daily,code,asOf),valuation(price),Map.copyOf(financial),
                estimate(estimate),shortSale(shortSale,asOf),loan(loan,asOf)));
        }
        boolean any=evidence.stream().anyMatch(e -> usable(e.technical()) || usable(e.valuation()) || usable(e.financial()) || usable(e.investorEstimate()) || usable(e.shortSale()) || usable(e.loan()));
        boolean complete=evidence.stream().allMatch(e -> List.of(e.technical(),e.valuation(),e.financial(),e.investorEstimate(),e.shortSale(),e.loan())
            .stream().allMatch(m -> "AVAILABLE".equals(m.get("status"))));
        return new EvidenceBatch(complete?"AVAILABLE":any?"PARTIAL":"UNAVAILABLE",clock.instant(),List.copyOf(evidence));
    }

    public String formattedEvidence(List<String> codes) {
        EvidenceBatch batch=fetchEvidence(codes);
        try {
            return "[KIS 검증 조회 근거] "+batch.status()+"\n"
                +"기술 지표는 실제 완료 일봉의 명시된 구간만 사용하며 100일봉을 52주 데이터로 간주하지 않는다. "
                +"장중 수급은 가집계 추정이며 확정 순매수·현재 영업일로 사용하지 않는다. "
                +"재무 결산년월은 공시 발표일이 아니며 분기/연간 구분과 미확인 단위를 유지한다.\n"
                +mapper.writeValueAsString(batch);
        } catch(Exception ignored) { return "[KIS 검증 조회 근거] UNAVAILABLE: 표현 변환 실패"; }
    }

    public Map<String,Object> readiness() {
        return Map.of("status",gateway.isEnabled()?"READY":"DISABLED","enabled",gateway.isEnabled(),"maxCandidates",5,
            "budgetMs",12000,"sources",List.of("completed_daily_bars","provider_52week_per_pbr","annual_quarterly_financial_ratio",
                "intraday_estimate_undated","daily_short_sale","daily_loan"),"financialUnitsConfirmed",false);
    }

    private KisSourceGateway.Snapshot read(String path,String tr,Map<String,String>params,Duration ttl,long deadline) {
        if(Thread.currentThread().isInterrupted()) return unavailableSnapshot("CANCELLED");
        if(System.nanoTime()>=deadline) return unavailableSnapshot("BUDGET_EXCEEDED");
        String key=path+new TreeMap<>(params);
        Cached cached=cache.get(key);Instant now=clock.instant();
        if(cached!=null && now.isBefore(cached.expiresAt()) && validCapture(cached.snapshot(),ttl)) return cached.snapshot();
        if(deadline-System.nanoTime()<=TimeUnit.SECONDS.toNanos(2)) return unavailableSnapshot("BUDGET_EXCEEDED");
        try {
            var snapshot=gateway.fetch(path,tr,params,deadline);
            if(System.nanoTime()>deadline) return unavailableSnapshot("BUDGET_EXCEEDED");
            if(snapshot==null) return unavailableSnapshot("UNAVAILABLE");
            if(!validCapture(snapshot,ttl)) return unavailableSnapshot("INVALID_TIMESTAMP");
            if("AVAILABLE".equals(snapshot.status()) && cache.size()<512) cache.put(key,new Cached(snapshot,now.plus(ttl)));
            return snapshot;
        } catch(Exception ignored) { return unavailableSnapshot("UNAVAILABLE"); }
    }

    private boolean validCapture(KisSourceGateway.Snapshot snapshot,Duration ttl) {
        return snapshot!=null && snapshot.fetchedAt()!=null && !snapshot.fetchedAt().isAfter(clock.instant())
            && !snapshot.fetchedAt().isBefore(clock.instant().minus(ttl));
    }
    private KisSourceGateway.Snapshot unavailableSnapshot(String status) { return new KisSourceGateway.Snapshot(status,clock.instant(),mapper.createObjectNode()); }
    private static Map<String,Object> unavailable(String status) { return Map.of("status",status); }
    private static String error(KisSourceGateway.Snapshot snapshot) {
        if(snapshot==null) return "UNAVAILABLE";
        if(!"AVAILABLE".equals(snapshot.status())) return snapshot.status()==null?"UNAVAILABLE":snapshot.status();
        if(snapshot.body()==null || !snapshot.body().isObject()) return "INVALID_RESPONSE";
        if(!snapshot.body().path("rt_cd").isTextual() || !"0".equals(snapshot.body().path("rt_cd").asText())) return "PROVIDER_ERROR";
        return null;
    }
    private static LinkedHashMap<String,Object> provenance(KisSourceGateway.Snapshot snapshot) {
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();out.put("status","AVAILABLE");out.put("source","KIS OpenAPI");
        out.put("exchange","KRX");out.put("fetchedAt",snapshot.fetchedAt().toString());return out;
    }

    private Map<String,Object> technical(KisSourceGateway.Snapshot snapshot,String code,Instant asOf) {
        String error=error(snapshot);if(error!=null) return unavailable(error);
        JsonNode body=snapshot.body();String responseCode=body.path("output1").path("stck_shrn_iscd").asText("");
        if(!code.equals(responseCode)) return unavailable("INVALID_IDENTITY");
        JsonNode rows=body.path("output2");if(!rows.isArray() || rows.size()>100) return unavailable("INVALID_RESPONSE");
        if(rows.isEmpty()) return unavailable("EMPTY");
        TreeMap<LocalDate,Bar> bars=new TreeMap<>();Set<LocalDate> seen=new HashSet<>();
        LocalDate today=asOf.atZone(KST).toLocalDate();
        boolean todayComplete=asOf.atZone(KST).toLocalTime().isAfter(LocalTime.of(15,35));boolean incomplete=false;
        try {
            for(JsonNode row:rows) {
                LocalDate date=day(row.path("stck_bsop_date"));
                if(date==null || date.isAfter(today) || date.isBefore(today.minusDays(200)) || !KoreanMarketCalendar.isTradingDay(date) || !seen.add(date))
                    return unavailable("INVALID_RESPONSE");
                BigDecimal open=positive(row.path("stck_oprc")),high=positive(row.path("stck_hgpr")),low=positive(row.path("stck_lwpr")),close=positive(row.path("stck_clpr"));
                Long volume=integer(row.path("acml_vol"));
                if(open==null || high==null || low==null || close==null || volume==null || volume<0 || low.compareTo(high)>0
                    || open.compareTo(low)<0 || open.compareTo(high)>0 || close.compareTo(low)<0 || close.compareTo(high)>0) return unavailable("INVALID_RESPONSE");
                if(date.equals(today) && !todayComplete) { incomplete=true;continue; }
                bars.put(date,new Bar(date,open,high,low,close,volume));
            }
        } catch(Exception ignored) { return unavailable("INVALID_RESPONSE"); }
        if(bars.size()<20) return unavailable("INSUFFICIENT_COVERAGE");
        List<Bar> ordered=new ArrayList<>(bars.values());
        // Every date in the requested trailing period must have an actual row, including holidays in the exchange calendar.
        LocalDate expected=todayComplete && KoreanMarketCalendar.isTradingDay(today)?today:today.minusDays(1);
        while(!KoreanMarketCalendar.isTradingDay(expected)) expected=expected.minusDays(1);
        if(!bars.lastKey().equals(expected)) return unavailable("STALE_COVERAGE");
        int needed=Math.min(60,ordered.size());
        for(int i=ordered.size()-1;i>=ordered.size()-needed;i--) {
            if(!ordered.get(i).date().equals(expected)) return unavailable("INCOMPLETE_COVERAGE");
            expected=expected.minusDays(1);while(!KoreanMarketCalendar.isTradingDay(expected)) expected=expected.minusDays(1);
        }
        var out=provenance(snapshot);out.put("status",ordered.size()>=60?"AVAILABLE":"PARTIAL_COVERAGE");
        out.put("barCount",ordered.size());out.put("coverageStart",bars.firstKey().toString());out.put("coverageEnd",bars.lastKey().toString());
        out.put("observedDate",bars.lastKey().toString());out.put("observationTimeBasis","DATE_ONLY");
        out.put("priceUnit","KRW");out.put("volumeUnit","shares");out.put("adjustedPrices",true);out.put("incompleteCurrentBarExcluded",incomplete);
        BigDecimal ma5=meanClose(ordered,5),ma20=meanClose(ordered,20);out.put("ma5",ma5);out.put("ma20",ma20);
        if(ordered.size()>=60) {BigDecimal ma60=meanClose(ordered,60);out.put("ma60",ma60);out.put("maAligned",ma5.compareTo(ma20)>0 && ma20.compareTo(ma60)>0);}
        BigDecimal averageVolume=meanVolume(ordered.subList(ordered.size()-20,ordered.size()));out.put("averageVolume20",averageVolume);
        out.put("volumeAverageBasis","latest20CompletedBarsIncludingLatest");
        if(averageVolume.signum()>0) out.put("volumeRatio20",BigDecimal.valueOf(ordered.getLast().volume()).divide(averageVolume,4,RoundingMode.HALF_UP));
        if(ordered.size()>=21) {
            BigDecimal priorHigh=ordered.subList(ordered.size()-21,ordered.size()-1).stream().map(Bar::high).max(BigDecimal::compareTo).orElseThrow();
            out.put("previous20High",priorHigh);out.put("breakout20",ordered.getLast().high().compareTo(priorHigh)>0);
        }
        List<Bar> recent5=ordered.subList(ordered.size()-5,ordered.size()),prior15=ordered.subList(ordered.size()-20,ordered.size()-5);
        BigDecimal oldRange=range(prior15),newRange=range(recent5),oldVolume=meanVolume(prior15),newVolume=meanVolume(recent5);
        if(oldRange.signum()>0 && oldVolume.signum()>0) {
            out.put("rangeCompressionRatio5To15",newRange.divide(oldRange,4,RoundingMode.HALF_UP));
            out.put("volumeCompressionRatio5To15",newVolume.divide(oldVolume,4,RoundingMode.HALF_UP));
            out.put("contraction",newRange.compareTo(oldRange)<0 && newVolume.compareTo(oldVolume)<0);
        }
        out.putAll(DeterministicTechnicalEvidence.calculate(ordered.stream()
            .map(bar->new DeterministicTechnicalEvidence.Bar(bar.date(),bar.high(),bar.low(),bar.close())).toList(),bars.lastKey()));
        return Map.copyOf(out);
    }

    private Map<String,Object> valuation(KisSourceGateway.Snapshot snapshot) {
        String error=error(snapshot);if(error!=null) return unavailable(error);
        JsonNode row=snapshot.body().path("output");if(!row.isObject() || positive(row.path("stck_prpr"))==null) return unavailable("INVALID_RESPONSE");
        var out=provenance(snapshot);out.put("currentPrice",positive(row.path("stck_prpr")));out.put("priceUnit","KRW");
        out.put("observationTimeBasis","NOT_PROVIDED");
        putPositive(out,"per",row,"per");putPositive(out,"pbr",row,"pbr");
        BigDecimal high=positive(row.path("w52_hgpr")),low=positive(row.path("w52_lwpr"));
        if(high!=null && low!=null && high.compareTo(low)>=0) {out.put("high52",high);out.put("low52",low);}
        out.put("ratioUnit","multiple");return Map.copyOf(out);
    }

    private Map<String,Object> financial(String code,String type,Instant asOf,long deadline) {
        var snapshot=read("/uapi/domestic-stock/v1/finance/financial-ratio","FHKST66430300",
            Map.of("FID_DIV_CLS_CODE",type,"fid_cond_mrkt_div_code","J","fid_input_iscd",code),Duration.ofHours(6),deadline);
        String error=error(snapshot);if(error!=null) return unavailable(error);
        JsonNode rows=snapshot.body().path("output");
        if(rows.isObject()) rows=mapper.createArrayNode().add(rows);
        if(!rows.isArray() || rows.size()>100) return unavailable("INVALID_RESPONSE");
        if(rows.isEmpty()) return unavailable("EMPTY");
        TreeMap<YearMonth,JsonNode> dated=new TreeMap<>();YearMonth current=YearMonth.from(asOf.atZone(KST));
        try {for(JsonNode row:rows) {
            String raw=row.path("stac_yymm").asText("");if(!raw.matches("[0-9]{6}")) return unavailable("INVALID_RESPONSE");
            YearMonth period=YearMonth.parse(raw,MONTH);
            if(period.isAfter(current) || period.atEndOfMonth().isAfter(asOf.atZone(KST).toLocalDate()) || dated.putIfAbsent(period,row)!=null)
                return unavailable("INVALID_RESPONSE");
        }}catch(Exception ignored){return unavailable("INVALID_RESPONSE");}
        var out=provenance(snapshot);var latest=dated.lastEntry();JsonNode row=latest.getValue();
        out.put("period",latest.getKey().format(MONTH));out.put("periodType","0".equals(type)?"ANNUAL":"QUARTERLY");
        out.put("providerUnit","UNCONFIRMED");out.put("releaseTime","UNKNOWN");
        Map<String,String> fields=Map.of("roe","roe_val","eps","eps","bps","bps","debtRatio","lblt_rate",
            "revenueGrowth","grs","operatingProfitGrowth","bsop_prfi_inrt","netIncomeGrowth","ntin_inrt");
        int verified=0;for(var field:fields.entrySet()) {BigDecimal number=decimal(row.path(field.getValue()));if(number!=null){out.put(field.getKey(),number);verified++;}}
        if(verified==0) return unavailable("INVALID_RESPONSE");return Map.copyOf(out);
    }

    private Map<String,Object> estimate(KisSourceGateway.Snapshot snapshot) {
        String error=error(snapshot);if(error!=null) return unavailable(error);
        JsonNode rows=snapshot.body().path("output2");if(!rows.isArray() || rows.size()>30) return unavailable("INVALID_RESPONSE");
        List<Map<String,Object>> observations=new ArrayList<>();
        for(JsonNode row:rows) {
            Long foreign=integer(row.path("frgn_fake_ntby_qty")),institution=integer(row.path("orgn_fake_ntby_qty")),sum=integer(row.path("sum_fake_ntby_qty"));
            if(foreign==null || institution==null || sum==null) continue;
            try {if(Math.addExact(foreign,institution)!=sum) continue;}catch(ArithmeticException ignored){continue;}
            String group=row.path("bsop_hour_gb").asText("");if(group.isBlank() || group.length()>12) continue;
            observations.add(Map.of("inputClassification",group,"foreignEstimatedNetBuyShares",foreign,"institutionEstimatedNetBuyShares",institution,"sumEstimatedNetBuyShares",sum));
        }
        if(observations.isEmpty()) return unavailable(rows.isEmpty()?"EMPTY":"INVALID_RESPONSE");
        var out=provenance(snapshot);out.put("status","ESTIMATE_UNDATED");out.put("quantityUnit","shares");
        out.put("inputClassificationIsNotTimestamp",true);out.put("observations",List.copyOf(observations));
        // A single observation is safe to expose without pretending the classification identifies the latest session.
        if(observations.size()==1) out.putAll(observations.getFirst());return Map.copyOf(out);
    }

    private Map<String,Object> shortSale(KisSourceGateway.Snapshot snapshot,Instant asOf) {
        String error=error(snapshot);if(error!=null) return unavailable(error);
        var rows=datedRisk(snapshot.body().path("output2"),"stck_bsop_date",asOf);if(rows==null) return unavailable("INVALID_RESPONSE");
        if(rows.isEmpty()) return unavailable("EMPTY");var latest=rows.lastEntry();JsonNode row=latest.getValue();
        Long quantity=integer(row.path("ssts_cntg_qty")),volume=integer(row.path("acml_vol"));
        BigDecimal ratio=decimal(row.path("ssts_vol_rlim"));
        if(quantity==null || volume==null || quantity<0 || volume<0 || quantity>volume || ratio!=null && (ratio.signum()<0 || ratio.compareTo(new BigDecimal("100"))>0)) return unavailable("INVALID_RESPONSE");
        var out=provenance(snapshot);out.put("observedDate",latest.getKey().toString());out.put("shortSaleShares",quantity);out.put("tradedShares",volume);
        out.put("quantityUnit","shares");if(ratio!=null){out.put("shortSaleVolumeRatioRaw",ratio);out.put("ratioUnit","PROVIDER_UNIT_UNCONFIRMED");}return Map.copyOf(out);
    }

    private Map<String,Object> loan(KisSourceGateway.Snapshot snapshot,Instant asOf) {
        String error=error(snapshot);if(error!=null) return unavailable(error);
        var rows=datedRisk(snapshot.body().path("output1"),"bsop_date",asOf);if(rows==null) return unavailable("INVALID_RESPONSE");
        if(rows.isEmpty()) return unavailable("EMPTY");var latest=rows.lastEntry();JsonNode row=latest.getValue();
        Long added=integer(row.path("new_stcn")),redeemed=integer(row.path("rdmp_stcn")),balance=integer(row.path("rmnd_stcn")),change=integer(row.path("prdy_rmnd_vrss"));
        if(added==null || redeemed==null || balance==null || change==null || added<0 || redeemed<0 || balance<0) return unavailable("INVALID_RESPONSE");
        var out=provenance(snapshot);out.put("observedDate",latest.getKey().toString());out.put("newLoanShares",added);out.put("redeemedLoanShares",redeemed);
        out.put("loanBalanceShares",balance);out.put("loanBalanceChangeShares",change);out.put("quantityUnit","shares");return Map.copyOf(out);
    }

    private TreeMap<LocalDate,JsonNode> datedRisk(JsonNode rows,String field,Instant asOf) {
        if(!rows.isArray() || rows.size()>100) return null;TreeMap<LocalDate,JsonNode> dated=new TreeMap<>();
        LocalDate today=asOf.atZone(KST).toLocalDate();
        try {for(JsonNode row:rows){LocalDate date=day(row.path(field));
            if(date==null || date.isAfter(today) || date.isBefore(today.minusDays(30)) || !KoreanMarketCalendar.isTradingDay(date) || dated.putIfAbsent(date,row)!=null) return null;
        }}catch(Exception ignored){return null;}return dated;
    }
    private static boolean usable(Map<String,Object> map) {return Set.of("AVAILABLE","PARTIAL","PARTIAL_COVERAGE","ESTIMATE_UNDATED").contains(map.get("status"));}
    private static boolean bothAvailable(Map<String,Object>a,Map<String,Object>b){return "AVAILABLE".equals(a.get("status")) && "AVAILABLE".equals(b.get("status"));}
    private static boolean anyAvailable(Map<String,Object>a,Map<String,Object>b){return "AVAILABLE".equals(a.get("status")) || "AVAILABLE".equals(b.get("status"));}
    private static BigDecimal decimal(JsonNode node) {
        if(node==null || !node.isTextual() && !node.isNumber()) return null;String value=node.asText().trim();
        if(value.length()>48 || !value.matches("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) return null;
        try {BigDecimal number=new BigDecimal(value.replace(",",""));return number.abs().compareTo(new BigDecimal("1000000000000000"))<=0?number:null;}catch(NumberFormatException ignored){return null;}
    }
    private static BigDecimal positive(JsonNode node){BigDecimal value=decimal(node);return value!=null && value.signum()>0?value:null;}
    private static Long integer(JsonNode node){BigDecimal value=decimal(node);try{return value==null?null:value.longValueExact();}catch(ArithmeticException ignored){return null;}}
    private static LocalDate day(JsonNode node){String value=node.asText("");return value.matches("[0-9]{8}")?LocalDate.parse(value,DAY):null;}
    private static void putPositive(Map<String,Object>out,String name,JsonNode row,String field){BigDecimal value=positive(row.path(field));if(value!=null)out.put(name,value);}
    private static BigDecimal meanClose(List<Bar>bars,int count){return bars.subList(bars.size()-count,bars.size()).stream().map(Bar::close).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP);}
    private static BigDecimal meanVolume(List<Bar>bars){return bars.stream().map(b->BigDecimal.valueOf(b.volume())).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(bars.size()),2,RoundingMode.HALF_UP);}
    private static BigDecimal range(List<Bar>bars){return bars.stream().map(Bar::high).max(BigDecimal::compareTo).orElseThrow().subtract(bars.stream().map(Bar::low).min(BigDecimal::compareTo).orElseThrow());}
    private static LocalDate latestCompletedDate(Instant asOf) {
        ZonedDateTime kst=asOf.atZone(KST);LocalDate date=kst.toLocalDate();
        if(!kst.toLocalTime().isAfter(LocalTime.of(15,35)) || !KoreanMarketCalendar.isTradingDay(date)) date=date.minusDays(1);
        while(!KoreanMarketCalendar.isTradingDay(date)) date=date.minusDays(1);return date;
    }
}
