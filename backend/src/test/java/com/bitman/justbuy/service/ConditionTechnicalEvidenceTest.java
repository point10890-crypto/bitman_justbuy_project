package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionCheck;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ConditionTechnicalEvidenceTest {
    @Test void realKisPanoceanSnapshotConfirmsBreakoutWithoutClaimingUnobservedFacts() throws Exception {
        Map<LocalDate, ConditionTechnicalEvidence.Bar> rows = new HashMap<>();
        try (var stream = getClass().getResourceAsStream("/condition/panocean-kis-20261014.csv")) {
            var lines = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).toList();
            for (String line : lines) {
                var v = line.split(",");
                rows.put(LocalDate.parse(v[0], java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                    new ConditionTechnicalEvidence.Bar(Double.valueOf(v[1]), Double.valueOf(v[2]),
                        Double.valueOf(v[3]), Double.valueOf(v[4]), Double.valueOf(v[5])));
            }
        }
        var checks = ConditionTechnicalEvidence.evaluate("BREAKOUT", rows, day, Instant.parse("2026-10-14T12:00:00Z"));
        assertThat(check(checks, "technical.0").status()).isEqualTo("PASS");
        assertThat(check(checks, "technical.1").observedValue()).contains("7230원", "6630원", "6600원");
        assertThat(check(checks, "technical.2").observedValue()).contains("+10.38%");
        assertThat(check(checks, "technical.3").status()).isEqualTo("UNKNOWN");
    }
    final LocalDate day = LocalDate.of(2026, 10, 14);
    final Instant noon = Instant.parse("2026-10-14T03:00:00Z");

    Map<LocalDate, ConditionTechnicalEvidence.Bar> bars() {
        Map<LocalDate, ConditionTechnicalEvidence.Bar> bars = new HashMap<>();
        bars.put(day, new ConditionTechnicalEvidence.Bar(104d, 112d, 103d, 110d, 300d));
        LocalDate d = day;
        for (int i = 0; i < 60; i++) {
            do { d = d.minusDays(1); } while (!KoreanMarketCalendar.isTradingDay(d));
            double close = i < 5 ? 100 : i < 20 ? 95 : 90;
            bars.put(d, new ConditionTechnicalEvidence.Bar(close, close + 2, close - 2, close, 100d));
        }
        return bars;
    }
    ConditionCheck check(List<ConditionCheck> checks, String id) {
        return checks.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
    @Test void breakoutMeasuresBothOpenAndPreviousHighAndKeepsUnobservedVwapUnknown() {
        var checks = ConditionTechnicalEvidence.evaluate("BREAKOUT", bars(), day, noon);
        assertThat(check(checks, "technical.0").status()).isEqualTo("PASS");
        assertThat(check(checks, "technical.1").status()).isEqualTo("PASS");
        assertThat(check(checks, "technical.2").status()).isEqualTo("PASS");
        assertThat(check(checks, "technical.3").status()).isEqualTo("UNKNOWN");
        assertThat(check(checks, "supply.0").status()).isEqualTo("UNKNOWN");
        var rows = bars();
        rows.put(day, new ConditionTechnicalEvidence.Bar(111d, 112d, 103d, 110d, 300d));
        assertThat(check(ConditionTechnicalEvidence.evaluate("BREAKOUT", rows, day, noon), "technical.1").status()).isEqualTo("FAIL");
    }
    @Test void averagesRequireAllTradingDaysAndExcludeTheCurrentBar() {
        var rows = bars();
        var checks = ConditionTechnicalEvidence.evaluate("REVERSAL_EDGE", rows, day, noon);
        assertThat(check(checks, "technical.0").status()).isEqualTo("PASS");
        rows.remove(day.minusDays(1));
        assertThat(check(ConditionTechnicalEvidence.evaluate("REVERSAL_EDGE", rows, day, noon), "technical.0").status()).isEqualTo("UNKNOWN");
        assertThat(check(ConditionTechnicalEvidence.evaluate("BREAKOUT", rows, day, noon), "technical.2").status()).isEqualTo("UNKNOWN");
    }
    @Test void intradayHighCannotBeCalledConfirmedClosingStrength() {
        var checks = ConditionTechnicalEvidence.evaluate("JONGGA_V2", bars(), day, noon);
        assertThat(check(checks, "technical.0").status()).isEqualTo("UNKNOWN");
        checks = ConditionTechnicalEvidence.evaluate("JONGGA_V2", bars(), day, Instant.parse("2026-10-14T07:00:00Z"));
        assertThat(check(checks, "technical.0").status()).isEqualTo("PASS");
        assertThat(check(checks, "technical.1").status()).isEqualTo("UNKNOWN");
    }
    @Test void invalidCandleAndMissingVolumeNeverBecomePasses() {
        var rows = bars();
        rows.put(day, new ConditionTechnicalEvidence.Bar(104d, 109d, 103d, 110d, null));
        var checks = ConditionTechnicalEvidence.evaluate("BREAKOUT", rows, day, noon);
        assertThat(checks.stream().filter(c -> c.category().equals("technical")))
            .allMatch(c -> c.status().equals("UNKNOWN"));
    }
    @Test void noYearHighOrThemeClaimsFromOnlySixtyDailyBars() {
        for (String mode : List.of("FLOW_LEADER", "CATALYST_BURST")) {
            assertThat(ConditionTechnicalEvidence.evaluate(mode, bars(), day, noon))
                .allMatch(c -> c.status().equals("UNKNOWN"));
        }
    }
}
