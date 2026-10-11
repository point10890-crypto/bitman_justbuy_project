package com.bitman.justbuy.service;

import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrecomputeSchedulerDeliveryConcurrencyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T02:50:00Z"), ZoneOffset.UTC);

    @Test void startupCacheRecoveryDoesNotRebroadcastAlreadyPublishedPicks() {
        var service = mock(AnalysisService.class);
        var notifier = mock(TelegramNotifier.class);
        when(service.getPrecomputed(anyString())).thenAnswer(call -> response(call.getArgument(0)));
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), notifier, null, null,
            new ConditionRunService(), CLOCK);
        ReflectionTestUtils.invokeMethod(scheduler, "runStartupPrecompute");
        assertThat(scheduler.getLastRunTimes()).hasSize(4);
        verify(service, never()).runAnalysisForPublication(anyString(), anyString());
        verify(notifier, never()).sendAnalysisResult(anyString(), any());
        verify(notifier, never()).sendAnalysisFailed(anyString(), anyString());
    }

    @Test void overlappingCronInvocationsProduceOnlyOneBatchAndOneDeliveryPerMode() throws Exception {
        var service = mock(AnalysisService.class);
        var notifier = mock(TelegramNotifier.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(service.runAnalysisForPublication(anyString(), anyString())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("fixture latch not released");
            }
            return response(call.getArgument(1));
        });
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), notifier, null, null,
            new ConditionRunService(), CLOCK);
        Thread first = Thread.ofVirtual().start(scheduler::scheduledPrecompute);
        Thread second = null;
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            second = Thread.ofVirtual().start(() -> { try { scheduler.scheduledPrecompute(); } finally { secondDone.countDown(); } });
            assertThat(secondDone.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown(); first.join(3000); if (second != null) second.join(3000);
        }
        assertThat(calls).hasValue(4);
        assertThat(scheduler.getLastRunTimes()).hasSize(4);
        verify(notifier, times(4)).sendAnalysisResult(anyString(), any());
    }

    @Test void startupRefreshAndCronShareTheSameBatchGuard() throws Exception {
        var service = mock(AnalysisService.class);
        var notifier = mock(TelegramNotifier.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cronDone = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(service.runAnalysisForPublication(anyString(), anyString())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("fixture latch not released");
            }
            return response(call.getArgument(1));
        });
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), notifier, null, null,
            new ConditionRunService(), CLOCK);
        Thread startup = Thread.ofVirtual().start(() -> ReflectionTestUtils.invokeMethod(scheduler, "runStartupPrecompute"));
        Thread cron = null;
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            cron = Thread.ofVirtual().start(() -> { try { scheduler.scheduledPrecompute(); } finally { cronDone.countDown(); } });
            assertThat(cronDone.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown(); startup.join(3000); if (cron != null) cron.join(3000);
        }
        assertThat(calls).hasValue(4);
        assertThat(scheduler.getLastRunTimes()).hasSize(4);
        verify(notifier, times(4)).sendAnalysisResult(anyString(), any());
    }

    private static AnalysisResponse response(String mode) {
        String code = switch (mode) { case "BREAKOUT" -> "005930"; case "REVERSAL_EDGE" -> "000660";
            case "FLOW_LEADER" -> "035420"; default -> "068270"; };
        return new AnalysisResponse(mode, "fixture", List.of(), null, "fixture",
            List.of(new StockPick("fixture " + code, code, "70000", null, null, "관찰", "fixture", null, null,
                "KIS fixture rank", "KIS fixture price", "2026-10-12T02:50:00Z")), null,
            "2026-10-12T02:50:00Z", true, new AnalysisResponse.Metadata(100, 2, 2, "COMPLETE",
                Map.of("status", "AVAILABLE", "requestedCodes", 1, "enrichedCodes", 1,
                    "availableFacts", 5, "realtimeFreshQuotes", 0)));
    }
}
