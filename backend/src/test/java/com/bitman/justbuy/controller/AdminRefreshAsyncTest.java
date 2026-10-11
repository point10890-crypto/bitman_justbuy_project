package com.bitman.justbuy.controller;

import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.service.PrecomputeScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminRefreshAsyncTest {
    @Test
    void runningRefreshStillPollsAfterThirtyMinutesInsteadOfLosingItsJob() throws Exception {
        var scheduler = mock(PrecomputeScheduler.class);
        when(scheduler.normalizeRefreshModes(any())).thenReturn(List.of("BREAKOUT"));
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(scheduler.refreshModes(any())).thenAnswer(invocation -> {
            try { release.await(); return Map.of("BREAKOUT", "success"); }
            finally { finished.countDown(); }
        });
        var controller = new AdminController(null, null, null, List.of(), null, null, null, new ConditionRunService());
        ReflectionTestUtils.setField(controller, "precomputeScheduler", scheduler);
        UUID owner = UUID.randomUUID();
        var created = Instant.parse("2026-10-12T01:00:00Z");
        var later = Instant.parse("2026-10-12T01:31:00Z");
        try (var time = Mockito.mockStatic(Instant.class, Mockito.CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(created);
            var started = controller.refreshAll(owner, new AdminController.RefreshAllRequest(List.of("BREAKOUT")));
            String jobId = (String) started.getBody().get("jobId");
            time.when(Instant::now).thenReturn(later);
            assertThat(controller.refreshStatus(owner, jobId).getBody()).containsEntry("status", "running");
        } finally { release.countDown(); }
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
    }
    @Test
    void refreshStartsAJobBeforeTheSlowFourModeRefreshFinishes() {
        var scheduler = mock(PrecomputeScheduler.class);
        when(scheduler.normalizeRefreshModes(any())).thenReturn(List.of("BREAKOUT"));
        when(scheduler.refreshModes(any())).thenAnswer(invocation -> {
            Thread.sleep(600);
            return Map.of("BREAKOUT", "success");
        });
        var controller = new AdminController(null, null, null, List.of(), null, null, null, new ConditionRunService());
        ReflectionTestUtils.setField(controller, "precomputeScheduler", scheduler);

        long start = System.nanoTime();
        var response = controller.refreshAll(UUID.randomUUID(), new AdminController.RefreshAllRequest(List.of("BREAKOUT")));
        long durationMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsKeys("jobId", "startedAt");
        assertThat(durationMillis).isLessThan(300);
    }
}
