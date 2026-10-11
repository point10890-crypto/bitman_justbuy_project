package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncJobRetentionTest {
    @Test
    void runningJobsRemainPollableAndTerminalRetentionStartsWhenTheyFinish() {
        var owner = UUID.randomUUID();
        var jobs = new AsyncJobManager();
        var created = Instant.parse("2026-10-12T01:00:00Z");
        var later = Instant.parse("2026-10-12T01:31:00Z");
        var afterRetention = Instant.parse("2026-10-12T02:02:00Z");
        try (var time = Mockito.mockStatic(Instant.class, Mockito.CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(created);
            String jobId = jobs.createJob(owner);
            jobs.markRunning(jobId);
            time.when(Instant::now).thenReturn(later);
            assertThat(jobs.getJob(jobId, owner)).isNotNull();
            jobs.markComplete(jobId, new AnalysisResponse("BREAKOUT", "query", List.of(), null, "fixture", List.of(
                new StockPick("Samsung", "005930", "70,000", null, null, "watch", "fixture")), null,
                created.toString(), true, new AnalysisResponse.Metadata(1, 2, 2)));
            assertThat(jobs.getJob(jobId, owner).status()).isEqualTo(AsyncJobManager.JobStatus.COMPLETE);
            time.when(Instant::now).thenReturn(afterRetention);
            assertThat(jobs.getJob(jobId, owner)).isNull();
            jobs.markComplete(jobId, null);
            assertThat(jobs.getJob(jobId, owner)).isNull();
        }
    }
}
