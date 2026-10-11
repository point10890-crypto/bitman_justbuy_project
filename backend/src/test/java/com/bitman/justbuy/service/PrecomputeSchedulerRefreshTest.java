package com.bitman.justbuy.service;

import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.condition.run.ConditionRunStatus;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrecomputeSchedulerRefreshTest {
    @Mock AnalysisService analysisService;
    @Mock TelegramNotifier notifier;
    private ConditionRunService runs;
    private PrecomputeScheduler scheduler;

    @BeforeEach
    void setUp() {
        runs = new ConditionRunService();
        // Direct construction skips @PostConstruct and every external source is
        // mocked or absent, so these tests never start a pipeline or send a message.
        scheduler = new PrecomputeScheduler(analysisService, new ConditionSearchFormulaCatalog(), notifier, null, null, runs);
    }

    @Test
    void failedModeReturnsItsErrorAndOtherModesStillComplete() {
        when(analysisService.runLiveAnalysis(anyString(), anyString()))
            .thenAnswer(invocation -> response(invocation.getArgument(1)));
        when(analysisService.runLiveAnalysis(anyString(), eq("BREAKOUT")))
            .thenThrow(new IllegalStateException("provider timed out"));

        var result = scheduler.refreshAll();

        assertThat(result).containsEntry("BREAKOUT", "error: provider timed out")
            .containsEntry("REVERSAL_EDGE", "success")
            .containsEntry("FLOW_LEADER", "success")
            .containsEntry("CATALYST_BURST", "success");
        verify(analysisService).runLiveAnalysis(anyString(), eq("CATALYST_BURST"));
        var failedRun = runs.recent(10).stream().filter(run -> "BREAKOUT".equals(run.mode())).findFirst().orElseThrow();
        assertThat(failedRun.status()).isEqualTo(ConditionRunStatus.FAILED);
        assertThat(failedRun.errorMessage()).isEqualTo("provider timed out");
        assertThat(runs.recent(10)).filteredOn(run -> !"BREAKOUT".equals(run.mode()))
            .allSatisfy(run -> assertThat(run.status()).isEqualTo(ConditionRunStatus.COMPLETE));
    }

    @Test
    void allExecutionFailuresReturnErrorsInsteadOfSuccess() {
        when(analysisService.runLiveAnalysis(anyString(), anyString()))
            .thenThrow(new IllegalStateException("source unavailable"));

        var result = scheduler.refreshAll();

        assertThat(result).hasSize(4);
        assertThat(result.values()).allMatch(value -> value.equals("error: source unavailable"));
        assertThat(runs.recent(10)).hasSize(4)
            .allSatisfy(run -> assertThat(run.status()).isEqualTo(ConditionRunStatus.FAILED));
    }

    @Test
    void failedNotificationDoesNotReplaceTheOriginalExecutionFailure() {
        when(analysisService.runLiveAnalysis(anyString(), anyString()))
            .thenThrow(new IllegalStateException("provider timed out"));
        doThrow(new IllegalStateException("notification unavailable"))
            .when(notifier).sendAnalysisFailed(anyString(), anyString());

        var result = scheduler.refreshAll();

        assertThat(result.values()).allMatch(value -> value.equals("error: provider timed out"));
        assertThat(runs.recent(10)).allSatisfy(run -> assertThat(run.errorMessage()).isEqualTo("provider timed out"));
    }

    private static AnalysisResponse response(String mode) {
        return new AnalysisResponse(mode, "fixture query", List.of(), null, "fixture result",
            List.of(new StockPick("삼성전자", "005930", "100", "110", "90", "watch", "fixture")),
            null, Instant.now().toString(), true, new AnalysisResponse.Metadata(20, 2, 2));
    }
}
