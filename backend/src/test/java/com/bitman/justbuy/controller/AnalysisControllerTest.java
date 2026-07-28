package com.bitman.justbuy.controller;

import com.bitman.justbuy.condition.run.ConditionRunEvent;
import com.bitman.justbuy.condition.run.ConditionRunEventType;
import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.condition.run.ConditionRunStatus;
import com.bitman.justbuy.dto.AnalysisRequest;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.AsyncJobManager;
import com.bitman.justbuy.service.ConditionSearchPipeline;
import com.bitman.justbuy.service.SubscriptionService;
import com.bitman.justbuy.service.TradingResearchViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisControllerTest {

    @Mock ConditionSearchPipeline conditionSearchPipeline;
    @Mock UserRepository userRepository;
    @Mock AsyncJobManager jobManager;
    @Mock SubscriptionService subscriptionService;

    private ConditionRunService conditionRunService;
    private AnalysisController controller;

    @BeforeEach
    void setUp() {
        conditionRunService = new ConditionRunService();
        controller = new AnalysisController(
            conditionSearchPipeline,
            jobManager,
            new SubscriptionAccessGuard(userRepository, subscriptionService),
            conditionRunService,
            new TradingResearchViewService()
        );
    }

    @Test
    void liveAnalysisCacheHitKeepsResponseContractAndRecordsRun() {
        UUID userId = allowProUser();
        AnalysisResponse cached = response("BREAKOUT");
        when(conditionSearchPipeline.isValidMode("BREAKOUT")).thenReturn(true);
        when(conditionSearchPipeline.getCachedLive("breakout query", "BREAKOUT")).thenReturn(cached);

        var response = controller.liveAnalysis(userId, new AnalysisRequest("breakout query", "BREAKOUT"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(cached);
        verify(jobManager, never()).createJob();

        var run = conditionRunService.recent(1).get(0);
        assertThat(run.status()).isEqualTo(ConditionRunStatus.COMPLETE);
        assertThat(run.pickCount()).isEqualTo(1);
        assertThat(conditionRunService.events(run.runId()))
            .extracting(ConditionRunEvent::type)
            .contains(ConditionRunEventType.CACHE_HIT, ConditionRunEventType.COMPLETED);
    }

    @Test
    void liveAnalysisAcceptedResponseIncludesRunIdAndCompletesRun() {
        UUID userId = allowProUser();
        AnalysisResponse analysis = response("BREAKOUT");
        when(conditionSearchPipeline.isValidMode("BREAKOUT")).thenReturn(true);
        when(conditionSearchPipeline.getCachedLive("breakout query", "BREAKOUT")).thenReturn(null);
        when(jobManager.createJob()).thenReturn("job-123");
        when(conditionSearchPipeline.runLiveAnalysis("breakout query", "BREAKOUT")).thenReturn(analysis);

        var response = controller.liveAnalysis(userId, new AnalysisRequest("breakout query", "BREAKOUT"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("jobId", "job-123");
        assertThat(body).containsEntry("status", "pending");
        assertThat(body.get("runId")).isInstanceOf(String.class);

        UUID runId = UUID.fromString((String) body.get("runId"));
        verify(jobManager, timeout(1000)).markComplete(eq("job-123"), eq(analysis));

        var run = conditionRunService.find(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(ConditionRunStatus.COMPLETE);
        assertThat(run.pickCount()).isEqualTo(1);
        assertThat(conditionRunService.events(runId))
            .extracting(ConditionRunEvent::type)
            .contains(
                ConditionRunEventType.JOB_CREATED,
                ConditionRunEventType.AI_STARTED,
                ConditionRunEventType.PICKS_PARSED,
                ConditionRunEventType.COMPLETED
            );
    }

    @Test
    void completedJobExposesTradingAgentsResearchViewWithoutExecution() {
        UUID userId = allowProUser();
        AnalysisResponse analysis = response("BREAKOUT");
        when(jobManager.getJob("job-123")).thenReturn(new AsyncJobManager.JobEntry(
            AsyncJobManager.JobStatus.COMPLETE, analysis, null, Instant.now()
        ));

        var response = controller.getTradingResearch(userId, "job-123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
            .isInstanceOf(com.bitman.justbuy.dto.TradingResearchResponse.class);
        var body = (com.bitman.justbuy.dto.TradingResearchResponse) response.getBody();
        assertThat(body.executionAllowed()).isFalse();
        assertThat(body.informationalRating()).isEqualTo("WATCH");
    }

    private UUID allowProUser() {
        UUID userId = UUID.randomUUID();
        User user = new User("pro@example.com", "pro", "hash");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(subscriptionService.isActivePro(user)).thenReturn(true);
        return userId;
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
