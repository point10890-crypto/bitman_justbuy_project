package com.bitman.justbuy.controller;

import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.dto.AnalysisRequest;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.service.AsyncJobManager;
import com.bitman.justbuy.service.ConditionSearchPipeline;
import com.bitman.justbuy.service.LiveAnalysisQuotaService;
import com.bitman.justbuy.service.TradingResearchViewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisJobOwnershipTest {
    @Test
    void anotherPrincipalCannotPollOrReadResearchEvenWithTheJobId() throws Exception {
        var owner = UUID.randomUUID();
        var other = UUID.randomUUID();
        var pipeline = mock(ConditionSearchPipeline.class);
        var jobs = new AsyncJobManager();
        when(pipeline.isValidMode("BREAKOUT")).thenReturn(true);
        when(pipeline.runLiveAnalysis("query", "BREAKOUT")).thenReturn(new AnalysisResponse(
            "BREAKOUT", "query", List.of(), null, "private owner result", List.of(
                new StockPick("Samsung", "005930", "70,000", null, null, "watch", "fixture")),
            null, "2026-10-11T00:00:00Z", true, new AnalysisResponse.Metadata(1, 2, 2)));
        var controller = new AnalysisController(pipeline, jobs, mock(SubscriptionAccessGuard.class),
            new ConditionRunService(), new TradingResearchViewService(), new LiveAnalysisQuotaService());
        var started = controller.liveAnalysis(owner, new AnalysisRequest("query", "BREAKOUT"));
        String jobId = (String) ((Map<?, ?>) started.getBody()).get("jobId");
        for (int attempt = 0; attempt < 100 && jobs.getJob(jobId).status() != AsyncJobManager.JobStatus.COMPLETE; attempt++) Thread.sleep(10);

        assertThat(controller.getJobStatus(owner, jobId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThatThrownBy(() -> controller.getJobStatus(other, jobId)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.getTradingResearch(other, jobId)).isInstanceOf(ApiException.class);
    }
}
