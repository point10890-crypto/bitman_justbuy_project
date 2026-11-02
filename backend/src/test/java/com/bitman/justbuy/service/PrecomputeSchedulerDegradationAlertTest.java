package com.bitman.justbuy.service;

import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.dto.AgentResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** The admin alert has to say why agents failed, and a billing outage must not repeat on every run. */
class PrecomputeSchedulerDegradationAlertTest {

    final TelegramNotifier notifier = mock(TelegramNotifier.class);
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T02:53:59Z"));
    final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    final PrecomputeScheduler scheduler = new PrecomputeScheduler(mock(AnalysisService.class),
        new ConditionSearchFormulaCatalog(), notifier, null, null, mock(ConditionRunService.class), clock);

    final List<AgentResult> creditsGone = List.of(
        AgentResult.error("chatgpt", "gpt-5.5", "HTTP 429 credit_balance_exhausted", 964),
        new AgentResult("deepseek", "{...}", "deepseek-flash", 1, 1, "success", null, 35817));

    @Test
    void alertNamesTheFailedAgentAndTheBillingFix() {
        scheduler.notifyAgentDegradation("CATALYST_BURST", 2, 1, 4, creditsGone);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).sendToAdmin(text.capture());
        assertThat(text.getValue())
            .contains("chatgpt: HTTP 429 credit_balance_exhausted")
            .contains("크레딧이 소진")
            .contains("platform.openai.com/settings/organization/billing");
    }

    @Test
    void aBillingOutageAlertsOnceAcrossModesUntilThreeHoursPass() {
        scheduler.notifyAgentDegradation("CATALYST_BURST", 2, 1, 4, creditsGone);
        scheduler.notifyAgentDegradation("BREAKOUT", 2, 1, 5, creditsGone);
        now.set(now.get().plus(Duration.ofHours(2)));
        scheduler.notifyAgentDegradation("FLOW_LEADER", 2, 1, 3, creditsGone);
        verify(notifier, times(1)).sendToAdmin(anyString());

        now.set(now.get().plus(Duration.ofHours(1).plusMinutes(1)));
        scheduler.notifyAgentDegradation("REVERSAL_EDGE", 2, 1, 2, creditsGone);
        verify(notifier, times(2)).sendToAdmin(anyString());
    }

    @Test
    void aDifferentCauseIsReportedImmediately() {
        scheduler.notifyAgentDegradation("CATALYST_BURST", 2, 1, 4, creditsGone);
        scheduler.notifyAgentDegradation("CATALYST_BURST", 2, 1, 4,
            List.of(AgentResult.error("deepseek", "deepseek-flash", "HTTP 503", 1200)));
        verify(notifier, times(2)).sendToAdmin(anyString());
    }

    @Test
    void healthyRunsSendNothing() {
        scheduler.notifyAgentDegradation("BREAKOUT", 2, 2, 5, List.of());
        verifyNoInteractions(notifier);
    }
}
