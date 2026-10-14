package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConditionLiquidityServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final Instant now = Instant.parse("2026-10-14T03:00:00Z");
    final KisApiService kis = mock(KisApiService.class);
    final ConditionLiquidityService service = new ConditionLiquidityService(kis, Clock.fixed(now, ZoneOffset.UTC));
    ObjectNode daily(long today, long previous, int count) {
        ObjectNode root = mapper.createObjectNode().put("rt_cd", "0");
        root.putObject("output1").put("stck_shrn_iscd", "005930");
        var rows = root.putArray("output2");
        rows.addObject().put("stck_bsop_date", "20261014").put("acml_tr_pbmn", today);
        LocalDate day = LocalDate.of(2026, 10, 13);
        for (int i = 0; i < count; i++) {
            while (!KoreanMarketCalendar.isTradingDay(day)) day = day.minusDays(1);
            rows.addObject().put("stck_bsop_date", day.toString().replace("-", "")).put("acml_tr_pbmn", previous);
            day = day.minusDays(1);
        }
        return root;
    }
    void stub(ObjectNode body) {
        when(kis.fetchVerifiedReadOnly(anyString(), anyString(), anyMap(), anyLong()))
            .thenReturn(new KisApiService.ReadOnlySnapshot("AVAILABLE", now, body));
    }
    @Test void usesActualTurnoverAndExcludesTodayFromAverage() {
        stub(daily(100_000_000_000L, 4_900_000_000L, 20));
        assertThat(service.evaluate("REVERSAL_EDGE", "005930").eligible()).isFalse();
        assertThat(service.evaluate("BREAKOUT", "005930").eligible()).isTrue();
        verify(kis, times(1)).fetchVerifiedReadOnly(anyString(), anyString(), anyMap(), anyLong());
    }
    @Test void nineteenDaysCannotMasqueradeAsTwenty() {
        stub(daily(100_000_000_000L, 30_000_000_000L, 19));
        assertThat(service.evaluate("FLOW_LEADER", "005930").eligible()).isFalse();
    }
    @Test void aFetchCrossingTenOClockUsesTheNewThreshold() {
        Clock moving = mock(Clock.class);
        var time = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-14T00:59:59Z"));
        when(moving.instant()).thenAnswer(call -> time.get());
        when(kis.fetchVerifiedReadOnly(anyString(), anyString(), anyMap(), anyLong())).thenAnswer(call -> {
            time.set(Instant.parse("2026-10-14T01:00:02Z"));
            return new KisApiService.ReadOnlySnapshot("AVAILABLE", time.get(), daily(3_000_000_000L, 30_000_000_000L, 20));
        });
        assertThat(new ConditionLiquidityService(kis, moving).evaluate("BREAKOUT", "005930").eligible()).isFalse();
    }
    @Test void rejectsMissingTurnoverInsteadOfMultiplyingPriceAndVolume() {
        var body = daily(10_000_000_000L, 30_000_000_000L, 20);
        var row = (ObjectNode) body.path("output2").get(0);
        row.remove("acml_tr_pbmn"); row.put("stck_clpr", "100000").put("acml_vol", "10000000");
        stub(body);
        assertThat(service.evaluate("BREAKOUT", "005930").eligible()).isFalse();
    }
    @Test void rejectsMismatchedIdentityFutureAndStaleResponses() {
        var body = daily(100_000_000_000L, 30_000_000_000L, 20);
        ((ObjectNode) body.path("output1")).put("stck_shrn_iscd", "000660"); stub(body);
        assertThat(service.evaluate("BREAKOUT", "005930").eligible()).isFalse();
        ((ObjectNode) body.path("output1")).put("stck_shrn_iscd", "005930");
        for (Instant fetched : List.of(now.plusSeconds(1), now.minusSeconds(61))) {
            when(kis.fetchVerifiedReadOnly(anyString(), anyString(), anyMap(), anyLong()))
                .thenReturn(new KisApiService.ReadOnlySnapshot("AVAILABLE", fetched, body));
            assertThat(new ConditionLiquidityService(kis, Clock.fixed(now, ZoneOffset.UTC))
                .evaluate("BREAKOUT", "005930").eligible()).isFalse();
        }
    }
    @Test void removesIneligibleRowsAndPreservesRiskAndOriginalScores() {
        stub(daily(30_000_000_000L, 30_000_000_000L, 20));
        var good = signal("005930", 80);
        var bad = signal("000660", 99); // response identity does not match this requested stock
        var result = service.filterSignals("BREAKOUT", List.of(bad, good), 3);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().stockCode()).isEqualTo("005930");
        assertThat(result.getFirst().rank()).isEqualTo(1);
        assertThat(result.getFirst().finalScore()).isEqualTo(80);
        assertThat(result.getFirst().riskFlags()).containsExactly("과열 주의");
        assertThat(result.getFirst().evidence()).anyMatch(s -> s.contains("정렬 가점 5"));
        assertThat(service.filterSignals("BREAKOUT", result, 3)).isEqualTo(result);
    }
    @Test void turnoverBonusChangesOrderingWithoutOverwritingOriginalScores() {
        when(kis.fetchVerifiedReadOnly(anyString(), anyString(), anyMap(), anyLong())).thenAnswer(call -> {
            java.util.Map<String, String> params = call.getArgument(2);
            String code = params.get("FID_INPUT_ISCD");
            var body = daily(code.equals("005930") ? 30_000_000_000L : 10_000_000_000L, 30_000_000_000L, 20);
            ((ObjectNode) body.path("output1")).put("stck_shrn_iscd", code);
            return new KisApiService.ReadOnlySnapshot("AVAILABLE", now, body);
        });
        var result = service.filterSignals("BREAKOUT", List.of(signal("000660", 84), signal("005930", 80)), 3);
        assertThat(result).extracting(ConditionSignalDto::stockCode).containsExactly("005930", "000660");
        assertThat(result).extracting(ConditionSignalDto::finalScore).containsExactly(80, 84);
    }
    @Test void unavailableResponseIsCachedToAvoidRepeatedRequestsFromFiveSectionPaths() {
        when(kis.fetchVerifiedReadOnly(anyString(), anyString(), anyMap(), anyLong()))
            .thenReturn(new KisApiService.ReadOnlySnapshot("UNAVAILABLE", now, mapper.createObjectNode()));
        assertThat(service.evaluate("BREAKOUT", "005930").eligible()).isFalse();
        assertThat(service.evaluate("FLOW_LEADER", "005930").eligible()).isFalse();
        verify(kis, times(1)).fetchVerifiedReadOnly(anyString(), anyString(), anyMap(), anyLong());
    }
    static ConditionSignalDto signal(String code, int score) {
        return new ConditionSignalDto("shortTerm", "BREAKOUT", 1, "테스트", code,
            "100", "100", "110", "90", "+1%", "포착", score, 0, score,
            "측정", List.of("기존 근거"), List.of("과열 주의"), "이탈", "2026-10-14T12:00:00+09:00");
    }
}
