package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.math.BigDecimal;
import java.util.*;

/** Deterministic, bounded candidates and raw provider quotes; no generated tickers or timestamps. */
@Service
public class KisCandidateSourceService {
    private static final String Q = "/uapi/domestic-stock/v1/quotations/";
    private static final String R = "/uapi/domestic-stock/v1/ranking/";
    private static final int MAX_CANDIDATES = 600;
    private static final String SCOPE = "KIS HTS 저장조건 최대 3개·조건당 100종목, 순위별 최초 30종목·신고근접 0~10%(0~100만원), 검증된 마스터 코드순 후보를 합친 최대 600종목. 전체 시장 검색이 아닙니다. 신고근접의 기준 기간은 52주로 가정하지 않습니다.";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private final KisSourceGateway gateway;
    private final StockUniverseService universe;
    private final Clock clock;
    private volatile KeywordSearchDataService.Snapshot cached;

    @Autowired public KisCandidateSourceService(KisSourceGateway gateway, StockUniverseService universe) {
        this(gateway, universe, Clock.systemUTC());
    }
    KisCandidateSourceService(KisSourceGateway gateway, StockUniverseService universe, Clock clock) {
        this.gateway = gateway; this.universe = universe; this.clock = clock;
    }

    public synchronized KeywordSearchDataService.Snapshot snapshot() {
        if (!gateway.isEnabled() || Thread.currentThread().isInterrupted()) return unavailable("KIS 신규 소스가 비활성 상태입니다.");
        Instant now = clock.instant();
        var current = cached;
        if (current != null && !now.isBefore(current.fetchedAt()) && now.isBefore(current.fetchedAt().plusSeconds(30))) return current;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Set<String> warnings = new LinkedHashSet<>();
        var master = new LinkedHashMap<String, StockUniverseService.Stock>();
        for (var stock : universe.stocks()) if (!stock.excluded()) master.putIfAbsent(stock.code(), stock);
        Set<String> candidates = new LinkedHashSet<>();
        int verifiedSources = collectHts(candidates, master, warnings, deadline);
        verifiedSources += collectRanking(candidates, master, warnings, deadline, Q + "volume-rank", "FHPST01710000", "20171");
        verifiedSources += collectRanking(candidates, master, warnings, deadline, R + "fluctuation", "FHPST01700000", "20170");
        verifiedSources += collectNearNewHigh(candidates, master, warnings, deadline);
        master.keySet().stream().sorted().limit(MAX_CANDIDATES).forEach(code -> { if (candidates.size() < MAX_CANDIDATES) candidates.add(code); });
        if (master.isEmpty() && !candidates.isEmpty()) warnings.add("검증된 종목 마스터가 없어 후보 식별을 확인하지 못했습니다.");
        var quotes = new LinkedHashMap<String, KeywordSearchDataService.Quote>();
        var selected = new ArrayList<>(candidates);
        int verifiedQuoteBatches = 0;
        for (int from = 0; from < selected.size(); from += 30) {
            if (!within(deadline)) { warnings.add("20초 조회 제한으로 일부 후보만 확인했습니다."); break; }
            List<String> batch = selected.subList(from, Math.min(from + 30, selected.size()));
            KisSourceGateway.Snapshot source = gateway.fetch(Q + "intstock-multprice", "FHKST11300006", batchParams(batch), deadline);
            if (!ok(source) || !within(deadline) || !source.body().path("output").isArray()) { warnings.add("일부 다중 시세를 확인하지 못했습니다."); continue; }
            verifiedQuoteBatches++;
            Set<String> missing = new LinkedHashSet<>(batch);
            JsonNode rows = source.body().path("output");
            if (rows.size() > 30) warnings.add("다중 시세 응답의 30종목 제한을 넘은 행을 제외했습니다.");
            for (int i = 0; i < Math.min(30, rows.size()); i++) {
                var quote = parseQuote(rows.get(i), batch, master, source.fetchedAt(), warnings);
                if (quote == null) continue;
                if (quotes.putIfAbsent(quote.code(), quote) != null) warnings.add("중복 시세 행을 제외했습니다.");
                missing.remove(quote.code());
            }
            if (!missing.isEmpty()) warnings.add("요청한 일부 종목의 실제 시세를 확인하지 못했습니다.");
        }
        String status = quotes.isEmpty() && (!selected.isEmpty() || verifiedSources == 0) ? "UNAVAILABLE"
            : warnings.isEmpty() ? "COMPLETE" : "PARTIAL";
        if (!selected.isEmpty() && verifiedQuoteBatches == 0) status = "UNAVAILABLE";
        var result = new KeywordSearchDataService.Snapshot(status, List.copyOf(quotes.values()), clock.instant(), SCOPE, List.copyOf(warnings));
        if (gateway.isEnabled() && !Thread.currentThread().isInterrupted()) cached = result;
        return result;
    }

