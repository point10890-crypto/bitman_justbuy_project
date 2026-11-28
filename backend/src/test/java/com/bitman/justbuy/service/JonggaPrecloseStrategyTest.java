package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionCheck;
import com.bitman.justbuy.service.JonggaPrecloseStrategy.Facts;
import com.bitman.justbuy.service.JonggaPrecloseStrategy.Minute;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class JonggaPrecloseStrategyTest {
    private static final Instant DECISION = Instant.parse("2026-11-27T06:20:00Z");

    @Test void bothVerifiedSetupsUseCompletedVolumeWithoutRewardingLargerDailyGain() {
        var facts = facts(bars(40, 120), 10_030L);
        var lower = JonggaPrecloseStrategy.evaluate(facts, 3, DECISION);
        var upper = JonggaPrecloseStrategy.evaluate(facts, 12, DECISION);

        assertThat(lower.eligible()).isTrue();
        assertThat(lower.complete()).isTrue();
        assertThat(lower.setups()).containsExactly("STRENGTH_CONTINUATION", "CONFIRMED_20D_BREAKOUT");
        assertThat(lower.score()).isBetween(1, 99).isEqualTo(upper.score());
        assertThat(lower.evidence()).anyMatch(line -> line.contains("종가×거래량 가중평균"));
        assertThat(JonggaPrecloseStrategy.evaluate(facts, 12.001, DECISION).eligible()).isFalse();
    }

    @Test void aMildRiseMayQualifyOnlyViaTheConfirmedBreakout() {
        var verdict = JonggaPrecloseStrategy.evaluate(facts(bars(10, 120), 10_005L), 8, DECISION);
        assertThat(verdict.setups()).containsExactly("CONFIRMED_20D_BREAKOUT");
        assertThat(JonggaPrecloseStrategy.evaluate(facts(bars(10, 119), 10_005L), 8, DECISION).eligible()).isFalse();
    }

    @Test void usesNeitherOpenMinuteNorLaterRowsAndRequiresTheMostRecentWindow() {
        var valid = bars(40, 120);
        var sample = new ArrayList<>(valid);
        sample.add(new Minute(DECISION, 50_000, 1_000_000));
        sample.add(new Minute(DECISION.plusSeconds(60), 80_000, 1_000_000));
        assertThat(JonggaPrecloseStrategy.evaluate(facts(sample, 10_030L), 8, DECISION.plusSeconds(10)))
            .isEqualTo(JonggaPrecloseStrategy.evaluate(facts(valid, 10_030L), 8, DECISION.plusSeconds(10)));

        sample.remove(19);
        var missing = JonggaPrecloseStrategy.evaluate(facts(sample, 10_030L), 8, DECISION);
        assertThat(missing.eligible()).isFalse();
        assertThat(missing.complete()).isFalse();
        assertThat(missing.checks()).anyMatch(check -> check.status().equals("UNKNOWN"));
    }

    @Test void duplicateAndStaleMinuteCannotReplaceAMissingObservation() {
        var sample = new ArrayList<>(bars(40, 120));
        sample.add(sample.get(0));
        var duplicate = JonggaPrecloseStrategy.evaluate(facts(sample, 10_030L), 8, DECISION);
        assertThat(duplicate.setups()).isEmpty();
        assertThat(duplicate.complete()).isFalse();

        var stale = bars(40, 120).stream()
            .map(bar -> new Minute(bar.startedAt().minusSeconds(60), bar.close(), bar.volume())).toList();
        assertThat(JonggaPrecloseStrategy.evaluate(facts(stale, 10_030L), 8, DECISION).complete()).isFalse();
    }

    @Test void unknownPriorHighNeverQualifiesBreakoutButDoesNotEraseMeasuredStrength() {
        var result = JonggaPrecloseStrategy.evaluate(facts(bars(40, 100), null), 8, DECISION);
        assertThat(result.setups()).containsExactly("STRENGTH_CONTINUATION");
        assertThat(result.complete()).isFalse();
        assertThat(result.checks()).filteredOn(check -> check.id().equals("technical.2"))
            .extracting(ConditionCheck::status).containsExactly("UNKNOWN");
    }

    @Test void hardGatesRejectLowTurnoverAndAvoidPromotingMissingMarketFacts() {
        var good = bars(40, 120);
        var lowTurnover = new Facts(9_950, 10_070, 9_999_999_999L, 10_030L, good);
        var unknown = new Facts(9_950, 0, 10_000_000_000L, 10_030L, good);
        var wideHigh = new Facts(9_950, 10_500, 10_000_000_000L, 10_030L, good);

        assertThat(JonggaPrecloseStrategy.evaluate(lowTurnover, 8, DECISION).eligible()).isFalse();
        assertThat(JonggaPrecloseStrategy.evaluate(wideHigh, 8, DECISION).eligible()).isFalse();
        assertThat(JonggaPrecloseStrategy.evaluate(unknown, 8, DECISION).complete()).isFalse();
        assertThat(JonggaPrecloseStrategy.evaluate(facts(good, 10_030L), Double.NaN, DECISION).eligible()).isFalse();
    }

    @Test void inconsistentDayHighOrNonTradingWindowIsUnknown() {
        var sample = new ArrayList<>(bars(40, 120));
        sample.set(5, new Minute(sample.get(5).startedAt(), 10_071, 100));
        var result = JonggaPrecloseStrategy.evaluate(facts(sample, 10_030L), 8, DECISION);
        assertThat(result.eligible()).isFalse();
        assertThat(result.checks()).filteredOn(check -> check.id().equals("technical.0"))
            .extracting(ConditionCheck::status).containsExactly("UNKNOWN");
        assertThat(JonggaPrecloseStrategy.evaluate(facts(bars(40, 120), 10_030L), 8, DECISION.plusSeconds(600))
            .checks()).allMatch(check -> check.status().equals("UNKNOWN"));
    }

    private static Facts facts(List<Minute> bars, Long prior20High) {
        return new Facts(9_950, 10_070, 10_000_000_000L, prior20High, bars);
    }

    private static List<Minute> bars(int rise, long finalVolume) {
        return IntStream.range(0, 20)
            .mapToObj(i -> new Minute(DECISION.minusSeconds((20L - i) * 60),
                10_000 + rise * i / 19, i < 15 ? 100 : finalVolume)).toList();
    }
}
