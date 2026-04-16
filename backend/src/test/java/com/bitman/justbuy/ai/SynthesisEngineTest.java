package com.bitman.justbuy.ai;

import com.bitman.justbuy.ai.agent.ChatGptAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.StockPick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SynthesisEngineTest {

    @Mock ChatGptAgent chatGptAgent;
    SynthesisEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SynthesisEngine(chatGptAgent);
    }

    // ── isAvailable ──────────────────────────────────────────
    @Test
    void isAvailable_delegatesToChatGptAgent() {
        when(chatGptAgent.isAvailable()).thenReturn(true);
        assertThat(engine.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_false_whenChatGptNotAvailable() {
        when(chatGptAgent.isAvailable()).thenReturn(false);
        assertThat(engine.isAvailable()).isFalse();
    }

    // ── synthesize calls chatGptAgent.synthesize() ────────────
    @Test
    void synthesize_callsChatGptSynthesize() {
        AgentResult mockResult = successResult("chatgpt", "합성 결과", "gpt-4o");
        when(chatGptAgent.synthesize(any(), any())).thenReturn(mockResult);

        AgentResult r1 = successResult("chatgpt", "GPT 분석", "gpt-4o-search-preview");
        AgentResult r2 = successResult("grok", "Grok 분석", "grok-4");

        engine.synthesizeWithResult(List.of(r1, r2), "기술적 돌파", "BREAKOUT", "2026-04-03");

        verify(chatGptAgent).synthesize(anyString(), anyString());
        verify(chatGptAgent, never()).analyze(anyString(), anyString());
    }

    @Test
    void synthesize_doesNotCallChatGptAnalyze() {
        when(chatGptAgent.synthesize(any(), any())).thenReturn(successResult("chatgpt", "ok", "gpt-4o"));
        engine.synthesizeWithResult(List.of(successResult("grok", "ok", "grok-4")), "q", "m", "2026-04-03");
        verify(chatGptAgent, never()).analyze(anyString(), anyString());
    }

    @Test
    void synthesize_returnsResultFromChatGpt() {
        AgentResult expected = successResult("chatgpt", "최종 분석 결과", "gpt-4o");
        when(chatGptAgent.synthesize(any(), any())).thenReturn(expected);

        AgentResult result = engine.synthesizeWithResult(
            List.of(successResult("grok", "grok분석", "grok-4")),
            "삼성전자", "분석해줘", "2026-04-03");

        assertThat(result.content()).isEqualTo("최종 분석 결과");
        assertThat(result.status()).isEqualTo("success");
    }

    // ── prompt injection checks ──────────────────────────────
    @Test
    void synthesize_injectsModeInPrompt() {
        when(chatGptAgent.synthesize(any(), anyString())).thenReturn(successResult("chatgpt", "ok", "gpt-4o"));
        ArgumentCaptor<String> userMsgCaptor = ArgumentCaptor.forClass(String.class);

        engine.synthesizeWithResult(
            List.of(successResult("chatgpt", "분석", "gpt-4o")),
            "쿼리", "수급분석", "2026-04-03");

        verify(chatGptAgent).synthesize(anyString(), userMsgCaptor.capture());
        assertThat(userMsgCaptor.getValue()).contains("수급분석");
    }

    @Test
    void synthesize_injectsQueryInPrompt() {
        when(chatGptAgent.synthesize(any(), anyString())).thenReturn(successResult("chatgpt", "ok", "gpt-4o"));
        ArgumentCaptor<String> userMsgCaptor = ArgumentCaptor.forClass(String.class);

        engine.synthesizeWithResult(
            List.of(successResult("grok", "분석", "grok-4")),
            "삼성전자 분석해줘", "분석해줘", "2026-04-03");

        verify(chatGptAgent).synthesize(anyString(), userMsgCaptor.capture());
        assertThat(userMsgCaptor.getValue()).contains("삼성전자");
    }

    @Test
    void synthesize_injectsTodayInPrompt() {
        when(chatGptAgent.synthesize(any(), anyString())).thenReturn(successResult("chatgpt", "ok", "gpt-4o"));
        ArgumentCaptor<String> userMsgCaptor = ArgumentCaptor.forClass(String.class);

        engine.synthesizeWithResult(
            List.of(successResult("chatgpt", "분석", "gpt-4o")),
            "q", "m", "2026-04-03");

        verify(chatGptAgent).synthesize(anyString(), userMsgCaptor.capture());
        assertThat(userMsgCaptor.getValue()).contains("2026-04-03");
    }

    @Test
    void synthesize_grokDataPriorityMentioned_whenGrokSucceeded() {
        when(chatGptAgent.synthesize(any(), anyString())).thenReturn(successResult("chatgpt", "ok", "gpt-4o"));
        ArgumentCaptor<String> userMsgCaptor = ArgumentCaptor.forClass(String.class);

        engine.synthesizeWithResult(
            List.of(successResult("grok", "grok분석", "grok-4")),
            "q", "m", "2026-04-03");

        verify(chatGptAgent).synthesize(anyString(), userMsgCaptor.capture());
        assertThat(userMsgCaptor.getValue()).containsAnyOf("Grok", "GROK");
    }

    @Test
    void synthesize_supplyDataRule_inSupplyMode() {
        when(chatGptAgent.synthesize(any(), anyString())).thenReturn(successResult("chatgpt", "ok", "gpt-4o"));
        ArgumentCaptor<String> userMsgCaptor = ArgumentCaptor.forClass(String.class);

        engine.synthesizeWithResult(
            List.of(successResult("grok", "수급 데이터", "grok-4")),
            "수급 쿼리", "수급분석", "2026-04-03");

        verify(chatGptAgent).synthesize(anyString(), userMsgCaptor.capture());
        String msg = userMsgCaptor.getValue();
        assertThat(msg).containsAnyOf("수급분석 종합", "GROK 검색", "수급 데이터 우선");
    }

    // ── realPrices injection ─────────────────────────────────
    @Test
    void synthesize_injectsRealPricesBlock_whenProvided() {
        when(chatGptAgent.synthesize(any(), anyString())).thenReturn(successResult("chatgpt", "ok", "gpt-4o"));
        ArgumentCaptor<String> userMsgCaptor = ArgumentCaptor.forClass(String.class);

        StockPick pick = new StockPick("삼성전자", "005930", null, null, null, "매수", null);
        Map<String, String> prices = Map.of("005930", "78000");

        engine.synthesizeWithResult(
            List.of(successResult("chatgpt", "분석", "gpt-4o")),
            "q", "m", "2026-04-03", "", List.of(pick), prices);

        verify(chatGptAgent).synthesize(anyString(), userMsgCaptor.capture());
        assertThat(userMsgCaptor.getValue()).contains("78000");
        assertThat(userMsgCaptor.getValue()).contains("005930");
    }

    @Test
    void synthesize_noRealPricesBlock_whenEmpty() {
        when(chatGptAgent.synthesize(any(), anyString())).thenReturn(successResult("chatgpt", "ok", "gpt-4o"));
        ArgumentCaptor<String> userMsgCaptor = ArgumentCaptor.forClass(String.class);

        engine.synthesizeWithResult(
            List.of(successResult("chatgpt", "분석", "gpt-4o")),
            "q", "m", "2026-04-03", "", List.of(), Map.of());

        verify(chatGptAgent).synthesize(anyString(), userMsgCaptor.capture());
        assertThat(userMsgCaptor.getValue()).doesNotContain("실시간 시세 참조");
    }

    // ── helpers ──────────────────────────────────────────────
    private AgentResult successResult(String agent, String content, String model) {
        return new AgentResult(agent, content, model, 100, 200, "success", null, 500L);
    }
}
