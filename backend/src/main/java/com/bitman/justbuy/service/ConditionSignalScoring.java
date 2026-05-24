package com.bitman.justbuy.service;

import java.util.ArrayList;
import java.util.List;

final class ConditionSignalScoring {

    private ConditionSignalScoring() {}

    record ScoreBreakdown(
        int ruleScore,
        int aiScore,
        int finalScore,
        List<String> evidence,
        List<String> riskFlags
    ) {}

    static ScoreBreakdown shortTerm(int volumeRank,
                                    int gainerRank,
                                    int foreignRank,
                                    double changeRate,
                                    long volume,
                                    long foreignNetBuy) {
        int score = 40;
        List<String> evidence = new ArrayList<>();
        List<String> riskFlags = new ArrayList<>();

        int volumeScore = rankContribution(volumeRank, 18);
        int momentumRankScore = rankContribution(gainerRank, 18);
        int flowScore = rankContribution(foreignRank, 12);
        int momentumScore = clamp((int) Math.round(Math.max(0, changeRate) * 2.5), 0, 25);
        int liquidityScore = liquidityScore(volume);
        int overlapScore = volumeRank > 0 && gainerRank > 0 ? 8 : 0;

        score += volumeScore + momentumRankScore + flowScore + momentumScore + liquidityScore + overlapScore;

        if (volumeRank > 0) {
            evidence.add("\uAC70\uB798\uB7C9 \uC0C1\uC704 " + volumeRank + "\uC704");
        }
        if (gainerRank > 0) {
            evidence.add("\uC0C1\uC2B9\uB960 \uC0C1\uC704 " + gainerRank + "\uC704");
        }
        if (volumeRank > 0 && gainerRank > 0) {
            evidence.add("\uAC70\uB798\uB7C9\uACFC \uC0C1\uC2B9\uB960 \uB3D9\uC2DC \uD655\uC778");
        }
        if (foreignRank > 0) {
            evidence.add("\uC678\uAD6D\uC778 \uC21C\uB9E4\uC218 \uC0C1\uC704 " + foreignRank + "\uC704");
        }
        if (foreignNetBuy > 0) {
            evidence.add("\uC678\uAD6D\uC778 \uC21C\uB9E4\uC218 " + foreignNetBuy + "\uC8FC");
        }

        int riskPenalty = 0;
        if (changeRate >= 20) {
            riskPenalty += 10;
            riskFlags.add("\uB2E8\uAE30 \uACFC\uC5F4 \uAD6C\uAC04");
        } else if (changeRate >= 15) {
            riskPenalty += 5;
            riskFlags.add("\uC0C1\uC2B9\uB960 \uACFC\uC5F4 \uC8FC\uC758");
        }
        if (volume > 0 && volume < 100_000) {
            riskPenalty += 6;
            riskFlags.add("\uAC70\uB798\uB7C9 \uBD80\uC871");
        }
        if (volumeRank <= 0) {
            riskFlags.add("\uAC70\uB798\uB7C9 \uC21C\uC704 \uBBF8\uD655\uC778");
        }

        score -= riskPenalty;
        int finalScore = clamp(score, 0, 99);
        int aiScore = clamp(40 + flowScore + overlapScore + (foreignNetBuy > 0 ? 8 : 0), 0, 99);
        return new ScoreBreakdown(
            finalScore,
            aiScore,
            finalScore,
            List.copyOf(evidence),
            List.copyOf(riskFlags)
        );
    }

    private static int rankContribution(int rank, int maxPoints) {
        if (rank <= 0) return 0;
        int capped = Math.min(rank, 30);
        return clamp((int) Math.round(maxPoints * ((31D - capped) / 30D)), 0, maxPoints);
    }

    private static int liquidityScore(long volume) {
        if (volume >= 1_000_000) return 5;
        if (volume >= 300_000) return 3;
        if (volume > 0) return 1;
        return 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
