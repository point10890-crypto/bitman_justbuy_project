package com.bitman.justbuy.service;

import java.util.ArrayList;
import java.util.List;

final class ConditionSignalScoring {

    private static final int RANK_BONUS_LIMIT = 30;
    private static final double SHORT_TERM_ENTRY_GAIN = 3.0;
    private static final double SHORT_TERM_UPPER_GAIN = 15.0;

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
        return shortTerm(volumeRank, gainerRank, foreignRank, changeRate, volume, foreignNetBuy, 0);
    }

    static ScoreBreakdown shortTerm(int volumeRank,
                                    int gainerRank,
                                    int foreignRank,
                                    double changeRate,
                                    long volume,
                                    long foreignNetBuy,
                                    int turnoverRank) {
        int score = 40;
        List<String> evidence = new ArrayList<>();
        List<String> riskFlags = new ArrayList<>();

        int volumeScore = shortTermRankContribution(volumeRank, 18);
        int momentumRankScore = shortTermRankContribution(gainerRank, 18);
        int flowScore = shortTermRankContribution(foreignRank, 12);
        int turnoverScore = shortTermRankContribution(turnoverRank, 6);
        // The entry band confirms direction. A larger daily gain inside it earns no extra points.
        double scoredChange = changeRate > SHORT_TERM_UPPER_GAIN ? 0 : Math.min(Math.max(0, changeRate), SHORT_TERM_ENTRY_GAIN);
        int momentumScore = (int) Math.round(scoredChange * 2.5);
        int liquidityScore = liquidityScore(volume);
        int overlapScore = shortTermTopRank(volumeRank) && shortTermTopRank(gainerRank) ? 8 : 0;

        score += volumeScore + momentumRankScore + flowScore + turnoverScore + momentumScore + liquidityScore + overlapScore;

        if (volumeRank > 0) {
            evidence.add("\uAC70\uB798\uB7C9 \uC0C1\uC704 " + volumeRank + "\uC704");
        }
        if (gainerRank > 0) {
            evidence.add("\uC0C1\uC2B9\uB960 \uC0C1\uC704 " + gainerRank + "\uC704");
        }
        if (turnoverRank > 0) {
            evidence.add("거래대금 상위 " + turnoverRank + "위");
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
        } else if (changeRate > SHORT_TERM_UPPER_GAIN) {
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

    static ScoreBreakdown leader(int rank,
                                 double changeRate,
                                 long foreignNet,
                                 long institutionNet,
                                 long totalSmartNet) {
        int score = 40;
        List<String> evidence = new ArrayList<>();
        List<String> riskFlags = new ArrayList<>();

        boolean doubleBuy = foreignNet > 0 && institutionNet > 0;
        int doubleBuyScore = doubleBuy ? 22 : 0;
        int flowMagnitude = magnitudeScore(totalSmartNet);
        int rankScore = rankContribution(rank, 18);
        int momentumScore = clamp((int) Math.round(Math.max(0, changeRate) * 1.5), 0, 12);

        score += doubleBuyScore + flowMagnitude + rankScore + momentumScore;

        if (doubleBuy) {
            evidence.add("외국인+기관 쌍끌이 순매수");
        }
        if (rank > 0) {
            evidence.add("스마트머니 순매수 상위 " + rank + "위");
        }
        if (foreignNet > 0) {
            evidence.add("외국인 순매수 " + foreignNet + "주");
        }
        if (institutionNet > 0) {
            evidence.add("기관 순매수 " + institutionNet + "주");
        }
        if (changeRate > 0) {
            evidence.add("당일 등락률 " + formatPercent(changeRate));
        }

        int riskPenalty = 0;
        if (changeRate >= 20) {
            riskPenalty += 10;
            riskFlags.add("과열 구간 — 추격 주의");
        } else if (changeRate >= 12) {
            riskPenalty += 4;
            riskFlags.add("단기 상승폭 확대");
        }
        if (!doubleBuy) {
            riskFlags.add("쌍끌이 미확인 — 단독 수급");
        }
        riskFlags.add("수급 이탈 시 주도주 지위 약화");

        score -= riskPenalty;
        int finalScore = clamp(score, 0, 99);
        int aiScore = clamp(45 + doubleBuyScore / 2 + flowMagnitude, 0, 99);
        return new ScoreBreakdown(
            finalScore,
            aiScore,
            finalScore,
            List.copyOf(evidence),
            List.copyOf(riskFlags)
        );
    }

    private static int magnitudeScore(long smartNet) {
        long net = Math.abs(smartNet);
        if (net >= 1_000_000) return 18;
        if (net >= 300_000) return 12;
        if (net >= 50_000) return 6;
        if (net > 0) return 2;
        return 0;
    }

    private static String formatPercent(double value) {
        return (value >= 0 ? "+" : "") + String.format(java.util.Locale.US, "%.2f", value) + "%";
    }

    private static int rankContribution(int rank, int maxPoints) {
        if (rank <= 0) return 0;
        int capped = Math.min(rank, RANK_BONUS_LIMIT);
        return clamp((int) Math.round(maxPoints * ((RANK_BONUS_LIMIT + 1D - capped) / RANK_BONUS_LIMIT)), 0, maxPoints);
    }

    private static boolean shortTermTopRank(int rank) {
        return rank > 0 && rank <= RANK_BONUS_LIMIT;
    }

    private static int shortTermRankContribution(int rank, int maxPoints) {
        return shortTermTopRank(rank) ? rankContribution(rank, maxPoints) : 0;
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
