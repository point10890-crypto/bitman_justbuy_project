package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramSourceCoverageTest {
    private String message(Map<String, Object> expansion) {
        var rest = mock(RestTemplate.class);
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn("{\"ok\":true}");
        var notifier = new TelegramNotifier("fixture-token", "fixture-chat", "", rest);
        notifier.sendAnalysisResult("BREAKOUT", new AnalysisResponse("BREAKOUT", "fixture", List.of(), null, "fixture",
            List.of(new StockPick("A<B>", "005930", "70000", null, null, "관찰", "체결 <확인>")), null,
            "2026-10-12T00:30:00Z", true, new AnalysisResponse.Metadata(100, 2, 2, "PUBLISHED", expansion)));
        var entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).postForObject(anyString(), entity.capture(), eq(String.class));
        return (String) ((Map<?, ?>) entity.getValue().getBody()).get("text");
    }
    @Test void showsVerifiedCoverageAndPartialDataWithoutClaimingEverythingIsRealtime() {
        assertThat(message(Map.of("status", "PARTIAL", "requestedCodes", 2, "enrichedCodes", 1,
            "availableFacts", 5, "realtimeFreshQuotes", 1)))
            .contains("추가자료 확인 후보 1/2종목", "조회 후보 중 실시간 체결 1종목", "일부 미확인")
            .doesNotContain("추천 종목 자료 확인", "전체 실시간", "모든 자료 최신");
    }
    @Test void legacyMetadataDoesNotInventNewCoverage() {
        assertThat(message(null)).doesNotContain("자료 확인", "실시간 체결");
    }
    @Test void malformedMetadataDoesNotEchoValuesOrSensitiveKeys() {
        assertThat(message(Map.of("status", "fixture-secret<bad>", "requestedCodes", "fixture-key",
            "enrichedCodes", -1, "realtimeFreshQuotes", 900000, "approvalKey", "fixture-approval")))
            .doesNotContain("fixture-secret", "fixture-key", "fixture-approval", "900000", "-1종목");
    }
    @Test void providerAndAgentTextCannotBreakTelegramHtml() {
        assertThat(message(null)).contains("A&lt;B&gt;", "체결 &lt;확인&gt;")
            .doesNotContain("<b>1. A<B>");
    }
}
