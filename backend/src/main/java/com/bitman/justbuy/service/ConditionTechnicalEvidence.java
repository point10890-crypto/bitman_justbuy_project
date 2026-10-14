package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionCheck;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import java.time.*;
import java.util.*;

/** Measured daily-bar checks only. Does not invent intraday VWAP, flows or theme membership. */
final class ConditionTechnicalEvidence {
    private ConditionTechnicalEvidence() {}
    record Bar(Double open, Double high, Double low, Double close, Double volume) {
        boolean valid() {
            return positive(open) && positive(high) && positive(low) && positive(close)
                && low <= Math.min(open, close) && high >= Math.max(open, close);
        }
    }

    static List<ConditionCheck> evaluate(String mode, Map<LocalDate, Bar> bars, LocalDate date, Instant fetchedAt) {
        List<ConditionCheck> checks = new ArrayList<>(ConditionCheck.unknown(mode));
        Bar today = bars.get(date), previous = bars.get(previous(date));
        if (today == null || !today.valid() || fetchedAt == null) return checks;
        Double ma5 = average(bars, date, 5, false), ma20 = average(bars, date, 20, false);
        Double ma60 = average(bars, date, 60, false), vol5 = average(bars, date, 5, true);
        Double vol20 = average(bars, date, 20, true);
        Double change = previous != null && previous.valid() ? (today.close / previous.close - 1) * 100 : null;
        String source = "KIS 일봉 · " + date + " 기준 재조회 (포착 당시 판정과 구분)";
        if ("BREAKOUT".equals(mode)) {
            if (positive(vol20) && positive(today.volume))
                put(checks, "technical.0", "당일 누적 거래량 ≥ 직전 20거래일 평균의 2배 (동시간대 비교 아님)",
                    today.volume >= vol20 * 2, fmt("누적 %.0f주 / 직전 평균 %.0f주 · %.2f배", today.volume, vol20, today.volume / vol20), source, fetchedAt);
            if (previous != null && previous.valid())
                put(checks, "technical.1", "현재가 > 전일 고가 및 당일 시가",
                    today.close > previous.high && today.close > today.open,
                    fmt("현재 %.0f원 / 전일 고가 %.0f원 / 시가 %.0f원", today.close, previous.high, today.open), source, fetchedAt);
            if (change != null) put(checks, "technical.2", "당일 상승률 3~15%", change >= 3 && change <= 15,
                fmt("%+.2f%% · 직전 종가 대비", change), source, fetchedAt);
        } else if ("REVERSAL_EDGE".equals(mode)) {
            if (positive(ma5) && positive(ma20) && positive(ma60))
                put(checks, "technical.0", "직전 완료 일봉의 이동평균 5 > 20 > 60", ma5 > ma20 && ma20 > ma60,
                    fmt("5일 %.2f원 / 20일 %.2f원 / 60일 %.2f원", ma5, ma20, ma60), source, fetchedAt);
            if (positive(ma20) && previous != null && previous.valid())
                put(checks, "technical.1", "전일 20일선 ±2% 접촉 후 당일 양봉·전일 종가 위 반등",
                    previous.low <= ma20 * 1.02 && previous.high >= ma20 * .98
                        && previous.close >= ma20 * .98 && today.close > today.open && today.close > previous.close,
                    fmt("20일선 %.2f원 / 전일 저가 %.0f원·고가 %.0f원·종가 %.0f원 / 시가 %.0f원·현재 %.0f원",
                        ma20, previous.low, previous.high, previous.close, today.open, today.close), source, fetchedAt);
            if (positive(vol5) && positive(vol20) && positive(today.volume))
                put(checks, "technical.2", "직전 5일 평균 거래량 < 20일 평균, 당일 누적 > 5일 평균",
                    vol5 < vol20 && today.volume > vol5,
                    fmt("5일 %.0f주 / 20일 %.0f주 / 당일 누적 %.0f주", vol5, vol20, today.volume), source, fetchedAt);
        } else if ("JONGGA_V2".equals(mode)) {
            // An unfinished bar cannot confirm a high closing price.
            var observed = fetchedAt.atZone(ZoneId.of("Asia/Seoul"));
            if (observed.toLocalDate().isAfter(date) || (observed.toLocalDate().equals(date)
                && !observed.toLocalTime().isBefore(LocalTime.of(15, 40))))
                put(checks, "technical.0", "종가 ≥ 당일 고가의 97%", today.close >= today.high * .97,
                    fmt("종가 %.0f원 / 당일 고가 %.0f원 · %.2f%%", today.close, today.high, today.close / today.high * 100), source, fetchedAt);
            if (change != null) put(checks, "technical.3", "당일 상승률 5~15%", change >= 5 && change <= 15,
                fmt("%+.2f%% · 직전 종가 대비", change), source, fetchedAt);
        }
        return List.copyOf(checks);
    }

    private static Double average(Map<LocalDate, Bar> bars, LocalDate date, int count, boolean volume) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            date = previous(date);
            Bar bar = bars.get(date);
            if (bar == null || !bar.valid()) return null;
            Double value = volume ? bar.volume : bar.close;
            if (!positive(value)) return null;
            sum += value;
        }
        return sum / count;
    }
    private static LocalDate previous(LocalDate date) {
        do { date = date.minusDays(1); } while (!KoreanMarketCalendar.isTradingDay(date));
        return date;
    }
    private static String fmt(String pattern, Object... args) { return String.format(Locale.ROOT, pattern, args); }
    private static void put(List<ConditionCheck> checks, String id, String criterion, boolean pass,
                            String observed, String source, Instant at) {
        checks.replaceAll(c -> c.id().equals(id) ? new ConditionCheck(id, c.category(), criterion,
            pass ? "PASS" : "FAIL", observed, source, at.toString()) : c);
    }

    private static boolean positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }
}
