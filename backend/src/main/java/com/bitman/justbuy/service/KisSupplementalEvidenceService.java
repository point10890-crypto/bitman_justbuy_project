package com.bitman.justbuy.service;

import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, read-only supporting facts. Financial units and release times are not inferred. */
@Service
public class KisSupplementalEvidenceService {
    private static final String FINANCE="/uapi/domestic-stock/v1/finance/";
    private static final String QUOTES="/uapi/domestic-stock/v1/quotations/";
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE=DateTimeFormatter.BASIC_ISO_DATE;
    private static final long BUDGET_NANOS=Duration.ofSeconds(12).toNanos();
    private record Source(String name,String endpoint,String tr,String output,String type,List<String> fields) {}
    private record Cached(KisSourceGateway.Snapshot snapshot,Instant cachedAt) {}
    private static Source source(String name,String endpoint,String tr,String output,String type,String fields) {
        return new Source(name,endpoint,tr,output,type,List.of(fields.split(" ")));
    }
    private static final List<Source> SOURCES=List.of(
        source("growthRatio","growth-ratio","FHKST66430800","output","FINANCIAL","grs bsop_prfi_inrt equt_inrt totl_aset_inrt"),
        source("stabilityRatio","stability-ratio","FHKST66430600","output","FINANCIAL","lblt_rate bram_depn crnt_rate quck_rate"),
        source("incomeStatement","income-statement","FHKST66430200","output","FINANCIAL","sale_account sale_cost sale_totl_prfi depr_cost sell_mang bsop_prti bsop_non_ernn bsop_non_expn op_prfi spec_prfi spec_loss thtr_ntin"),
        source("balanceSheet","balance-sheet","FHKST66430100","output","FINANCIAL","cras fxas total_aset flow_lblt fix_lblt total_lblt cpfn cfp_surp prfi_surp total_cptl"),
        source("investorDaily","investor-trade-by-stock-daily","FHPTJ04160001","output2","FLOW","frgn_ntby_qty frgn_reg_ntby_qty frgn_nreg_ntby_qty prsn_ntby_qty orgn_ntby_qty scrt_ntby_qty ivtr_ntby_qty pe_fund_ntby_vol bank_ntby_qty insu_ntby_qty mrbn_ntby_qty fund_ntby_qty etc_ntby_qty etc_corp_ntby_vol etc_orgt_ntby_vol frgn_ntby_tr_pbmn prsn_ntby_tr_pbmn orgn_ntby_tr_pbmn"),
        source("programDaily","program-trade-by-stock-daily","FHPPG04650201","output","FLOW","whol_smtn_seln_vol whol_smtn_shnu_vol whol_smtn_ntby_qty whol_smtn_seln_tr_pbmn whol_smtn_shnu_tr_pbmn whol_smtn_ntby_tr_pbmn whol_ntby_vol_icdc whol_ntby_tr_pbmn_icdc2"),
        source("creditBalance","daily-credit-balance","FHPST04760000","output","CREDIT","whol_loan_new_stcn whol_loan_rdmp_stcn whol_loan_rmnd_stcn whol_loan_new_amt whol_loan_rdmp_amt whol_loan_rmnd_amt whol_loan_rmnd_rate whol_loan_gvrt whol_stln_new_stcn whol_stln_rdmp_stcn whol_stln_rmnd_stcn whol_stln_new_amt whol_stln_rdmp_amt whol_stln_rmnd_amt whol_stln_rmnd_rate whol_stln_gvrt")
    );
    private static final List<String> SECTOR_FIELDS=List.of("bstp_nmix_prpr","bstp_nmix_prdy_vrss","bstp_nmix_prdy_ctrt","acml_vol","acml_tr_pbmn","ascn_issu_cnt","down_issu_cnt","stnr_issu_cnt","uplm_issu_cnt","lslm_issu_cnt","acml_vol_rlim","acml_tr_pbmn_rlim");
    private final KisSourceGateway gateway;
    private final Clock clock;
    private final Map<String,Cached> cache=new ConcurrentHashMap<>();
    private final AtomicInteger rotation=new AtomicInteger();
    private LocalDate calendarRequestDay;
    private KisSourceGateway.Snapshot calendarSnapshot;

