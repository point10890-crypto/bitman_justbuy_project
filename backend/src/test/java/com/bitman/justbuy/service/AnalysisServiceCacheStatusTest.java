package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisServiceCacheStatusTest {
    @TempDir Path dataDir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cacheStatusExpiresTheSameDayAfternoonResultAfterMarketClose() throws Exception {
        var service = serviceAt("2026-10-12T06:40:00Z"); // 15:40 KST
        var response = saveResult("2026-10-12T05:50:00Z"); // 14:50 KST, TTL is still valid

        assertThat(service.getPrecomputed("BREAKOUT")).isNull();
        assertThat(service.getCacheStatus().get("BREAKOUT"))
            .containsEntry("exists", true)
            .containsEntry("valid", false)
            .containsEntry("updatedAt", response.updatedAt())
            .containsEntry("elapsedMinutes", 50L)
            .containsEntry("ttlMinutes", 175L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-12T06:29:00Z", "2026-10-12T06:30:00Z"})
    void bothViewsKeepTheResultUntilTheExistingMarketCloseBoundary(String now) throws Exception {
        var service = serviceAt(now); // 15:29 and exactly 15:30 KST
        var response = saveResult("2026-10-12T05:50:00Z");

        assertThat(service.getPrecomputed("BREAKOUT")).isEqualTo(response);
        assertThat(service.getCacheStatus().get("BREAKOUT")).containsEntry("valid", true);
    }

    @Test
    void ttlExpiredResultIsInvalidInBothViewsBeforeMarketClose() throws Exception {
        var service = serviceAt("2026-10-12T02:46:00Z"); // 11:46 KST
        saveResult("2026-10-11T23:50:00Z"); // 08:50 KST, 176 minutes ago

        assertThat(service.getPrecomputed("BREAKOUT")).isNull();
        assertThat(service.getCacheStatus().get("BREAKOUT"))
            .containsEntry("valid", false).containsEntry("elapsedMinutes", 176L);
    }

    @Test
    void storedResultRemainsAvailableForHistoryAfterMarketClose() throws Exception {
        var service = serviceAt("2026-10-12T06:40:00Z");
        var response = saveResult("2026-10-12T05:50:00Z");

        assertThat(service.getStoredPrecomputed("BREAKOUT")).isEqualTo(response);
        assertThat(service.getPrecomputed("BREAKOUT")).isNull();
    }

    private AnalysisService serviceAt(String now) {
        // An isolated temp directory and fixed clock avoid production files,
        // external providers and waiting for a real market close.
        return new AnalysisService(null, new ConditionSearchFormulaCatalog(), null, mapper,
            dataDir.toString(), Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    private AnalysisResponse saveResult(String updatedAt) throws Exception {
        var response = new AnalysisResponse("BREAKOUT", "fixture query", List.of(), null,
            "fixture result", List.of(new StockPick("Samsung", "005930", "70,000", null, null, "watch", "fixture",
                80, "fixture", "kis-rank", "kis-quote", updatedAt)), null, updatedAt, true, new AnalysisResponse.Metadata(1, 2, 2));
        mapper.writeValue(dataDir.resolve("BREAKOUT.json").toFile(), response);
        return response;
    }
}
