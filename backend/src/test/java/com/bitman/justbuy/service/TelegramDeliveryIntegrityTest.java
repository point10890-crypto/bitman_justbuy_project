package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class TelegramDeliveryIntegrityTest {
    @Test
    void missingTargetsAndStopsStayUnavailableInsteadOfInventingPercentagePrices() {
        var rest = mock(RestTemplate.class);
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn("{\"ok\":true}");
        var notifier = new TelegramNotifier("fixture-token", "fixture-chat", "", rest);
        notifier.sendAnalysisResult("BREAKOUT", new AnalysisResponse("BREAKOUT", "query", List.of(), null, "fixture",
            List.of(new StockPick("Samsung", "005930", "70,000", null, null, "watch", "fixture")), null,
            "2026-10-12T00:30:00Z", true, new AnalysisResponse.Metadata(1, 2, 2)));
        var entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).postForObject(anyString(), entity.capture(), eq(String.class));
        String text = (String) ((Map<?, ?>) entity.getValue().getBody()).get("text");
        assertThat(text).contains("70,000").doesNotContain("77,000", "66,500");
    }
    @Test
    void missingOrMalformedAcknowledgementNeverReportsASentMemberMessage() {
        var rest = mock(RestTemplate.class);
        var notifier = new TelegramNotifier("fixture-token", "fixture-chat", "", rest);
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
            .thenReturn(null, "{\"ok\" : false}", "{\"unexpected\":true}");
        assertThat(notifier.sendToMember("fixture-member", "fixture")).isFalse();
        assertThat(notifier.sendToMember("fixture-member", "fixture")).isFalse();
        assertThat(notifier.sendToMember("fixture-member", "fixture")).isFalse();
    }
}
