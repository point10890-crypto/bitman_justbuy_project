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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatGptAgentTest {

    @Mock RestTemplate restTemplate;
    AiProperties props;
    ChatGptAgent agent;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        props = new AiProperties("test-openai-key", "");
        agent = new ChatGptAgent(props, restTemplate, mapper);
    }

    // ── isAvailable ──────────────────────────────────────────
    @Test
    void isAvailable_returnsTrue_whenKeyPresent() {
        assertThat(agent.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_returnsFalse_whenKeyBlank() {
        var a = new ChatGptAgent(new AiProperties("", ""), restTemplate, mapper);
        assertThat(a.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_returnsFalse_whenKeyNull() {
        var a = new ChatGptAgent(new AiProperties(null, null), restTemplate, mapper);
        assertThat(a.isAvailable()).isFalse();
    }

    // ── name ─────────────────────────────────────────────────
    @Test
    void name_returnsChatgpt() {
        assertThat(agent.name()).isEqualTo("chatgpt");
    }

    // ── model constants ──────────────────────────────────────
    @Test
    void analysisModel_isSearchPreview() {
        assertThat(ChatGptAgent.ANALYSIS_MODEL).isEqualTo("gpt-4o-search-preview");
    }

    @Test
    void synthesisModel_isGpt4o() {
        assertThat(ChatGptAgent.SYNTHESIS_MODEL).isEqualTo("gpt-4o");
    }

    // ── analyze ──────────────────────────────────────────────
    @Test
    void analyze_callsOpenAiWithSearchOptions() throws Exception {
        stubOkResponse("gpt-4o-search-preview-2024-11-20", "분석 결과입니다.", 100, 200);

        AgentResult result = agent.analyze("system", "user");

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.content()).isEqualTo("분석 결과입니다.");
        assertThat(result.agent()).isEqualTo("chatgpt");

        // web_search_options가 요청 body에 포함됐는지 검증
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(contains("/chat/completions"), eq(HttpMethod.POST),
            captor.capture(), eq(String.class));
        String body = (String) captor.getValue().getBody();
        assertThat(body).contains("web_search_options");
        assertThat(body).contains("gpt-4o-search-preview");
        assertThat(body).contains("KR");
        assertThat(body).contains("Seoul");
    }

    @Test
    void analyze_returnsSkipped_whenNotAvailable() {
        var a = new ChatGptAgent(new AiProperties("", ""), restTemplate, mapper);
        AgentResult result = a.analyze("system", "user");
        assertThat(result.status()).isEqualTo("skipped");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void analyze_returnsError_onRestException() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenThrow(new RuntimeException("connection refused"));
        AgentResult result = agent.analyze("system", "user");
        assertThat(result.status()).isEqualTo("error");
        assertThat(result.error()).contains("connection refused");
    }

    // ── synthesize ───────────────────────────────────────────
    @Test
    void synthesize_callsGpt4oWithoutSearchOptions() throws Exception {
        stubOkResponse("gpt-4o-2024-08-06", "합성 결과입니다.", 300, 400);

        AgentResult result = agent.synthesize("system", "user");

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.content()).isEqualTo("합성 결과입니다.");

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(contains("/chat/completions"), eq(HttpMethod.POST),
            captor.capture(), eq(String.class));
        String body = (String) captor.getValue().getBody();
        assertThat(body).contains("gpt-4o");
        // synthesize는 웹검색 없음
        assertThat(body).doesNotContain("web_search_options");
    }

    @Test
    void synthesize_usesCorrectModel() throws Exception {
        stubOkResponse(ChatGptAgent.SYNTHESIS_MODEL, "ok", 10, 20);
        AgentResult result = agent.synthesize("sys", "usr");
        assertThat(result.model()).isEqualTo(ChatGptAgent.SYNTHESIS_MODEL);
    }

    // ── token counting ───────────────────────────────────────
    @Test
    void analyze_correctlyParsesTokenCounts() throws Exception {
        stubOkResponse("gpt-4o-search-preview", "내용", 1500, 800);
        AgentResult result = agent.analyze("sys", "usr");
        assertThat(result.inputTokens()).isEqualTo(1500);
        assertThat(result.outputTokens()).isEqualTo(800);
    }

    // ── authorization header ─────────────────────────────────
    @Test
    void analyze_setsAuthorizationHeader() throws Exception {
        stubOkResponse("gpt-4o-search-preview", "ok", 10, 20);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        agent.analyze("sys", "usr");
        verify(restTemplate).exchange(anyString(), any(), captor.capture(), eq(String.class));
        HttpHeaders headers = captor.getValue().getHeaders();
        assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer test-openai-key");
    }

    // ── helpers ──────────────────────────────────────────────
    private void stubOkResponse(String model, String content, int promptTokens, int completionTokens)
            throws Exception {
        String json = mapper.writeValueAsString(java.util.Map.of(
            "model", model,
            "choices", java.util.List.of(java.util.Map.of(
                "message", java.util.Map.of("role", "assistant", "content", content)
            )),
            "usage", java.util.Map.of(
                "prompt_tokens", promptTokens,
                "completion_tokens", completionTokens
            )
        ));
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(json));
    }
}
