package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConditionCaptureStoreTest {
    @TempDir Path dir;
    final Instant now = Instant.parse("2026-10-14T03:00:00Z");
    @Test void persistsMarketObservationsOncePerMinuteAndRestoresWatchlist() throws Exception {
        var store = new ConditionCaptureStore(new ObjectMapper().findAndRegisterModules(), mock(KisApiService.class), dir, Clock.fixed(now, ZoneOffset.UTC));
        store.record("daily", "JONGGA_V2", "005930", now, Map.of("actualTurnover", 12000000000L));
        store.record("daily", "JONGGA_V2", "005930", now, Map.of("actualTurnover", 12000000000L));
        store.watch(List.of(ConditionLiquidityServiceTest.signal("005930", 80)));
        assertThat(Files.readAllLines(dir.resolve("2026-10-14.jsonl"))).hasSize(1);
        assertThat(Files.readString(dir.resolve("2026-10-14.jsonl"))).contains("actualTurnover", "12000000000");
        var restored = new ConditionCaptureStore(new ObjectMapper().findAndRegisterModules(), mock(KisApiService.class), dir, Clock.fixed(now, ZoneOffset.UTC));
        assertThat(restored.watchedCodes()).containsExactly("005930");
    }
    @Test void collectsProviderMinutesForRestoredCandidatesAndRecordsMissingCoverage() throws Exception {
        var kis = mock(KisApiService.class);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new ConditionCaptureStore(mapper,kis,dir,Clock.fixed(now,ZoneOffset.UTC));
        store.watch(List.of(ConditionLiquidityServiceTest.signal("005930",80)));
        when(kis.fetchMinuteEvidence(org.mockito.ArgumentMatchers.eq("005930"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(new KisApiService.ExtendedSnapshot("UNAVAILABLE","005930",now,mapper.createArrayNode()));
        store.collect();
        var text = Files.readString(dir.resolve("2026-10-14.jsonl"));
        assertThat(text).contains("UNAVAILABLE","PROVIDER_MINUTE_BARS_PARTIAL_COVERAGE","attempted");
        assertThat(Files.readAllLines(dir.resolve("2026-10-14.jsonl"))).hasSize(2);
        verify(kis).fetchMinuteEvidence(org.mockito.ArgumentMatchers.eq("005930"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong());
    }
    @Test void refusesUnsafeIdentityAndDoesNotCollectOutsideTradingSession() throws Exception {
        var kis = mock(KisApiService.class);
        var store = new ConditionCaptureStore(new ObjectMapper(), kis, dir, Clock.fixed(Instant.parse("2026-10-14T13:00:00Z"), ZoneOffset.UTC));
        store.record("daily", "BREAKOUT", "../invalid", now, Map.of());
        store.collect();
        verifyNoInteractions(kis);
        try (var files = Files.list(dir)) { assertThat(files.count()).isZero(); }
    }
}
