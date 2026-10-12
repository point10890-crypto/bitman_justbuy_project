package com.bitman.justbuy.service;

import com.bitman.justbuy.condition.run.ConditionRunEventType;
import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.condition.run.ConditionRunTrigger;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionSectionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MainConditionAnalysisStatusTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void completedEmptyAnalysisHasItsActualFinishedTimeAlongsidePreservedLegacyCache() {
        var runs = new ConditionRunService();
        var run = runs.create(ConditionRunTrigger.ADMIN_REFRESH, "REVERSAL_EDGE", "PRIVATE_MEMBER_QUERY");
        runs.recordEvent(run.runId(), ConditionRunEventType.AI_STARTED, "started");
        runs.markComplete(run.runId(), response(List.of(), "2026-10-12T01:32:07Z"));
        var pipeline = mock(ConditionSearchPipeline.class);
        when(pipeline.getStoredPrecomputed("REVERSAL_EDGE")).thenReturn(legacy());

        try (var context = context(pipeline, runs)) {
            var section = context.getBean(MainConditionService.class).getSection("swing");
            var latest = mapper.valueToTree(section).get("latestAnalysis");

            assertThat(latest).isNotNull();
            assertThat(latest.path("outcome").asText()).isEqualTo("EMPTY");
            assertThat(latest.path("checkedAt").asText()).isEqualTo(runs.find(run.runId()).orElseThrow().finishedAt().toString());
            assertThat(latest.path("agentsUsed").asInt()).isEqualTo(2);
            assertThat(latest.path("agentsSucceeded").asInt()).isEqualTo(2);
            assertThat(latest.path("publishedCount").asInt()).isZero();
            assertThat(section.asOf()).isEqualTo("2026-10-11T02:47:26Z");
            assertThat(section.sourceStatus()).isEqualTo("LEGACY_UNVERIFIED");
            assertThat(section.signals()).isEmpty();
        }
    }

    @Test
    void legacyCacheWithoutACompletedAttemptDoesNotInventARecentAnalysis() {
        var pipeline = mock(ConditionSearchPipeline.class);
        when(pipeline.getStoredPrecomputed("REVERSAL_EDGE")).thenReturn(legacy());
        try (var context = context(pipeline, new ConditionRunService())) {
            var section = context.getBean(MainConditionService.class).getSection("swing");
            var json = mapper.valueToTree(section);
            assertThat(json.has("latestAnalysis")).isTrue();
            assertThat(json.get("latestAnalysis").isNull()).isTrue();
            assertThat(section.sourceStatus()).isEqualTo("LEGACY_UNVERIFIED");
        }
    }

    @Test
    void aNewerCacheHitCannotReplaceTheLastActualAnalysisTimeAndOutcome() {
        var runs = new ConditionRunService();
        var actual = runs.create(ConditionRunTrigger.ADMIN_REFRESH, "REVERSAL_EDGE", "private");
        runs.recordEvent(actual.runId(), ConditionRunEventType.AI_STARTED, "started");
        runs.markComplete(actual.runId(), response(List.of(), "2026-10-12T01:32:07Z"));
        var cache = runs.create(ConditionRunTrigger.SCHEDULER_STARTUP, "REVERSAL_EDGE", "private");
        runs.recordEvent(cache.runId(), ConditionRunEventType.CACHE_HIT, "startup cache hit");
        runs.markComplete(cache.runId(), response(List.of(verified()), "2026-10-12T01:31:35Z"));

        try (var context = context(mock(ConditionSearchPipeline.class), runs)) {
            var latest = mapper.valueToTree(context.getBean(MainConditionService.class).getSection("swing")).get("latestAnalysis");
            assertThat(latest).isNotNull();
            assertThat(latest.path("outcome").asText()).isEqualTo("EMPTY");
            assertThat(latest.path("checkedAt").asText()).isEqualTo(runs.find(actual.runId()).orElseThrow().finishedAt().toString());
        }
    }

    @Test
    void analysisStatusContainsOnlyWhitelistedTimeOutcomeAndCounters() {
        var runs = new ConditionRunService();
        var run = runs.create(ConditionRunTrigger.ADMIN_REFRESH, "REVERSAL_EDGE", "PRIVATE_MEMBER_QUERY");
        runs.recordEvent(run.runId(), ConditionRunEventType.AI_STARTED, "PRIVATE_EVENT_PAYLOAD", Map.of("private", "PRIVATE_METRIC"));
        runs.markFailed(run.runId(), "PRIVATE_ERROR_PAYLOAD");

        try (var context = context(mock(ConditionSearchPipeline.class), runs)) {
            JsonNode latest = mapper.valueToTree(context.getBean(MainConditionService.class).getSection("swing")).get("latestAnalysis");
            assertThat(latest).isNotNull();
            assertThat(latest.size()).isEqualTo(5);
            assertThat(latest.path("outcome").asText()).isEqualTo("FAILED");
            assertThat(latest.path("agentsUsed").isInt()).isTrue();
            assertThat(latest.path("agentsSucceeded").isInt()).isTrue();
            assertThat(latest.path("publishedCount").isInt()).isTrue();
            assertThat(latest.toString()).doesNotContain("PRIVATE_", "query", "error", "events", "runId", "traceId");
        }
    }

    @Test
    void rowLevelLiveAnalysisDoesNotOverrideTheScheduledSectionStatus() {
        var runs = new ConditionRunService();
        var run = runs.create(ConditionRunTrigger.LIVE_ANALYSIS, "REVERSAL_EDGE", "a private stock query");
        runs.recordEvent(run.runId(), ConditionRunEventType.AI_STARTED, "started");
        runs.markComplete(run.runId(), response(List.of(), "2026-10-12T01:32:07Z"));
        try (var context = context(mock(ConditionSearchPipeline.class), runs)) {
            var section = mapper.valueToTree(context.getBean(MainConditionService.class).getSection("swing"));
            assertThat(section.has("latestAnalysis")).isTrue();
            assertThat(section.get("latestAnalysis").isNull()).isTrue();
        }
    }

    @Test
    void oldSectionConstructorRemainsCompatibleAndStatusIsNullable() {
        var section = new ConditionSectionResponse("swing", "swing", "Swing", "REVERSAL_EDGE", "/api/conditions/swing", "2026-10-12T01:32:07Z", "DATA_UNAVAILABLE", List.of());
        var json = mapper.valueToTree(section);
        assertThat(json.has("latestAnalysis")).isTrue();
        assertThat(json.get("latestAnalysis").isNull()).isTrue();
    }

    private static AnnotationConfigApplicationContext context(ConditionSearchPipeline pipeline, ConditionRunService runs) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(ConditionRunService.class, () -> runs);
        context.registerBean(MainConditionService.class, () -> new MainConditionService(pipeline));
        context.refresh();
        return context;
    }

    private static AnalysisResponse response(List<StockPick> picks, String updatedAt) {
        return new AnalysisResponse("REVERSAL_EDGE", "private query", List.of(), null, "private content", picks, null, updatedAt, true,
            new AnalysisResponse.Metadata(100, 2, 2));
    }

    private static AnalysisResponse legacy() {
        return response(List.of(new StockPick("Legacy", "005930", "78,100", "82,000", "75,000", "watch", "legacy")), "2026-10-11T02:47:26Z");
    }

    private static StockPick verified() {
        return new StockPick("Verified", "005930", "78,100", "82,000", "75,000", "watch", "verified", 0, "", "KIS", "NAVER", "2026-10-12T01:31:35Z");
    }
}
