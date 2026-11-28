package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionCheck;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.util.KoreanMarketCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/** Uses the same live ranking price that is displayed, with independent daily reference levels. */
final class ShortTermBreakoutEvidence {
    static final String BREAKOUT_CHECK = "technical.1";
    static final String SOURCE = "KIS 실시간 랭킹 · 일봉";
    static final String QUOTE_SOURCE = "KIS OpenAPI";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String CRITERION = "현재가 > 전일 고가 및 당일 시가";

    private ShortTermBreakoutEvidence() {}

    static ConditionCheck evaluate(ConditionSignalDto signal, Map<LocalDate, ConditionTechnicalEvidence.Bar> bars,
                                   LocalDate session, Instant dailyFetchedAt, Instant now) {
        Instant sessionOpen = session.atTime(LocalTime.of(9, 0)).atZone(KST).toInstant();
        var quote = signal.conditionChecks().stream().filter(check ->
            ConditionCheck.BREAKOUT_DAILY_CHANGE_CHECK.equals(check.id()) && QUOTE_SOURCE.equals(check.source())
                && "PASS".equals(check.status())).findFirst().orElse(null);
        if (quote == null || quote.observedAt() == null || dailyFetchedAt.isBefore(sessionOpen)
            || dailyFetchedAt.isAfter(now) || !session.equals(now.atZone(KST).toLocalDate())) return unknown();

        Instant quoteAt;
        try {
            quoteAt = OffsetDateTime.parse(quote.observedAt()).toInstant();
        } catch (DateTimeParseException ignored) {
            return unknown();
        }
        if (quoteAt.isBefore(sessionOpen) || quoteAt.isBefore(now.minusSeconds(120)) || quoteAt.isAfter(now)) return unknown();

        long price = ShortTermRealtimeScanner.parsePrice(signal.currentPrice());
        var previousSession = KoreanMarketCalendar.previousTradingDay(session);
        var previous = previousSession == null ? null : bars.get(previousSession);
        var today = bars.get(session);
        if (price <= 0 || previous == null || !previous.valid() || today == null || !today.valid()) return unknown();

        boolean passed = price > previous.high() && price > today.open();
        String observation = String.format(Locale.KOREA, "현재 %,d원 / 전일 고가 %,.0f원 / 시가 %,.0f원",
            price, previous.high(), today.open());
        Instant checkedAt = quoteAt.isAfter(dailyFetchedAt) ? quoteAt : dailyFetchedAt;
        return new ConditionCheck(BREAKOUT_CHECK, "technical", CRITERION, passed ? "PASS" : "FAIL",
            observation, SOURCE, checkedAt.toString());
    }

    private static ConditionCheck unknown() {
        return new ConditionCheck(BREAKOUT_CHECK, "technical", CRITERION, "UNKNOWN", null, null, null);
    }
}
