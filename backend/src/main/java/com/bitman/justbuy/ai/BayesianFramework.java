package com.bitman.justbuy.ai;

import java.util.List;
import java.util.Map;

/**
 * 베이지안 추론 프레임워크.
 * 정보 출처별 신뢰도 가중치 기반으로 사후확률을 계산한다.
 */
public class BayesianFramework {

    /** 소스 신뢰도 등급 */
    public enum SourceGrade { S, A, B, C, D }

    /** 소스 등급별 기본 신뢰도 */
    public static final Map<SourceGrade, Double> SOURCE_RELIABILITY = Map.of(
        SourceGrade.S, 0.925,
        SourceGrade.A, 0.825,
        SourceGrade.B, 0.725,
        SourceGrade.C, 0.575,
        SourceGrade.D, 0.275
    );

    /** 상세 소스 신뢰도 맵 */
    public static final Map<String, SourceInfo> DETAILED_SOURCE_RELIABILITY = Map.ofEntries(
        Map.entry("거래소_공시", new SourceInfo(SourceGrade.S, 0.95)),
        Map.entry("증권사_리서치", new SourceInfo(SourceGrade.S, 0.90)),
        Map.entry("전문_애널리스트", new SourceInfo(SourceGrade.A, 0.85)),
        Map.entry("기관투자자_보고서", new SourceInfo(SourceGrade.A, 0.80)),
        Map.entry("업계_전문매체", new SourceInfo(SourceGrade.B, 0.75)),
        Map.entry("경제_미디어", new SourceInfo(SourceGrade.B, 0.70)),
        Map.entry("종합_경제지", new SourceInfo(SourceGrade.C, 0.60)),
        Map.entry("일반_뉴스", new SourceInfo(SourceGrade.C, 0.55)),
        Map.entry("SNS_블로그", new SourceInfo(SourceGrade.D, 0.30)),
        Map.entry("커뮤니티", new SourceInfo(SourceGrade.D, 0.25))
    );

    public record SourceInfo(SourceGrade grade, double reliability) {}

    /** 증거 항목 */
    public record EvidenceItem(
        String description,
        String source,
        SourceGrade sourceGrade,
        double reliability,
        Direction direction,
        double strength
    ) {}

    public enum Direction { SUPPORTING, OPPOSING, NEUTRAL }

    /** 베이지안 평가 결과 */
    public record BayesianAssessment(
        String hypothesis,
        double priorProbability,
        List<EvidenceItem> evidenceItems,
        double posteriorProbability,
        double confidenceLow,
        double confidenceHigh,
        String reasoning
    ) {}

    /**
     * 간소화된 베이지안 업데이트.
     * Prior odds × ∏(likelihood ratios) = Posterior odds → 확률로 변환
     */
    public static double updatePosterior(double prior, List<EvidenceItem> evidence) {
        if (evidence.isEmpty()) return prior;

        double odds = prior / (1.0 - Math.max(prior, 0.001));

        for (EvidenceItem e : evidence) {
            double impact = e.reliability() * e.strength();
            double likelihoodRatio;

            switch (e.direction()) {
                case SUPPORTING -> likelihoodRatio = 1.0 + impact;
                case OPPOSING -> likelihoodRatio = 1.0 / (1.0 + impact);
                default -> likelihoodRatio = 1.0;
            }

            odds *= likelihoodRatio;
        }

        double posterior = odds / (1.0 + odds);
        return Math.max(0.01, Math.min(0.99, posterior));
    }

    /**
     * 베이지안 평가를 수행하고 결과를 반환.
     */
    public static BayesianAssessment performAssessment(
            String hypothesis, double prior, List<EvidenceItem> evidence) {

        double posterior = updatePosterior(prior, evidence);

        // 신뢰구간
        double avgReliability = evidence.isEmpty() ? 0
            : evidence.stream().mapToDouble(EvidenceItem::reliability).average().orElse(0);
        double intervalWidth = Math.max(0.05,
            0.3 * (1.0 - avgReliability) * (1.0 / Math.max(evidence.size(), 1)));

        // 추론 설명
        long supporting = evidence.stream().filter(e -> e.direction() == Direction.SUPPORTING).count();
        long opposing = evidence.stream().filter(e -> e.direction() == Direction.OPPOSING).count();

        StringBuilder reasoning = new StringBuilder();
        reasoning.append(String.format("사전확률 %.0f%% → 사후확률 %.0f%%\n",
            prior * 100, posterior * 100));
        reasoning.append(String.format("증거 %d건 (지지 %d건, 반대 %d건)\n",
            evidence.size(), supporting, opposing));

        if (supporting > 0) {
            reasoning.append("주요 지지 증거: ").append(
                evidence.stream()
                    .filter(e -> e.direction() == Direction.SUPPORTING)
                    .limit(2)
                    .map(EvidenceItem::description)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("")
            ).append("\n");
        }
        if (opposing > 0) {
            reasoning.append("주요 반대 증거: ").append(
                evidence.stream()
                    .filter(e -> e.direction() == Direction.OPPOSING)
                    .limit(2)
                    .map(EvidenceItem::description)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("")
            ).append("\n");
        }

        return new BayesianAssessment(
            hypothesis, prior, evidence, posterior,
            Math.max(0, posterior - intervalWidth),
            Math.min(1, posterior + intervalWidth),
            reasoning.toString()
        );
    }
}
