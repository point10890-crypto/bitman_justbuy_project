package com.bitman.justbuy.condition.run;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionRunOutcomeTest {
    @Test
    void zeroSuccessfulAgentsIsFailedAndNeverCompleted() {
        var service = new ConditionRunService();
        var run = service.create(ConditionRunTrigger.ADMIN_REFRESH, "BREAKOUT", "query");
        service.markComplete(run.runId(), result(2, 0, List.of()));

        assertThat(service.find(run.runId()).orElseThrow().status()).isEqualTo(ConditionRunStatus.FAILED);
        assertThat(service.events(run.runId())).extracting(ConditionRunEvent::type)
            .contains(ConditionRunEventType.FAILED).doesNotContain(ConditionRunEventType.COMPLETED);
    }

    @Test
    void successfulAgentsWithNoPicksRemainDistinguishableFromProviderFailure() {
        var service = new ConditionRunService();
        var run = service.create(ConditionRunTrigger.ADMIN_REFRESH, "BREAKOUT", "query");
        service.markComplete(run.runId(), result(2, 2, List.of()));

        assertThat(service.find(run.runId()).orElseThrow().status()).isEqualTo(ConditionRunStatus.PARTIAL);
        assertThat(service.find(run.runId()).orElseThrow().errorCode()).isNull();
        assertThat(service.events(run.runId()).getLast().metrics()).containsEntry("outcome", "EMPTY");
    }

    @Test
    void publishedPicksWithAgentFailureArePartial() {
        var service = new ConditionRunService();
        var run = service.create(ConditionRunTrigger.ADMIN_REFRESH, "BREAKOUT", "query");
        service.markComplete(run.runId(), result(2, 1, List.of(
            new StockPick("Samsung", "005930", "70,000", null, null, "watch", "fixture"))));
        assertThat(service.find(run.runId()).orElseThrow().status()).isEqualTo(ConditionRunStatus.PARTIAL);
    }

    private static AnalysisResponse result(int used, int succeeded, List<StockPick> picks) {
        return new AnalysisResponse("BREAKOUT", "query", List.of(), null, "fixture", picks, null,
            "2026-10-11T00:00:00Z", true, new AnalysisResponse.Metadata(1, used, succeeded));
    }
}
