package com.bitman.justbuy.dto;

import java.util.List;
import java.util.Map;

/**
 * 에이전트 간 합의 분석 결과 DTO.
 */
public record ConsensusResult(
    List<ConsensusStock> stocks,
    String overallSentiment,     // bullish|neutral|bearish
    int agreementScore,          // 0-100
    List<Divergence> divergences,
    int agentCount
) {

    /** 종목별 합의 정보 */
    public record ConsensusStock(
        String name,
        String code,
        String consensusAction,    // 매수|매도|관망|주목
        int consensusScore,        // 0-100
        int mentionCount,
        Map<String, AgentVote> agentVotes,
        double averageConfidence,
        ScenarioConsensus scenarioConsensus,
        Long avgTargetPrice,
        Long avgStopLoss
    ) {}

    /** 에이전트 개별 투표 */
    public record AgentVote(
        String action,
        double confidence,
        Long targetPrice,
        Long stopLoss
    ) {}

    /** 시나리오 합의 */
    public record ScenarioConsensus(
        ScenarioAvg bull,
        ScenarioAvg base,
        ScenarioAvg bear
    ) {}

    public record ScenarioAvg(double avgProbability, Long avgTarget) {}

    /** 에이전트 간 의견 충돌 */
    public record Divergence(
        String stockCode,
        String stockName,
        String type,            // action_conflict | target_divergence
        List<String> agents,
        String details
    ) {}
}
