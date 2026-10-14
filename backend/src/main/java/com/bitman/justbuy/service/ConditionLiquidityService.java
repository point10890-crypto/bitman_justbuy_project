package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.dto.condition.ConditionCheck;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Shared, fail-closed liquidity gate for all home sections and their live candidate producers. */
@Service
public class ConditionLiquidityService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String EVIDENCE_PREFIX = "유동성: ";
    private static final int CANDIDATE_LIMIT = 30;
    private final KisApiService kis;
    private final Clock clock;
    private final Cache<String, Amounts> cache = Caffeine.newBuilder()
        .maximumSize(500).expireAfterWrite(60, TimeUnit.SECONDS).build();
    private record Amounts(BigDecimal today, BigDecimal average20, LocalDate date, Instant fetchedAt,
                           Map<LocalDate, ConditionTechnicalEvidence.Bar> bars) {}
    private record Ranked(ConditionSignalDto signal, ConditionLiquidityPolicy.Result result) {}

    @Autowired public ConditionLiquidityService(KisApiService kis) { this(kis, Clock.systemUTC()); }
    ConditionLiquidityService(KisApiService kis, Clock clock) { this.kis = kis; this.clock = clock; }

    public ConditionLiquidityPolicy.Result evaluate(String mode, String code) {
        return evaluate(mode, code, System.nanoTime() + Duration.ofSeconds(5).toNanos());
    }

    public AnalysisResponse filterAnalysis(String mode, AnalysisResponse response) {
        if (response == null || !ConditionLiquidityPolicy.RULES.containsKey(mode)
            || response.stockPicks() == null || response.stockPicks().isEmpty()) return response;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Map<String, Integer> bonuses = new HashMap<>();
        List<StockPick> picks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StockPick pick : response.stockPicks()) {
            if (pick == null || !seen.add(pick.code())) continue;
            if (seen.size() > CANDIDATE_LIMIT) break;
            var decision = evaluate(mode, pick.code(), deadline);
            if (!decision.eligible()) continue;
            bonuses.put(pick.code(), decision.bonus());
            String reason = Objects.requireNonNullElse(pick.reason(), "");
            int previous = reason.indexOf("\n" + EVIDENCE_PREFIX);
            if (previous >= 0) reason = reason.substring(0, previous);
            picks.add(new StockPick(pick.name(), pick.code(), pick.currentPrice(), pick.targetPrice(), pick.stopLoss(),
                pick.action(), reason + "\n" + EVIDENCE_PREFIX + decision.reason(), pick.financialScore(), pick.financialSummary(),
                pick.candidateSource(), pick.quoteSource(), pick.quoteCheckedAt()));
        }
        // AI scores remain untouched; measured turnover contributes only to result ordering.
        Map<String, Integer> scores = new HashMap<>();
        if (response.consensus() != null && response.consensus().stocks() != null)
            response.consensus().stocks().forEach(stock -> scores.put(stock.code(), stock.consensusScore()));
        picks.sort(Comparator.comparingInt((StockPick pick) -> scores.getOrDefault(pick.code(), 0) + bonuses.get(pick.code())).reversed());
        return AnalysisPublicationView.finalizePicks(response, picks);
    }

    public ConditionLiquidityPolicy.Result evaluate(String mode, String code, long deadline) {
        if (!ConditionLiquidityPolicy.RULES.containsKey(mode) || code == null || !code.matches("[0-9]{6}"))
            return unavailable();
        ZonedDateTime now = clock.instant().atZone(KST);
        LocalDate date = marketDate(now);
        String key = date + ":" + code;
        Amounts amounts = cache.getIfPresent(key);
        if (amounts != null && (amounts.fetchedAt().isAfter(now.toInstant())
            || amounts.fetchedAt().isBefore(now.toInstant().minusSeconds(60)))) {
            cache.invalidate(key);
            amounts = null;
        }
        if (amounts == null) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) return unavailable();
            try {
                amounts = cache.get(key, ignored -> {
                    Amounts loaded;
                    try { loaded = load(code, date, deadline); }
                    catch (RuntimeException error) { loaded = null; }
                    return loaded == null ? new Amounts(null, null, date, clock.instant(), Map.of()) : loaded;
                });
            } catch (RuntimeException ignored) { return unavailable(); }
        }
        if (amounts == null || amounts.today() == null) return unavailable();
        now = clock.instant().atZone(KST);
        if (!marketDate(now).equals(date)) return unavailable();
        LocalTime time = date.equals(now.toLocalDate()) ? now.toLocalTime() : LocalTime.of(15, 30);
        var result = ConditionLiquidityPolicy.evaluate(mode, amounts.today(), amounts.average20(), time);
        return new ConditionLiquidityPolicy.Result(result.eligible(), result.bonus(), result.reason()
            + " · KIS 실제 일봉 거래대금(KRW) " + amounts.date() + " · 조회 " + amounts.fetchedAt().atZone(KST), amounts.fetchedAt().toString());
    }

    /** Keep original rule/AI scores; use the measured turnover bonus only for ordering, once per pass. */
    public List<ConditionSignalDto> filterSignals(String mode, List<ConditionSignalDto> signals, int limit) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        List<Ranked> eligible = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (signals != null) for (ConditionSignalDto signal : signals) {
            if (signal == null || !seen.add(signal.stockCode())) continue;
            if (seen.size() > CANDIDATE_LIMIT) break;
            var result = evaluate(mode, signal.stockCode(), deadline);
            if (result.eligible()) eligible.add(new Ranked(signal, result));
        }
        eligible.sort(Comparator.comparingInt((Ranked item) -> item.signal().finalScore() + item.result().bonus()).reversed());
        List<ConditionSignalDto> result = new ArrayList<>();
        for (Ranked item : eligible) {
            if (result.size() >= limit) break;
            var s = item.signal();
            List<String> evidence = new ArrayList<>();
            if (s.evidence() != null) s.evidence().stream().filter(e -> !e.startsWith(EVIDENCE_PREFIX)).forEach(evidence::add);
            evidence.add(EVIDENCE_PREFIX + item.result().reason());
            evidence.add(EVIDENCE_PREFIX + "동일 시간대 20일 평균·눌림 대비 증가·업종/테마 순위·마지막 30분 비교 미확인: 추가 가점 없음");
            var rule = ConditionLiquidityPolicy.RULES.get(mode);
            List<ConditionCheck> checks = new ArrayList<>();
            checks.add(new ConditionCheck("liquidity.0", "liquidity",
                "누적 " + rule.minimumEok() + "억 이상" + (rule.average20Eok() > 0 ? " · 직전 20거래일 평균 " + rule.average20Eok() + "억 이상" : "")
                    + " (장초반 완화 적용 시 실제 최소값은 관측값에 표시)",
                "PASS", item.result().reason(), "KIS 일봉 acml_tr_pbmn · 원(KRW)", item.result().observedAt()));
            s.conditionChecks().stream().filter(c -> !"liquidity".equals(c.category())).forEach(checks::add);
            // Reuse the verified response already fetched for turnover: no extra request per card.
            Amounts observations = cache.getIfPresent(marketDate(clock.instant().atZone(KST)) + ":" + s.stockCode());
            if (observations != null && observations.fetchedAt().toString().equals(item.result().observedAt())) {
                for (ConditionCheck measured : ConditionTechnicalEvidence.evaluate(mode, observations.bars(),
                    observations.date(), observations.fetchedAt())) {
                    if (!"technical".equals(measured.category())) continue;
                    boolean previousRefresh = checks.stream().anyMatch(c -> c.id().equals(measured.id())
                        && c.source() != null && c.source().startsWith("KIS 일봉 · "));
                    if ("UNKNOWN".equals(measured.status()) && !previousRefresh) continue;
                    checks.removeIf(c -> c.id().equals(measured.id()));
                    checks.add(measured);
                }
            }
            result.add(new ConditionSignalDto(s.section(), s.mode(), result.size() + 1, s.stockName(), s.stockCode(),
                s.capturePrice(), s.currentPrice(), s.highPrice(), s.stopLoss(), s.maxReturnPct(), s.status(),
                s.ruleScore(), s.aiScore(), s.finalScore(), s.summary(), List.copyOf(evidence), s.riskFlags(),
                s.invalidation(), s.capturedAt(), checks));
        }
        return List.copyOf(result);
    }

    private Amounts load(String code, LocalDate date, long deadline) {
        var params = Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", code,
            "FID_INPUT_DATE_1", date.minusDays(100).format(DateTimeFormatter.BASIC_ISO_DATE),
            "FID_INPUT_DATE_2", date.format(DateTimeFormatter.BASIC_ISO_DATE),
            "FID_PERIOD_DIV_CODE", "D", "FID_ORG_ADJ_PRC", "0");
        var response = kis.fetchVerifiedReadOnly("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice",
            "FHKST03010100", params, deadline);
        Instant now = clock.instant();
        if (response == null || !"AVAILABLE".equals(response.status()) || response.body() == null
            || response.fetchedAt() == null || response.fetchedAt().isAfter(now)
            || response.fetchedAt().isBefore(now.minusSeconds(60)) || System.nanoTime() >= deadline) return null;
        JsonNode body = response.body();
        if (!"0".equals(body.path("rt_cd").asText()) || !body.path("output2").isArray()
            || !code.equals(body.path("output1").path("stck_shrn_iscd").asText())) return null;
        Map<LocalDate, BigDecimal> amounts = new HashMap<>();
        Map<LocalDate, ConditionTechnicalEvidence.Bar> bars = new HashMap<>();
        Set<LocalDate> seen = new HashSet<>();
        for (JsonNode row : body.path("output2")) {
            // The API can pad output2 with empty objects; those are not trading observations.
            String text = row.path("stck_bsop_date").asText("");
            if (text.isBlank()) continue;
            LocalDate day;
            try { day = LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE); }
            catch (RuntimeException ignored) { return null; }
            if (day.isAfter(date) || !seen.add(day)) return null;
            BigDecimal value = amount(row.path("acml_tr_pbmn"));
            if (value != null) amounts.put(day, value);
            bars.put(day, new ConditionTechnicalEvidence.Bar(number(row.path("stck_oprc")),
                number(row.path("stck_hgpr")), number(row.path("stck_lwpr")),
                number(row.path("stck_clpr")), number(row.path("acml_vol"))));
        }
        BigDecimal today = amounts.get(date);
        if (today == null) return null;
        BigDecimal sum = BigDecimal.ZERO;
        LocalDate previous = date;
        boolean complete = true;
        for (int i = 0; i < 20; i++) {
            do { previous = previous.minusDays(1); } while (!KoreanMarketCalendar.isTradingDay(previous));
            BigDecimal value = amounts.get(previous);
            if (value == null) { complete = false; break; }
            sum = sum.add(value);
        }
        return new Amounts(today, complete ? sum.divide(BigDecimal.valueOf(20)) : null, date, response.fetchedAt(), Map.copyOf(bars));
    }

    private static Double number(JsonNode node) {
        BigDecimal value = amount(node);
        return value == null ? null : value.doubleValue();
    }

    private static BigDecimal amount(JsonNode node) {
        if (node == null || (!node.isTextual() && !node.isNumber())) return null;
        String text = node.asText().trim();
        if (!text.matches("[0-9]{1,20}")) return null;
        return new BigDecimal(text);
    }
    private static LocalDate marketDate(ZonedDateTime now) {
        LocalDate date = now.toLocalDate();
        if (now.toLocalTime().isBefore(LocalTime.of(9, 0))) date = date.minusDays(1);
        while (!KoreanMarketCalendar.isTradingDay(date)) date = date.minusDays(1);
        return date;
    }
    private static ConditionLiquidityPolicy.Result unavailable() {
        return new ConditionLiquidityPolicy.Result(false, 0, "거래대금 데이터 미확인");
    }
}