    private int collectHts(Set<String> candidates, Map<String, StockUniverseService.Stock> master, Set<String> warnings, long deadline) {
        String id = gateway.htsId();
        if (id == null || id.isBlank()) { warnings.add("HTS ID가 없어 저장 조건검색은 제외했습니다."); return 0; }
        var titles = gateway.fetch(Q + "psearch-title", "HHKST03900300", Map.of("user_id", id), deadline);
        if (!ok(titles) || !titles.body().path("output2").isArray()) { warnings.add("HTS 저장 조건 목록을 확인하지 못했습니다."); return 0; }
        JsonNode list = titles.body().path("output2");
        int success = 1;
        if (list.size() > 3) warnings.add("HTS 저장 조건 중 최대 3개만 조회했습니다.");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(3, list.size()) && within(deadline); i++) {
            String seq = text(list.get(i), "seq");
            if (!seq.matches("[0-9]{1,10}") || !seen.add(seq)) { warnings.add("식별이 확인되지 않은 HTS 조건을 제외했습니다."); continue; }
            var response = gateway.fetch(Q + "psearch-result", "HHKST03900400", Map.of("user_id", id, "seq", seq), deadline);
            if (documentedEmpty(response)) { success++; continue; }
            if (!ok(response) || !response.body().path("output2").isArray()) { warnings.add("일부 HTS 조건검색 결과를 확인하지 못했습니다."); continue; }
            success++;
            JsonNode rows = response.body().path("output2");
            if (rows.size() >= 100) warnings.add("HTS 조건당 100종목 제한으로 더 많은 후보가 있을 수 있습니다.");
            for (int row = 0; row < Math.min(100, rows.size()); row++) addCandidate(text(rows.get(row), "code"), candidates, master, warnings);
        }
        return success;
    }
    private int collectRanking(Set<String> candidates, Map<String, StockUniverseService.Stock> master, Set<String> warnings,
                               long deadline, String path, String tr, String screen) {
        if (!within(deadline)) return 0;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("FID_COND_MRKT_DIV_CODE", "J"); params.put("FID_COND_SCR_DIV_CODE", screen);
        params.put("FID_INPUT_ISCD", "0000"); params.put("FID_DIV_CLS_CODE", "0"); params.put("FID_BLNG_CLS_CODE", "0");
        params.put("FID_TRGT_CLS_CODE", "0"); params.put("FID_TRGT_EXLS_CLS_CODE", "0");
        params.put("FID_INPUT_PRICE_1", ""); params.put("FID_INPUT_PRICE_2", ""); params.put("FID_VOL_CNT", ""); params.put("FID_INPUT_DATE_1", "");
        if (path.endsWith("fluctuation")) { params.put("FID_RANK_SORT_CLS_CODE", "0"); params.put("FID_INPUT_CNT_1", "30"); params.put("FID_PRC_CLS_CODE", "0"); params.put("FID_RSFL_RATE1", ""); params.put("FID_RSFL_RATE2", ""); }
        var response = gateway.fetch(path, tr, params, deadline);
        if (!ok(response) || !response.body().path("output").isArray()) { warnings.add("일부 KIS 순위 후보를 확인하지 못했습니다."); return 0; }
        JsonNode rows = response.body().path("output");
        if (rows.size() > 30) warnings.add("순위별 최대 30종목을 후보로 사용했습니다.");
        for (int i = 0; i < Math.min(30, rows.size()); i++) {
            String code = text(rows.get(i), "mksc_shrn_iscd");
            if (code.isBlank()) code = text(rows.get(i), "stck_shrn_iscd");
            addCandidate(code, candidates, master, warnings);
        }
        return 1;
    }
    private static void addCandidate(String code, Set<String> result, Map<String, StockUniverseService.Stock> master, Set<String> warnings) {
        if (!code.matches("[0-9]{6}") || !master.containsKey(code)) { warnings.add("종목 마스터에서 식별되지 않거나 제외 대상인 후보를 제외했습니다."); return; }
        if (result.size() < MAX_CANDIDATES) result.add(code);
    }
    private int collectNearNewHigh(Set<String> candidates, Map<String, StockUniverseService.Stock> master,
                                   Set<String> warnings, long deadline) {
        if (!within(deadline)) return 0;
        Map<String, String> params = Map.ofEntries(
            Map.entry("fid_aply_rang_vol", "0"), Map.entry("fid_cond_mrkt_div_code", "J"),
            Map.entry("fid_cond_scr_div_code", "20187"), Map.entry("fid_div_cls_code", "0"),
            Map.entry("fid_input_cnt_1", "0"), Map.entry("fid_input_cnt_2", "10"),
            Map.entry("fid_prc_cls_code", "0"), Map.entry("fid_input_iscd", "0000"),
            Map.entry("fid_trgt_cls_code", "0"), Map.entry("fid_trgt_exls_cls_code", "0"),
            Map.entry("fid_aply_rang_prc_1", "0"), Map.entry("fid_aply_rang_prc_2", "1000000"));
        var response = gateway.fetch(R + "near-new-highlow", "FHPST01870000", params, deadline);
        if (!ok(response) || !response.body().path("output").isArray()) {
            warnings.add("KIS 신고근접 후보를 확인하지 못했습니다."); return 0;
        }
        JsonNode rows = response.body().path("output");
        if (rows.size() >= 30) warnings.add("신고근접 순위 최초 30종목만 확인하며 연속 조회는 수행하지 않습니다.");
        for (int i = 0; i < Math.min(30, rows.size()); i++) {
            // This ranking establishes candidate identity only, never a 52-week high or a current quote.
            addCandidate(text(rows.get(i), "mksc_shrn_iscd"), candidates, master, warnings);
        }
        return 1;
    }
    public KeywordSearchDataService.Quote lookup(String code) {
        if (!gateway.isEnabled() || code == null || !code.matches("[0-9]{6}") || Thread.currentThread().isInterrupted()) return null;
        var stock = universe.find(code).orElse(null);
        if (stock == null || stock.excluded()) return null;
        var current = cached;
        Instant now = clock.instant();
        if (current != null && !now.isBefore(current.fetchedAt()) && now.isBefore(current.fetchedAt().plusSeconds(30))) {
            var match = current.quotes().stream().filter(q -> q.code().equals(code)).findFirst();
            if (match.isPresent()) return match.get();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        var response = gateway.fetch(Q + "intstock-multprice", "FHKST11300006", batchParams(List.of(code)), deadline);
        if (!ok(response) || !within(deadline) || !response.body().path("output").isArray()) return null;
        for (JsonNode row : response.body().path("output")) {
            var quote = parseQuote(row, List.of(code), Map.of(code, stock), response.fetchedAt(), new LinkedHashSet<>());
            if (quote != null) return quote;
        }
        return null;
    }
    public List<String> candidates() { return snapshot().quotes().stream().map(KeywordSearchDataService.Quote::code).toList(); }
    public Map<String, Object> readiness() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", gateway.isEnabled()); result.put("htsConfigured", gateway.htsId() != null && !gateway.htsId().isBlank());
        result.put("masterStatus", universe.status()); result.put("maxCandidates", MAX_CANDIDATES); result.put("scope", SCOPE);
        result.put("status", !gateway.isEnabled() ? "DISABLED" : cached == null ? "NOT_QUERIED" : cached.status());
        if (cached != null) result.put("lastFetchedAt", cached.fetchedAt().toString());
        return Collections.unmodifiableMap(result);
    }
    private boolean ok(KisSourceGateway.Snapshot response) {
        return response != null && response.body() != null && response.fetchedAt() != null && !response.fetchedAt().isAfter(clock.instant())
            && !Set.of("DISABLED", "UNAVAILABLE", "RIGHTS_UNCONFIRMED").contains(response.status()) && "0".equals(response.body().path("rt_cd").asText());
    }
    private boolean documentedEmpty(KisSourceGateway.Snapshot response) {
        return response != null && response.body() != null && response.fetchedAt() != null && !response.fetchedAt().isAfter(clock.instant())
            && "1".equals(response.body().path("rt_cd").asText()) && "MCA05918".equals(response.body().path("msg_cd").asText());
    }
    private KeywordSearchDataService.Quote parseQuote(JsonNode row, List<String> expected,
            Map<String, StockUniverseService.Stock> master, Instant fetchedAt, Set<String> warnings) {
        String code = text(row, "inter_shrn_iscd");
        var stock = master.get(code);
        if (!expected.contains(code) || stock == null || stock.excluded()) { warnings.add("시세 종목 식별이 일치하지 않는 행을 제외했습니다."); return null; }
        String market = text(row, "kospi_kosdaq_cls_name");
        if (!market.isBlank() && !(stock.market().equals("KOSPI") ? Set.of("KOSPI", "코스피", "거래소", "유가증권") : Set.of("KOSDAQ", "코스닥")).contains(market)) {
            warnings.add("시장 식별이 일치하지 않는 시세를 제외했습니다."); return null;
        }
        BigDecimal price = number(row.path("inter2_prpr"), false, true);
        if (price == null || price.signum() <= 0) { warnings.add("실제 가격이 확인되지 않은 행을 제외했습니다."); return null; }
        BigDecimal change = number(row.path("prdy_ctrt"), true, false), volume = number(row.path("acml_vol"), false, true);
        BigDecimal turnover = number(row.path("acml_tr_pbmn"), false, true);
        if (change == null || volume == null || turnover == null) warnings.add("일부 종목의 등락률·거래량·거래대금을 확인하지 못했습니다.");
        Instant observedAt = observedAt(row, fetchedAt);
        if (observedAt == null) warnings.add("KIS 다중 시세의 원 관측 시각을 확인하지 못했습니다. 조회 시각과 구분합니다.");
        return new KeywordSearchDataService.Quote(code, stock.name(), stock.market(), price, change, volume, turnover, "KIS_INTSTOCK_MULTPRICE_KRX", fetchedAt, observedAt);
    }
    private static Map<String, String> batchParams(List<String> codes) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(30, codes.size()); i++) { params.put("FID_COND_MRKT_DIV_CODE_" + (i + 1), "J"); params.put("FID_INPUT_ISCD_" + (i + 1), codes.get(i)); }
        return params;
    }
    private static BigDecimal number(JsonNode node, boolean signed, boolean integer) {
        if (node == null || !node.isTextual() && !node.isNumber()) return null;
        String value = node.asText().trim();
        if (!value.matches((signed ? "[+-]?" : "") + "[0-9]+" + (integer ? "" : "(?:\\.[0-9]+)?"))) return null;
        try { return new BigDecimal(value); } catch (RuntimeException ignored) { return null; }
    }
    private static Instant observedAt(JsonNode row, Instant fetchedAt) {
        String date = text(row, "stck_bsop_date"); if (date.isBlank()) date = text(row, "bsop_date");
        String time = text(row, "stck_cntg_hour"); if (time.isBlank()) time = text(row, "bsop_hour");
        if (!date.matches("[0-9]{8}") || !time.matches("[0-9]{6}")) return null;
        try { Instant value = LocalDateTime.of(LocalDate.parse(date, DAY), LocalTime.parse(time, TIME)).atZone(SEOUL).toInstant(); return value.isAfter(fetchedAt) ? null : value; }
        catch (RuntimeException ignored) { return null; }
    }
    private static String text(JsonNode node, String field) { return node != null && node.path(field).isTextual() ? node.path(field).asText().trim() : ""; }
    private static boolean within(long deadline) { return !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline; }
    private KeywordSearchDataService.Snapshot unavailable(String warning) { return new KeywordSearchDataService.Snapshot("UNAVAILABLE", List.of(), clock.instant(), SCOPE, List.of(warning)); }
}
