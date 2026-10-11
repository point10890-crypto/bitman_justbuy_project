package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.service.RuntimeAiConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProviderIntegrityTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RestTemplate rest = mock(RestTemplate.class);

    void response(String json) {
        when(rest.exchange(anyString(), any(), any(), eq(String.class))).thenReturn(ResponseEntity.ok(json));
    }

    @Test void grokRejectsIncompleteAndKeepsUsage() {
        response("""
            {"status":"incomplete","model":"actual-grok","output":[{"type":"message","content":[{"type":"output_text","text":"partial"}]}],"usage":{"input_tokens":20,"output_tokens":30}}
            """);
        AgentResult result = new GrokAgent(new AiProperties("", "fixture"), rest, mapper).analyze("s", "u");
        assertThat(result.status()).isEqualTo("error");
        assertThat(result.model()).isEqualTo("actual-grok");
        assertThat(result.inputTokens() + result.outputTokens()).isEqualTo(50);
    }

    @Test void grokRejectsMissingCompletionAndSourcesOnly() {
        GrokAgent grok = new GrokAgent(new AiProperties("", "fixture"), rest, mapper);
        response("{}");
        assertThat(grok.analyze("s", "u").status()).isEqualTo("error");
        response("{\"status\":\"completed\",\"citations\":[\"https://krx.co.kr\"]}");
        assertThat(grok.analyze("s", "u").status()).isEqualTo("error");
    }

    @Test void grokJoinsAllTextAndRetainsStringAndAnnotationCitations() {
        response("""
            {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"first","annotations":[{"type":"url_citation","url":"https://dart.fss.or.kr/report"}]},{"type":"output_text","text":"second"}]}],"citations":["https://krx.co.kr/source"]}
            """);
        AgentResult result = new GrokAgent(new AiProperties("", "fixture"), rest, mapper).analyze("s", "u");
        assertThat(result.status()).isEqualTo("success");
        assertThat(result.content()).contains("first", "second", "https://krx.co.kr/source", "https://dart.fss.or.kr/report");
    }

    @Test void geminiRejectsTruncationAndPreservesModelTotalAndThoughtUsage() {
        response("""
            {"modelVersion":"actual-gemini","candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"partial"}]}}],"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":30,"thoughtsTokenCount":40,"totalTokenCount":90}}
            """);
        AgentResult result = new GeminiAgent(new AiProperties("", "", "fixture"), rest, mapper).analyze("s", "u");
        assertThat(result.status()).isEqualTo("error");
        assertThat(result.model()).isEqualTo("actual-gemini");
        assertThat(result.inputTokens() + result.outputTokens()).isEqualTo(90);
        assertThat(mapper.valueToTree(result).path("thoughtTokens").asInt()).isEqualTo(40);
        assertThat(mapper.valueToTree(result).path("totalTokens").asInt()).isEqualTo(90);
    }

    @Test void geminiRejectsSourcesWithoutAnswerAndMissingFinishReason() {
        GeminiAgent gemini = new GeminiAgent(new AiProperties("", "", "fixture"), rest, mapper);
        response("{\"candidates\":[{\"finishReason\":\"SAFETY\",\"groundingMetadata\":{\"groundingChunks\":[{\"web\":{\"uri\":\"https://krx.co.kr\"}}]}}]}");
        assertThat(gemini.analyze("s", "u").status()).isEqualTo("error");
        response("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"answer\"}]}}]}");
        assertThat(gemini.analyze("s", "u").status()).isEqualTo("error");
    }

    DeepSeekAgent deepSeek(String baseUrl) {
        RuntimeAiConfigService config = mock(RuntimeAiConfigService.class);
        when(config.getDeepSeekConfig()).thenReturn(new RuntimeAiConfigService.DeepSeekConfig("fixture", baseUrl, "fixture-model", "test", null));
        return new DeepSeekAgent(new AiProperties("", ""), config, rest, mapper);
    }

    @Test void deepSeekRequiresStopAndPreservesSpentTokens() {
        response("{\"model\":\"actual-deepseek\",\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":30}}");
        AgentResult result = deepSeek("https://api.deepseek.com").analyze("s", "u");
        assertThat(result.status()).isEqualTo("error");
        assertThat(result.inputTokens() + result.outputTokens()).isEqualTo(50);
        response("{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}");
        assertThat(deepSeek("https://api.deepseek.com").analyze("s", "u").status()).isEqualTo("error");
    }

    @Test void deepSeekNeverSendsCredentialsToUntrustedOrHttpDestinations() {
        for (String base : new String[]{"http://api.deepseek.com", "https://attacker.example", "https://api.deepseek.com.attacker.example", "https://api.deepseek.com@attacker.example"}) {
            assertThat(deepSeek(base).analyze("s", "u").status()).isEqualTo("error");
        }
        verifyNoInteractions(rest);
    }

    @Test void openAiSynthesisRequiresStopAndKeepsSpentTokens() {
        response("{\"model\":\"actual-openai\",\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":30}}");
        var result = new ChatGptAgent(new AiProperties("fixture", ""), rest, mapper).synthesize("s", "u");
        assertThat(result.status()).isEqualTo("error");
        assertThat(result.inputTokens() + result.outputTokens()).isEqualTo(50);
        assertThat(result.model()).isEqualTo("actual-openai");
    }

    @Test void openAiIncompleteAnalysisKeepsModelAndTokens() {
        response("{\"model\":\"actual-openai\",\"status\":\"incomplete\",\"usage\":{\"input_tokens\":20,\"output_tokens\":30}}");
        var result = new ChatGptAgent(new AiProperties("fixture", ""), rest, mapper).analyze("s", "u");
        assertThat(result.status()).isEqualTo("error");
        assertThat(result.inputTokens() + result.outputTokens()).isEqualTo(50);
        assertThat(result.model()).isEqualTo("actual-openai");
    }

    @Test void interruptedProviderCallsKeepTheirCapacityUntilActualHttpExit() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(4);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(rest.exchange(anyString(), any(), any(), eq(String.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() <= 4) {
                entered.countDown();
                boolean released = false;
                while (!released) {
                    try { released = release.await(50, java.util.concurrent.TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* non-cooperative HTTP fixture */ }
                }
            }
            return ResponseEntity.ok("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"fixture answer\"}}]}");
        });
        DeepSeekAgent deep = deepSeek("https://api.deepseek.com");
        var workers = new java.util.ArrayList<Thread>();
        try {
            for (int i = 0; i < 4; i++) workers.add(Thread.startVirtualThread(() -> deep.analyze("s", "u")));
            assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            workers.forEach(Thread::interrupt);
            AgentResult extra = deep.analyze("s", "u");
            assertThat(extra.status()).isEqualTo("error");
            assertThat(extra.error()).contains("capacity");
        } finally {
            release.countDown();
            for (Thread worker : workers) worker.join(1000);
        }
    }

    @Test void providerTransportFailuresCannotExposeCredentialUrlsOrResponseBodies() {
        when(rest.exchange(anyString(), any(), any(), eq(String.class))).thenThrow(new ResourceAccessException(
            "I/O error on POST request for https://provider.example/generate?key=SYNTHETIC_PRIVATE_KEY; Authorization: Bearer SYNTHETIC_PRIVATE_KEY"));
        var chat = new ChatGptAgent(new AiProperties("SYNTHETIC_PRIVATE_KEY", ""), rest, mapper);
        var results = java.util.List.of(
            new GeminiAgent(new AiProperties("", "", "SYNTHETIC_PRIVATE_KEY"), rest, mapper).analyze("s", "u"),
            new GrokAgent(new AiProperties("", "SYNTHETIC_PRIVATE_KEY"), rest, mapper).analyze("s", "u"),
            deepSeek("https://api.deepseek.com").analyze("s", "u"),
            chat.analyze("s", "u"), chat.synthesize("s", "u"));
        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo("error");
            assertThat(result.content()).isEmpty();
            assertThat(result.error()).doesNotContain("SYNTHETIC_PRIVATE_KEY", "https://", "Authorization", "key=");
        });
    }
}
