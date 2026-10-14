package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionCheck;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConditionCheckTest {
    @Test void allFiveModesHaveFiveCategoriesAndNoInventedPasses() {
        for (String mode : ConditionLiquidityPolicy.RULES.keySet()) {
            var checks = ConditionCheck.unknown(mode);
            assertThat(checks).extracting(ConditionCheck::category)
                .contains("liquidity", "technical", "catalyst", "fundamental", "supply");
            assertThat(checks).allMatch(c -> "UNKNOWN".equals(c.status()));
        }
    }
    @Test void dailyChangeIsOnlyAPartialTechnicalCheckAndNeedsAValidObservation() {
        assertThat(ConditionCheck.withDailyChangeText("BREAKOUT", "--", "KIS", "2026-10-14T12:00:00+09:00"))
            .allMatch(c -> "UNKNOWN".equals(c.status()));
        assertThat(ConditionCheck.withDailyChangeText("BREAKOUT", "-4.2%", "KIS", "2026-10-14T12:00:00+09:00"))
            .anyMatch(c -> "FAIL".equals(c.status()) && c.observedValue().equals("-4.20%"));
        var checks = ConditionCheck.withDailyChange("JONGGA_V2", 5.0, "V2", "2026-10-14T15:00:00+09:00");
        assertThat(checks.stream().filter(c -> "PASS".equals(c.status()))).hasSize(1);
        assertThat(ConditionCheck.withDailyChange("BREAKOUT", 15.01, "KIS", "2026-10-14T12:00:00+09:00"))
            .anyMatch(c -> "FAIL".equals(c.status()));
        for (Double value : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY})
            assertThat(ConditionCheck.withDailyChange("BREAKOUT", value, "KIS", "date"))
                .allMatch(c -> "UNKNOWN".equals(c.status()));
        assertThat(ConditionCheck.withDailyChange("JONGGA_V2", 10.0, "V2", null))
            .allMatch(c -> "UNKNOWN".equals(c.status()));
    }
}
