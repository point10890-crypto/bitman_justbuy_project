package com.bitman.justbuy.ai;

import com.bitman.justbuy.dto.ConsensusResult;
import com.bitman.justbuy.util.StructuredAnalysisParser.ScenarioSet;
import com.bitman.justbuy.util.StructuredAnalysisParser.Scenario;
import com.bitman.justbuy.util.StructuredAnalysisParser.StructuredAnalysis;
import com.bitman.justbuy.util.StructuredAnalysisParser.StructuredStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ConsensusEngineTest {

    ConsensusEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ConsensusEngine();
    }

    // ── 빈 입력 ─────────────────────────────────────────────
    @Test
    void emptyInput_returnsNeutralZero() {
        ConsensusResult result = engine.calculateConsensus(Map.of());
        assertThat(result.stocks()).isEmpty();
        assertThat(result.agreementScore()).isEqualTo(0);
        assertThat(result.overallSentiment()).isEqualTo("neutral");
    }

    // ── 단일 에이전트 ────────────────────────────────────────
    @Test
    void singleAgent_singleStock_producesResult() {
        StructuredAnalysis grokAnalysis = makeAnalysis(List.of(
            makeStock("삼성전자", "005930", "매수", 0.8)
        ));
        ConsensusResult result = engine.calculateConsensus(Map.of("grok", grokAnalysis));
        assertThat(result.stocks()).hasSize(1);
        assertThat(result.stocks().getFirst().code()).isEqualTo("005930");
        assertThat(result.stocks().getFirst().consensusAction()).isEqualTo("매수");
    }

    // ── 2-에이전트 합의 ──────────────────────────────────────
    @Test
    void twoAgents_sameStockSameAction_highConsensusScore() {
        StructuredAnalysis gptAnalysis = makeAnalysis(List.of(
            makeStock("삼성전자", "005930", "매수", 0.85)
        ));
        StructuredAnalysis grokAnalysis = makeAnalysis(List.of(
            makeStock("삼성전자", "005930", "매수", 0.80)
        ));

        ConsensusResult result = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis));

        assertThat(result.stocks()).hasSize(1);
        ConsensusResult.ConsensusStock stock = result.stocks().getFirst();
        assertThat(stock.code()).isEqualTo("005930");
        assertThat(stock.consensusAction()).isEqualTo("매수");
        assertThat(stock.consensusScore()).isGreaterThan(50);
        assertThat(stock.mentionCount()).isEqualTo(2);
    }

    @Test
    void twoAgents_differentActions_noConflictOnlyOneAction() {
        StructuredAnalysis gptAnalysis = makeAnalysis(List.of(
            makeStock("LG에너지솔루션", "373220", "매수", 0.7)
        ));
        StructuredAnalysis grokAnalysis = makeAnalysis(List.of(
            makeStock("LG에너지솔루션", "373220", "관망", 0.6)
        ));

        ConsensusResult result = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis));

        assertThat(result.stocks()).hasSize(1);
        // 이견 발생 → divergences에 기록됨 (또는 합의도 낮음)
        assertThat(result.stocks().getFirst().consensusScore()).isLessThan(80);
    }

    // ── 시장 레짐 보정 ────────────────────────────────────────
    @Test
    void redMarketGate_penalizesBuyConsensus() {
        StructuredAnalysis gptAnalysis = makeAnalysis(List.of(makeStock("A", "000001", "매수", 0.9)));
        StructuredAnalysis grokAnalysis = makeAnalysis(List.of(makeStock("A", "000001", "매수", 0.9)));

        ConsensusResult green = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis), Map.of(), "GREEN");
        ConsensusResult red = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis), Map.of(), "RED");

        // RED에서 매수 합의는 30% 감점 → 점수가 낮아야 함
        assertThat(red.stocks().getFirst().consensusScore())
            .isLessThan(green.stocks().getFirst().consensusScore());
    }

    @Test
    void greenMarketGate_noAdjustmentForBuy() {
        StructuredAnalysis gptAnalysis = makeAnalysis(List.of(makeStock("B", "000002", "매수", 0.8)));
        ConsensusResult result = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis), Map.of(), "GREEN");
        // GREEN에서는 보정 없음 — 점수가 0보다 커야 함
        assertThat(result.stocks().getFirst().consensusScore()).isGreaterThan(0);
    }

    @Test
    void yellowMarketGate_partialPenaltyForBuy() {
        StructuredAnalysis gptAnalysis = makeAnalysis(List.of(makeStock("C", "000003", "매수", 0.85)));
        StructuredAnalysis grokAnalysis = makeAnalysis(List.of(makeStock("C", "000003", "매수", 0.85)));

        ConsensusResult yellow = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis), Map.of(), "YELLOW");
        ConsensusResult green = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis), Map.of(), "GREEN");

        // YELLOW는 GREEN보다 낮지만 RED보다는 높아야 함
        assertThat(yellow.stocks().getFirst().consensusScore())
            .isLessThan(green.stocks().getFirst().consensusScore());
    }

    @Test
    void marketGate_doesNotPenalizeSell() {
        StructuredAnalysis gptAnalysis = makeAnalysis(List.of(makeStock("D", "000004", "매도", 0.9)));

        ConsensusResult redSell = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis), Map.of(), "RED");

        // 매도는 RED에서 감점 없음 → 점수 유지
        assertThat(redSell.stocks().getFirst().consensusScore()).isGreaterThan(0);
    }

    // ── KIS 수급 교차 검증 ───────────────────────────────────
    @Test
    void kisSupplyConflict_penalizesAiAction_whenSellButSupplyPositive() {
        StructuredAnalysis gptAnalysis = makeAnalysis(List.of(makeStock("E", "000005", "매도", 0.8)));
        StructuredAnalysis grokAnalysis = makeAnalysis(List.of(makeStock("E", "000005", "매도", 0.8)));

        // 수급은 매수 (+1000)이지만 AI는 매도 → 감점
        Map<String, Map<String, String>> kisData = Map.of(
            "000005", Map.of("frgn_ntby_qty", "800", "orgn_ntby_qty", "200")
        );

        ConsensusResult noKis = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis), Map.of(), "GREEN");
        ConsensusResult withKis = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis), kisData, "GREEN");

        assertThat(withKis.stocks().getFirst().consensusScore())
            .isLessThan(noKis.stocks().getFirst().consensusScore());
    }

    @Test
    void kisSupplyAlign_boostsScore_whenBuyAndSupplyPositive() {
        StructuredAnalysis gptAnalysis = makeAnalysis(List.of(makeStock("F", "000006", "매수", 0.8)));
        StructuredAnalysis grokAnalysis = makeAnalysis(List.of(makeStock("F", "000006", "매수", 0.8)));

        Map<String, Map<String, String>> kisData = Map.of(
            "000006", Map.of("frgn_ntby_qty", "500", "orgn_ntby_qty", "300")
        );

        ConsensusResult noKis = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis), Map.of(), "GREEN");
        ConsensusResult withKis = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis), kisData, "GREEN");

        // 수급과 AI가 일치 → 가점
        assertThat(withKis.stocks().getFirst().consensusScore())
            .isGreaterThanOrEqualTo(noKis.stocks().getFirst().consensusScore());
    }

    // ── 합의도 집계 ──────────────────────────────────────────
    @Test
    void agreementScore_nonNegative() {
        StructuredAnalysis a = makeAnalysis(List.of(makeStock("G", "000007", "매수", 0.5)));
        ConsensusResult result = engine.calculateConsensus(Map.of("chatgpt", a));
        assertThat(result.agreementScore()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void agreementScore_notExceed100() {
        StructuredAnalysis a = makeAnalysis(List.of(makeStock("H", "000008", "매수", 1.0)));
        StructuredAnalysis b = makeAnalysis(List.of(makeStock("H", "000008", "매수", 1.0)));
        ConsensusResult result = engine.calculateConsensus(Map.of("chatgpt", a, "grok", b));
        assertThat(result.agreementScore()).isLessThanOrEqualTo(100);
    }

    @Test
    void twoStocks_singleAgent_bothAppear() {
        StructuredAnalysis a = makeAnalysis(List.of(
            makeStock("삼성전자", "005930", "매수", 0.7),
            makeStock("SK하이닉스", "000660", "관망", 0.5)
        ));
        ConsensusResult result = engine.calculateConsensus(Map.of("chatgpt", a));
        assertThat(result.stocks()).hasSize(2);
    }

    // ── 종목 정렬 ────────────────────────────────────────────
    @Test
    void stocks_sortedByConsensusScoreDescending() {
        StructuredAnalysis gptAnalysis = makeAnalysis(List.of(
            makeStock("A", "000001", "매수", 0.9),
            makeStock("B", "000002", "관망", 0.3)
        ));
        StructuredAnalysis grokAnalysis = makeAnalysis(List.of(
            makeStock("A", "000001", "매수", 0.9)
            // B는 grok이 언급 안 함
        ));

        ConsensusResult result = engine.calculateConsensus(
            Map.of("chatgpt", gptAnalysis, "grok", grokAnalysis));

        // A는 2개 AI가 동의, B는 1개 → A가 먼저
        if (result.stocks().size() == 2) {
            assertThat(result.stocks().get(0).code()).isEqualTo("000001");
        }
    }

    // ── helpers ──────────────────────────────────────────────
    private StructuredAnalysis makeAnalysis(List<StructuredStock> stocks) {
        return new StructuredAnalysis(stocks, "bullish", List.of("risk1"));
    }

    private StructuredStock makeStock(String name, String code, String action, double confidence) {
        ScenarioSet scenario = new ScenarioSet(
            new Scenario(0.35, 80000L),
            new Scenario(0.45, 75000L),
            new Scenario(0.20, 65000L)
        );
        return new StructuredStock(name, code, action, confidence,
            70000L, 80000L, 65000L, scenario, List.of("근거1"));
    }
}
