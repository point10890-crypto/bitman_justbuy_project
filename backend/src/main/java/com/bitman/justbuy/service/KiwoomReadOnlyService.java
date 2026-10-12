package com.bitman.justbuy.service;

import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded Kiwoom market queries run alongside verified primary sources; trading is never exposed. */
@Service
public class KiwoomReadOnlyService {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE=DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final long BUDGET=Duration.ofSeconds(12).toNanos();
    private static final long HTTP_BOUND=Duration.ofSeconds(3).toNanos();
    private static final long SPACING=Duration.ofMillis(210).toNanos();
    private enum Environment {
        REAL("https://api.kiwoom.com","KIWOOM_REST",SPACING,5),
        MOCK("https://mockapi.kiwoom.com","KIWOOM_REST_MOCK",Duration.ofMillis(1010).toNanos(),1);
        final String baseUrl,source;
        final long spacingNanos;
        final int requestsPerSecond;
        Environment(String baseUrl,String source,long spacingNanos,int requestsPerSecond) {
            this.baseUrl=baseUrl;this.source=source;this.spacingNanos=spacingNanos;this.requestsPerSecond=requestsPerSecond;
        }
        static Environment parse(String value) {
            if(value==null) return null;
            try {return valueOf(value.trim().toUpperCase(Locale.ROOT));}
            catch(IllegalArgumentException ignored) {return null;}
        }
    }
    private static final List<String> PARTS=List.of("quote","valuation","technical","investorDaily");
    private static final Map<String,String> ENDPOINTS=Map.of("ka10001","/api/dostk/stkinfo",
        "ka10095","/api/dostk/stkinfo","ka10081","/api/dostk/chart","ka10059","/api/dostk/stkinfo");
    record Response(int httpStatus,JsonNode body,boolean hasMore) {}
    @FunctionalInterface interface Transport {
        Response send(String path,String apiId,String token,Map<String,String> params,long deadlineNanos) throws Exception;
    }
    private record Read(String status,Instant fetchedAt,JsonNode body,boolean hasMore) {}
    private record Cached(Read value,Instant expiresAt) {}
    private record Bar(LocalDate date,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,BigDecimal volume) {}
    private final Transport transport;
    private final Clock clock;
    private final boolean requested,licensed;
    private final String appKey,appSecret;
    private final Environment environment;
    private final ReentrantLock requestLock=new ReentrantLock();
    private final Map<String,Cached> cache=new ConcurrentHashMap<>();
    private String token;
    private Instant tokenExpiresAt=Instant.EPOCH,authCooldownUntil=Instant.EPOCH;
    private long nextRequestNanos;
    private volatile String lastStatus="NOT_REQUESTED";

