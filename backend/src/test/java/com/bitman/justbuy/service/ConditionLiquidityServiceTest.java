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
    @Test void allSectionsCanDiscoverQualifiedCandidatesBeyondTheOldThirtyRowCutoff() {
        when(kis.fetchVerifiedReadOnly(anyString(), anyString(), anyMap(), anyLong())).thenAnswer(call -> {
            java.util.Map<String, String> params = call.getArgument(2);
            String code = params.get("FID_INPUT_ISCD");
            boolean qualified = code.equals("005930");
            var body = daily(qualified ? 100_000_000_000L : 1L, qualified ? 30_000_000_000L : 1L, 20);
            ((ObjectNode) body.path("output1")).put("stck_shrn_iscd", code);
            return new KisApiService.ReadOnlySnapshot("AVAILABLE", now, body);
        });
        var candidates = new java.util.ArrayList<ConditionSignalDto>();
        for (int i = 100000; i < 100030; i++) candidates.add(signal(Integer.toString(i), 99));
        candidates.add(signal("005930", 80));
        for (String mode : ConditionLiquidityPolicy.RULES.keySet()) {
            assertThat(service.filterSignals(mode, candidates, 3)).as(mode)
                .extracting(ConditionSignalDto::stockCode).containsExactly("005930");
        }
    }
    @Test void newerMissingBarsCannotKeepAPreviousRefreshPass() {
        stub(daily(30_000_000_000L, 10_000_000_000L, 20));
        var checks = new java.util.ArrayList<>(com.bitman.justbuy.dto.condition.ConditionCheck.unknown("BREAKOUT"));
        checks.set(2, new com.bitman.justbuy.dto.condition.ConditionCheck("technical.1", "technical", "돌파",
            "PASS", "이전 관측", "KIS 일봉 · 이전 재조회", now.minusSeconds(60).toString()));
        var result = service.filterSignals("BREAKOUT", List.of(signal("005930", 80).withChecks(checks)), 3).getFirst();
        assertThat(result.conditionChecks()).filteredOn(c -> c.id().equals("technical.1"))
            .extracting(com.bitman.justbuy.dto.condition.ConditionCheck::status).containsExactly("UNKNOWN");
    }
    @Test void verifiedDailyResponseAttachesMeasuredTechnicalEvidenceWithoutExtraFetches() {
        var body = daily(30_000_000_000L, 10_000_000_000L, 20);
        for (var row : body.path("output2")) {
            var bar = (ObjectNode) row;
            bar.put("stck_oprc", "100").put("stck_hgpr", "102").put("stck_lwpr", "99")
                .put("stck_clpr", "100").put("acml_vol", "100");
        }
        ((ObjectNode) body.path("output2").get(0)).put("stck_oprc", "104").put("stck_hgpr", "112")
            .put("stck_lwpr", "103").put("stck_clpr", "110").put("acml_vol", "300");
        stub(body);
        var result = service.filterSignals("BREAKOUT", List.of(signal("005930", 80)), 3).getFirst();
        assertThat(result.conditionChecks()).filteredOn(c -> c.id().equals("technical.1"))
            .extracting(com.bitman.justbuy.dto.condition.ConditionCheck::status).containsExactly("PASS");
        assertThat(result.capturePrice()).isEqualTo("100");
        assertThat(result.finalScore()).isEqualTo(80);
        assertThat(result.conditionChecks()).filteredOn(c -> c.id().equals("technical.1"))
            .allMatch(c -> c.source().contains("포착 당시 판정과 구분"));
        assertThat(service.filterSignals("BREAKOUT", List.of(result), 3).getFirst()).isEqualTo(result);
        verify(kis, times(1)).fetchVerifiedReadOnly(anyString(), anyString(), anyMap(), anyLong());
    }
    @Test void serializedEvidenceDoesNotPromoteGenericOrAiTextToPassedRules() throws Exception {
        stub(daily(100_000_000_000L, 30_000_000_000L, 20));
        var signal = new ConditionSignalDto("leaders", "FLOW_LEADER", 1, "삼성전자", "005930",
            "100", "100", "110", "90", "10%", "후보", 80, 90, 85,
            "AI says all rules passed", List.of("정배열", "외국인/기관 수급"), List.of(), "", now.toString());
        var filtered = service.filterSignals("FLOW_LEADER", List.of(signal), 3).getFirst();
        var tree = mapper.valueToTree(filtered);
        assertThat(tree.path("conditionChecks").isArray()).isTrue();
        var checks = tree.path("conditionChecks");
        assertThat(checks.findValuesAsText("status")).contains("PASS", "UNKNOWN");
        assertThat(checks.findValuesAsText("category")).contains("liquidity", "technical", "catalyst", "fundamental", "supply");
        long passed = java.util.stream.StreamSupport.stream(checks.spliterator(), false)
            .filter(c -> "PASS".equals(c.path("status").asText())).count();
        assertThat(passed).isEqualTo(1);
        var turnover = checks.get(0);
        assertThat(turnover.path("observedValue").asText()).contains("1000억");
        assertThat(turnover.path("source").asText()).contains("KIS");
        assertThat(turnover.path("observedAt").asText()).isEqualTo(now.toString());
        assertThat(mapper.valueToTree(service.filterSignals("FLOW_LEADER", List.of(filtered), 3).getFirst())
            .path("conditionChecks").size()).isEqualTo(checks.size());
    }
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
