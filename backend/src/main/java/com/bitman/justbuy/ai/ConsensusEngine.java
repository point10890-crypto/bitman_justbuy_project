package com.bitman.justbuy.ai;

import com.bitman.justbuy.dto.ConsensusResult;
import com.bitman.justbuy.dto.ConsensusResult.*;
import com.bitman.justbuy.util.StructuredAnalysisParser.StructuredAnalysis;
import com.bitman.justbuy.util.StructuredAnalysisParser.StructuredStock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 합의 엔진 — 에이전트 간 합의/이견 분석.
 */
@Component
public class ConsensusEngine {

    private static final Logger log = LoggerFactory.getLogger(ConsensusEngine.class);

    /** 에이전트 역할별 기본 가중치 */
    private static final Map<String, Double> AGENT_BASE_WEIGHTS = Map.of(
        "claude", 1.2,
        "gemini", 1.0,
        "chatgpt", 1.1,
        "perplexity", 1.15,
        "grok", 0.9
    );

    /**
     * 여러 에이전트의 구조화 분석 결과로부터 합의를 계산한다.
     */
    public ConsensusResult calculateConsensus(Map<String, StructuredAnalysis> agentResults) {
        int agentCount = agentResults.size();
        if (agentCount == 0) {
            return new ConsensusResult(List.of(), "neutral", 0, List.of(), 0);
        }

        // 1. 모든 종목을 코드별로 집계
        Map<String, StockAggregate> stockMap = new LinkedHashMap<>();
        for (var entry : agentResults.entrySet()) {
            String agent = entry.getKey();
            StructuredAnalysis analysis = entry.getValue();
            for (StructuredStock stock : analysis.stocks()) {
                stockMap.computeIfAbsent(stock.code(), k -> new StockAggregate(stock.name()))
                    .entries.add(new AgentEntry(agent, stock));
            }
        }

        // 2. 종목별 합의 계산
        List<ConsensusStock> consensusStocks = new ArrayList<>();
        List<Divergence> divergences = new ArrayList<>();

        for (var mapEntry : stockMap.entrySet()) {
            String code = mapEntry.getKey();
            StockAggregate agg = mapEntry.getValue();
            int mentionCount = agg.entries.size();

            // 에이전트별 투표
            Map<String, AgentVote> agentVotes = new LinkedHashMap<>();
            Map<String, Double> actionWeights = new HashMap<>();
            double totalWeight = 0;
            double weightedConfidence = 0;

            for (AgentEntry ae : agg.entries) {
                double weight = AGENT_BASE_WEIGHTS.getOrDefault(ae.agent, 1.0);
                agentVotes.put(ae.agent, new AgentVote(
                    ae.stock.action(), ae.stock.confidence(),
                    ae.stock.targetPrice(), ae.stock.stopLoss()));

                actionWeights.merge(ae.stock.action(), weight, Double::sum);
                weightedConfidence += ae.stock.confidence() * weight;
                totalWeight += weight;
            }

            // 합의 액션 결정
            String consensusAction = actionWeights.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("주목");

            double actionAgreement = actionWeights.getOrDefault(consensusAction, 0.0) / Math.max(totalWeight, 0.001);
            double mentionRatio = (double) mentionCount / agentCount;
            int consensusScore = (int) Math.round(actionAgreement * mentionRatio * 100);
            double avgConfidence = totalWeight > 0 ? weightedConfidence / totalWeight : 0;

            // 시나리오 합산
            ScenarioConsensus scenarioConsensus = calculateScenarioConsensus(agg.entries);

            // 평균 목표가/손절가
            List<Long> targets = agg.entries.stream()
                .map(e -> e.stock.targetPrice()).filter(Objects::nonNull).toList();
            List<Long> stops = agg.entries.stream()
                .map(e -> e.stock.stopLoss()).filter(Objects::nonNull).toList();

            Long avgTarget = targets.isEmpty() ? null : Math.round(targets.stream().mapToLong(Long::longValue).average().orElse(0));
            Long avgStop = stops.isEmpty() ? null : Math.round(stops.stream().mapToLong(Long::longValue).average().orElse(0));

            consensusStocks.add(new ConsensusStock(
                agg.name, code, consensusAction, consensusScore, mentionCount,
                agentVotes, avgConfidence, scenarioConsensus, avgTarget, avgStop));

            // 이견 탐지
            detectDivergences(code, agg, targets, divergences);
        }

        // 합의 점수 순 정렬
        consensusStocks.sort(Comparator.comparingInt(ConsensusStock::consensusScore).reversed());

        // 3. 전체 센티먼트
        String overallSentiment = calculateOverallSentiment(agentResults);

        // 4. 전체 합의도
        int agreementScore = consensusStocks.isEmpty() ? 0 :
            (int) Math.round(consensusStocks.stream().mapToInt(ConsensusStock::consensusScore).average().orElse(0));

        return new ConsensusResult(consensusStocks, overallSentiment, agreementScore, divergences, agentCount);
    }

