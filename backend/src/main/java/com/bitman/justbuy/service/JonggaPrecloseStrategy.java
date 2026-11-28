package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionCheck;
import com.bitman.justbuy.util.KoreanMarketCalendar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Evaluates the observed pre-close setup. Closing auction or subsequent prices are not inputs. */
final class JonggaPrecloseStrategy {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime CONTINUOUS_OPEN = LocalTime.of(9, 0);
    private static final LocalTime CONTINUOUS_END = LocalTime.of(15, 20);
    private static final long MIN_TURNOVER_WON = 10_000_000_000L;
    private static final int WINDOW_MINUTES = 20;
    private static final String QUOTE_SOURCE = "KIS KRX 당일 시세";
    private static final String MINUTE_SOURCE = "KIS KRX 완료 1분봉";
    private static final String RANGE_SOURCE = "KIS KRX 시가·고가 및 완료 1분봉";
    private static final String BREAKOUT_SOURCE = "KIS KRX 완료 일봉·1분봉";

    record Minute(Instant startedAt, long close, long volume) {}

    record Facts(long todayOpen, long todayHigh, long turnoverWon, Long prior20High, List<Minute> minutes) {}

    record Judgement(List<String> setups, int score, List<ConditionCheck> checks, List<String> evidence) {
        boolean eligible() { return !setups.isEmpty(); }

        boolean complete() {
            return checks.stream().noneMatch(check -> "UNKNOWN".equals(check.status()));
        }
    }

    private record Window(Instant endedAt, long firstClose, long nextToLastClose, long lastClose, long highestClose,
                          BigDecimal previousVolume, BigDecimal recentVolume, BigDecimal priceVolume) {
        BigDecimal totalVolume() { return previousVolume.add(recentVolume); }

        // The recent interval is 5 minutes and the earlier interval is 15 minutes.
        BigDecimal relativeRecentVolume() { return recentVolume.multiply(BigDecimal.valueOf(3)); }
    }

    private JonggaPrecloseStrategy() {}

    static boolean inGainBand(double dayChangePct) {
        return dayChangePct >= 3 && dayChangePct <= 12;
    }

    static Judgement evaluate(Facts facts, double dayChangePct, Instant decisionAt) {
        Instant cutoff = validCutoff(decisionAt);
        Window window = cutoff == null || facts == null ? null : completedWindow(facts.minutes(), cutoff);
        long turnover = facts == null ? 0 : facts.turnoverWon();
        ConditionCheck liquidity = check("liquidity.0", "당일 누적 거래대금 100억원 이상",
            cutoff != null && turnover > 0 ? format("%,d원", turnover) : null,
            turnover >= MIN_TURNOVER_WON, QUOTE_SOURCE, decisionAt);
        ConditionCheck change = check("technical.3", "전일 종가 대비 당일 상승률 3~12%",
            cutoff != null && Double.isFinite(dayChangePct) ? format("%+.2f%%", dayChangePct) : null,
            inGainBand(dayChangePct), QUOTE_SOURCE, decisionAt);
        ConditionCheck range = rangeCheck(facts, window);
        ConditionCheck weightedMean = meanCheck(window);
        ConditionCheck continuation = continuationCheck(window);
        ConditionCheck breakout = breakoutCheck(facts, window);
        List<ConditionCheck> checks = List.of(liquidity, change, range, weightedMean, continuation, breakout);

        List<String> setups = new ArrayList<>(2);
        int score = 0;
        if (passed(liquidity) && passed(change) && passed(range) && passed(weightedMean)) {
            if (passed(continuation)) setups.add("STRENGTH_CONTINUATION");
            if (passed(breakout)) setups.add("CONFIRMED_20D_BREAKOUT");
            if (!setups.isEmpty()) score = 60 + (passed(continuation) ? 15 : 0) + (passed(breakout) ? 20 : 0);
        }
        return new Judgement(List.copyOf(setups), score, checks, checks.stream()
            .filter(check -> !"UNKNOWN".equals(check.status()))
            .map(check -> (passed(check) ? "충족 · " : "미충족 · ") + check.criterion() + " · " + check.observedValue()).toList());
    }

    private static ConditionCheck rangeCheck(Facts facts, Window window) {
        String criterion = "최종 완료 분봉 종가 > 당일 시가, 당일 관측 고가의 97% 이상";
        if (window == null || facts == null || facts.todayOpen() <= 0 || facts.todayHigh() < facts.todayOpen()
            || window.highestClose() > facts.todayHigh())
            return check("technical.0", criterion, null, false, RANGE_SOURCE, null);
        long last = window.lastClose();
        return check("technical.0", criterion,
            format("완료 분봉 종가 %,d원 · 당일 시가 %,d원 · 관측 고가 %,d원", last, facts.todayOpen(), facts.todayHigh()),
            last > facts.todayOpen() && decimal(last).compareTo(decimal(facts.todayHigh()).multiply(new BigDecimal("0.97"))) >= 0,
            RANGE_SOURCE, window.endedAt());
    }

    private static ConditionCheck meanCheck(Window window) {
        String criterion = "최종 완료 분봉 종가 > 직전 20분 종가×거래량 가중평균";
        if (window == null) return check("technical.4", criterion, null, false, MINUTE_SOURCE, null);
        BigDecimal mean = window.priceVolume().divide(window.totalVolume(), 2, RoundingMode.HALF_UP);
        return check("technical.4", criterion,
            format("완료 분봉 종가 %,d원 · 종가×거래량 가중평균 %,.2f원", window.lastClose(), mean),
            decimal(window.lastClose()).multiply(window.totalVolume()).compareTo(window.priceVolume()) > 0,
            MINUTE_SOURCE, window.endedAt());
    }

