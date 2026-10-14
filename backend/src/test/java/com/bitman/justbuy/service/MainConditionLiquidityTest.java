package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionSection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MainConditionLiquidityTest {
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
}
