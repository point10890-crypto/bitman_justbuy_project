package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Real, bounded source collection. This service never chooses or invents a stock. */
@Service
public class KeywordSearchDataService {
    private static final String BASE = "https://m.stock.naver.com/api";
    private static final String SOURCE = "NAVER_FINANCE";
    private static final String SCOPE = "KOSPI·KOSDAQ 각각 시가총액 상위 200종목, 최대 400종목. 전체 시장 검색이 아닙니다.";
    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final long HTTP_BOUND_NANOS = TimeUnit.SECONDS.toNanos(5);

    public record Quote(String code, String name, String market, BigDecimal price, BigDecimal changePct,
                        BigDecimal volume, BigDecimal tradingValueKrw, String source, Instant fetchedAt, Instant observedAt) {}
    public record Snapshot(String status, List<Quote> quotes, Instant fetchedAt, String scope, List<String> warnings) {
        public Snapshot { quotes = List.copyOf(quotes); warnings = List.copyOf(warnings); }
    }
    public record Flow(String code, long foreignNet, long institutionNet, int days,
                       String fromDate, String toDate, Instant fetchedAt,String source,String environment) {
        public Flow(String code,long foreignNet,long institutionNet,int days,String fromDate,String toDate,Instant fetchedAt) {
            this(code,foreignNet,institutionNet,days,fromDate,toDate,fetchedAt,"KIS OpenAPI","REAL");
        }
    }
    private record CachedSnapshot(Snapshot value, Instant expiresAt) {}
    private record CachedQuote(Quote value, Instant expiresAt) {}

    private final RestTemplate client;
    private final ObjectMapper mapper;
    private final KisApiService kis;
    private final Clock clock;
    private final ReentrantLock snapshotLock = new ReentrantLock();
    private final Map<String, CachedQuote> quoteCache = new ConcurrentHashMap<>();
    private volatile CachedSnapshot snapshotCache;
    private KisCandidateSourceService expandedCandidates;
    private KisSourceGateway sourceGateway;
    private KiwoomReadOnlyService kiwoom;

    @Autowired(required=false)
    public void configureKiwoom(KiwoomReadOnlyService kiwoom) {this.kiwoom=kiwoom;}

    public Map<String,Object> kiwoomReadiness() {
        return kiwoom==null?Map.of("enabled",false,"status","NOT_CONFIGURED","source","KIWOOM_REST","environment","UNKNOWN"):kiwoom.readiness();
    }
    /** Both providers receive the same verified candidate codes; provider facts remain independent. */
    public Map<String,Object> kiwoomEvidence(List<String> codes) {
        if(kiwoom==null || !kiwoom.isEnabled()) return Map.of();
        List<String> verified=codes==null?List.of():codes.stream().filter(c->c!=null&&c.matches("[0-9]{6}")).distinct().limit(3).toList();
        return verified.isEmpty()?Map.of():kiwoom.fetchEvidence(verified);
    }
    /** Existing permitted KIS current-price API; independent observations never fill another quote's fields. */
    public Map<String,Quote> kisQuotes(List<String> codes) {
        if(kis==null || !kis.isAvailable() || codes==null || Thread.currentThread().isInterrupted()) return Map.of();
        Map<String,Quote> observations=new LinkedHashMap<>();
        for(String code:codes.stream().filter(c->c!=null&&c.matches("[0-9]{6}")).distinct().limit(3).toList()) {
            if(Thread.currentThread().isInterrupted())break;
            try {
                Map<String,String> facts=kis.fetchCurrentPrice(code);
                if(facts==null || !code.equals(facts.get("stockCode")) || !"KIS OpenAPI".equals(facts.get("source"))
                    || !"KRX".equals(facts.get("exchange"))) continue;
                Instant fetched=Instant.parse(facts.get("fetchedAt")),now=clock.instant();
                if(fetched.isAfter(now) || fetched.isBefore(now.minusSeconds(180)))continue;
                BigDecimal price=number(mapper.valueToTree(facts.get("currentPrice")),false,true);
                if(price==null || price.signum()<=0)continue;
                String rate=facts.get("changeRate");
                BigDecimal change=number(mapper.valueToTree(rate==null?null:rate.replaceFirst("%$","")),true,false);
                BigDecimal volume=number(mapper.valueToTree(facts.get("volume")),false,true);
                BigDecimal value=number(mapper.valueToTree(facts.get("거래대금")),false,true);
                // Some KIS price responses omit the name; preserve that absence, never invent a name.
                observations.put(code,new Quote(code,facts.getOrDefault("name",""),"UNKNOWN",price,change,volume,value,"KIS OpenAPI",fetched,null));
            }catch(RuntimeException ignored){/* Do not expose provider responses or credentials. */}
        }
        return Collections.unmodifiableMap(observations);
    }

