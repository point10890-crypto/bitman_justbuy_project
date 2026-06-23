package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.dto.condition.EvidencePacket;
import com.bitman.justbuy.dto.condition.EvidenceVerdict;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortTermRealtimeScannerAiVerifyTest {

    private static final ZonedDateTime NOW =
        ZonedDateTime.of(2026, 5, 18, 9, 30, 0, 0, ZoneId.of("Asia/Seoul"));

    @Test
    void verifyAndEnrichBlendsScoreAndStatusFromVerdict() {
        DeepSeekConditionVerifier verifier = mock(DeepSeekConditionVerifier.class);
        when(verifier.isAvailable()).thenReturn(true);

        ShortTermRealtimeScanner scanner =
            new ShortTermRealtimeScanner(null, (TrackRecordService) null, verifier);

        List<ConditionSignalDto> ruleSignals = scanner.buildSignals(volumeList(), gainerList(), NOW);
        assertThat(ruleSignals).isNotEmpty();
        ConditionSignalDto top = ruleSignals.getFirst();

        EvidenceVerdict verdict = new EvidenceVerdict(
            top.stockCode(),
            90,
            "VALID",
            "AI 검증 요약",
            List.of("AI 보강 증거"),
            List.of("AI 리스크"),
            "AI 무효화 조건",
            "AI포착"
        );
        when(verifier.verify(anyList())).thenReturn(Map.of(top.stockCode(), verdict));

        List<ConditionSignalDto> enriched = scanner.verifyAndEnrich(ruleSignals, NOW);

        ConditionSignalDto result = enriched.stream()
            .filter(s -> s.stockCode().equals(top.stockCode()))
            .findFirst()
            .orElseThrow();

        int expectedFinal = (int) Math.round(0.6 * top.ruleScore() + 0.4 * 90);
        assertThat(result.aiScore()).isEqualTo(90);
        assertThat(result.finalScore()).isEqualTo(expectedFinal);
        assertThat(result.status()).isEqualTo("AI포착");
        assertThat(result.summary()).isEqualTo("AI 검증 요약");
        assertThat(result.invalidation()).isEqualTo("AI 무효화 조건");
        assertThat(result.evidence()).contains("AI 보강 증거");
        assertThat(result.evidence()).containsAll(top.evidence());
        assertThat(result.riskFlags()).contains("AI 리스크");
        // rule score is unchanged; only ai/final are blended
        assertThat(result.ruleScore()).isEqualTo(top.ruleScore());
    }

    @Test
    void verifyAndEnrichDropsRejectedVerdictAndReRanks() {
        DeepSeekConditionVerifier verifier = mock(DeepSeekConditionVerifier.class);
        when(verifier.isAvailable()).thenReturn(true);

        ShortTermRealtimeScanner scanner =
            new ShortTermRealtimeScanner(null, (TrackRecordService) null, verifier);

        List<ConditionSignalDto> ruleSignals = scanner.buildSignals(volumeList(), gainerList(), NOW);
        assertThat(ruleSignals).hasSizeGreaterThanOrEqualTo(2);
        ConditionSignalDto dropped = ruleSignals.getFirst();

        EvidenceVerdict reject = new EvidenceVerdict(
            dropped.stockCode(), 10, "REJECT", "부적합", List.of(), List.of(), "", "제외"
        );
        when(verifier.verify(anyList())).thenReturn(Map.of(dropped.stockCode(), reject));

        List<ConditionSignalDto> enriched = scanner.verifyAndEnrich(ruleSignals, NOW);

        assertThat(enriched).extracting(ConditionSignalDto::stockCode)
            .doesNotContain(dropped.stockCode());
        assertThat(enriched).hasSize(ruleSignals.size() - 1);
        // results are re-ranked 1..n after the drop
        assertThat(enriched.getFirst().rank()).isEqualTo(1);
    }

    @Test
    void verifyAndEnrichReturnsInputUnchangedWhenVerifierIsNull() {
        ShortTermRealtimeScanner scanner = new ShortTermRealtimeScanner(null);

        List<ConditionSignalDto> ruleSignals = scanner.buildSignals(volumeList(), gainerList(), NOW);
        List<ConditionSignalDto> result = scanner.verifyAndEnrich(ruleSignals, NOW);

        assertThat(result).isSameAs(ruleSignals);
    }

    @Test
    void verifyAndEnrichReturnsInputUnchangedWhenVerifierUnavailable() {
        DeepSeekConditionVerifier verifier = mock(DeepSeekConditionVerifier.class);
        when(verifier.isAvailable()).thenReturn(false);

        ShortTermRealtimeScanner scanner =
            new ShortTermRealtimeScanner(null, (TrackRecordService) null, verifier);

        List<ConditionSignalDto> ruleSignals = scanner.buildSignals(volumeList(), gainerList(), NOW);
        List<ConditionSignalDto> result = scanner.verifyAndEnrich(ruleSignals, NOW);

        assertThat(result).isSameAs(ruleSignals);
    }

    private static List<KisApiService.RankedStock> volumeList() {
        return List.of(
            stock("로보티즈", "108490", "31200", "7.50", "2500000"),
            stock("디아이", "003160", "7120", "5.53", "1800000"),
            stock("한미반도체", "042700", "139000", "3.10", "1200000")
        );
    }

    private static List<KisApiService.RankedStock> gainerList() {
        return List.of(
            stock("로보티즈", "108490", "31200", "7.50", "2500000"),
            stock("디아이", "003160", "7120", "5.53", "1800000"),
            stock("한미반도체", "042700", "139000", "3.10", "1200000")
        );
    }

    private static KisApiService.RankedStock stock(String name, String code, String price, String change, String volume) {
        return new KisApiService.RankedStock(name, code, price, change, volume, 0);
    }
}
