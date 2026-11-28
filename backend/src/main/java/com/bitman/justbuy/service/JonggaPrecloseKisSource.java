package com.bitman.justbuy.service;

import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Verifies same-session KRX observations for the closing-session strategy. */
@Component
public class JonggaPrecloseKisSource {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime OPEN = LocalTime.of(9, 0);
    private static final LocalTime AUCTION_START = LocalTime.of(15, 20);
    private static final long MAX_OBSERVATION_SECONDS = 90;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final String DAILY_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String DAILY_TR = "FHKST03010100";

    public record Observation(JonggaPrecloseStrategy.Facts facts, Instant dailyFetchedAt, Instant minuteFetchedAt) {}

    private final KisApiService kis;
    private final Clock clock;

    @Autowired
    public JonggaPrecloseKisSource(KisApiService kis) {
        this(kis, Clock.systemUTC());
    }

    JonggaPrecloseKisSource(KisApiService kis, Clock clock) {
        this.kis = kis;
        this.clock = clock;
    }

    public Optional<Observation> load(String code, Instant asOf, long deadlineNanos) {
        if (code == null || !code.matches("[0-9]{6}") || asOf == null) return Optional.empty();
        LocalDate session = asOf.atZone(KST).toLocalDate();
        if (!KoreanMarketCalendar.isTradingDay(session) || !inSession(asOf, session)
            || !canRead(asOf, session, deadlineNanos)) return Optional.empty();

        var params = Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", code,
            "FID_INPUT_DATE_1", session.minusDays(100).format(DAY), "FID_INPUT_DATE_2", session.format(DAY),
            "FID_PERIOD_DIV_CODE", "D", "FID_ORG_ADJ_PRC", "1");
        var day = kis.fetchVerifiedReadOnly(DAILY_PATH, DAILY_TR, params, deadlineNanos);
        if (day == null || !"AVAILABLE".equals(day.status()) || !validFetch(day.fetchedAt(), asOf, session)
            || day.body() == null || !"0".equals(day.body().path("rt_cd").textValue())
            || !code.equals(day.body().path("output1").path("stck_shrn_iscd").textValue())) return Optional.empty();

        var dailyRows = dailyRows(day.body().path("output2"), session);
        if (dailyRows == null || !dailyRows.containsKey(session)) return Optional.empty();
        var today = dailyRows.get(session);
        long open = number(today.path("stck_oprc"));
        long high = number(today.path("stck_hgpr"));
        long turnover = number(today.path("acml_tr_pbmn"));
        if (open <= 0 || high < open || turnover < 0 || !canRead(asOf, session, deadlineNanos)) return Optional.empty();

        var intraday = kis.fetchMinuteEvidence(code, asOf, deadlineNanos);
        if (intraday == null || !"AVAILABLE".equals(intraday.status()) || !code.equals(intraday.stockCode())
            || !validFetch(intraday.fetchedAt(), asOf, session) || !canRead(asOf, session, deadlineNanos)) return Optional.empty();

        var minutes = minutes(intraday.rows(), code, asOf, session, high);
        if (minutes == null || minutes.isEmpty()) return Optional.empty();
        var facts = new JonggaPrecloseStrategy.Facts(open, high, turnover, prior20High(dailyRows, session), minutes);
        return Optional.of(new Observation(facts, day.fetchedAt(), intraday.fetchedAt()));
    }

    private boolean canRead(Instant asOf, LocalDate session, long deadlineNanos) {
        Instant now = clock.instant();
        return !Thread.currentThread().isInterrupted() && KisRestThrottle.remaining(deadlineNanos) > 0
            && inSession(now, session) && !now.isBefore(asOf) && !now.isAfter(asOf.plusSeconds(MAX_OBSERVATION_SECONDS));
    }

    private boolean validFetch(Instant fetchedAt, Instant asOf, LocalDate session) {
        return fetchedAt != null && !fetchedAt.isBefore(asOf) && !fetchedAt.isAfter(clock.instant())
            && inSession(fetchedAt, session);
    }

    private static boolean inSession(Instant instant, LocalDate session) {
        var local = instant.atZone(KST);
        var time = local.toLocalTime();
        return local.toLocalDate().equals(session) && !time.isBefore(OPEN) && time.isBefore(AUCTION_START);
    }

    private static Map<LocalDate, JsonNode> dailyRows(JsonNode rows, LocalDate session) {
        if (!rows.isArray()) return null;
        Map<LocalDate, JsonNode> byDate = new HashMap<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) return null;
            // KIS may pad the daily response with empty rows.
            if (row.path("stck_bsop_date").asText().isBlank()
                && row.properties().stream().allMatch(field -> field.getValue().asText().isBlank())) continue;
            LocalDate date = date(row.path("stck_bsop_date"));
            if (date == null || date.isAfter(session) || !KoreanMarketCalendar.isTradingDay(date)
                || byDate.putIfAbsent(date, row) != null) return null;
        }
        return byDate;
    }

    private static Long prior20High(Map<LocalDate, JsonNode> dailyRows, LocalDate session) {
        LocalDate date = session;
        long recentHigh = 0;
        for (int i = 0; i < 20; i++) {
            date = KoreanMarketCalendar.previousTradingDay(date);
            JsonNode row = dailyRows.get(date);
            if (row == null) return null;
            long open = number(row.path("stck_oprc"));
            long high = number(row.path("stck_hgpr"));
            long low = number(row.path("stck_lwpr"));
            long close = number(row.path("stck_clpr"));
            if (low <= 0 || open < low || close < low || high < open || high < close) return null;
            recentHigh = Math.max(recentHigh, high);
        }
        return recentHigh;
    }

    private static List<JonggaPrecloseStrategy.Minute> minutes(JsonNode rows, String code, Instant asOf,
                                                               LocalDate session, long dailyHigh) {
        if (rows == null || !rows.isArray()) return null;
        Map<Instant, JonggaPrecloseStrategy.Minute> byTime = new TreeMap<>();
        for (JsonNode row : rows) {
            if (!session.equals(date(row.path("stck_bsop_date")))
                || (row.has("stck_shrn_iscd") && !code.equals(row.path("stck_shrn_iscd").textValue()))) return null;
            LocalTime time = minuteTime(row.path("stck_cntg_hour"));
            if (time == null || time.isBefore(OPEN)) return null;
            Instant startedAt = session.atTime(time).atZone(KST).toInstant();
            // KIS can show the previous minute's volume in the open minute before its first trade.
            if (startedAt.plusSeconds(60).isAfter(asOf)) continue;
            long close = number(row.path("stck_prpr"));
            long volume = number(row.path("cntg_vol"));
            if (close <= 0 || close > dailyHigh || volume < 0) return null;
            var minute = new JonggaPrecloseStrategy.Minute(startedAt, close, volume);
            if (byTime.putIfAbsent(startedAt, minute) != null) return null;
        }
        return List.copyOf(byTime.values());
    }

    private static LocalDate date(JsonNode node) {
        String value = node.textValue();
        if (value == null || !value.matches("[0-9]{8}")) return null;
        try {
            return LocalDate.parse(value, DAY);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static LocalTime minuteTime(JsonNode node) {
        String value = node.textValue();
        if (value == null || !value.matches("[0-9]{4}00")) return null;
        try {
            return LocalTime.parse(value, MINUTE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static long number(JsonNode node) {
        String value = node.asText();
        if (!value.matches("[0-9]{1,19}")) return -1;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