    /**
     * 합의 결과를 Claude 합성 프롬프트에 전달할 텍스트로 포맷팅
     */
    public String formatForSynthesis(ConsensusResult consensus) {
        if (consensus.stocks().isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n📊 [자동 합의 분석 결과]\n");
        sb.append("전체 합의도: ").append(consensus.agreementScore())
          .append("% | 시장 센티먼트: ").append(consensus.overallSentiment())
          .append(" | 참여 에이전트: ").append(consensus.agentCount()).append("개\n\n");
        sb.append("■ 합의 종목 (합의 점수 순):\n");

        for (ConsensusStock stock : consensus.stocks().stream().limit(10).toList()) {
            String votes = stock.agentVotes().entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().action() +
                    "(" + Math.round(e.getValue().confidence() * 100) + "%)")
                .collect(Collectors.joining(", "));

            sb.append("  ").append(stock.name()).append("(").append(stock.code())
              .append(") — 합의: ").append(stock.consensusAction())
              .append(" [").append(stock.consensusScore()).append("점] | ")
              .append(stock.mentionCount()).append("/").append(consensus.agentCount()).append(" 언급\n");
            sb.append("    투표: ").append(votes).append("\n");

            if (stock.scenarioConsensus() != null) {
                ScenarioConsensus sc = stock.scenarioConsensus();
                sb.append("    시나리오: 강세").append(Math.round(sc.bull().avgProbability() * 100))
                  .append("% / 기준").append(Math.round(sc.base().avgProbability() * 100))
                  .append("% / 약세").append(Math.round(sc.bear().avgProbability() * 100)).append("%\n");
            }
            if (stock.avgTargetPrice() != null) {
                sb.append("    평균 목표가: ").append(String.format("%,d", stock.avgTargetPrice())).append("원");
                if (stock.avgStopLoss() != null) {
                    sb.append(" | 평균 손절가: ").append(String.format("%,d", stock.avgStopLoss())).append("원");
                }
                sb.append("\n");
            }
        }

        if (!consensus.divergences().isEmpty()) {
            sb.append("\n■ 주요 이견:\n");
            for (Divergence div : consensus.divergences()) {
                sb.append("  ⚡ ").append(div.stockName()).append("(").append(div.stockCode())
                  .append("): ").append(div.details()).append("\n");
            }
        }

        sb.append("\n위 합의 데이터를 참고하여:\n");
        sb.append("1. 합의 점수 70% 이상 종목은 높은 신뢰도로 추천\n");
        sb.append("2. 이견이 있는 종목은 양쪽 근거를 분석하여 판정\n");
        sb.append("3. 시나리오 확률은 에이전트 합산 기반으로 최종 조정\n");

        return sb.toString();
    }

    // --- Private helpers ---

