package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionSignalScoringTest {
    @Test
    void strongerDailyGainDoesNotOutrankAnIdenticalEarlyBreakout() {
        var early = ConditionSignalScoring.shortTerm(8, 8, 12, 3, 300_000, 0);
        var withinBand = ConditionSignalScoring.shortTerm(8, 8, 12, 14.9, 300_000, 0);
        var atUpperBound = ConditionSignalScoring.shortTerm(8, 8, 12, 15, 300_000, 0);
        var overheated = ConditionSignalScoring.shortTerm(8, 8, 12, 15.01, 300_000, 0);
        var extreme = ConditionSignalScoring.shortTerm(8, 8, 12, 25, 300_000, 0);

        assertThat(withinBand.finalScore()).isEqualTo(early.finalScore());
        assertThat(atUpperBound.finalScore()).isEqualTo(early.finalScore());
        assertThat(atUpperBound.riskFlags()).doesNotContain("상승률 과열 주의");
        assertThat(overheated.finalScore()).isLessThan(early.finalScore());
        assertThat(overheated.riskFlags()).contains("상승률 과열 주의");
        assertThat(extreme.finalScore()).isLessThan(overheated.finalScore());
    }

    @Test
    void rankThirtyOneThroughEightyDoNotGetTopThirtyPointsOrOverlapBonus() {
        var unranked = ConditionSignalScoring.shortTerm(0, 0, 0, 4, 300_000, 0);
        var topThirty = ConditionSignalScoring.shortTerm(30, 30, 30, 4, 300_000, 0);
        var tail = ConditionSignalScoring.shortTerm(31, 80, 40, 4, 300_000, 0);

        assertThat(tail.finalScore()).isEqualTo(unranked.finalScore());
        assertThat(topThirty.finalScore()).isGreaterThan(tail.finalScore());
        assertThat(tail.evidence()).contains("거래량 상위 31위", "상승률 상위 80위");
    }

    @Test
    void measuredTurnoverRankGetsABoundedBonusAndItsOwnEvidence() {
        var noTurnover = ConditionSignalScoring.shortTerm(0, 0, 0, 4, 300_000, 0);
        var firstTurnover = ConditionSignalScoring.shortTerm(0, 0, 0, 4, 300_000, 0, 1);
        var tailTurnover = ConditionSignalScoring.shortTerm(0, 0, 0, 4, 300_000, 0, 31);

        assertThat(firstTurnover.finalScore() - noTurnover.finalScore()).isBetween(1, 6);
        assertThat(firstTurnover.evidence()).contains("거래대금 상위 1위");
        assertThat(tailTurnover.finalScore()).isEqualTo(noTurnover.finalScore());
    }
}
