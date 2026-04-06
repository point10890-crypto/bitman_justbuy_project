package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GrokAgentTest {

    @Mock RestTemplate restTemplate;
    AiProperties props;
    GrokAgent agent;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        props = new AiProperties("", "test-grok-key");
        agent = new GrokAgent(props, restTemplate, mapper);
    }

    // ── isAvailable ──────────────────────────────────────────
    @Test
    void isAvailable_returnsTrue_whenKeyPresent() {
        assertThat(agent.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_returnsFalse_whenKeyBlank() {
        var a = new GrokAgent(new AiProperties("", ""), restTemplate, mapper);
        assertThat(a.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_returnsFalse_whenKeyNull() {
        var a = new GrokAgent(new AiProperties(null, null), restTemplate, mapper);
        assertThat(a.isAvailable()).isFalse();
    }

    // ── name ─────────────────────────────────────────────────
    @Test
    void name_returnsGrok() {
        assertThat(agent.name()).isEqualTo("grok");
    }

    // ── model constants ──────────────────────────────────────
    @Test
    void analysisModel_isMultiAgent() {
        assertThat(GrokAgent.ANALYSIS_MODEL).isEqualTo("grok-4.20-multi-agent-0309");
    }

    @Test
    void critiqueModel_isFast() {
        assertThat(GrokAgent.CRITIQUE_MODEL).isEqualTo("grok-4-1-fast-reasoning");
    }

    // ── analyze — Responses API ──────────────────────────────
    @Test
    void analyze_callsResponsesEndpoint() throws Exception {
        stubResponsApiOk("grok-4.20-multi-agent-0309", "수급 분석 결과", 200, 400);

        AgentResult result = agent.analyze("system", "user");

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.content()).isEqualTo("수급 분석 결과");
        assertThat(result.agent()).isEqualTo("grok");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(), eq(String.class));
        assertThat(urlCaptor.getValue()).endsWith("/responses");
    }

    @Test
    void analyze_includesWebSearchAndXSearchTools() throws Exception {
        stubResponsApiOk("grok-4.20-multi-agent-0309", "ok", 100, 200);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        agent.analyze("sys", "usr");
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));

        String body = (String) captor.getValue().getBody();
        assertThat(body).contains("web_search");
        assertThat(body).contains("x_search");
        assertThat(body).contains("dart.fss.or.kr");
        assertThat(body).contains("finance.naver.com");
        assertThat(body).contains("from_date");
    }

    @Test
    void analyze_usesMultiAgentModel() throws Exception {
        stubResponsApiOk(GrokAgent.ANALYSIS_MODEL, "ok", 100, 200);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        agent.analyze("sys", "usr");
        verify(restTemplate).exchange(anyString(), any(), captor.capture(), eq(String.class));
        assertThat((String) captor.getValue().getBody()).contains(GrokAgent.ANALYSIS_MODEL);
    }

    @Test
    void analyze_returnsSkipped_whenNotAvailable() {
        var a = new GrokAgent(new AiProperties("", ""), restTemplate, mapper);
        AgentResult result = a.analyze("sys", "usr");
        assertThat(result.status()).isEqualTo("skipped");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void analyze_returnsError_onRestException() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenThrow(new RuntimeException("timeout"));
        AgentResult result = agent.analyze("sys", "usr");
        assertThat(result.status()).isEqualTo("error");
        assertThat(result.error()).contains("timeout");
    }

    // ── critique — chat completions fallback ─────────────────
    @Test
    void critique_doesNotIncludeSearchTools() throws Exception {
        // critique uses cheaper model — no search tools
        stubResponsApiOk(GrokAgent.CRITIQUE_MODEL, "비판 결과", 50, 100);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        agent.critique("sys", "usr");
        verify(restTemplate).exchange(anyString(), any(), captor.capture(), eq(String.class));
        String body = (String) captor.getValue().getBody();
        // critique mode: enableSearch=false → tools 없음
        assertThat(body).doesNotContain("web_search");
        assertThat(body).doesNotContain("x_search");
    }

    @Test
    void critique_usesFastModel() throws Exception {
        stubResponsApiOk(GrokAgent.CRITIQUE_MODEL, "ok", 50, 100);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        agent.critique("sys", "usr");
        verify(restTemplate).exchange(anyString(), any(), captor.capture(), eq(String.class));
        assertThat((String) captor.getValue().getBody()).contains(GrokAgent.CRITIQUE_MODEL);
    }

    // ── Responses API response parsing ───────────────────────
    @Test
    void analyze_parsesResponsesApiFormat_outputArray() throws Exception {
        String json = mapper.writeValueAsString(Map.of(
            "model", "grok-4.20-multi-agent-0309",
            "output", List.of(Map.of(
                "type", "message",
                "content", List.of(Map.of("type", "output_text", "text", "X피드 센티먼트: 강세"))
            )),
            "usage", Map.of("input_tokens", 300, "output_tokens", 600)
        ));
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(json));

        AgentResult result = agent.analyze("sys", "usr");
        assertThat(result.status()).isEqualTo("success");
        assertThat(result.content()).contains("X피드 센티먼트");
        assertThat(result.inputTokens()).isEqualTo(300);
        assertThat(result.outputTokens()).isEqualTo(600);
    }

    @Test
    void analyze_fallbackToChoicesFormat_whenOutputEmpty() throws Exception {
        // OpenAI-compatible 폴백
        String json = mapper.writeValueAsString(Map.of(
            "model", "grok-4",
            "choices", List.of(Map.of(
                "message", Map.of("role", "assistant", "content", "폴백 결과")
            )),
            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20)
        ));
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(json));

        AgentResult result = agent.analyze("sys", "usr");
        assertThat(result.status()).isEqualTo("success");
        assertThat(result.content()).isEqualTo("폴백 결과");
    }

    @Test
    void analyze_appendsCitations_whenPresent() throws Exception {
        String json = mapper.writeValueAsString(Map.of(
            "model", "grok-4",
            "output", List.of(Map.of(
                "type", "message",
                "content", List.of(Map.of("type", "output_text", "text", "분석 내용"))
            )),
            "citations", List.of(
                Map.of("url", "https://finance.naver.com/stock/123"),
                Map.of("url", "https://dart.fss.or.kr/reports/456")
            ),
            "usage", Map.of("input_tokens", 10, "output_tokens", 20)
        ));
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(json));

        AgentResult result = agent.analyze("sys", "usr");
        assertThat(result.content()).contains("검색 출처");
        assertThat(result.content()).contains("finance.naver.com");
        assertThat(result.content()).contains("dart.fss.or.kr");
    }

    // ── authorization header ─────────────────────────────────
    @Test
    void analyze_setsAuthorizationHeader() throws Exception {
        stubResponsApiOk("grok-4", "ok", 10, 20);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        agent.analyze("sys", "usr");
        verify(restTemplate).exchange(anyString(), any(), captor.capture(), eq(String.class));
        assertThat(captor.getValue().getHeaders().getFirst("Authorization"))
            .isEqualTo("Bearer test-grok-key");
    }

    // ── helpers ──────────────────────────────────────────────
    private void stubResponsApiOk(String model, String text, int inputTokens, int outputTokens)
            throws Exception {
        String json = mapper.writeValueAsString(Map.of(
            "model", model,
            "output", List.of(Map.of(
                "type", "message",
                "content", List.of(Map.of("type", "output_text", "text", text))
            )),
            "usage", Map.of("input_tokens", inputTokens, "output_tokens", outputTokens)
        ));
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(json));
    }
}