    public KiwoomReadOnlyService(ObjectMapper mapper,boolean enabled,boolean licensed,String appKey,String appSecret) {
        this(mapper,enabled,licensed,appKey,appSecret,"REAL");
    }
    @Autowired public KiwoomReadOnlyService(ObjectMapper mapper,
            @Value("${bitman.kiwoom.enabled:false}") boolean enabled,
            @Value("${bitman.kiwoom.licensed:false}") boolean licensed,
            @Value("${bitman.kiwoom.app-key:}") String appKey,
            @Value("${bitman.kiwoom.app-secret:}") String appSecret,
            @Value("${bitman.kiwoom.environment:REAL}") String environment) {
        this(new HttpTransport(mapper,Environment.parse(environment)),mapper,Clock.systemUTC(),enabled,licensed,appKey,appSecret,environment);
    }
    KiwoomReadOnlyService(Transport transport,ObjectMapper mapper,Clock clock,boolean enabled,boolean licensed,String appKey,String appSecret) {
        this(transport,mapper,clock,enabled,licensed,appKey,appSecret,"REAL");
    }
    KiwoomReadOnlyService(Transport transport,ObjectMapper mapper,Clock clock,boolean enabled,boolean licensed,String appKey,String appSecret,String environment) {
        this.transport=transport;this.clock=clock;this.requested=enabled;this.licensed=licensed;
        this.appKey=appKey==null?"":appKey.trim();this.appSecret=appSecret==null?"":appSecret.trim();
        this.environment=Environment.parse(environment);
    }
    public boolean isEnabled() { return "READY".equals(gate()); }
    private String gate() {
        return environment==null?"INVALID_ENVIRONMENT":!requested?"DISABLED":
            environment==Environment.REAL&&!licensed?"RIGHTS_UNCONFIRMED":
            !credential(appKey)||!credential(appSecret)?"NOT_CONFIGURED":"READY";
    }
    private String source() {return environment==null?"KIWOOM_REST":environment.source;}
    private String environmentName() {return environment==null?"INVALID":environment.name();}
    private String scope() {
        return environment==Environment.MOCK?"모의투자 서버의 시장 조회 자료를 KIS와 함께 검증. 실전 이용 권한 확인 상태는 별도이며 주문·계좌는 사용하지 않음. 최대 3종목·첫 응답 페이지":
            "KIS와 병행하는 시장 조회 자료, 최대 3종목·첫 응답 페이지. 날짜 미상 재무와 불완전 기간을 명시함";
    }
    public Map<String,Object> readiness() {
        return Map.ofEntries(Map.entry("status",gate()),Map.entry("requested",requested),Map.entry("licensed",licensed),
            Map.entry("configured",credential(appKey)&&credential(appSecret)),Map.entry("enabled",isEnabled()),Map.entry("source",source()),
            Map.entry("environment",environmentName()),Map.entry("scope",scope()),Map.entry("allowedApiIds",List.of("ka10001","ka10095","ka10081","ka10059")),
            Map.entry("candidateLimit",3),Map.entry("maxRequestsPerSecond",environment==null?0:environment.requestsPerSecond),Map.entry("runtimeStatus",lastStatus));
    }
    public KeywordSearchDataService.Quote lookup(String code) {
        if(!isEnabled()||code==null||!code.matches("[0-9]{6}")) return null;
        long deadline=System.nanoTime()+BUDGET;
        var quote=quotes(List.of(code),deadline).get(code);
        return quote!=null?quote:basicQuote(code,read("ka10001",Map.of("stk_cd",code),deadline));
    }
    public Map<String,KeywordSearchDataService.Quote> lookupAll(List<String> codes) {
        if(!isEnabled()) return Map.of();
        return quotes(codes(codes),System.nanoTime()+BUDGET);
    }
    private Map<String,KeywordSearchDataService.Quote> quotes(List<String> codes,long deadline) {
        if(codes.isEmpty()) return Map.of();
        Read response=read("ka10095",Map.of("stk_cd",String.join("|",codes)),deadline);
        if(!"AVAILABLE".equals(response.status())) return Map.of();
        JsonNode rows=response.body().path("atn_stk_infr");
        if(!rows.isArray()||rows.size()>100) return Map.of();
        Map<String,KeywordSearchDataService.Quote> result=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
        for(JsonNode row:rows) {
            String code=row.path("stk_cd").asText("");
            if(!codes.contains(code)) continue;
            if(!seen.add(code)) return Map.of();
            var quote=parseQuote(row,code,response.fetchedAt(),true);
            if(quote!=null) result.put(code,quote);
        }
        return Collections.unmodifiableMap(result);
    }
    public Map<String,Object> fetchEvidence(List<String> codes) {
        Map<String,List<String>> missing=new LinkedHashMap<>();for(String code:codes(codes)) missing.put(code,PARTS);
        return fetchEvidence(codes,missing);
    }
    public Map<String,Object> fetchEvidence(List<String> requestedCodes,Map<String,List<String>> missingPartsByCode) {
        Map<String,Object> batch=new LinkedHashMap<>();batch.put("source",source());batch.put("environment",environmentName());batch.put("scope",scope());batch.put("fetchedAt",clock.instant().toString());
        batch.put("candidateLimit",3);batch.put("budgetSeconds",12);batch.put("scoringEnabled",false);
        Map<String,Object> candidates=new LinkedHashMap<>();batch.put("candidates",candidates);
        if(!isEnabled()) {batch.put("status",gate());return batch;}
        var codes=codes(requestedCodes);
        Map<String,Set<String>> missing=new LinkedHashMap<>();
        for(String code:codes) {
            var requestedParts=missingPartsByCode==null?null:missingPartsByCode.get(code);
            Set<String> selected=new LinkedHashSet<>();if(requestedParts!=null) for(String part:requestedParts) if(PARTS.contains(part)) selected.add(part);
            missing.put(code,selected);Map<String,Object> facts=new LinkedHashMap<>();
            for(String part:PARTS) facts.put(part,status(selected.contains(part)?"BUDGET_EXCEEDED":"NOT_REQUESTED"));
            candidates.put(code,facts);
        }
        if(codes.isEmpty()) {batch.put("status","EMPTY");return batch;}
        if(missing.values().stream().allMatch(Set::isEmpty)) {batch.put("status","NOT_REQUESTED");return batch;}
        long deadline=System.nanoTime()+BUDGET;
        var quoteCodes=codes.stream().filter(c->missing.get(c).contains("quote")).toList();
        var quotes=quotes(quoteCodes,deadline);
        LocalDate today=LocalDate.now(clock.withZone(KST));LocalDate completed=completed(clock.instant());
        LocalDate investorDate=KoreanMarketCalendar.previousTradingDay(today);
        for(String code:codes) {
            @SuppressWarnings("unchecked") Map<String,Object> facts=(Map<String,Object>)candidates.get(code);
            var needed=missing.get(code);
            if(needed.contains("quote")) {
                var quote=quotes.get(code);String api="ka10095";
                if(quote==null) {quote=basicQuote(code,read("ka10001",Map.of("stk_cd",code),deadline));api="ka10001";}
                facts.put("quote",quoteEvidence(quote,api));
            }
            if(needed.contains("valuation")) facts.put("valuation",valuation(code,read("ka10001",Map.of("stk_cd",code),deadline)));
            if(needed.contains("technical")) facts.put("technical",technical(code,completed,read("ka10081",Map.of("stk_cd",code,"base_dt",completed.format(DATE),"upd_stkpc_tp","1"),deadline)));
            if(needed.contains("investorDaily")) facts.put("investorDaily",investors(code,investorDate,read("ka10059",Map.of("dt",investorDate.format(DATE),"stk_cd",code,"amt_qty_tp","2","trde_tp","0","unit_tp","1"),deadline)));
        }
        long usable=candidates.values().stream().flatMap(value->((Map<?,?>)value).values().stream())
            .filter(value->value instanceof Map<?,?> facts&&Set.of("AVAILABLE","PARTIAL_COVERAGE").contains(facts.get("status"))).count();
        batch.put("availableFacts",usable);batch.put("status",usable>0?"PARTIAL":"UNAVAILABLE");
        return batch;
    }
    private Read read(String api,Map<String,String> params,long deadline) {
        if(!isEnabled()) return failed(gate());
        String path=ENDPOINTS.get(api);if(path==null) return failed("NOT_ALLOWED");
        if(Thread.currentThread().isInterrupted()||deadline-System.nanoTime()<HTTP_BOUND) return failed("BUDGET_EXCEEDED");
        String key=api+new TreeMap<>(params);boolean locked=false;
        try {
            locked=requestLock.tryLock(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(!locked) return failed("BUDGET_EXCEEDED");
            Instant now=clock.instant();Cached cached=cache.get(key);
            if(cached!=null&&!now.isBefore(cached.value().fetchedAt())&&now.isBefore(cached.expiresAt())) return cached.value();
            if(!authenticate(deadline)) return failed("AUTH_UNAVAILABLE");
            if(!reserve(deadline)) return failed("BUDGET_EXCEEDED");
            Response response=transport.send(path,api,token,params,deadline);
            Instant received=clock.instant();Read value;
            if(System.nanoTime()>deadline) value=failed("BUDGET_EXCEEDED");
            else if(response==null||response.body()==null||!response.body().isObject()) value=failed("INVALID_RESPONSE");
            else if(response.httpStatus()!=200) {
                if(response.httpStatus()==401||response.httpStatus()==403) {token=null;authCooldownUntil=received.plusSeconds(30);}
                value=failed("PROVIDER_UNAVAILABLE");
            } else if(!"0".equals(response.body().path("return_code").asText(""))) value=failed("PROVIDER_UNAVAILABLE");
            else value=new Read("AVAILABLE",received,response.body(),response.hasMore());
            long seconds=!"AVAILABLE".equals(value.status())?5:"ka10095".equals(api)?20:"ka10001".equals(api)?60:600;
            if(cache.size()>=256) cache.clear();cache.put(key,new Cached(value,received.plusSeconds(seconds)));
            lastStatus=value.status();return value;
        } catch(InterruptedException ignored) {Thread.currentThread().interrupt();return failed("INTERRUPTED");}
        catch(Exception ignored) {lastStatus="PROVIDER_UNAVAILABLE";return failed("PROVIDER_UNAVAILABLE");}
        finally {if(locked) requestLock.unlock();}
    }
    private boolean authenticate(long deadline) throws Exception {
        Instant now=clock.instant();
        if(token!=null&&now.plusSeconds(60).isBefore(tokenExpiresAt)) return true;
        if(now.isBefore(authCooldownUntil)||!reserve(deadline)) return false;
        // A timeout or transport failure also shares the cooldown; never retry authentication per fact.
        authCooldownUntil=clock.instant().plusSeconds(30);token=null;
        Response response=transport.send("/oauth2/token","au10001","",Map.of("grant_type","client_credentials","appkey",appKey,"secretkey",appSecret),deadline);
        Instant received=clock.instant();authCooldownUntil=received.plusSeconds(30);token=null;
        if(System.nanoTime()>deadline||response==null||response.httpStatus()!=200||response.body()==null||!"0".equals(response.body().path("return_code").asText(""))) return false;
        String candidate=response.body().path("token").asText("");
        if(!candidate.matches("[A-Za-z0-9._~+/=-]{10,2048}")||!"bearer".equalsIgnoreCase(response.body().path("token_type").asText(""))) return false;
        try {
            Instant expiry=LocalDateTime.parse(response.body().path("expires_dt").asText(""),STAMP).atZone(KST).toInstant();
            if(!received.plusSeconds(60).isBefore(expiry)||expiry.isAfter(received.plus(Duration.ofHours(25)))) return false;
            token=candidate;tokenExpiresAt=expiry;authCooldownUntil=Instant.EPOCH;return true;
        } catch(DateTimeException ignored) {return false;}
    }
    private boolean reserve(long deadline) throws InterruptedException {
        long wait=Math.max(0,nextRequestNanos-System.nanoTime());
        if(deadline-System.nanoTime()<wait+HTTP_BOUND||Thread.currentThread().isInterrupted()) return false;
        if(wait>0) TimeUnit.NANOSECONDS.sleep(wait);
        if(deadline-System.nanoTime()<HTTP_BOUND) return false;
        nextRequestNanos=System.nanoTime()+environment.spacingNanos;return true;
    }
    private Read failed(String status) {return new Read(status,clock.instant(),MissingNode.getInstance(),false);}
    private static List<String> codes(List<String> codes) {
        return codes==null?List.of():codes.stream().filter(Objects::nonNull).filter(c->c.matches("[0-9]{6}")).distinct().limit(3).toList();
    }
    private static boolean credential(String value) {return !value.isBlank()&&value.length()<=512&&value.chars().noneMatch(Character::isISOControl);}
    private Map<String,Object> status(String status) {return Map.of("status",status,"source",source(),"environment",environmentName());}
    private Map<String,Object> base(Read response,String code,String api) {
        Map<String,Object> facts=new LinkedHashMap<>();facts.put("status",response.status());facts.put("source",source());facts.put("environment",environmentName());
        facts.put("apiId",api);facts.put("requestedStockCode",code);facts.put("fetchedAt",response.fetchedAt().toString());
        facts.put("firstResponseOnly",true);facts.put("providerHasMore",response.hasMore());return facts;
    }
    private static Map<String,Object> rejected(Map<String,Object> facts,String reason) {facts.put("status",reason);return facts;}
    private KeywordSearchDataService.Quote basicQuote(String code,Read response) {
        return "AVAILABLE".equals(response.status())?parseQuote(response.body(),code,response.fetchedAt(),false):null;
    }
    private KeywordSearchDataService.Quote parseQuote(JsonNode row,String requestedCode,Instant fetchedAt,boolean dated) {
        if(!requestedCode.equals(row.path("stk_cd").asText(""))) return null;
        String name=row.path("stk_nm").asText("").trim();
        if(name.isEmpty()||name.length()>80||name.chars().anyMatch(Character::isISOControl)) return null;
        BigDecimal price=price(row.path("cur_prc")),percent=decimal(row.path("flu_rt")),volume=quantity(row.path("trde_qty"));
        if(price==null||percent==null||percent.abs().compareTo(new BigDecimal("1000"))>0||volume==null) return null;
        BigDecimal money=dated?decimal(row.path("trde_prica")):null;
        if(dated&&(money==null||money.signum()<0)) return null;
        Instant observedAt=null;
        if(dated) {
            try {
                String date=row.path("dt").asText(""),time=row.path("cntr_tm").asText("");
                if(!date.matches("[0-9]{8}")||!time.matches("[0-9]{6}")) return null;
                observedAt=LocalDateTime.parse(date+time,STAMP).atZone(KST).toInstant();
                // Cache reuse keeps the original capture time but must recheck against the current market clock.
                Instant checkedAt=clock.instant();if(fetchedAt.isAfter(checkedAt)) return null;
                LocalDate providerDay=observedAt.atZone(KST).toLocalDate(),today=checkedAt.atZone(KST).toLocalDate();
                LocalDate oldest=KoreanMarketCalendar.previousTradingDay(today);
                if(!KoreanMarketCalendar.isTradingDay(providerDay)||providerDay.isBefore(oldest)||observedAt.isAfter(checkedAt.plusSeconds(5))) return null;
                LocalTime timeNow=checkedAt.atZone(KST).toLocalTime();
                boolean open=KoreanMarketCalendar.isTradingDay(today)&&!timeNow.isBefore(LocalTime.of(9,0))&&timeNow.isBefore(LocalTime.of(15,30));
                if(open&&(!providerDay.equals(today)||Duration.between(observedAt,checkedAt).compareTo(Duration.ofSeconds(180))>0)) return null;
            } catch(DateTimeException ignored) {return null;}
        }
        return new KeywordSearchDataService.Quote(requestedCode,name,"UNKNOWN",price,percent,volume,
            money==null?null:money.multiply(new BigDecimal("1000000")),source(),fetchedAt,observedAt);
    }
    private Map<String,Object> quoteEvidence(KeywordSearchDataService.Quote quote,String api) {
        if(quote==null) return status("UNAVAILABLE");
        Map<String,Object> out=new LinkedHashMap<>();out.put("status","AVAILABLE");out.put("source",source());out.put("environment",environmentName());out.put("apiId",api);
        out.put("stockCode",quote.code());out.put("stockName",quote.name());out.put("market",quote.market());out.put("currentPrice",quote.price());
        out.put("changePct",quote.changePct());out.put("volume",quote.volume());out.put("priceUnit","KRW");out.put("fetchedAt",quote.fetchedAt().toString());
        if(quote.tradingValueKrw()!=null) {out.put("tradingValueKrw",quote.tradingValueKrw());out.put("tradingValueUnit","KRW");}
        out.put("observationTimeBasis",quote.observedAt()==null?"NOT_PROVIDED":"PROVIDER_DATE_AND_TIME");
        if(quote.observedAt()!=null) out.put("observedAt",quote.observedAt().toString());return out;
    }
    private Map<String,Object> valuation(String code,Read response) {
        var out=base(response,code,"ka10001");if(!"AVAILABLE".equals(response.status())) return out;
        if(basicQuote(code,response)==null) return rejected(out,"INVALID_DATA");
        JsonNode body=response.body();int found=0;
        for(String field:List.of("per","pbr","roe")) {
            BigDecimal value=decimal(body.path(field));
            if(value!=null&&(field.equals("roe")||value.signum()>0)) {out.put(field,value);found++;}
        }
        if(found==0) return rejected(out,"EMPTY");
        out.put("ratioUnit","per/pbr:multiple;roe:percent");out.put("observationTimeBasis","NOT_PROVIDED");
        out.put("providerUpdateBasis","WEEKLY_OR_EARNINGS_SEASON");out.put("reportingPeriodStatus","UNKNOWN");return out;
    }
    private Map<String,Object> technical(String code,LocalDate completed,Read response) {
        var out=base(response,code,"ka10081");if(!"AVAILABLE".equals(response.status())) return out;
        if(!code.equals(response.body().path("stk_cd").asText(""))) return rejected(out,"INVALID_DATA");
        JsonNode rows=response.body().path("stk_dt_pole_chart_qry");
        if(!rows.isArray()||rows.size()>1200) return rejected(out,"INVALID_DATA");
        TreeMap<LocalDate,Bar> dated=new TreeMap<>();Set<LocalDate> seen=new HashSet<>();
        LocalDate today=LocalDate.now(clock.withZone(KST));
        for(JsonNode row:rows) {
            LocalDate date=date(row.path("dt"));
            if(date==null||date.isAfter(today)||!KoreanMarketCalendar.isTradingDay(date)||!seen.add(date)) return rejected(out,"INVALID_DATA");
            if(date.isAfter(completed)) continue;
            BigDecimal open=price(row.path("open_pric")),high=price(row.path("high_pric")),low=price(row.path("low_pric")),close=price(row.path("cur_prc")),volume=quantity(row.path("trde_qty"));
            if(open==null||high==null||low==null||close==null||volume==null||high.compareTo(low)<0||open.compareTo(low)<0||open.compareTo(high)>0||close.compareTo(low)<0||close.compareTo(high)>0) return rejected(out,"INVALID_DATA");
            dated.put(date,new Bar(date,open,high,low,close,volume));
        }
        if(dated.isEmpty()) return rejected(out,"EMPTY");
        if(!dated.lastKey().equals(completed)) return rejected(out,"STALE_DATA");
        // Count actual consecutive market days, not merely returned rows; missing bars cannot fill a period.
        List<Bar> bars=new ArrayList<>();LocalDate cursor=completed;
        for(int i=0;i<100&&cursor!=null;i++) {
            Bar bar=dated.get(cursor);if(bar==null) break;
            bars.add(bar);cursor=KoreanMarketCalendar.previousTradingDay(cursor);
        }
        Collections.reverse(bars);out.put("availableBars",Math.min(100,dated.size()));
        out.put("calendarCoverage","LATEST_CONSECUTIVE_COMPLETED_TRADING_DAYS");
        out.put("bars",bars.size());out.put("observedDate",completed.toString());out.put("fromDate",bars.getFirst().date().toString());out.put("observationTimeBasis","DATE_ONLY");
        out.put("coverageStart",bars.getFirst().date().toString());out.put("coverageEnd",completed.toString());
        out.put("priceUnit","KRW");out.put("adjustmentBasis","PROVIDER_ADJUSTED_AT_REQUEST_BASE_DATE");out.put("historyLimit",100);
        out.put("close",bars.getLast().close());out.put("high",bars.getLast().high());
        for(int period:List.of(5,20,60)) if(bars.size()>=period) out.put("ma"+period,mean(bars,period));
        if(bars.size()>=60) out.put("maAligned",mean(bars,5).compareTo(mean(bars,20))>0&&mean(bars,20).compareTo(mean(bars,60))>0);
        if(bars.size()>=20) {
            BigDecimal averageVolume=bars.subList(bars.size()-20,bars.size()).stream().map(Bar::volume)
                .reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(20),8,RoundingMode.HALF_UP);
            if(averageVolume.signum()>0) out.put("volumeRatio20",bars.getLast().volume().divide(averageVolume,8,RoundingMode.HALF_UP));
            out.put("volumeRatioBasis","LATEST_COMPLETED_VOLUME_OVER_20_COMPLETED_AVERAGE");
        }
        if(bars.size()>=21) {
            BigDecimal prior=bars.subList(bars.size()-21,bars.size()-1).stream().map(Bar::high).max(BigDecimal::compareTo).orElseThrow();
            out.put("previous20High",prior);out.put("breakout20",bars.getLast().high().compareTo(prior)>0);out.put("breakoutBasis","LATEST_COMPLETED_HIGH_ABOVE_PREVIOUS_20_COMPLETED_HIGHS");
        }
        if(bars.size()<60) out.put("status","PARTIAL_COVERAGE");return out;
    }
    private Map<String,Object> investors(String code,LocalDate expected,Read response) {
        var out=base(response,code,"ka10059");if(!"AVAILABLE".equals(response.status())) return out;
        JsonNode rows=response.body().path("stk_invsr_orgn");
        if(!rows.isArray()||rows.size()>1200) return rejected(out,"INVALID_DATA");
        TreeMap<LocalDate,long[]> dated=new TreeMap<>();Set<LocalDate> seen=new HashSet<>();
        LocalDate today=LocalDate.now(clock.withZone(KST));
        for(JsonNode row:rows) {
            LocalDate date=date(row.path("dt"));
            if(date==null||date.isAfter(today)||!KoreanMarketCalendar.isTradingDay(date)||!seen.add(date)) return rejected(out,"INVALID_DATA");
            if(date.isAfter(expected)) continue;
            Long foreign=integer(row.path("frgnr_invsr")),institution=integer(row.path("orgn"));
            if(foreign==null||institution==null) return rejected(out,"INVALID_DATA");dated.put(date,new long[]{foreign,institution});
        }
        if(dated.isEmpty()) return rejected(out,"EMPTY");
        if(!dated.lastKey().equals(expected)) return rejected(out,"STALE_DATA");
        List<Map<String,Object>> observations=new ArrayList<>();long foreignSum=0,institutionSum=0;
        LocalDate cursor=expected;
        try {for(int i=0;i<5;i++) {
            long[] value=dated.get(cursor);if(value==null) break;
            observations.add(Map.of("observedDate",cursor.toString(),"foreignNetBuyShares",value[0],"institutionNetBuyShares",value[1]));
            foreignSum=Math.addExact(foreignSum,value[0]);institutionSum=Math.addExact(institutionSum,value[1]);cursor=KoreanMarketCalendar.previousTradingDay(cursor);
        }}catch(ArithmeticException ignored) {return rejected(out,"INVALID_DATA");}
        out.put("observations",List.copyOf(observations));out.put("days",observations.size());out.put("observedDate",expected.toString());
        out.put("observationTimeBasis","DATE_ONLY");out.put("quantityUnit","shares");out.put("identityBasis","REQUEST_PARAMETER");out.put("flowBasis","DAILY_NET_BUY_PREVIOUS_TRADING_DAYS");
        out.put("foreignNetBuyShares",dated.get(expected)[0]);out.put("institutionNetBuyShares",dated.get(expected)[1]);
        if(observations.size()==5) {out.put("foreignNetBuy5dShares",foreignSum);out.put("institutionNetBuy5dShares",institutionSum);}
        else out.put("status","PARTIAL_COVERAGE");return out;
    }
    private static LocalDate completed(Instant instant) {
        var time=instant.atZone(KST);LocalDate today=time.toLocalDate();
        return KoreanMarketCalendar.isTradingDay(today)&&time.toLocalTime().isAfter(LocalTime.of(15,35))?today:KoreanMarketCalendar.previousTradingDay(today);
    }
    private static LocalDate date(JsonNode node) {
        String raw=node.asText("");try {return raw.matches("[0-9]{8}")?LocalDate.parse(raw,DATE):null;}catch(DateTimeException ignored) {return null;}
    }
    private static BigDecimal decimal(JsonNode node) {
        if(!node.isTextual()&&!node.isNumber()) return null;String raw=node.asText().trim();
        if(raw.length()>48||!raw.matches("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) return null;
        try {BigDecimal value=new BigDecimal(raw.replace(",",""));return value.abs().compareTo(new BigDecimal("1000000000000000"))<=0?value:null;}catch(NumberFormatException ignored) {return null;}
    }
    private static BigDecimal price(JsonNode node) {BigDecimal value=decimal(node);return value!=null&&value.signum()!=0&&value.abs().compareTo(new BigDecimal("1000000000"))<=0?value.abs():null;}
    private static BigDecimal quantity(JsonNode node) {BigDecimal value=decimal(node);return value!=null&&value.signum()>=0&&value.stripTrailingZeros().scale()<=0?value:null;}
    private static Long integer(JsonNode node) {BigDecimal value=decimal(node);try {return value==null?null:value.longValueExact();}catch(ArithmeticException ignored) {return null;}}
    private static BigDecimal mean(List<Bar> bars,int count) {return bars.subList(bars.size()-count,bars.size()).stream().map(Bar::close).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP);}

    private static final class HttpTransport implements Transport {
        private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        private final ObjectMapper mapper;
        private final Environment environment;
        HttpTransport(ObjectMapper mapper,Environment environment) {this.mapper=mapper;this.environment=environment;}
        private URI requestUri(String path,String api) {
            if(environment==null) throw new IllegalStateException("Invalid environment");
            if(!("au10001".equals(api)&&"/oauth2/token".equals(path))&&!Objects.equals(path,ENDPOINTS.get(api))) throw new IllegalArgumentException("Read endpoint not allowed");
            if(path==null) throw new IllegalArgumentException("Read endpoint not allowed");
            return URI.create(environment.baseUrl+path);
        }
        @Override public Response send(String path,String api,String token,Map<String,String> params,long deadline) throws Exception {
            URI target=requestUri(path,api);
            long left=deadline-System.nanoTime();if(left<=0) throw new TimeoutException();
            HttpRequest.Builder request=HttpRequest.newBuilder(target)
                .timeout(Duration.ofNanos(Math.min(HTTP_BOUND,left))).header("Content-Type","application/json;charset=UTF-8")
                .header("api-id",api).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(params),StandardCharsets.UTF_8));
            if(!token.isBlank()) request.header("Authorization","Bearer "+token);
            var pending=client.sendAsync(request.build(),ignored->new LimitedBody());
            try {
                var response=pending.get(Math.min(HTTP_BOUND,left),TimeUnit.NANOSECONDS);
                if(response.statusCode()!=200) return new Response(response.statusCode(),MissingNode.getInstance(),false);
                return new Response(200,mapper.readTree(response.body()),"Y".equalsIgnoreCase(response.headers().firstValue("cont-yn").orElse("N")));
            } finally {if(!pending.isDone()) pending.cancel(true);}
        }
    }
    /** Bound untrusted response bytes before JSON parsing and enforce the async whole-response timeout. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() {return result;}
        @Override public void onSubscribe(Flow.Subscription subscription) {this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers) {
            for(ByteBuffer buffer:buffers) {
                if(bytes.size()+buffer.remaining()>512*1024) {subscription.cancel();result.completeExceptionally(new IllegalStateException("Response limit exceeded"));return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) {result.completeExceptionally(error);}
        @Override public void onComplete() {result.complete(bytes.toByteArray());}
    }
}
