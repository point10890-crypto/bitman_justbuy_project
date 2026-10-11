package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisFreshCacheIntegrityTest {
    @TempDir Path dataDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void legacyStoredRowsRemainReadableForHistoryButNeverAppearAsFreshVerifiedCache() throws Exception {
        var legacy = response("2026-10-12T00:30:00Z", new StockPick("Samsung", "005930", "70,000", null, null, "watch", "fixture"));
        write(legacy);
        var service = service();
        assertThat(service.getPrecomputed("BREAKOUT")).isNull();
        assertThat(service.getCacheStatus().get("BREAKOUT")).containsEntry("valid", false);
        assertThat(service.getStoredPrecomputed("BREAKOUT")).isEqualTo(legacy);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "2026-10-12T01:01:00Z"})
    void malformedMissingAndFutureAnalysisTimestampCannotBypassFreshness(String updatedAt) throws Exception {
        write(response(updatedAt, verifiedPick("70,000", "2026-10-12T00:30:00Z")));
        assertThat(service().getPrecomputed("BREAKOUT")).isNull();
        assertThat(service().getCacheStatus().get("BREAKOUT")).containsEntry("valid", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10%", "-70000", "NaN", "0", "7,00"})
    void provenanceDoesNotMakeMalformedCurrentPricesVerified(String currentPrice) throws Exception {
        write(response("2026-10-12T00:30:00Z", verifiedPick(currentPrice, "2026-10-12T00:30:00Z")));
        assertThat(service().getPrecomputed("BREAKOUT")).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "2026-10-12T01:01:00Z", "2026-10-09T00:30:00Z"})
    void missingFutureAndOldQuoteTimestampsDoNotBecomeCurrentCache(String quoteCheckedAt) throws Exception {
        write(response("2026-10-12T00:30:00Z", verifiedPick("70,000", quoteCheckedAt)));
        assertThat(service().getPrecomputed("BREAKOUT")).isNull();
    }

    @Test
    void currentVerifiedCacheKeepsTheExistingResponseContract() throws Exception {
        var response = response("2026-10-12T00:30:00Z", verifiedPick("70,000", "2026-10-12T00:30:00Z"));
        write(response);
        assertThat(service().getPrecomputed("BREAKOUT")).isEqualTo(response);
        assertThat(service().getCacheStatus().get("BREAKOUT")).containsEntry("valid", true);
    }

    private AnalysisService service() {
        return new AnalysisService(null, new ConditionSearchFormulaCatalog(), null, mapper, dataDir.toString(), clock);
    }
    private void write(AnalysisResponse response) throws Exception { mapper.writeValue(dataDir.resolve("BREAKOUT.json").toFile(), response); }
    private static StockPick verifiedPick(String price, String checkedAt) {
        return new StockPick("Samsung", "005930", price, null, null, "watch", "fixture", 80, "fixture",
            "kis-rank", "kis-quote", checkedAt);
    }
    private static AnalysisResponse response(String updatedAt, StockPick pick) {
        return new AnalysisResponse("BREAKOUT", "query", List.of(), null, "fixture", List.of(pick), null, updatedAt,
            true, new AnalysisResponse.Metadata(1, 2, 2, "COMPLETE"));
    }
}
