package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalTime;
import static org.assertj.core.api.Assertions.assertThat;

class ConditionLiquidityPolicyTest {
    private static BigDecimal eok(long value) { return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(100_000_000)); }
    private ConditionLiquidityPolicy.Result check(String mode, long today, Long average, LocalTime time) {
        return ConditionLiquidityPolicy.evaluate(mode, eok(today), average == null ? null : eok(average), time);
    }
    @Test void appliesDifferentMinimumsToAllFiveSections() {
        var close = LocalTime.of(15, 30);
        for (String mode : new String[]{"BREAKOUT", "CATALYST_BURST", "JONGGA_V2"}) {
            assertThat(check(mode, 99, null, close).eligible()).as(mode).isFalse();
            assertThat(check(mode, 100, null, close).eligible()).as(mode).isTrue();
        }
        assertThat(check("REVERSAL_EDGE", 10, 49L, close).eligible()).isFalse();
        assertThat(check("REVERSAL_EDGE", 10, 50L, close).eligible()).isTrue();
        assertThat(check("FLOW_LEADER", 499, 200L, close).eligible()).isFalse();
        assertThat(check("FLOW_LEADER", 500, 199L, close).eligible()).isFalse();
        assertThat(check("FLOW_LEADER", 500, 200L, close).eligible()).isTrue();
    }
    @Test void missingAmountsDoNotPassAndOneWonBelowBoundaryFails() {
        assertThat(ConditionLiquidityPolicy.evaluate("BREAKOUT", null, null, LocalTime.NOON).eligible()).isFalse();
        assertThat(check("REVERSAL_EDGE", 1000, null, LocalTime.NOON).eligible()).isFalse();
        assertThat(check("FLOW_LEADER", 1000, null, LocalTime.NOON).eligible()).isFalse();
        assertThat(ConditionLiquidityPolicy.evaluate("BREAKOUT", eok(100).subtract(BigDecimal.ONE), null, LocalTime.NOON).eligible()).isFalse();
        assertThat(check("BREAKOUT", -1, null, LocalTime.NOON).eligible()).isFalse();
    }
    @Test void morningThresholdsAreExplicitAndClosingBetNeverUsesThem() {
        assertThat(check("BREAKOUT", 30, null, LocalTime.of(9, 59)).eligible()).isTrue();
        assertThat(check("BREAKOUT", 30, null, LocalTime.of(10, 0)).eligible()).isFalse();
        assertThat(check("CATALYST_BURST", 50, null, LocalTime.of(10, 59)).eligible()).isTrue();
        assertThat(check("CATALYST_BURST", 50, null, LocalTime.of(11, 0)).eligible()).isFalse();
        assertThat(check("FLOW_LEADER", 150, 200L, LocalTime.of(9, 30)).eligible()).isTrue();
        assertThat(check("FLOW_LEADER", 250, 200L, LocalTime.of(10, 30)).eligible()).isTrue();
        assertThat(check("JONGGA_V2", 50, null, LocalTime.of(9, 30)).eligible()).isFalse();
        assertThat(check("BREAKOUT", 30, null, LocalTime.of(8, 59)).eligible()).isFalse();
    }
    @Test void awardsOnlyMeasuredAbsoluteAmountBonuses() {
        assertThat(check("BREAKOUT", 299, null, LocalTime.NOON).bonus()).isZero();
        assertThat(check("BREAKOUT", 300, null, LocalTime.NOON).bonus()).isEqualTo(5);
        assertThat(check("REVERSAL_EDGE", 100, 50L, LocalTime.NOON).bonus()).isEqualTo(5);
        assertThat(check("FLOW_LEADER", 1000, 200L, LocalTime.NOON).bonus()).isEqualTo(5);
        assertThat(check("JONGGA_V2", 500, null, LocalTime.NOON).bonus()).isEqualTo(10);
    }
}