    @Autowired(required = false)
    public void configureExpandedSources(KisCandidateSourceService candidates, KisSourceGateway gateway) {
        this.expandedCandidates=candidates;this.sourceGateway=gateway;
    }

    @Autowired
    public KeywordSearchDataService(KisApiService kis, ObjectMapper mapper) {
        this(newClient(), mapper, kis, Clock.systemUTC());
    }

    KeywordSearchDataService(RestTemplate client, ObjectMapper mapper, KisApiService kis, Clock clock) {
        this.client = client;
        this.mapper = mapper;
        this.kis = kis;
        this.clock = clock;
    }

    private static RestTemplate newClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(factory);
    }

    public Snapshot snapshot() {
        if (expandedCandidates != null && sourceGateway != null && sourceGateway.isEnabled()) return expandedCandidates.snapshot();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        if (Thread.currentThread().isInterrupted()) return unavailable("조회가 중단되었습니다.");
        Snapshot cached = cachedSnapshot();
        if (cached != null) return cached;
        boolean acquired = false;
        try {
            acquired = snapshotLock.tryLock(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!acquired) return unavailable("시장 데이터 조회 대기 시간이 초과되었습니다.");
            cached = cachedSnapshot();
            if (cached != null) return cached;
            var quotes = new LinkedHashMap<String, Quote>();
            var warnings = new LinkedHashSet<String>();
            int successfulPages = 0;
            boolean stopped = false;
            for (String market : List.of("KOSPI", "KOSDAQ")) {
                for (int page = 1; page <= 2; page++) {
                    // Reserve the complete 2s connect + 3s read bound before starting a request.
                    if (!canStartHttp(deadline)) { stopped = true; break; }
                    JsonNode body = fetch(BASE + "/stocks/marketValue/" + market + "?page=" + page + "&pageSize=100");
                    if (!withinBudget(deadline)) { stopped = true; break; }
                    if (body == null || !body.path("stocks").isArray()) {
                        warnings.add("일부 시장 페이지를 확인하지 못했습니다.");
                        continue;
                    }
                    successfulPages++;
                    JsonNode rows = body.path("stocks");
                    if (rows.size() > 100) warnings.add("페이지당 100종목 조회 제한이 적용되었습니다.");
                    Instant fetchedAt = clock.instant();
                    for (int i = 0; i < Math.min(100, rows.size()); i++) {
                        Quote quote = parseQuote(rows.get(i), market, null, fetchedAt, warnings);
                        if (quote != null) quotes.putIfAbsent(quote.code(), quote);
                    }
                }
                if (stopped) break;
            }
            if (stopped) warnings.add(Thread.currentThread().isInterrupted()
                ? "조회가 중단되어 일부 데이터만 확인했습니다." : "20초 조회 제한으로 일부 데이터만 확인했습니다.");
            String status = successfulPages == 0 ? "UNAVAILABLE"
                : successfulPages == 4 && warnings.isEmpty() ? "COMPLETE" : "PARTIAL";
            if (successfulPages == 0) warnings.add("실제 시장 데이터를 확인하지 못했습니다. 대체 종목을 생성하지 않습니다.");
            var result = new Snapshot(status, new ArrayList<>(quotes.values()), clock.instant(), SCOPE, new ArrayList<>(warnings));
            if (!Thread.currentThread().isInterrupted()) snapshotCache = new CachedSnapshot(result,
                clock.instant().plusSeconds("UNAVAILABLE".equals(status) ? 5 : 30));
            return result;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return unavailable("조회가 중단되었습니다.");
        } finally {
            if (acquired) snapshotLock.unlock();
        }
    }

    private Snapshot cachedSnapshot() {
        CachedSnapshot cached = snapshotCache;
        Instant now = clock.instant();
        return cached != null && !now.isBefore(cached.value().fetchedAt()) && now.isBefore(cached.expiresAt())
            ? cached.value() : null;
    }

    private Snapshot unavailable(String warning) {
        return new Snapshot("UNAVAILABLE", List.of(), clock.instant(), SCOPE, List.of(warning));
    }

    /** Exact-code lookup. The basic endpoint does not supply volume or trading value. */
    public Quote lookup(String code) {
        if (code == null || !code.matches("[0-9]{6}") || Thread.currentThread().isInterrupted()) return null;
        if (expandedCandidates != null && sourceGateway != null && sourceGateway.isEnabled()) return expandedCandidates.lookup(code);
        CachedQuote cached = quoteCache.get(code);
        Instant now = clock.instant();
        if (cached != null && !now.isBefore(cached.value().fetchedAt()) && now.isBefore(cached.expiresAt())) return cached.value();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        JsonNode body = fetch(BASE + "/stock/" + code + "/basic");
        if (body == null || !withinBudget(deadline)) return null;
        Quote quote = parseQuote(body, null, code, clock.instant(), new LinkedHashSet<>());
        if (quote != null) {
            if (quoteCache.size() >= 512) quoteCache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
            if (quoteCache.size() < 512) quoteCache.put(code, new CachedQuote(quote, now.plusSeconds(30)));
        }
        return quote;
    }

    public Map<String, Flow> flows(List<String> codes, int days) {
        if (codes == null || days < 1 || days > 30 || Thread.currentThread().isInterrupted()) return Map.of();
        List<String> selected = codes.stream().filter(code -> code != null && code.matches("[0-9]{6}"))
            .distinct().limit(20).toList();
        if (selected.isEmpty() || kis == null || !kis.isAvailable()) return Map.of();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        Map<String, Flow> result = new LinkedHashMap<>();
        for (String code : selected) {
            // The dedicated existing KIS read client has a 1s connect + 1s read bound.
            if (!withinBudget(deadline) || deadline - System.nanoTime() <= TimeUnit.SECONDS.toNanos(2)) break;
            KisApiService.InvestorFlowSnapshot flow;
            try { flow = kis.fetchInvestorFlowSnapshot(code, days, deadline); }
            catch (RuntimeException ignored) { continue; }
            if (!withinBudget(deadline)) break;
            if (flow == null || !code.equals(flow.code()) || flow.days() != days || flow.fetchedAt() == null
                || flow.fromDate() == null || flow.toDate() == null || flow.fetchedAt().isAfter(clock.instant())) continue;
            result.put(code, new Flow(code, flow.foreignNet(), flow.institutionNet(), flow.days(),
                flow.fromDate(), flow.toDate(), flow.fetchedAt()));
        }
        return Collections.unmodifiableMap(result);
    }

    private JsonNode fetch(String url) {
        if (Thread.currentThread().isInterrupted()) return null;
        try {
            var headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Accept", "application/json");
            headers.set("Referer", "https://m.stock.naver.com/");
            ResponseEntity<String> response = client.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null || response.getBody().length() > 1_048_576) return null;
            JsonNode body = mapper.readTree(response.getBody());
            return body != null && body.isObject() ? body : null;
        } catch (Exception ignored) {
            // No raw exceptions, response bodies, or request URLs are exposed or logged.
            return null;
        }
    }

    private Quote parseQuote(JsonNode row, String requestedMarket, String expectedCode, Instant fetchedAt, Set<String> warnings) {
        if (row == null || !row.isObject()) return null;
        String code = text(row, "stockCode");
        String itemCode = text(row, "itemCode");
        if (code.isEmpty()) code = itemCode;
        if (!code.matches("[0-9]{6}") || !itemCode.isEmpty() && !code.equals(itemCode)
            || expectedCode != null && !expectedCode.equals(code)) {
            warnings.add("종목 식별이 확인되지 않은 행을 제외했습니다.");
            return null;
        }
        String name = text(row, "stockName");
        BigDecimal price = number(row.path("closePrice"), false, true);
        if (name.isBlank() || price == null || price.signum() <= 0) {
            warnings.add("종목명·실제 가격이 확인되지 않은 행을 제외했습니다.");
            return null;
        }
        String market = text(row.path("stockExchangeType"), "name");
        if (!market.equals("KOSPI") && !market.equals("KOSDAQ")) market = requestedMarket;
        if (requestedMarket != null && market != null && !requestedMarket.equals(market)) {
            warnings.add("시장 식별이 일치하지 않는 행을 제외했습니다.");
            return null;
        }
        BigDecimal changePct = number(row.path("fluctuationsRatio"), true, false);
        BigDecimal volume = number(row.path("accumulatedTradingVolume"), false, true);
        if (changePct == null) warnings.add("일부 종목의 등락률을 확인하지 못했습니다.");
        if (volume == null) warnings.add("일부 종목의 거래량을 확인하지 못했습니다.");
        // Public Naver response checked 2026-10-12: 5,350,036 display (million KRW),
        // 5350036000000 Raw, and '5조 3,500억원' agree. Only the validated raw KRW field is used.
        BigDecimal tradingValue = number(row.path("accumulatedTradingValueRaw"), false, true);
        if (tradingValue == null) warnings.add("일부 종목의 거래대금 원 단위를 확인하지 못했습니다.");
        Instant observedAt = observedAt(row.path("localTradedAt"), fetchedAt);
        if (observedAt == null) warnings.add("일부 종목의 실제 관측 시각을 확인하지 못했습니다.");
        else if (Duration.between(observedAt, fetchedAt).compareTo(Duration.ofDays(7)) > 0)
            warnings.add("7일 이상 지난 관측 시각의 종목이 포함되어 있습니다.");
        return new Quote(code, name, market, price, changePct, volume, tradingValue, SOURCE, fetchedAt, observedAt);
    }

    private static String text(JsonNode row, String field) {
        return row != null && row.path(field).isTextual() ? row.path(field).asText().trim() : "";
    }

    private static BigDecimal number(JsonNode node, boolean signed, boolean integer) {
        if (node == null || !node.isTextual() && !node.isNumber()) return null;
        String value = node.isTextual() ? node.asText().trim() : node.asText();
        String sign = signed ? "[+-]?" : "";
        String fraction = integer ? "" : "(?:\\.[0-9]+)?";
        if (!value.matches(sign + "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)" + fraction)) return null;
        try {
            BigDecimal parsed = new BigDecimal(value.replace(",", ""));
            return !signed && parsed.signum() < 0 ? null : parsed;
        } catch (NumberFormatException ignored) { return null; }
    }

    private static Instant observedAt(JsonNode node, Instant fetchedAt) {
        if (node == null || !node.isTextual()) return null;
        try {
            Instant value = OffsetDateTime.parse(node.asText()).toInstant();
            return value.isAfter(fetchedAt) ? null : value;
        } catch (RuntimeException ignored) { return null; }
    }

    private static boolean withinBudget(long deadline) {
        return !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline;
    }

    private static boolean canStartHttp(long deadline) {
        return withinBudget(deadline) && deadline - System.nanoTime() > HTTP_BOUND_NANOS;
    }
}
