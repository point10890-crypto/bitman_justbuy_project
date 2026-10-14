package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermRealtimeScannerTest {
    @Test void realMoneyRankingAddsEarlyMoversAbsentFromVolumeAndGainers() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var input = getClass().getResourceAsStream("/condition/ranking-coverage-20261014.json")) {
            var fixture = mapper.readTree(input);
            java.util.function.Function<com.fasterxml.jackson.databind.JsonNode,List<KisApiService.RankedStock>> rows = node -> {
                var result = new java.util.ArrayList<KisApiService.RankedStock>();
                for(var r:node) result.add(stock(r.path("hts_kor_isnm").asText(),
                    r.has("mksc_shrn_iscd")?r.path("mksc_shrn_iscd").asText():r.path("stck_shrn_iscd").asText(),
                    r.path("stck_prpr").asText(),r.path("prdy_ctrt").asText(),r.path("acml_vol").asText()));
                return result;
            };
            var scanner = new ShortTermRealtimeScanner(null);
            var now = ZonedDateTime.parse("2026-10-14T15:00:00+09:00");
            var old = scanner.buildCandidates(rows.apply(fixture.get("volume")),rows.apply(fixture.get("gainers")),List.of(),now,200,false,List.of());
            var expanded = scanner.buildCandidates(rows.apply(fixture.get("volume")),rows.apply(fixture.get("gainers")),List.of(),now,200,false,rows.apply(fixture.get("turnover")));
            assertThat(old).extracting(ConditionSignalDto::stockCode).doesNotContain("006400","278470","096770");
            assertThat(expanded).extracting(ConditionSignalDto::stockCode).contains("006400","278470","096770");
            assertThat(expanded).noneMatch(s -> s.stockName().startsWith("KODEX") || s.stockName().startsWith("TIGER"));
        }
    }

    @Test void verificationRejectionBackfillsFromTheNextQualifiedCandidate() {
        var verifier = org.mockito.Mockito.mock(DeepSeekConditionVerifier.class);
        org.mockito.Mockito.when(verifier.isAvailable()).thenReturn(true);
        org.mockito.Mockito.when(verifier.verify(org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Map.of(
            "100000", new com.bitman.justbuy.dto.condition.EvidenceVerdict("100000", 0, "REJECT", "위험", List.of(), List.of(), "", "")));
        var scanner = new ShortTermRealtimeScanner(null, (TrackRecordService) null, verifier);
        var pool = java.util.stream.IntStream.range(100000, 100006)
            .mapToObj(i -> ConditionLiquidityServiceTest.signal(Integer.toString(i), 80)).toList();
        var result = scanner.publishVerified(pool, ZonedDateTime.parse("2026-10-14T12:00:00+09:00"), KiwoomConditionEnricher.empty("NOT_CONFIGURED"));
        assertThat(result).extracting(ConditionSignalDto::stockCode).containsExactly("100001", "100002", "100003");
        assertThat(result).extracting(ConditionSignalDto::rank).containsExactly(1, 2, 3);
    }


    @Test
    void scheduledScanRunsEveryThreeMinutesByDefault() throws NoSuchMethodException {
        Scheduled scheduled = ShortTermRealtimeScanner.class
            .getMethod("scheduledScan")
            .getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString())
            .isEqualTo("${bitman.short-term.realtime.interval-ms:180000}");
        assertThat(new ShortTermRealtimeScanner(null).freshTtl())
            .isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    void buildSignalsRanksStocksThatOverlapVolumeAndGainerLists() {
        ShortTermRealtimeScanner scanner = new ShortTermRealtimeScanner(null);
        ZonedDateTime now = ZonedDateTime.of(2026, 5, 18, 9, 10, 0, 0, ZoneId.of("Asia/Seoul"));

        List<KisApiService.RankedStock> volume = List.of(
            stock("삼성전자", "005930", "78100", "1.20", "900000"),
            stock("로보티즈", "108490", "31200", "7.50", "2500000"),
            stock("한미반도체", "042700", "139000", "3.10", "1200000")
        );
        List<KisApiService.RankedStock> gainers = List.of(
            stock("로보티즈", "108490", "31200", "7.50", "2500000"),
            stock("디아이", "003160", "7120", "5.53", "1800000"),
            stock("한미반도체", "042700", "139000", "3.10", "1200000")
        );

        List<ConditionSignalDto> signals = scanner.buildSignals(volume, gainers, now);

        assertThat(signals).hasSize(3);
        assertThat(signals.getFirst().stockName()).isEqualTo("로보티즈");
        assertThat(signals.getFirst().status()).isEqualTo("실시간 포착");
        assertThat(signals.getFirst().section()).isEqualTo("shortTerm");
        assertThat(signals.getFirst().mode()).isEqualTo("BREAKOUT");
        assertThat(signals.getFirst().capturedAt()).startsWith("2026-05-18T09:10:00");
        assertThat(signals.getFirst().evidence()).contains("KIS 정규장 실시간 스캔");
    }

    @Test
    void buildSignalsBoostsStocksConfirmedByForeignNetBuyRanking() {
        ShortTermRealtimeScanner scanner = new ShortTermRealtimeScanner(null);
        ZonedDateTime now = ZonedDateTime.of(2026, 5, 18, 9, 25, 0, 0, ZoneId.of("Asia/Seoul"));

        List<KisApiService.RankedStock> volume = List.of(
            stock("거래량강세", "111111", "12000", "2.10", "1800000"),
            stock("수급동반", "222222", "24000", "4.20", "950000"),
            stock("상승률강세", "333333", "18000", "5.00", "700000")
        );
        List<KisApiService.RankedStock> gainers = List.of(
            stock("상승률강세", "333333", "18000", "5.00", "700000"),
            stock("수급동반", "222222", "24000", "4.20", "950000"),
            stock("거래량강세", "111111", "12000", "2.10", "1800000")
        );
        List<KisApiService.RankedStock> foreignNetBuy = List.of(
            stock("수급동반", "222222", "24000", "4.20", "950000", 150000),
            stock("상승률강세", "333333", "18000", "5.00", "700000", 20000)
        );

        List<ConditionSignalDto> signals = scanner.buildSignals(volume, gainers, foreignNetBuy, now);

        assertThat(signals).hasSize(3);
        assertThat(signals.getFirst().stockCode()).isEqualTo("222222");
        assertThat(signals.getFirst().summary()).contains("외국인 순매수 1위");
        assertThat(signals.getFirst().evidence()).contains("외국인 순매수 상위 1위");
        assertThat(signals.getFirst().ruleScore()).isGreaterThan(50);
        assertThat(signals.getFirst().aiScore()).isZero();
    }

    @Test
    void buildSignalsExcludesEtfEtfLikeProductsFromShortTermPicks() {
        ShortTermRealtimeScanner scanner = new ShortTermRealtimeScanner(null);
        ZonedDateTime now = ZonedDateTime.of(2026, 5, 18, 9, 20, 0, 0, ZoneId.of("Asia/Seoul"));

        List<KisApiService.RankedStock> volume = List.of(
            stock("KODEX 200선물인버스2X", "252670", "120", "1.69", "9000000"),
            stock("KODEX 인버스", "114800", "1146", "0.97", "8000000"),
            stock("TIGER 200선물인버스2X", "252710", "127", "1.60", "7000000"),
            stock("로보티즈", "108490", "31200", "7.50", "2500000"),
            stock("디아이", "003160", "7120", "5.53", "1800000"),
            stock("한미반도체", "042700", "139000", "3.10", "1200000")
        );
        List<KisApiService.RankedStock> gainers = List.of(
            stock("KODEX 200선물인버스2X", "252670", "120", "1.69", "9000000"),
            stock("로보티즈", "108490", "31200", "7.50", "2500000"),
            stock("디아이", "003160", "7120", "5.53", "1800000"),
            stock("한미반도체", "042700", "139000", "3.10", "1200000")
        );

        List<ConditionSignalDto> signals = scanner.buildSignals(volume, gainers, now);

        assertThat(signals).extracting(ConditionSignalDto::stockName)
            .containsExactly("로보티즈", "디아이", "한미반도체");
    }

    private static KisApiService.RankedStock stock(String name, String code, String price, String change, String volume) {
        return new KisApiService.RankedStock(name, code, price, change, volume, 0);
    }

    private static KisApiService.RankedStock stock(String name, String code, String price, String change, String volume,
                                                  long foreignNetBuy) {
        return new KisApiService.RankedStock(name, code, price, change, volume, foreignNetBuy);
    }
}
