package com.bitman.justbuy.condition.run;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionRunServiceTest {

    private final ConditionRunService service = new ConditionRunService(
        Clock.fixed(Instant.parse("2026-06-07T00:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void createStartsQueuedRunAndRecordsCreationEvent() {
        ConditionRunSnapshot run = service.create(
            ConditionRunTrigger.LIVE_ANALYSIS,
            "BREAKOUT",
            "query\nwith newline"
        );

        assertThat(run.status()).isEqualTo(ConditionRunStatus.QUEUED);
        assertThat(run.trigger()).isEqualTo(ConditionRunTrigger.LIVE_ANALYSIS);
        assertThat(run.mode()).isEqualTo("BREAKOUT");
        assertThat(run.query()).isEqualTo("query with newline");
        assertThat(run.traceId()).startsWith("cr-");

        assertThat(service.events(run.runId()))
            .extracting(ConditionRunEvent::type)
            .containsExactly(ConditionRunEventType.RUN_CREATED);
    }

    @Test
    void completeStoresSafeSummaryMetrics() {
        ConditionRunSnapshot run = service.create(
            ConditionRunTrigger.SCHEDULER_CRON,
            "FLOW_LEADER",
            "leaders query"
        );

        service.markStarted(run.runId(), "started");
        service.recordEvent(run.runId(), ConditionRunEventType.AI_STARTED, "provider started",
            Map.of("provider", "deepseek", "raw", "line1\nline2"));
        service.markComplete(run.runId(), response("FLOW_LEADER"));

        ConditionRunSnapshot completed = service.find(run.runId()).orElseThrow();

        assertThat(completed.status()).isEqualTo(ConditionRunStatus.COMPLETE);
        assertThat(completed.pickCount()).isEqualTo(1);
        assertThat(completed.agentsUsed()).isEqualTo(2);
        assertThat(completed.agentsSucceeded()).isEqualTo(2);

        assertThat(service.events(run.runId()))
            .extracting(ConditionRunEvent::type)
            .containsExactly(
                ConditionRunEventType.RUN_CREATED,
                ConditionRunEventType.RUN_STARTED,
                ConditionRunEventType.AI_STARTED,
                ConditionRunEventType.COMPLETED
            );
    }

    @Test
    void failedRunKeepsErrorWithoutThrowingForUnknownRun() {
        ConditionRunSnapshot run = service.create(
            ConditionRunTrigger.ADMIN_REFRESH,
            "REVERSAL_EDGE",
            "swing query"
        );

        service.markFailed(run.runId(), "provider timeout");
        service.markFailed(java.util.UUID.randomUUID(), "ignored");

        ConditionRunSnapshot failed = service.find(run.runId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(ConditionRunStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("EXECUTION_FAILED");
        assertThat(failed.errorMessage()).isEqualTo("provider timeout");
        assertThat(service.events(run.runId()))
            .extracting(ConditionRunEvent::type)
            .containsExactly(ConditionRunEventType.RUN_CREATED, ConditionRunEventType.FAILED);
    }

    private static AnalysisResponse response(String mode) {
        return new AnalysisResponse(
            mode,
            mode + " query",
            List.of(),
            null,
            mode + " content",
            List.of(new StockPick("Robotis", "108490", "31,200", "34,350", null, "watch", "setup")),
            null,
            "2026-06-07T09:00:00+09:00",
            true,
            new AnalysisResponse.Metadata(123, 2, 2)
        );
    }
}
