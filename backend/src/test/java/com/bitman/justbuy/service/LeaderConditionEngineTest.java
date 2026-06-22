package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderConditionEngineTest {

    private static final ZonedDateTime NOW =
        ZonedDateTime.of(2026, 6, 18, 10, 20, 0, 0, ZoneId.of("Asia/Seoul"));

    @Test
    void buildSignalsRanksDoubleNetBuyCandidatesAndExcludesEtps() {
        LeaderConditionEngine engine = new LeaderConditionEngine(null);

        List<KisApiService.ConditionCandidate> candidates = List.of(
            candidate("KODEX 레버리지", "122630", "18,500", "+4.0", 900_000, 600_000),
            candidate("알테오젠", "196170", "330,000", "+6.0", 900_000, 600_000),
            candidate("한화오션", "042660", "85,000", "+3.0", 200_000, 100_000),
            candidate("에코프로", "086520", "120,000", "+2.0", -50_000, 150_000)
        );

        List<ConditionSignalDto> signals = engine.buildSignals(candidates, NOW);

        assertThat(signals).hasSize(3);
        assertThat(signals).extracting(ConditionSignalDto::stockName)
            .doesNotContain("KODEX 레버리지");
        assertThat(signals.getFirst().stockName()).isEqualTo("알테오젠");
        assertThat(signals.getFirst().stockCode()).isEqualTo("196170");
        assertThat(signals.getFirst().section()).isEqualTo("leaders");
        assertThat(signals.getFirst().mode()).isEqualTo("FLOW_LEADER");
        assertThat(signals.getFirst().evidence()).contains("외국인+기관 쌍끌이 순매수");
        assertThat(signals.getFirst().capturedAt()).startsWith("2026-06-18T10:20:00");
    }

    @Test
    void singleSidedFlowIsFlaggedAsNotDoubleBuy() {
        LeaderConditionEngine engine = new LeaderConditionEngine(null);

        List<ConditionSignalDto> signals = engine.buildSignals(List.of(
            candidate("에코프로", "086520", "120,000", "+2.0", -50_000, 150_000)
        ), NOW);

        assertThat(signals).hasSize(1);
        assertThat(signals.getFirst().riskFlags()).contains("쌍끌이 미확인 — 단독 수급");
    }

    @Test
    void freshTtlIsAtLeastTenMinutes() {
        assertThat(new LeaderConditionEngine(null).freshTtl())
            .isEqualTo(java.time.Duration.ofMinutes(20));
    }

    private static KisApiService.ConditionCandidate candidate(String name, String code, String price,
                                                              String change, long foreignNet, long institutionNet) {
        long total = foreignNet + institutionNet;
        return new KisApiService.ConditionCandidate(
            name, code, price, change, foreignNet, institutionNet, 0, total,
            "KIS OpenAPI", "테스트 후보");
    }
}
