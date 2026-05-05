package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.service.RuntimeAiConfigService;
import com.bitman.justbuy.service.RuntimeAiConfigService.DeepSeekConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeepSeekAgentTest {

    @Mock RestTemplate restTemplate;
    @Mock RuntimeAiConfigService runtimeAiConfigService;

    ObjectMapper mapper = new ObjectMapper();
    DeepSeekAgent agent;

    @BeforeEach
    void setUp() {
        AiProperties props = new AiProperties("", "", "", "", "", "");
        agent = new DeepSeekAgent(props, runtimeAiConfigService, restTemplate, mapper);
    }

    @Test
    void isAvailable_usesRuntimeConfig() {
        when(runtimeAiConfigService.getDeepSeekConfig())
            .thenReturn(new DeepSeekConfig("sk-test", "https://api.deepseek.com", "deepseek-v4-flash", "runtime", null));

        assertThat(agent.isAvailable()).isTrue();
    }

    @Test
    void analyze_callsDeepSeekChatCompletions() throws Exception {
        when(runtimeAiConfigService.getDeepSeekConfig())
            .thenReturn(new DeepSeekConfig("sk-test", "https://api.deepseek.com", "deepseek-v4-flash", "runtime", null));
        stubOkResponse("deepseek-v4-flash", "ok", 11, 22);

        AgentResult result = agent.analyze("system", "user");

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.inputTokens()).isEqualTo(11);
        assertThat(result.outputTokens()).isEqualTo(22);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://api.deepseek.com/chat/completions"),
            eq(HttpMethod.POST), captor.capture(), eq(String.class));

        HttpHeaders headers = captor.getValue().getHeaders();
        assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer sk-test");

        String body = (String) captor.getValue().getBody();
        assertThat(body).contains("deepseek-v4-flash");
        assertThat(body).contains("thinking");
        assertThat(body).contains("disabled");
    }

    @Test
    void analyze_returnsSkipped_whenKeyMissing() {
        when(runtimeAiConfigService.getDeepSeekConfig())
            .thenReturn(new DeepSeekConfig("", "https://api.deepseek.com", "deepseek-v4-flash", "none", null));

        AgentResult result = agent.analyze("system", "user");

        assertThat(result.status()).isEqualTo("skipped");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void analyze_returnsError_onRestException() {
        when(runtimeAiConfigService.getDeepSeekConfig())
            .thenReturn(new DeepSeekConfig("sk-test", "https://api.deepseek.com", "deepseek-v4-flash", "runtime", null));
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenThrow(new RuntimeException("timeout"));

        AgentResult result = agent.analyze("system", "user");

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.error()).contains("timeout");
    }

    private void stubOkResponse(String model, String content, int promptTokens, int completionTokens)
            throws Exception {
        String json = mapper.writeValueAsString(Map.of(
            "model", model,
            "choices", List.of(Map.of(
                "message", Map.of("role", "assistant", "content", content)
            )),
            "usage", Map.of(
                "prompt_tokens", promptTokens,
                "completion_tokens", completionTokens
            )
        ));
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(json));
    }
}