    private ScenarioConsensus calculateScenarioConsensus(List<AgentEntry> entries) {
        List<AgentEntry> withScenarios = entries.stream()
            .filter(e -> e.stock.scenario() != null).toList();
        if (withScenarios.isEmpty()) return null;

        double bullProb = 0, baseProb = 0, bearProb = 0;
        long bullTarget = 0, baseTarget = 0, bearTarget = 0;
        int bullCnt = 0, baseCnt = 0, bearCnt = 0;

        for (AgentEntry ae : withScenarios) {
            var sc = ae.stock.scenario();
            bullProb += sc.bull().probability();
            baseProb += sc.base().probability();
            bearProb += sc.bear().probability();
            if (sc.bull().target() != null) { bullTarget += sc.bull().target(); bullCnt++; }
            if (sc.base().target() != null) { baseTarget += sc.base().target(); baseCnt++; }
            if (sc.bear().target() != null) { bearTarget += sc.bear().target(); bearCnt++; }
        }

        int n = withScenarios.size();
        return new ScenarioConsensus(
            new ScenarioAvg(bullProb / n, bullCnt > 0 ? bullTarget / bullCnt : null),
            new ScenarioAvg(baseProb / n, baseCnt > 0 ? baseTarget / baseCnt : null),
            new ScenarioAvg(bearProb / n, bearCnt > 0 ? bearTarget / bearCnt : null)
        );
    }

    private String calculateOverallSentiment(Map<String, StructuredAnalysis> results) {
        double totalScore = 0;
        double totalWeight = 0;
        for (var entry : results.entrySet()) {
            double weight = AGENT_BASE_WEIGHTS.getOrDefault(entry.getKey(), 1.0);
            String sentiment = entry.getValue().marketSentiment();
            double score = "bullish".equals(sentiment) ? 1 : "bearish".equals(sentiment) ? -1 : 0;
            totalScore += score * weight;
            totalWeight += weight;
        }
        double avg = totalWeight > 0 ? totalScore / totalWeight : 0;
        return avg > 0.3 ? "bullish" : avg < -0.3 ? "bearish" : "neutral";
    }

    private void detectDivergences(String code, StockAggregate agg, List<Long> targets, List<Divergence> divergences) {
        // 매수 vs 매도 충돌
        List<AgentEntry> buyers = agg.entries.stream().filter(e -> "매수".equals(e.stock.action())).toList();
        List<AgentEntry> sellers = agg.entries.stream().filter(e -> "매도".equals(e.stock.action())).toList();
        if (!buyers.isEmpty() && !sellers.isEmpty()) {
            divergences.add(new Divergence(code, agg.name, "action_conflict",
                List.of(buyers.getFirst().agent, sellers.getFirst().agent),
                buyers.getFirst().agent + "(매수) vs " + sellers.getFirst().agent + "(매도) — 투자 판단 대립"));
        }

        // 목표가 30% 이상 괴리
        if (targets.size() >= 2) {
            long maxT = targets.stream().mapToLong(Long::longValue).max().orElse(0);
            long minT = targets.stream().mapToLong(Long::longValue).min().orElse(0);
            if (minT > 0 && (double)(maxT - minT) / minT > 0.3) {
                Optional<AgentEntry> maxAgent = agg.entries.stream().filter(e -> e.stock.targetPrice() != null && e.stock.targetPrice() == maxT).findFirst();
                Optional<AgentEntry> minAgent = agg.entries.stream().filter(e -> e.stock.targetPrice() != null && e.stock.targetPrice() == minT).findFirst();
                if (maxAgent.isPresent() && minAgent.isPresent()) {
                    divergences.add(new Divergence(code, agg.name, "target_divergence",
                        List.of(maxAgent.get().agent, minAgent.get().agent),
                        String.format("목표가 괴리: %s(%,d원) vs %s(%,d원)",
                            maxAgent.get().agent, maxT, minAgent.get().agent, minT)));
                }
            }
        }
    }

    private static class StockAggregate {
        final String name;
        final List<AgentEntry> entries = new ArrayList<>();
        StockAggregate(String name) { this.name = name; }
    }

    private record AgentEntry(String agent, StructuredStock stock) {}
}