    @Autowired public KisSupplementalEvidenceService(KisSourceGateway gateway,ObjectMapper mapper) {
        this(gateway,mapper,Clock.systemUTC());
    }
    KisSupplementalEvidenceService(KisSourceGateway gateway,ObjectMapper mapper,Clock clock) {
        this.gateway=gateway; this.clock=clock;
    }
    public Map<String,Object> readiness() {
        return Map.of("status",gateway!=null&&gateway.isEnabled()?"READY":"DISABLED","registeredSources",9,
            "maxCandidates",2,"budgetSeconds",12,"scoringEnabled",false,"financialUnitStatus","UNCONFIRMED");
    }
    public Map<String,Object> fetchEvidence(List<String> requestedCodes) {
        Instant started=clock.instant();
        Map<String,Object> batch=new LinkedHashMap<>();
        batch.put("fetchedAt",started.toString()); batch.put("registeredSources",9);
        batch.put("scoringEnabled",false); batch.put("budgetSeconds",12);
        Map<String,Object> candidates=new LinkedHashMap<>(); batch.put("candidates",candidates);
        batch.put("marketContexts",Map.of()); batch.put("calendar",status("NOT_REQUESTED"));
        batch.put("availableSources",0);
        if(gateway==null||!gateway.isEnabled()) { batch.put("status","DISABLED"); return batch; }
        List<String> codes=requestedCodes==null?List.of():requestedCodes.stream().filter(Objects::nonNull)
            .map(String::trim).filter(c->c.matches("[0-9]{6}")).distinct().limit(2).toList();
        if(codes.isEmpty()) { batch.put("status","EMPTY"); return batch; }
        for(String code:codes) {
            Map<String,Object> facts=new LinkedHashMap<>();
            for(Source source:SOURCES) facts.put(source.name(),status("BUDGET_EXCEEDED"));
            candidates.put(code,facts);
        }
        long deadline=System.nanoTime()+BUDGET_NANOS;
        LocalDate today=LocalDate.now(clock.withZone(KST));
        LocalDate completed=latestCompletedDate(today);
        Map<String,Object> calendar=calendarEvidence(today,deadline); batch.put("calendar",calendar);
        Map<String,Object> markets=new LinkedHashMap<>();
        markets.put("KOSPI",sectorEvidence("K","0001",deadline));
        markets.put("KOSDAQ",sectorEvidence("Q","1001",deadline)); batch.put("marketContexts",markets);
        // Rotate the starting source and visit both candidates per source before the next family.
        // Cached sources are considered even after the network budget has been consumed.
        int first=Math.floorMod(rotation.getAndIncrement(),SOURCES.size());
        for(int index=0;index<SOURCES.size();index++) {
            Source source=SOURCES.get((first+index)%SOURCES.size());
            for(String code:codes) {
                Map<String,String> params=parameters(source,code,today,completed);
                Duration ttl=source.type().equals("FINANCIAL")?Duration.ofHours(6):Duration.ofMinutes(10);
                KisSourceGateway.Snapshot snapshot=fetch(source.type().equals("FINANCIAL")?FINANCE+source.endpoint():QUOTES+source.endpoint(),source.tr(),params,ttl,deadline);
                Map<String,Object> facts=parse(source,snapshot,today,completed);
                facts.put("requestedStockCode",code); facts.put("identityBasis","REQUEST_PARAMETER");
                candidate(candidates,code).put(source.name(),facts);
            }
        }
        Set<String> availableFamilies=new HashSet<>();
        int availableObservations=0;
        if("AVAILABLE".equals(calendar.get("status"))) { availableFamilies.add("calendar"); availableObservations++; }
        for(Object value:candidates.values()) for(var entry:((Map<?,?>)value).entrySet())
            if("AVAILABLE".equals(((Map<?,?>)entry.getValue()).get("status"))) { availableFamilies.add(entry.getKey().toString()); availableObservations++; }
        batch.put("availableSources",availableFamilies.size());
        batch.put("availableObservations",availableObservations);
        batch.put("sourceObservationCount",SOURCES.size()*codes.size()+3);
        batch.put("candidateLimit",2);
        batch.put("status",availableFamilies.isEmpty()?"UNAVAILABLE":"PARTIAL");
        batch.put("scope","최대 2개 후보의 첫 응답 페이지. 미확인 단위와 추정 또는 일자 미상 자료는 점수에 사용하지 않음");
        return batch;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> candidate(Map<String,Object> candidates,String code) {
        return (Map<String,Object>)candidates.get(code);
    }
    private Map<String,String> parameters(Source source,String code,LocalDate today,LocalDate completed) {
        if(source.type().equals("FINANCIAL")) {
            String division=(source.name().equals("growthRatio")||source.name().equals("stabilityRatio"))?"fid_div_cls_code":"FID_DIV_CLS_CODE";
            return Map.of(division,"1","fid_cond_mrkt_div_code","J","fid_input_iscd",code);
        }
        Map<String,String> params=new LinkedHashMap<>();
        params.put("FID_COND_MRKT_DIV_CODE","J"); params.put("FID_INPUT_ISCD",code);
        params.put("FID_INPUT_DATE_1",DATE.format(source.type().equals("CREDIT")?today:completed));
        if(source.name().equals("investorDaily")) { params.put("FID_ORG_ADJ_PRC",""); params.put("FID_ETC_CLS_CODE",""); }
        if(source.type().equals("CREDIT")) params.put("FID_COND_SCR_DIV_CODE","20476");
        return params;
    }
    private KisSourceGateway.Snapshot fetch(String path,String tr,Map<String,String> params,Duration ttl,long deadline) {
        Instant now=clock.instant(); String key=path+new TreeMap<>(params);
        Cached cached=cache.get(key);
        if(cached!=null) {
            Duration allowed="AVAILABLE".equals(cached.snapshot().status())?ttl:Duration.ofSeconds(30);
            Instant basis="AVAILABLE".equals(cached.snapshot().status())?cached.snapshot().fetchedAt():cached.cachedAt();
            if(basis!=null&&!basis.isAfter(now)&&Duration.between(basis,now).compareTo(allowed)<0) return cached.snapshot();
        }
        if(deadline-System.nanoTime()<Duration.ofSeconds(2).toNanos()) return unavailable("BUDGET_EXCEEDED");
        KisSourceGateway.Snapshot snapshot;
        try { snapshot=gateway.fetch(path,tr,params,deadline); }
        catch(Exception error) { snapshot=unavailable("UNAVAILABLE"); }
        if(snapshot==null) snapshot=unavailable("UNAVAILABLE");
        if(System.nanoTime()>deadline) return unavailable("BUDGET_EXCEEDED");
        Instant receivedAt=clock.instant();
        if(snapshot.fetchedAt()==null||snapshot.fetchedAt().isAfter(receivedAt)||Duration.between(snapshot.fetchedAt(),receivedAt).compareTo(ttl)>0)
            return unavailable("STALE_OR_INVALID_FETCH_TIME");
        if(cache.size()>512) cache.clear();
        cache.put(key,new Cached(snapshot,receivedAt)); return snapshot;
    }
    private synchronized Map<String,Object> calendarEvidence(LocalDate today,long deadline) {
        if(!today.equals(calendarRequestDay)) {
            if(deadline-System.nanoTime()<Duration.ofSeconds(2).toNanos()) return status("BUDGET_EXCEEDED");
            calendarRequestDay=today; // The official sample asks callers to limit this to one request per day.
            calendarSnapshot=fetch(QUOTES+"chk-holiday","CTCA0903R",Map.of("BASS_DT",DATE.format(today),"CTX_AREA_FK","","CTX_AREA_NK",""),Duration.ofDays(1),deadline);
        }
        Map<String,Object> result=base(calendarSnapshot);
        if(!"AVAILABLE".equals(result.get("status"))) return result;
        List<JsonNode> rows=rows(calendarSnapshot.body().path("output"));
        if(rows.isEmpty()||rows.size()>100) return failed(result,"EMPTY_OR_OVERSIZED");
        List<Map<String,Object>> table=new ArrayList<>(); Set<LocalDate> dates=new HashSet<>();
        boolean coversToday=false;
        for(JsonNode row:rows) {
            LocalDate date=date(row.path("bass_dt").asText());
            if(date==null||date.isBefore(today)||date.isAfter(today.plusDays(60))||!dates.add(date)) return failed(result,"INVALID_DATA");
            Map<String,Object> entry=new LinkedHashMap<>(); entry.put("calendarDate",date.toString());
            for(String key:List.of("bzdy_yn","tr_day_yn","opnd_yn","sttl_day_yn")) {
                String value=row.path(key).asText(); if(!value.matches("[YN]")) return failed(result,"INVALID_DATA");
                entry.put(key,value);
            }
            String weekday=row.path("wday_dvsn_cd").asText();
            if(weekday.matches("0?[1-7]")) entry.put("wday_dvsn_cd",weekday);
            coversToday|=date.equals(today); table.add(entry);
        }
        if(!coversToday) return failed(result,"REQUEST_DATE_MISSING");
        result.put("calendar",table); result.put("observationTimeBasis","OFFICIAL_CALENDAR_SCHEDULE");
        result.put("requestDate",today.toString()); result.put("pagination","FIRST_PAGE_ONLY"); return result;
    }
    private Map<String,Object> sectorEvidence(String market,String code,long deadline) {
        var snapshot=fetch(QUOTES+"inquire-index-category-price","FHPUP02140000",Map.of("FID_COND_MRKT_DIV_CODE","U","FID_INPUT_ISCD",code,"FID_COND_SCR_DIV_CODE","20214","FID_MRKT_CLS_CODE",market,"FID_BLNG_CLS_CODE","0"),Duration.ofMinutes(10),deadline);
        Map<String,Object> result=base(snapshot); result.put("marketClass",market); result.put("requestedIndexCode",code);
        if(!"AVAILABLE".equals(result.get("status"))) return result;
        List<JsonNode> rows=rows(snapshot.body().path("output2"));
        if(rows.isEmpty()) rows=rows(snapshot.body().path("output1"));
        if(rows.isEmpty()||rows.size()>100) return failed(result,"EMPTY_OR_OVERSIZED");
        List<Map<String,Object>> sectors=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(JsonNode row:rows) {
            String sector=row.path("bstp_cls_code").asText();
            if(!sector.matches("[0-9]{4}")||!seen.add(sector)) return failed(result,"INVALID_DATA");
            Map<String,Object> fields=numeric(row,SECTOR_FIELDS);
            if(fields.isEmpty()) continue;
            fields.put("bstp_cls_code",sector);
            String name=row.path("hts_kor_isnm").asText(); if(!name.isBlank()&&name.length()<=80) fields.put("hts_kor_isnm",name);
            sectors.add(fields);
        }
        if(sectors.isEmpty()) return failed(result,"NO_VALID_FIELDS");
        result.put("status","UNDATED"); result.put("observationTimeBasis","FETCH_TIME_ONLY");
        result.put("rawSectors",sectors); result.put("providerUnit","UNCONFIRMED");
        result.put("candidateSectorMapping","NOT_INFERRED"); return result;
    }
    private Map<String,Object> parse(Source source,KisSourceGateway.Snapshot snapshot,LocalDate today,LocalDate completed) {
        Map<String,Object> result=base(snapshot); result.put("endpoint",source.endpoint());
        result.put("pagination","FIRST_PAGE_ONLY");
        if(!"AVAILABLE".equals(result.get("status"))) return result;
        List<JsonNode> rows=rows(snapshot.body().path(source.output()));
        if(rows.isEmpty()||rows.size()>100) return failed(result,"EMPTY_OR_OVERSIZED");
        JsonNode latest=null; LocalDate latestDate=null; Set<LocalDate> seen=new HashSet<>();
        for(JsonNode row:rows) {
            LocalDate rowDate;
            if(source.type().equals("FINANCIAL")) {
                String period=row.path("stac_yymm").asText();
                try { rowDate=period.matches("[0-9]{6}")?YearMonth.parse(period,DateTimeFormatter.ofPattern("yyyyMM")).atEndOfMonth():null; }
                catch(RuntimeException error) { rowDate=null; }
                if(rowDate==null||rowDate.isAfter(today)||!seen.add(rowDate)) return failed(result,"INVALID_DATA");
            } else if(source.type().equals("CREDIT")) {
                LocalDate trade=date(row.path("deal_date").asText()); rowDate=date(row.path("stlm_date").asText());
                if(trade==null||rowDate==null||trade.isAfter(rowDate)||rowDate.isAfter(today)||!seen.add(rowDate)) return failed(result,"INVALID_DATA");
            } else {
                rowDate=date(row.path("stck_bsop_date").asText());
                if(rowDate==null||rowDate.isAfter(completed)||!KoreanMarketCalendar.isTradingDay(rowDate)||!seen.add(rowDate)) return failed(result,"INVALID_DATA");
            }
            if(latestDate==null||rowDate.isAfter(latestDate)) { latestDate=rowDate; latest=row; }
        }
        Map<String,Object> fields=numeric(latest,source.fields());
        if(fields.isEmpty()) return failed(result,"NO_VALID_FIELDS");
        if(!source.type().equals("FINANCIAL")&&fields.entrySet().stream().anyMatch(e->isQuantity(e.getKey())&&((BigDecimal)e.getValue()).stripTrailingZeros().scale()>0)) return failed(result,"INVALID_DATA");
        if(source.type().equals("CREDIT")&&fields.entrySet().stream().anyMatch(e->e.getKey().endsWith("_stcn")&&((BigDecimal)e.getValue()).signum()<0)) return failed(result,"INVALID_DATA");
        result.put("rawFields",fields); result.put("rowCount",rows.size());
        if(source.type().equals("FINANCIAL")) {
            result.put("reportingPeriod",YearMonth.from(latestDate).toString()); result.put("periodType","QUARTERLY");
            result.put("periodBasis",source.name().equals("incomeStatement")?"YEAR_TO_DATE_CUMULATIVE":"PROVIDER_REPORTING_PERIOD");
            result.put("observationTimeBasis","REPORTING_PERIOD"); result.put("releaseTimeStatus","UNKNOWN"); result.put("providerUnit","UNCONFIRMED");
        } else {
            result.put("observationTimeBasis","DATE_ONLY"); result.put("quantityUnit","SHARES"); result.put("amountUnit","UNCONFIRMED"); result.put("ratioUnit","UNCONFIRMED");
            if(source.type().equals("CREDIT")) {
                result.put("tradeDate",date(latest.path("deal_date").asText()).toString()); result.put("settlementDate",latestDate.toString());
            } else result.put("observedDate",latestDate.toString());
        }
        return result;
    }
    private Map<String,Object> base(KisSourceGateway.Snapshot snapshot) {
        Map<String,Object> result=status(snapshot==null?"UNAVAILABLE":snapshot.status());
        result.put("source","KIS_OPEN_API"); result.put("scoringEnabled",false);
        if(snapshot!=null&&snapshot.fetchedAt()!=null) result.put("fetchedAt",snapshot.fetchedAt().toString());
        if("AVAILABLE".equals(result.get("status"))&&(snapshot.body()==null||!"0".equals(snapshot.body().path("rt_cd").asText()))) result.put("status","SOURCE_REJECTED");
        return result;
    }
    private Map<String,Object> numeric(JsonNode row,List<String> whitelist) {
        Map<String,Object> fields=new LinkedHashMap<>();
        for(String field:whitelist) {
            String value=row.path(field).asText().trim();
            if(value.length()>40||!value.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) continue;
            try { BigDecimal number=new BigDecimal(value); if(number.precision()<=24&&number.scale()<=8) fields.put(field,number); }
            catch(NumberFormatException ignored) { /* Missing facts are omitted, never replaced with zero. */ }
        }
        return fields;
    }
    private List<JsonNode> rows(JsonNode node) {
        if(node.isObject()) return List.of(node);
        if(!node.isArray()) return List.of();
        List<JsonNode> result=new ArrayList<>(); node.forEach(result::add); return result;
    }
    private Map<String,Object> status(String value) { Map<String,Object> result=new LinkedHashMap<>(); result.put("status",value); return result; }
    private boolean isQuantity(String field) { return field.endsWith("_qty")||field.endsWith("_vol")||field.endsWith("_stcn")||field.equals("whol_ntby_vol_icdc"); }
    private Map<String,Object> failed(Map<String,Object> result,String value) { result.put("status",value); return result; }
    private KisSourceGateway.Snapshot unavailable(String status) { return new KisSourceGateway.Snapshot(status,clock.instant(),MissingNode.getInstance()); }
    private LocalDate date(String value) {
        try { return value.matches("[0-9]{8}")?LocalDate.parse(value,DATE):null; } catch(RuntimeException ignored) { return null; }
    }
    private LocalDate latestCompletedDate(LocalDate today) {
        LocalDate result=LocalTime.now(clock.withZone(KST)).isBefore(LocalTime.of(15,35))?today.minusDays(1):today;
        while(!KoreanMarketCalendar.isTradingDay(result)) result=result.minusDays(1);
        return result;
    }
}
