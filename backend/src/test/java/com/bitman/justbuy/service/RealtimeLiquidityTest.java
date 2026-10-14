package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import java.time.ZonedDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RealtimeLiquidityTest {
    @Test void rejectedLiquidityDoesNotStartTheCaptureClock() {
        var scanner = new ShortTermRealtimeScanner(null);
        var liquidity = mock(ConditionLiquidityService.class);
        scanner.configureLiquidity(liquidity);
        when(liquidity.filterSignals(eq("BREAKOUT"), anyList(), eq(3)))
            .thenReturn(List.of()).thenAnswer(call -> call.getArgument(1));
        var rows = List.of(stock("000001"));
        assertThat(scanner.buildSignals(rows, rows, ZonedDateTime.parse("2026-10-14T09:10:00+09:00"))).isEmpty();
        var accepted = scanner.buildSignals(rows, rows, ZonedDateTime.parse("2026-10-14T10:00:00+09:00"));
        assertThat(accepted.getFirst().capturedAt()).isEqualTo("2026-10-14T10:00:00+09:00");
    }
    @Test void realtimeShortTermAppliesLiquidityBeforeItsTopThreeCut() {
        var scanner = new ShortTermRealtimeScanner(null);
        var liquidity = mock(ConditionLiquidityService.class);
        scanner.configureLiquidity(liquidity);
        when(liquidity.filterSignals(eq("BREAKOUT"), anyList(), eq(3))).thenAnswer(call -> {
            List<com.bitman.justbuy.dto.condition.ConditionSignalDto> rows = call.getArgument(1);
            assertThat(rows).hasSize(4);
            return List.of(rows.get(3));
        });
        // Ranking fixtures below are genuine candidates for the existing deterministic scoring rules.
        var rows = List.of(stock("000001"), stock("000002"), stock("000003"), stock("000004"));
        assertThat(scanner.buildSignals(rows, rows, ZonedDateTime.parse("2026-10-14T12:00:00+09:00")))
            .extracting(s -> s.stockCode()).containsExactly("000004");
    }
    @Test void leaderNeverSendsBelowLiquidityCandidateToAi() {
        var engine = new LeaderConditionEngine(null);
        var liquidity = mock(ConditionLiquidityService.class);
        engine.configureLiquidity(liquidity);
        when(liquidity.evaluate(eq("FLOW_LEADER"), anyString(), anyLong()))
            .thenReturn(new ConditionLiquidityPolicy.Result(false, 0, "20일 평균 미달"));
        var candidate = new KisApiService.ConditionCandidate("삼성전자", "005930", "100000", "3", 100000, 100000,
            0, 200000, "KIS", "쌍끌이");
        assertThat(engine.buildSignals(List.of(candidate), ZonedDateTime.parse("2026-10-14T12:00:00+09:00"))).isEmpty();
    }
    private KisApiService.RankedStock stock(String code) {
        return new KisApiService.RankedStock("테스트", code, "100000", "5", "1000000", 0);
    }
}
