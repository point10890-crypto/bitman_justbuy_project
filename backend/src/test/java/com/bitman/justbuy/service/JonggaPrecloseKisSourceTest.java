package com.bitman.justbuy.service;

import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JonggaPrecloseKisSourceTest {
    private static final String CODE = "005930";
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String TR_ID = "FHKST03010100";
    private static final LocalDate SESSION = LocalDate.of(2026, 11, 30);
    private static final Instant AS_OF = Instant.parse("2026-11-30T06:18:30Z");
    private static final Instant DAILY_AT = AS_OF.plusSeconds(1);
    private static final Instant MINUTE_AT = AS_OF.plusSeconds(2);
    private static final long DEADLINE = Long.MAX_VALUE;

    private final ObjectMapper mapper = new ObjectMapper();
    private final KisApiService kis = mock(KisApiService.class);
    private final JonggaPrecloseKisSource source = new JonggaPrecloseKisSource(kis, Clock.fixed(MINUTE_AT, ZoneOffset.UTC));

    @Test void keepsExactKrxTurnoverAndOnlyCompletedMinutesInTimeOrder() {
        var rows = minutes();
        addMinute(rows, "20261130", "151800", 9999, 100000);
        addMinute(rows, "20261130", "151900", 9999, 100000);
        stubDaily(daily(20), DAILY_AT);
        stubMinutes(rows, CODE, MINUTE_AT);

        var observation = source.load(CODE, AS_OF, DEADLINE).orElseThrow();
        var facts = observation.facts();
        assertThat(observation.dailyFetchedAt()).isEqualTo(DAILY_AT);
        assertThat(observation.minuteFetchedAt()).isEqualTo(MINUTE_AT);
        assertThat(facts.todayOpen()).isEqualTo(10_000);
        assertThat(facts.todayHigh()).isEqualTo(10_800);
        assertThat(facts.turnoverWon()).isEqualTo(10_000_000_001L);
        assertThat(facts.prior20High()).isEqualTo(10_519);
        assertThat(facts.minutes()).containsExactly(
            new JonggaPrecloseStrategy.Minute(Instant.parse("2026-11-30T06:16:00Z"), 10_300, 135),
            new JonggaPrecloseStrategy.Minute(Instant.parse("2026-11-30T06:17:00Z"), 10_350, 142));
        verify(kis).fetchVerifiedReadOnly(PATH, TR_ID, Map.of(
            "FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", CODE,
            "FID_INPUT_DATE_1", SESSION.minusDays(100).format(DateTimeFormatter.BASIC_ISO_DATE),
            "FID_INPUT_DATE_2", "20261130", "FID_PERIOD_DIV_CODE", "D", "FID_ORG_ADJ_PRC", "1"), DEADLINE);
        verify(kis).fetchMinuteEvidence(CODE, AS_OF, DEADLINE);
        verifyNoMoreInteractions(kis);
    }

    @Test void olderExtraBarCannotReplaceAMissingRecentTradingDay() {
        var body = daily(21);
        ((ArrayNode) body.path("output2")).remove(4);
        stubDaily(body, DAILY_AT);
        stubMinutes(minutes(), CODE, MINUTE_AT);

        assertThat(source.load(CODE, AS_OF, DEADLINE).orElseThrow().facts().prior20High()).isNull();
    }

    @Test void incompleteDailyBarCannotSupplyA20SessionHigh() {
        var body = daily(20);
        ((ObjectNode) body.path("output2").get(7)).remove("stck_clpr");
        stubDaily(body, DAILY_AT);
        stubMinutes(minutes(), CODE, MINUTE_AT);

        assertThat(source.load(CODE, AS_OF, DEADLINE).orElseThrow().facts().prior20High()).isNull();
    }

    @Test void requiresProviderDailySymbolAndActualCashForTheCurrentSession() {
        var wrongCode = daily(20);
        ((ObjectNode) wrongCode.path("output1")).put("stck_shrn_iscd", "000660");
        stubDaily(wrongCode, DAILY_AT);
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();

        var missingCash = daily(20);
        ((ObjectNode) missingCash.path("output2").get(0)).remove("acml_tr_pbmn");
        stubDaily(missingCash, DAILY_AT);
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();

        var previousSession = daily(20);
        ((ArrayNode) previousSession.path("output2")).remove(0);
        stubDaily(previousSession, DAILY_AT);
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();
    }

    @Test void rejectsConflictedBarTimeAndWrongMinuteIdentity() {
        stubDaily(daily(20), DAILY_AT);
        var conflict = minutes();
        addMinute(conflict, "20261130", "151700", 10_400, 143);
        stubMinutes(conflict, CODE, MINUTE_AT);
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();

        stubMinutes(minutes(), "000660", MINUTE_AT);
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();

        var wrongRowCode = minutes();
        ((ObjectNode) wrongRowCode.get(0)).put("stck_shrn_iscd", "000660");
        stubMinutes(wrongRowCode, CODE, MINUTE_AT);
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();
    }

    @Test void rejectsWrongDateMalformedTimeAndImpossibleMinutePrice() {
        stubDaily(daily(20), DAILY_AT);
        var wrongDate = minutes();
        addMinute(wrongDate, "20261127", "151700", 10_400, 143);
        stubMinutes(wrongDate, CODE, MINUTE_AT);
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();

        var badTime = minutes();
        addMinute(badTime, "20261130", "240000", 10_400, 143);
        stubMinutes(badTime, CODE, MINUTE_AT);
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();

        var badPrice = minutes();
        ((ObjectNode) badPrice.get(0)).put("stck_prpr", "10700.5");
        stubMinutes(badPrice, CODE, MINUTE_AT);
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();
    }

    @Test void rejectsStaleAndFutureProviderObservations() {
        stubDaily(daily(20), AS_OF.minusSeconds(91));
        stubMinutes(minutes(), CODE, MINUTE_AT);
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();

        stubDaily(daily(20), DAILY_AT);
        stubMinutes(minutes(), CODE, MINUTE_AT.plusSeconds(1));
        assertThat(source.load(CODE, AS_OF, DEADLINE)).isEmpty();
    }

    @Test void rejectsAuctionAndExpiredRequestsBeforeProviderReads() {
        var cutoff = Instant.parse("2026-11-30T06:20:00Z");
        var auction = new JonggaPrecloseKisSource(kis, Clock.fixed(cutoff, ZoneOffset.UTC));
        assertThat(auction.load(CODE, cutoff.minusSeconds(1), DEADLINE)).isEmpty();
        assertThat(source.load(CODE, AS_OF, System.nanoTime() - 1)).isEmpty();
        verifyNoInteractions(kis);
    }

    private void stubDaily(ObjectNode body, Instant fetchedAt) {
        when(kis.fetchVerifiedReadOnly(eq(PATH), eq(TR_ID), anyMap(), eq(DEADLINE)))
            .thenReturn(new KisApiService.ReadOnlySnapshot("AVAILABLE", fetchedAt, body));
    }

    private void stubMinutes(ArrayNode rows, String code, Instant fetchedAt) {
        when(kis.fetchMinuteEvidence(CODE, AS_OF, DEADLINE))
            .thenReturn(new KisApiService.ExtendedSnapshot("AVAILABLE", code, fetchedAt, rows));
    }

    private ObjectNode daily(int priorDays) {
        var body = mapper.createObjectNode().put("rt_cd", "0");
        body.putObject("output1").put("stck_shrn_iscd", CODE);
        var rows = body.putArray("output2");
        addDay(rows, SESSION, 10_000, 10_800).put("acml_tr_pbmn", "10000000001");
        LocalDate date = SESSION;
        for (int i = 0; i < priorDays; i++) {
            date = KoreanMarketCalendar.previousTradingDay(date);
            addDay(rows, date, 10_000, 10_500 + i);
        }
        rows.addObject();
        return body;
    }

    private static ObjectNode addDay(ArrayNode rows, LocalDate date, long open, long high) {
        return rows.addObject().put("stck_bsop_date", date.format(DateTimeFormatter.BASIC_ISO_DATE))
            .put("stck_oprc", open).put("stck_hgpr", high).put("stck_lwpr", open - 100).put("stck_clpr", open + 100);
    }

    private ArrayNode minutes() {
        var rows = mapper.createArrayNode();
        addMinute(rows, "20261130", "151700", 10_350, 142);
        addMinute(rows, "20261130", "151600", 10_300, 135);
        return rows;
    }

    private static void addMinute(ArrayNode rows, String date, String time, long close, long volume) {
        rows.addObject().put("stck_bsop_date", date).put("stck_cntg_hour", time)
            .put("stck_prpr", Long.toString(close)).put("cntg_vol", Long.toString(volume));
    }
}