    private static ConditionCheck continuationCheck(Window window) {
        String criterion = "20분 첫 종가 대비 마지막 종가 0.3% 이상 상승 및 최근 5분 평균 거래량이 이전 15분 이상";
        if (window == null) return check("technical.1", criterion, null, false, MINUTE_SOURCE, null);
        boolean rising = decimal(window.lastClose()).compareTo(decimal(window.firstClose()).multiply(new BigDecimal("1.003"))) >= 0;
        return check("technical.1", criterion,
            format("종가 변화 %+.2f%% · 평균 거래량 %.2f배",
                ((double) window.lastClose() / window.firstClose() - 1) * 100, volumeRatio(window)),
            rising && window.relativeRecentVolume().compareTo(window.previousVolume()) >= 0,
            MINUTE_SOURCE, window.endedAt());
    }

    private static ConditionCheck breakoutCheck(Facts facts, Window window) {
        String criterion = "마지막 두 완료 분봉 종가 모두 직전 20일 고가 초과 및 최근 5분 평균 거래량 1.2배 이상";
        Long priorHigh = facts == null ? null : facts.prior20High();
        if (window == null || priorHigh == null || priorHigh <= 0)
            return check("technical.2", criterion, null, false, BREAKOUT_SOURCE, null);
        return check("technical.2", criterion,
            format("마지막 두 종가 %,d원·%,d원 · 직전 20일 고가 %,d원 · 평균 거래량 %.2f배",
                window.nextToLastClose(), window.lastClose(), priorHigh, volumeRatio(window)),
            window.nextToLastClose() > priorHigh && window.lastClose() > priorHigh
                && window.relativeRecentVolume().compareTo(window.previousVolume().multiply(new BigDecimal("1.2"))) >= 0,
            BREAKOUT_SOURCE, window.endedAt());
    }

    private static Instant validCutoff(Instant decisionAt) {
        if (decisionAt == null) return null;
        Instant cutoff = decisionAt.truncatedTo(ChronoUnit.MINUTES);
        var local = cutoff.atZone(KST);
        if (!KoreanMarketCalendar.isTradingDay(local.toLocalDate())
            || local.toLocalTime().isBefore(CONTINUOUS_OPEN.plusMinutes(WINDOW_MINUTES))
            || local.toLocalTime().isAfter(CONTINUOUS_END)) return null;
        return cutoff;
    }

    private static Window completedWindow(List<Minute> minutes, Instant cutoff) {
        if (minutes == null) return null;
        Instant start = cutoff.minus(WINDOW_MINUTES, ChronoUnit.MINUTES);
        var indexed = new HashMap<Instant, Minute>(WINDOW_MINUTES);
        for (Minute minute : minutes) {
            if (minute == null || minute.startedAt() == null || minute.startedAt().isBefore(start)
                || !minute.startedAt().isBefore(cutoff)) continue;
            if (!minute.startedAt().equals(minute.startedAt().truncatedTo(ChronoUnit.MINUTES))
                || minute.close() <= 0 || minute.volume() <= 0 || indexed.putIfAbsent(minute.startedAt(), minute) != null)
                return null;
        }
        if (indexed.size() != WINDOW_MINUTES) return null;

        BigDecimal previousVolume = BigDecimal.ZERO;
        BigDecimal recentVolume = BigDecimal.ZERO;
        BigDecimal priceVolume = BigDecimal.ZERO;
        long highestClose = 0;
        for (int i = 0; i < WINDOW_MINUTES; i++) {
            Minute minute = Objects.requireNonNull(indexed.get(start.plus(i, ChronoUnit.MINUTES)));
            BigDecimal volume = decimal(minute.volume());
            priceVolume = priceVolume.add(decimal(minute.close()).multiply(volume));
            highestClose = Math.max(highestClose, minute.close());
            if (i < 15) previousVolume = previousVolume.add(volume);
            else recentVolume = recentVolume.add(volume);
        }
        return new Window(cutoff, indexed.get(start).close(), indexed.get(cutoff.minusSeconds(120)).close(),
            indexed.get(cutoff.minusSeconds(60)).close(), highestClose, previousVolume, recentVolume, priceVolume);
    }

    private static BigDecimal volumeRatio(Window window) {
        return window.relativeRecentVolume().divide(window.previousVolume(), 2, RoundingMode.HALF_UP);
    }

    private static ConditionCheck check(String id, String criterion, String value, boolean passed, String source, Instant at) {
        String category = "liquidity.0".equals(id) ? "liquidity" : "technical";
        if (value == null) return new ConditionCheck(id, category, criterion, "UNKNOWN", "관측 자료 미확인", "미확인", null);
        return new ConditionCheck(id, category, criterion, passed ? "PASS" : "FAIL", value, source, at.toString());
    }

    private static boolean passed(ConditionCheck check) { return "PASS".equals(check.status()); }

    private static BigDecimal decimal(long value) { return BigDecimal.valueOf(value); }

    private static String format(String template, Object... values) { return String.format(Locale.ROOT, template, values); }
}
