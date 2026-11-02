package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** JustBuy decides the market gate itself from KOSPI/KOSDAQ history — no MarketFlow call. */
class KrMarketGateServiceTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T05:00:00Z"));
    final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };

    /** 60 sessions, newest first, as Naver returns them (closePrice is a comma-formatted string). */
    static JsonNode series(double newest, double step) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < 60; i++) {
            rows.addObject().put("localTradedAt", "day-" + i)
                .put("closePrice", String.format(java.util.Locale.US, "%,.2f", newest - step * i));
        }
        return rows;
    }

    static JsonNode rising() { return series(4000, 10); }    // close > ma20 > ma60
    static JsonNode falling() { return series(2000, -10); }  // close < ma20 < ma60
    static JsonNode flat() { return series(3000, 0); }

    KrMarketGateService service(Map<String, JsonNode> byIndex) {
        return new KrMarketGateService(byIndex::get, clock);
    }

    @Test
    void bothIndicesTrendingUpIsGreen() {
        var gate = service(Map.of("KOSPI", rising(), "KOSDAQ", rising())).current();
        assertThat(gate.gate()).isEqualTo("GREEN");
        assertThat(gate.source()).isEqualTo("NAVER_INDEX");
        assertThat(gate.indices()).extracting(KrMarketGateService.IndexState::trend)
            .containsExactly(KrMarketGateService.Trend.UP, KrMarketGateService.Trend.UP);
    }

    @Test
    void aDownTrendWithNothingUpIsRed() {
        assertThat(service(Map.of("KOSPI", falling(), "KOSDAQ", falling())).gate()).isEqualTo("RED");
        assertThat(service(Map.of("KOSPI", falling(), "KOSDAQ", flat())).gate()).isEqualTo("RED");
    }

    @Test
    void mixedOrSidewaysIsYellow() {
        assertThat(service(Map.of("KOSPI", rising(), "KOSDAQ", falling())).gate()).isEqualTo("YELLOW");
        assertThat(service(Map.of("KOSPI", rising(), "KOSDAQ", flat())).gate()).isEqualTo("YELLOW");
        assertThat(service(Map.of("KOSPI", flat(), "KOSDAQ", flat())).gate()).isEqualTo("YELLOW");
    }

    @Test
    void movingAveragesUseTheNewestSessions() {
        var state = service(Map.of("KOSPI", rising())).state("KOSPI");
        assertThat(state.close()).isEqualTo(4000);
        assertThat(state.ma20()).isEqualTo(3905);    // mean of 4000..3810
        assertThat(state.ma60()).isEqualTo(3705);    // mean of 4000..3410
    }

    @Test
    void resultsAreCachedForTenMinutes() {
        AtomicInteger calls = new AtomicInteger();
        var svc = new KrMarketGateService(index -> { calls.incrementAndGet(); return rising(); }, clock);
        svc.gate();
        now.set(now.get().plus(Duration.ofMinutes(9)));
        svc.gate();
        assertThat(calls.get()).isEqualTo(2);        // one fetch per index
        now.set(now.get().plus(Duration.ofMinutes(2)));
        svc.gate();
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void aFailedFetchKeepsTheLastGateForADayThenFallsBackToYellow() {
        Map<String, JsonNode> data = new HashMap<>(Map.of("KOSPI", falling(), "KOSDAQ", falling()));
        Function<String, JsonNode> fetcher = index -> {
            JsonNode rows = data.get(index);
            if (rows == null) throw new IllegalStateException("HTTP 503");
            return rows;
        };
        var svc = new KrMarketGateService(fetcher, clock);
        assertThat(svc.gate()).isEqualTo("RED");

        data.clear();
        now.set(now.get().plus(Duration.ofMinutes(11)));
        var kept = svc.current();
        assertThat(kept.gate()).isEqualTo("RED");
        assertThat(kept.source()).isEqualTo("LAST_KNOWN");

        now.set(now.get().plus(Duration.ofDays(1)));
        var unavailable = svc.current();
        assertThat(unavailable.gate()).isEqualTo("YELLOW");
        assertThat(unavailable.source()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void tooFewSessionsIsTreatedAsAFailureNotAGuess() {
        ArrayNode short20 = MAPPER.createArrayNode();
        for (int i = 0; i < 20; i++) short20.addObject().put("closePrice", "3,000.00");
        var gate = service(Map.of("KOSPI", short20, "KOSDAQ", rising())).current();
        assertThat(gate.gate()).isEqualTo("YELLOW");
        assertThat(gate.source()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void neverThrowsWhenTheFetcherDoes() {
        var svc = new KrMarketGateService(index -> { throw new IllegalStateException("boom"); }, clock);
        assertThat(svc.gate()).isEqualTo("YELLOW");
    }
}
