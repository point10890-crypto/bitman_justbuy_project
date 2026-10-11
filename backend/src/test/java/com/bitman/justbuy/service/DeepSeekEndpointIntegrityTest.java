package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.agent.DeepSeekAgent;
import com.bitman.justbuy.controller.*;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeepSeekEndpointIntegrityTest {
    final DeepSeekAgent agent = mock(DeepSeekAgent.class);
    final DeepSeekEndpointService service = new DeepSeekEndpointService(agent, mock(RuntimeAiConfigService.class), new ObjectMapper());

    void response(String content) {
        when(agent.analyze(anyString(), anyString())).thenReturn(new AgentResult("deepseek", content,"fixture",20,30,"success",null,0));
    }
    @Test void arbitraryMapAndMalformedJsonAreErrorInsteadOfSuccessfulSchema() {
        for (String content : new String[]{"{}", "{\"unexpected\":true}", "not json"}) {
            response(content);
            assertThat(service.structuredSignal("BREAKOUT","삼성전자(005930)").get("status")).isEqualTo("error");
            assertThat(service.validatePicks(Map.of("stockPicks", java.util.List.of(Map.of("code","005930")))).get("status")).isEqualTo("error");
            assertThat(service.riskBrief(Map.of("stockPicks", java.util.List.of(Map.of("code","005930")))).get("status")).isEqualTo("error");
        }
    }
    @Test void structuredOutputCannotInventCodeOrLeakNonfiniteConfidence() {
        response("{\"mode\":\"BREAKOUT\",\"stockPicks\":[{\"name\":\"fake\",\"code\":\"999999\",\"currentPrice\":\"10000\",\"targetPrice\":\"12000\",\"stopLoss\":\"9000\",\"action\":\"매수\",\"reason\":\"근거\",\"risk\":\"변동성\",\"confidence\":0.8}],\"warnings\":[]}");
        assertThat(service.structuredSignal("BREAKOUT","삼성전자(005930)").get("status")).isEqualTo("error");
    }

    @Test void directEndpointRejectsOversizedInputBeforePaidProvider() {
        var endpoints = mock(DeepSeekEndpointService.class);
        var controller = new DeepSeekAnalysisController(endpoints, mock(SubscriptionAccessGuard.class));
        assertThatThrownBy(() -> controller.structuredSignal(UUID.randomUUID(),
            new DeepSeekAnalysisController.StructuredSignalRequest("BREAKOUT", "x".repeat(2001))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(endpoints);
    }

    @Test void allDirectPaidEndpointsUseTheSharedLiveQuota() {
        var endpoints = mock(DeepSeekEndpointService.class);
        var quota = mock(LiveAnalysisQuotaService.class);
        when(quota.quota()).thenReturn(5);
        var controller = new DeepSeekAnalysisController(endpoints, mock(SubscriptionAccessGuard.class), quota);
        UUID member = UUID.randomUUID();
        for (Runnable call : java.util.List.<Runnable>of(
            () -> controller.structuredSignal(member, new DeepSeekAnalysisController.StructuredSignalRequest("BREAKOUT", "삼성전자(005930)")),
            () -> controller.validatePicks(member, Map.of("stockPicks", java.util.List.of(Map.of("code", "005930")))),
            () -> controller.riskBrief(member, Map.of("stockPicks", java.util.List.of(Map.of("code", "005930")))))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(e.getCode()).isEqualTo("LIVE_ANALYSIS_QUOTA_EXCEEDED");
            });
        }
        verifyNoInteractions(endpoints);
    }
}
