package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.MultiAgentOrchestrator;
import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.condition.run.ConditionRunStatus;
import com.bitman.justbuy.condition.run.ConditionRunEvent;
import com.bitman.justbuy.condition.run.ConditionRunEventType;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import org.mockito.Mockito;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.bitman.justbuy.dto.AgentResult;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrecomputePublicationIntegrityTest {
    @TempDir Path dataDir;

    @Test
    void cachedBreakoutOverlapDoesNotEraseFreshSwingPublicationOrChangeItsOutcome() {
        var service = mock(AnalysisService.class);
        var breakout = verifiedResponse("BREAKOUT", "005930", "000660", "035420");
        var swing = verifiedResponse("REVERSAL_EDGE", "000660", "035420");
        when(service.getPrecomputed(anyString())).thenAnswer(call -> switch ((String) call.getArgument(0)) {
            case "BREAKOUT" -> breakout;
            case "FLOW_LEADER" -> verifiedResponse("FLOW_LEADER", "068270");
            case "CATALYST_BURST" -> verifiedResponse("CATALYST_BURST", "051910");
            default -> null;
        });
        when(service.runAnalysisForPublication(anyString(), eq("REVERSAL_EDGE"))).thenReturn(swing);
        var notifier = mock(TelegramNotifier.class);
        var runs = new ConditionRunService();
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), notifier, null, null, runs,
            Clock.fixed(Instant.parse("2026-10-12T01:51:00Z"), ZoneOffset.UTC));

        ReflectionTestUtils.invokeMethod(scheduler, "runStartupPrecompute");

        var saved = ArgumentCaptor.forClass(AnalysisResponse.class);
        verify(service).saveAnalysis(eq("REVERSAL_EDGE"), saved.capture());
        assertThat(saved.getValue().stockPicks()).extracting(StockPick::code).containsExactly("000660", "035420");
        assertThat(saved.getValue().metadata().publicationStatus()).isEqualTo("COMPLETE");
        verify(service).recordPublishedAnalysis(saved.getValue());
        var run = runs.recent(10).stream().filter(item -> "REVERSAL_EDGE".equals(item.mode())).findFirst().orElseThrow();
        assertThat(run.status()).isEqualTo(ConditionRunStatus.COMPLETE);
        assertThat(run.pickCount()).isEqualTo(2);
        assertThat(scheduler.getLastRunTimes()).containsEntry("REVERSAL_EDGE", "2026-10-12T01:51:00Z");
        assertThat(runs.events(run.runId())).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(ConditionRunEventType.NOTIFICATION_SKIPPED);
            assertThat(event.metrics()).containsEntry("reason", "CROSS_MODE_DUPLICATE")
                .containsEntry("pickCount", 0).containsEntry("publishedCount", 2);
        });
        verify(notifier, never()).sendAnalysisResult(anyString(), any());
    }

    @Test
    void overlappingScheduledModesBothPublishButOnlyTheFirstNotificationIsSent() {
        var service = mock(AnalysisService.class);
        when(service.runAnalysisForPublication(anyString(), anyString())).thenAnswer(call -> {
            String mode = call.getArgument(1);
            return "BREAKOUT".equals(mode) || "REVERSAL_EDGE".equals(mode)
                ? verifiedResponse(mode, "005930", "000660") : verifiedResponse(mode);
        });
        var notifier = mock(TelegramNotifier.class);
        when(notifier.sendAnalysisResult(anyString(), any()))
            .thenReturn(new TelegramNotifier.DeliveryReport(TelegramNotifier.DeliveryStatus.SENT, 1, 0));
        var runs = new ConditionRunService();
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), notifier, null, null, runs,
            Clock.fixed(Instant.parse("2026-10-12T01:51:00Z"), ZoneOffset.UTC));

        scheduler.scheduledPrecompute();

        for (String mode : List.of("BREAKOUT", "REVERSAL_EDGE")) {
            var saved = ArgumentCaptor.forClass(AnalysisResponse.class);
            verify(service).saveAnalysis(eq(mode), saved.capture());
            assertThat(saved.getValue().stockPicks()).extracting(StockPick::code).containsExactly("005930", "000660");
            assertThat(saved.getValue().metadata().publicationStatus()).isEqualTo("COMPLETE");
            var run = runs.recent(10).stream().filter(item -> mode.equals(item.mode())).findFirst().orElseThrow();
            assertThat(run.status()).isEqualTo(ConditionRunStatus.COMPLETE);
            assertThat(run.pickCount()).isEqualTo(2);
        }
        verify(service, times(2)).recordPublishedAnalysis(any());
        verify(notifier, times(1)).sendAnalysisResult(eq("BREAKOUT"), any());
        verify(notifier, times(1)).sendAnalysisResult(anyString(), any());
    }

    private static AnalysisResponse verifiedResponse(String mode, String... codes) {
        return new AnalysisResponse(mode, "fixture query", List.of(), null, "fixture report", java.util.Arrays.stream(codes)
            .map(code -> new StockPick("fixture " + code, code, "70,000", null, null, "watch", "fixture", 0, "",
                "KIS fixture rank", "KIS fixture quote", "2026-10-12T01:51:00Z")).toList(), null,
            "2026-10-12T01:51:00Z", true, new AnalysisResponse.Metadata(100, 2, 2));
    }

    @Test
    void manualAdminRefreshNeverSendsOperationalOrChannelMessages() {
        var service = mock(AnalysisService.class);
        when(service.runAnalysisForPublication(anyString(), anyString()))
            .thenReturn(AnalysisPublicationIntegrityTest.response(2, 2, List.of(AnalysisPublicationIntegrityTest.pick()), "2026-10-12T01:00:00Z"));
        var notifier = mock(TelegramNotifier.class);
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), notifier, null, null,
            new ConditionRunService(), Clock.fixed(Instant.parse("2026-10-12T01:00:00Z"), ZoneOffset.UTC));
        assertThat(scheduler.refreshModes(List.of("BREAKOUT"))).containsEntry("BREAKOUT", "success");
        when(service.runAnalysisForPublication(anyString(), anyString())).thenThrow(new IllegalStateException("fixture failure"));
        assertThat(scheduler.refreshModes(List.of("BREAKOUT"))).containsEntry("BREAKOUT", "error: fixture failure");
        verifyNoInteractions(notifier);
    }

    @Test
    void trackingAndEveryPublicRepresentationContainOnlyThePersistedFinalSet() throws Exception {
        var raw = new AnalysisResponse("FLOW_LEADER", "query", List.of(new AgentResult("fixture-agent",
            "검증된 후보\n📌 original (005930) — 현재가 70,000원\n근거: own opinion\n"
                + "📌 excluded (069500) — 현재가 40,000원\n근거: excluded opinion\n", "fixture", 1, 1, "success", null, 1)),
            null, "original raw report 069500", List.of(
                new StockPick("Samsung verified", "005930", "70,000", "77,000", "66,500", "watch", "fixture", 80, "fixture", "kis-rank", "kis-quote", "2026-10-12T01:00:00Z"),
                new StockPick("excluded", "069500", "40,000", null, null, "watch", "fixture")),
            null, "2026-10-12T01:00:00Z", true, new AnalysisResponse.Metadata(1, 2, 2));
        var orchestrator = mock(MultiAgentOrchestrator.class);
        when(orchestrator.runAnalysis(anyString(), anyString())).thenReturn(raw);
        var market = mock(MarketDataService.class);
        when(market.fetchStockName("005930")).thenReturn("Samsung verified");
        when(market.fetchStockName("069500")).thenReturn("KODEX 200");
        var service = new AnalysisService(orchestrator, new ConditionSearchFormulaCatalog(), mock(KeywordConditionAnalysisService.class), new ObjectMapper(), dataDir.toString());
        var tracking = mock(TrackRecordService.class);
        service.configureTracking(tracking);
        doAnswer(invocation -> {
            assertThat(service.getStoredPrecomputed("FLOW_LEADER")).isEqualTo(invocation.getArgument(0));
            return null;
        }).when(tracking).recordAnalysis(any());
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), null, null, market, new ConditionRunService(),
            Clock.fixed(Instant.parse("2026-10-12T01:00:00Z"), ZoneOffset.UTC));

        assertThat(scheduler.refreshModes(List.of("FLOW_LEADER"))).containsEntry("FLOW_LEADER", "success");
        var recorded = ArgumentCaptor.forClass(AnalysisResponse.class);
        verify(tracking).recordAnalysis(recorded.capture());
        var published = recorded.getValue();
        assertThat(published.stockPicks()).extracting(StockPick::code).containsExactly("005930");
        assertThat(published.stockPicks().getFirst().name()).isEqualTo("Samsung verified");
        assertThat(published.stockPicks().getFirst().quoteSource()).isEqualTo("kis-quote");
        assertThat(published.finalContent()).doesNotContain("069500");
        assertThat(published.round1().getFirst().content()).contains("own opinion").doesNotContain("069500", "excluded opinion");
        assertThat(published.metadata().publicationStatus()).isEqualTo("COMPLETE");
        verify(market, never()).fetchStockName("005930");
    }

    @Test
    void nightAndDisabledStartupLeaveMissingCacheWithoutPaidWork() {
        for (String instant : List.of("2026-10-12T11:00:00Z", "2026-10-12T01:00:00Z")) {
            var service = mock(AnalysisService.class);
            var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), null, null, null,
                new ConditionRunService(), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
            if (instant.endsWith("01:00:00Z")) ReflectionTestUtils.setField(scheduler, "startupRefreshEnabled", false);
            ReflectionTestUtils.invokeMethod(scheduler, "runStartupPrecompute");
            verify(service, never()).runAnalysisForPublication(anyString(), anyString());
            assertThat(scheduler.getLastRunTimes()).isEmpty();
        }
    }

    @Test
    void allowedStartupAttemptsEachMissingModeAtMostOncePerBoot() {
        var calls = new AtomicInteger();
        var orchestrator = mock(MultiAgentOrchestrator.class);
        when(orchestrator.runAnalysis(anyString(), anyString())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            String mode = invocation.getArgument(1);
            String code = switch (mode) { case "BREAKOUT" -> "005930"; case "REVERSAL_EDGE" -> "000660";
                case "FLOW_LEADER" -> "035420"; default -> "068270"; };
            return new AnalysisResponse(mode, "query", List.of(), null, "fixture", List.of(
                new StockPick("fixture " + code, code, "70,000", null, null, "watch", "fixture")), null,
                "2026-10-12T01:00:00Z", true, new AnalysisResponse.Metadata(1, 2, 2));
        });
        var service = new AnalysisService(orchestrator, new ConditionSearchFormulaCatalog(), mock(KeywordConditionAnalysisService.class), new ObjectMapper(), dataDir.toString());
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), null, null, null,
            new ConditionRunService(), Clock.fixed(Instant.parse("2026-10-12T01:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.invokeMethod(scheduler, "runStartupPrecompute");
        ReflectionTestUtils.invokeMethod(scheduler, "runStartupPrecompute");
        assertThat(calls).hasValue(4);
        assertThat(scheduler.getLastRunTimes()).hasSize(4);
    }

    @Test
    void sundayStartupNeverPaysForAMissingCache() {
        var service = mock(AnalysisService.class);
        when(service.runAnalysisForPublication(anyString(), anyString())).thenReturn(
            AnalysisPublicationIntegrityTest.response(2, 2, List.of(AnalysisPublicationIntegrityTest.pick()), "2026-10-11T01:00:00Z"));
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), null, null, null,
            new ConditionRunService(), Clock.fixed(Instant.parse("2026-10-11T01:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.invokeMethod(scheduler, "runStartupPrecompute");
        verify(service, never()).runLiveAnalysis(anyString(), anyString());
        verify(service, never()).runAnalysisForPublication(anyString(), anyString());
        assertThat(scheduler.getLastRunTimes()).isEmpty();
    }

    @Test
    void disabledTelegramDoesNotProduceANotifiedEventDuringMarketHours() {
        var orchestrator = mock(MultiAgentOrchestrator.class);
        when(orchestrator.runAnalysis(anyString(), anyString())).thenReturn(
            AnalysisPublicationIntegrityTest.response(2, 2, List.of(AnalysisPublicationIntegrityTest.pick()), "2026-10-12T01:00:00Z"));
        var service = new AnalysisService(orchestrator, new ConditionSearchFormulaCatalog(),
            mock(KeywordConditionAnalysisService.class), new ObjectMapper(), dataDir.toString());
        var runs = new ConditionRunService();
        var notifier = new TelegramNotifier("", "fixture-chat", "", mock(RestTemplate.class));
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), notifier, null, null, runs,
            Clock.fixed(Instant.parse("2026-10-12T01:00:00Z"), ZoneOffset.UTC));
        scheduler.scheduledPrecompute();
        var breakout = runs.recent(10).stream().filter(run -> "BREAKOUT".equals(run.mode())).findFirst().orElseThrow();
        assertThat(runs.events(breakout.runId())).extracting(ConditionRunEvent::type)
            .contains(ConditionRunEventType.NOTIFICATION_SKIPPED)
            .doesNotContain(ConditionRunEventType.NOTIFIED);
    }

    @Test
    void allFailedAgentsDoNotCountAsSchedulerSuccessOrAdvanceLastRunTime() {
        var service = mock(AnalysisService.class);
        when(service.runAnalysisForPublication(anyString(), anyString())).thenReturn(
            AnalysisPublicationIntegrityTest.response(2, 0, List.of(), "2026-10-11T00:00:00Z"));
        var runs = new ConditionRunService();
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), null, null, null, runs);

        var result = scheduler.refreshModes(List.of("BREAKOUT"));

        assertThat(result.get("BREAKOUT")).startsWith("error:");
        assertThat(scheduler.getLastRunTimes()).doesNotContainKey("BREAKOUT");
        assertThat(runs.recent(1).getFirst().status()).isEqualTo(ConditionRunStatus.FAILED);
    }

    @Test
    void finalPeriodicExclusionKeepsTheLastGoodCacheAndReportsEmpty() throws Exception {
        var mapper = new ObjectMapper();
        var old = AnalysisPublicationIntegrityTest.response(2, 2, List.of(AnalysisPublicationIntegrityTest.pick()), "2026-10-09T00:00:00Z");
        mapper.writeValue(dataDir.resolve("BREAKOUT.json").toFile(), old);
        var orchestrator = mock(MultiAgentOrchestrator.class);
        when(orchestrator.runAnalysis(anyString(), anyString())).thenReturn(
            AnalysisPublicationIntegrityTest.response(2, 2, List.of(
                new StockPick("KODEX 200", "069500", "40,000", null, null, "watch", "fixture")), "2026-10-11T00:00:00Z"));
        var service = new AnalysisService(orchestrator, new ConditionSearchFormulaCatalog(),
            mock(KeywordConditionAnalysisService.class), mapper, dataDir.toString());
        var runs = new ConditionRunService();
        var scheduler = new PrecomputeScheduler(service, new ConditionSearchFormulaCatalog(), null, null, null, runs);

        var result = scheduler.refreshModes(List.of("BREAKOUT"));

        assertThat(result).containsEntry("BREAKOUT", "empty");
        assertThat(service.getStoredPrecomputed("BREAKOUT")).isEqualTo(old);
        assertThat(scheduler.getLastRunTimes()).doesNotContainKey("BREAKOUT");
        assertThat(runs.recent(1).getFirst().status()).isEqualTo(ConditionRunStatus.PARTIAL);
    }
}
