package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
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

    @Test void recordsChangedDailyInputsAndClosingSourceOncePerUnchangedPayloadPerKstDay() throws Exception {
        var mapper = new ObjectMapper();
        var clock = new MutableClock(Instant.parse("2026-10-14T14:57:00Z"));
        var store = new ConditionCaptureStore(mapper, mock(KisApiService.class), dir, clock);
        for (int minute = 0; minute < 3; minute++) {
            clock.at = Instant.parse("2026-10-14T14:57:00Z").plusSeconds(60L * minute);
            store.record("daily", "ALL_SECTIONS", "005930", clock.instant(),
                mapper.readTree(minute == 2 ? "{\"output2\":[101]}" : "{\"output2\":[100]}"));
            store.record("source", "JONGGA_V2", "pool", clock.instant(), mapper.readTree("{\"date\":\"2026-10-14\"}"));
            store.record("source", "BREAKOUT", "pool", clock.instant(), Map.of("scan", "unchanged-payload"));
        }
        var rows = Files.readAllLines(dir.resolve("2026-10-14.jsonl"));
        var eventKinds = new ArrayList<String>();
        for (String row : rows) {
            var event = mapper.readTree(row);
            eventKinds.add(event.path("kind").asText() + "/" + event.path("mode").asText());
        }
        assertThat(eventKinds).containsExactly("daily/ALL_SECTIONS", "source/JONGGA_V2",
            "source/BREAKOUT", "source/BREAKOUT", "daily/ALL_SECTIONS", "source/BREAKOUT");
        assertThat(rows.get(4)).contains("101");
        assertThat(Files.readAllLines(dir.resolve("2026-10-14-breakout.jsonl"))).hasSize(3);

        clock.at = Instant.parse("2026-10-14T15:00:00Z");
        store.record("daily", "ALL_SECTIONS", "005930", clock.instant(), mapper.readTree("{\"output2\":[101]}"));
        store.record("source", "JONGGA_V2", "pool", clock.instant(), mapper.readTree("{\"date\":\"2026-10-14\"}"));
        assertThat(Files.readAllLines(dir.resolve("2026-10-15.jsonl"))).hasSize(2);
    }

    @Test void continuesSharedEventsAfterTheLegacyFileReachesCapacityAndAfterRestart() throws Exception {
        var mapper = new ObjectMapper();
        var kis = mock(KisApiService.class);
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        long legacySize = 128L * 1024 * 1024 - 32;
        Path shared = dir.resolve("2026-10-14.jsonl");
        fillThrough(shared, legacySize);
        Path continuation = dir.resolve("2026-10-14-part-000002.jsonl");
        Path expiredPart = Files.createFile(dir.resolve("2026-08-14-part-000002.jsonl"));
        Path retainedPart = Files.createFile(dir.resolve("2026-08-15-breakout-part-000002.jsonl"));
        Path unrelated = Files.createFile(dir.resolve("2026-08-14-custom-part-000002.jsonl"));

        var store = new ConditionCaptureStore(mapper, kis, dir, clock);
        store.record("coverage", "FOLLOWUP", "pool", now, Map.of("first", 1));
        var restarted = new ConditionCaptureStore(mapper, kis, dir, clock);
        restarted.record("publication", "FLOW_LEADER", "pool", now, Map.of("second", 2));

        assertThat(Files.size(shared)).isEqualTo(legacySize);
        assertThat(Files.readAllLines(continuation)).hasSize(2)
            .allSatisfy(row -> assertThat(mapper.readTree(row).path("schemaVersion").asInt()).isEqualTo(1));
        assertThat(Files.readString(continuation)).contains("\"first\":1", "\"second\":2");
        assertThat(Files.exists(expiredPart)).isFalse();
        assertThat(Files.exists(retainedPart)).isTrue();
        assertThat(Files.exists(unrelated)).isTrue();
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

    @Test void continuesWholeBreakoutEntriesInANewFileAtTheStrictByteCap() throws Exception {
        var store = new ConditionCaptureStore(new ObjectMapper(), mock(KisApiService.class), dir, Clock.fixed(now, ZoneOffset.UTC));
        Path dedicated = dir.resolve("2026-10-14-breakout.jsonl");
        long size = 16L * 1024 * 1024 - 200;
        fillThrough(dedicated, size);

        store.record("realtime_snapshot", "BREAKOUT", "pool", now, Map.of("status", "가".repeat(60)));

        assertThat(Files.size(dedicated)).isEqualTo(size);
        assertThat(Files.readAllLines(dir.resolve("2026-10-14-breakout-part-000002.jsonl"))).hasSize(1)
            .allSatisfy(row -> assertThat(row).contains("가".repeat(60)));
        assertThat(Files.readAllLines(dir.resolve("2026-10-14.jsonl"))).hasSize(1);
    }

    @Test void boundsDailyDiskUseAndWarnsOnceWithoutBlockingTheIndependentBreakoutStream() throws Exception {
        long sharedBytes = 128L * 1024 * 1024;
        for (String name : List.of("2026-10-14.jsonl", "2026-10-14-part-000002.jsonl", "2026-10-14-part-000003.jsonl"))
            fillThrough(dir.resolve(name), sharedBytes);
        var store = new ConditionCaptureStore(new ObjectMapper(), mock(KisApiService.class), dir, Clock.fixed(now, ZoneOffset.UTC));
        var logger = (Logger) LoggerFactory.getLogger(ConditionCaptureStore.class);
        var logEvents = new ListAppender<ILoggingEvent>();
        logEvents.start();
        logger.addAppender(logEvents);
        try {
            for (int attempt = 0; attempt < 15; attempt++) {
                store.record("coverage", "FOLLOWUP", "pool", now, Map.of("attempt", attempt));
            }
            store.record("source", "BREAKOUT", "pool", now, Map.of("scan", 42));
        } finally {
            logger.detachAppender(logEvents);
            logEvents.stop();
        }

        assertThat(Files.exists(dir.resolve("2026-10-14-part-000004.jsonl"))).isFalse();
        assertThat(Files.size(dir.resolve("2026-10-14-part-000003.jsonl"))).isEqualTo(sharedBytes);
        assertThat(Files.readAllLines(dir.resolve("2026-10-14-breakout.jsonl"))).hasSize(1);
        assertThat(logEvents.list).hasSize(1)
            .allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("daily segment limit reached"));
    }

    private static final class MutableClock extends Clock {
        private Instant at;
        MutableClock(Instant at) { this.at = at; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(at, zone); }
        @Override public Instant instant() { return at; }
    }

    private static void fillThrough(Path file, long size) throws Exception {
        try (var channel = Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[]{'\n'}));
        }
    }
}
