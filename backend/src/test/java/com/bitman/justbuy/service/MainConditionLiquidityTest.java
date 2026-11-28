package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionCheck;
import com.bitman.justbuy.dto.condition.ConditionSection;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MainConditionLiquidityTest {
    private static final Instant SCAN_AT = Instant.parse("2026-11-24T02:00:00Z");

    @Test void preservesMeasuredShortTermPublicationWithoutRepeatingLiquidity() {
        var scanner = mock(ShortTermRealtimeScanner.class);
        var liquidity = mock(ConditionLiquidityService.class);
        var captures = mock(ConditionCaptureStore.class);
        var published = List.of(measuredSignal("005930", 75, "PASS", ShortTermBreakoutEvidence.SOURCE),
            measuredSignal("000660", 91, "PASS", ShortTermBreakoutEvidence.SOURCE));
        stubSnapshot(scanner, published);
        var service = new MainConditionService(mock(ConditionSearchPipeline.class), scanner);
        service.configureLiquidity(liquidity);
        service.configureCaptures(captures);

        var result = service.getSection(ConditionSection.SHORT_TERM);

        assertThat(result.sourceStatus()).isEqualTo("REALTIME_SCAN");
        assertThat(result.signals()).containsExactlyElementsOf(published);
        verifyNoInteractions(liquidity);
        verify(captures).record(eq("publication"), eq("BREAKOUT"), eq("pool"), any(Instant.class),
            eq(Map.of("sourceStatus", "REALTIME_SCAN", "sourceAsOf", SCAN_AT.toString(), "signals", published)));
        verify(captures).watch(published);
    }

    @Test void fallsBackToOrdinaryLiquidityWhenAnyRowLacksEitherMeasuredPass() {
        var scanner = mock(ShortTermRealtimeScanner.class);
        var liquidity = mock(ConditionLiquidityService.class);
        var verified = measuredSignal("005930", 75, "PASS", ShortTermBreakoutEvidence.SOURCE);
        var rejectedBreakout = measuredSignal("000660", 91, "FAIL", ShortTermBreakoutEvidence.SOURCE);
        var untrustedBreakout = measuredSignal("000660", 91, "PASS", "AI");
        var complete = measuredSignal("000660", 91, "PASS", ShortTermBreakoutEvidence.SOURCE);
        var missingLiquidity = complete.withChecks(List.of(complete.conditionChecks().get(1)));
        var priorSessionBreakout = complete.withChecks(List.of(complete.conditionChecks().get(0),
            new ConditionCheck(ShortTermBreakoutEvidence.BREAKOUT_CHECK, "technical", "전일 고가·당일 시가 돌파",
                "PASS", "102원 > 101원", ShortTermBreakoutEvidence.SOURCE, "2026-11-23T02:00:00Z")));
        List<ConditionSignalDto> failingRows = List.of(rejectedBreakout, untrustedBreakout, missingLiquidity, priorSessionBreakout);
        var service = new MainConditionService(mock(ConditionSearchPipeline.class), scanner);
        service.configureLiquidity(liquidity);

        for (var row : failingRows) {
            var original = List.of(verified, row);
            stubSnapshot(scanner, original);
            when(liquidity.filterSignals("BREAKOUT", original, 3)).thenReturn(List.of(verified));

            var result = service.getSection(ConditionSection.SHORT_TERM);

            assertThat(result.signals()).containsExactly(verified);
            verify(liquidity).filterSignals("BREAKOUT", original, 3);
        }
    }

    @Test void allFiveSectionsAndAlertsExcludeUnconfirmedLiquidityIncludingCachedFallbacks() {
        var pipeline = mock(ConditionSearchPipeline.class);
        var scanner = mock(ShortTermRealtimeScanner.class);
        var leader = mock(LeaderConditionEngine.class);
        var closing = mock(JonggaV2SearchService.class);
        var liquidity = new ConditionLiquidityService(mock(KisApiService.class));
        when(scanner.latestFresh()).thenReturn(Optional.of(new ShortTermRealtimeScanner.ScanSnapshot(
            Instant.now().toString(), Instant.now(), "REALTIME_SCAN", true, List.of(ConditionLiquidityServiceTest.signal("005930", 80)))));
        when(leader.latestFresh()).thenReturn(Optional.of(new LeaderConditionEngine.LeaderSnapshot(
            Instant.now().toString(), Instant.now(), "LEADER_SCAN", true, List.of(ConditionLiquidityServiceTest.signal("005930", 80)))));
        var json = new ObjectMapper().createObjectNode().put("date", LocalDate.now().toString());
        json.putArray("signals").addObject().put("stock_code", "005930").put("stock_name", "삼성전자").put("current_price", 100);
        when(closing.latest()).thenReturn(json);
        for (String mode : List.of("REVERSAL_EDGE", "CATALYST_BURST")) when(pipeline.getStoredPrecomputed(mode)).thenReturn(response(mode));
        var service = new MainConditionService(pipeline, scanner, closing, leader);
        service.configureLiquidity(liquidity);
        var result = service.getMain();
        assertThat(List.of(result.sections().shortTerm(), result.sections().swing(), result.sections().leaders(),
            result.sections().themes(), result.sections().closingBet(), result.sections().alerts()))
            .allSatisfy(section -> assertThat(section.signals()).isEmpty());
    }

    @Test void appliesGateBeforeStoredTopThreeLimitAndReRanksSurvivors() {
        var pipeline = mock(ConditionSearchPipeline.class);
        when(pipeline.getPrecomputed("CATALYST_BURST")).thenReturn(response("CATALYST_BURST"));
        var liquidity = mock(ConditionLiquidityService.class);
        when(liquidity.filterSignals(eq("CATALYST_BURST"), anyList(), eq(3))).thenAnswer(call -> {
            List<com.bitman.justbuy.dto.condition.ConditionSignalDto> signals = call.getArgument(1);
            assertThat(signals).hasSize(4);
            return List.of(signals.get(3));
        });
        var service = new MainConditionService(pipeline);
        service.configureLiquidity(liquidity);
        assertThat(service.getSection(ConditionSection.THEMES).signals()).extracting(s -> s.stockCode()).containsExactly("000004");
    }

    private AnalysisResponse response(String mode) {
        List<StockPick> picks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) picks.add(new StockPick("테스트" + i, String.format("%06d", i), "100", "110", "90",
            "주목", "측정", 0, "", "KIS", "KIS", Instant.now().toString()));
        return new AnalysisResponse(mode, "query", List.of(), null, "", picks, null, Instant.now().toString(), true, null);
    }

    private void stubSnapshot(ShortTermRealtimeScanner scanner, List<ConditionSignalDto> signals) {
        when(scanner.latestFresh()).thenReturn(Optional.of(new ShortTermRealtimeScanner.ScanSnapshot(
            SCAN_AT.toString(), SCAN_AT, "REALTIME_SCAN", true, signals)));
    }

    private ConditionSignalDto measuredSignal(String code, int score, String breakoutStatus, String breakoutSource) {
        return ConditionLiquidityServiceTest.signal(code, score).withChecks(List.of(
            new ConditionCheck("liquidity.0", "liquidity", "거래대금 100억 이상", "PASS", "126억",
                ConditionLiquidityService.LIQUIDITY_CHECK_SOURCE, SCAN_AT.toString()),
            new ConditionCheck(ShortTermBreakoutEvidence.BREAKOUT_CHECK, "technical", "전일 고가·당일 시가 돌파",
                breakoutStatus, "102원 > 101원", breakoutSource, SCAN_AT.toString())
        ));
    }
}
