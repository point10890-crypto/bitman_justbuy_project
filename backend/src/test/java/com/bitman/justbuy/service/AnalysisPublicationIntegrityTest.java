package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.MultiAgentOrchestrator;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.JsonMappingException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisPublicationIntegrityTest {
    @TempDir Path dataDir;

    @Test
    void serializationFailureLeavesTheOriginalBytesAndNoTemporaryFile() throws Exception {
        String original = "last known good fixture bytes";
        Files.writeString(dataDir.resolve("BREAKOUT.json"), original);
        var mapper = mock(ObjectMapper.class);
        var writer = mock(ObjectWriter.class);
        when(mapper.writerWithDefaultPrettyPrinter()).thenReturn(writer);
        when(writer.writeValueAsString(any())).thenThrow(new JsonMappingException(null, "fixture serialization failed"));
        var service = new AnalysisService(null, new ConditionSearchFormulaCatalog(), null, mapper, dataDir.toString());

        assertThatThrownBy(() -> service.saveAnalysis("BREAKOUT", response(2, 2, List.of(pick()), "2026-10-11T00:00:00Z")))
            .hasCauseInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(dataDir.resolve("BREAKOUT.json"))).isEqualTo(original);
        try (var files = Files.list(dataDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly("BREAKOUT.json");
        }
    }

    @Test
    void timedOutWorkerCannotPublishLaterOrAdmitADuplicateWhileStillRunning() throws Exception {
        var mapper = new ObjectMapper();
        var old = response(2, 2, List.of(pick()), "2026-10-09T00:00:00Z");
        mapper.writeValue(dataDir.resolve("BREAKOUT.json").toFile(), old);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var orchestrator = mock(MultiAgentOrchestrator.class);
        when(orchestrator.runAnalysis("query", "BREAKOUT")).thenAnswer(invocation -> {
            try {
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { /* Deliberately uncooperative provider fixture. */ }
                }
                return response(2, 2, List.of(pick()), "2026-10-11T00:00:00Z");
            } finally { finished.countDown(); }
        });
        var tracking = mock(TrackRecordService.class);
        var service = new AnalysisService(orchestrator, new ConditionSearchFormulaCatalog(),
            mock(KeywordConditionAnalysisService.class), mapper, dataDir.toString());
        service.configureTracking(tracking);
        ReflectionTestUtils.setField(service, "runTimeoutMs", 50L);
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> service.runLiveAnalysis("query", "BREAKOUT")).hasMessageContaining("ANALYSIS_TIMEOUT");
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1000);
            assertThatThrownBy(() -> service.runLiveAnalysis("query", "BREAKOUT"))
                .isInstanceOf(AnalysisService.DuplicateAnalysisException.class);
        } finally { release.countDown(); }
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(service.getStoredPrecomputed("BREAKOUT")).isEqualTo(old);
        assertThat(service.getCachedLive("query", "BREAKOUT")).isNull();
        verify(tracking, never()).recordAnalysis(any());
    }

    @Test
    void failedAgentsDoNotReturnSuccessfulLiveResultsOrReplaceTheLastGoodFile() throws Exception {
        var mapper = new ObjectMapper();
        var old = response(2, 2, List.of(pick()), "2026-10-09T00:00:00Z");
        mapper.writeValue(dataDir.resolve("BREAKOUT.json").toFile(), old);
        var orchestrator = mock(MultiAgentOrchestrator.class);
        var keyword = mock(KeywordConditionAnalysisService.class);
        when(orchestrator.runAnalysis("query", "BREAKOUT"))
            .thenReturn(response(2, 0, List.of(), "2026-10-11T00:00:00Z"));
        var service = new AnalysisService(orchestrator, new ConditionSearchFormulaCatalog(), keyword, mapper, dataDir.toString());

        assertThatThrownBy(() -> service.runLiveAnalysis("query", "BREAKOUT"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(service.getStoredPrecomputed("BREAKOUT")).isEqualTo(old);
        assertThat(service.getCachedLive("query", "BREAKOUT")).isNull();
    }

    @Test
    void failedPersistencePropagatesAndDoesNotCreateALiveCache() throws Exception {
        Path unavailableDirectory = dataDir.resolve("not-a-directory");
        Files.writeString(unavailableDirectory, "fixture obstruction");
        var orchestrator = mock(MultiAgentOrchestrator.class);
        var keyword = mock(KeywordConditionAnalysisService.class);
        when(orchestrator.runAnalysis("query", "BREAKOUT"))
            .thenReturn(response(2, 2, List.of(pick()), "2026-10-11T00:00:00Z"));
        var service = new AnalysisService(orchestrator, new ConditionSearchFormulaCatalog(), keyword,
            new ObjectMapper(), unavailableDirectory.toString());

        assertThatThrownBy(() -> service.runLiveAnalysis("query", "BREAKOUT"))
            .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(java.io.IOException.class);
        assertThat(service.getCachedLive("query", "BREAKOUT")).isNull();
        assertThat(Files.readString(unavailableDirectory)).isEqualTo("fixture obstruction");
    }

    static StockPick pick() { return new StockPick("Samsung", "005930", "70,000", "77,000", "66,500", "watch", "fixture"); }
    static AnalysisResponse response(int used, int succeeded, List<StockPick> picks, String updatedAt) {
        return new AnalysisResponse("BREAKOUT", "query", List.of(), null, "fixture", picks, null,
            updatedAt, true, new AnalysisResponse.Metadata(1, used, succeeded));
    }
}
