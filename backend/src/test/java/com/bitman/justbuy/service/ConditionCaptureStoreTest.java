package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
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

    @Test void keepsBreakoutPoolStagesAfterTheSharedDailyFileReachesItsCap() throws Exception {
        var mapper = new ObjectMapper();
        var store = new ConditionCaptureStore(mapper, mock(KisApiService.class), dir, Clock.fixed(now, ZoneOffset.UTC));
        Path shared = dir.resolve("2026-10-14.jsonl");
        long sharedSize = 128L * 1024 * 1024 + 1;
        fillThrough(shared, sharedSize);
        Path retained = dir.resolve("2026-08-15-breakout.jsonl");
        Path expired = dir.resolve("2026-08-14-breakout.jsonl");
        Files.createFile(retained);
        Files.createFile(expired);

        store.record("source", "FLOW_LEADER", "pool", now, Map.of());
        store.record("source", "BREAKOUT", "005930", now, Map.of());
        store.record("selection", "BREAKOUT", "pool", now, Map.of());
        store.record("publication", "BREAKOUT", "pool", now, Map.of("sourceStatus", "PRECOMPUTED"));
        for (String kind : List.of("source", "candidate_selection", "verified_publication", "realtime_snapshot")) {
            store.record(kind, "BREAKOUT", "pool", now, Map.of("scan", 42));
        }
        store.record("publication", "BREAKOUT", "pool", now, Map.of("sourceStatus", "REALTIME_EMPTY"));
        store.record("publication", "BREAKOUT", "pool", now, Map.of("sourceStatus", "REALTIME_SCAN"));

        assertThat(Files.size(shared)).isEqualTo(sharedSize);
        assertThat(Files.exists(retained)).isTrue();
        assertThat(Files.exists(expired)).isFalse();
        var rows = Files.readAllLines(dir.resolve("2026-10-14-breakout.jsonl"));
        var kinds = new ArrayList<String>();
        for (String line : rows) kinds.add(mapper.readTree(line).path("kind").asText());
        assertThat(kinds).containsExactly("source", "candidate_selection", "verified_publication", "realtime_snapshot", "publication");
        assertThat(rows.getLast()).contains("REALTIME_EMPTY");
    }

    @Test void refusesAWholeBreakoutEntryThatWouldCrossTheStrictByteCap() throws Exception {
        var store = new ConditionCaptureStore(new ObjectMapper(), mock(KisApiService.class), dir, Clock.fixed(now, ZoneOffset.UTC));
        Path dedicated = dir.resolve("2026-10-14-breakout.jsonl");
        long size = 16L * 1024 * 1024 - 200;
        fillThrough(dedicated, size);

        store.record("realtime_snapshot", "BREAKOUT", "pool", now, Map.of("status", "가".repeat(60)));

        assertThat(Files.size(dedicated)).isEqualTo(size);
        assertThat(Files.readAllLines(dir.resolve("2026-10-14.jsonl"))).hasSize(1);
    }

    private static void fillThrough(Path file, long size) throws Exception {
        try (var channel = Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[]{'\n'}));
        }
    }
}
