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

    @Test void finalVerifiedScoreDeterminesThePublishedTopThree() {
        var verifier = org.mockito.Mockito.mock(DeepSeekConditionVerifier.class);
        org.mockito.Mockito.when(verifier.isAvailable()).thenReturn(true);
        org.mockito.Mockito.when(verifier.verify(org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Map.of(
            "100003", new com.bitman.justbuy.dto.condition.EvidenceVerdict("100003", 96, "VALID", "", List.of(), List.of(), "", "")));
        var scanner = new ShortTermRealtimeScanner(null, (TrackRecordService) null, verifier);
        var pool = java.util.stream.IntStream.range(100000, 100004)
            .mapToObj(i -> ConditionLiquidityServiceTest.signal(Integer.toString(i), 80)).toList();

        var result = scanner.publishVerified(pool, ZonedDateTime.parse("2026-10-14T12:00:00+09:00"), KiwoomConditionEnricher.empty("NOT_CONFIGURED"));

        assertThat(result).extracting(ConditionSignalDto::stockCode).containsExactly("100003", "100000", "100001");
        assertThat(result).extracting(ConditionSignalDto::rank).containsExactly(1, 2, 3);
    }

    @Test void searchesBeyondSixHighRankedNamesToFindMeasuredBreakout() {
        var at = ZonedDateTime.parse("2026-11-23T10:05:00+09:00");
        var higherRanked = java.util.stream.IntStream.range(100000, 100006)
            .mapToObj(code -> stock("상위 " + code, Integer.toString(code), "11200", "12.00", "1200000"))
            .toList();
        var actualBreakout = stock("돌파 확인", "100006", "10400", "4.00", "400000");
        var pool = new java.util.ArrayList<>(higherRanked);
        pool.add(actualBreakout);
        var kis = rankedSources(at, pool);
        for (var candidate : higherRanked) {
            daily(kis, at, candidate.code(), 11500, 11200);
        }
        // The leading ranking quote has moved past the previous high, although the daily close field lags it.
        daily(kis, at, actualBreakout.code(), 10300, 10200);
        var scanner = liveScanner(kis, at);

        var result = scanner.scanNow();

        assertThat(result.sourceStatus()).isEqualTo("REALTIME_SCAN");
        assertThat(result.signals()).extracting(ConditionSignalDto::stockCode).containsExactly("100006");
        var signal = result.signals().getFirst();
        assertThat(signal.conditionChecks()).anySatisfy(check -> {
            assertThat(check.id()).isEqualTo("technical.1");
            assertThat(check.status()).isEqualTo("PASS");
            assertThat(check.observedValue()).contains("10,400", "10,300", "10,000");
        });
        assertThat(signal.conditionChecks()).anySatisfy(check -> {
            assertThat(check.id()).isEqualTo("technical.2");
            assertThat(check.observedValue()).isEqualTo("+4.00%");
            assertThat(check.source()).isEqualTo("KIS OpenAPI");
        });
    }

    @Test void separatesMeasuredNoBreakoutFromMissingDailyEvidence() {
        var at = ZonedDateTime.parse("2026-11-23T10:05:00+09:00");
        var row = stock("관찰 종목", "100000", "10400", "4.00", "400000");
        var known = rankedSources(at, List.of(row));
        daily(known, at, row.code(), 10600, 10400);
        assertThat(liveScanner(known, at).scanNow().sourceStatus()).isEqualTo("REALTIME_EMPTY");

        var incomplete = rankedSources(at, List.of(row));
        daily(incomplete, at, row.code(), null, 10400);
        assertThat(liveScanner(incomplete, at).scanNow().sourceStatus()).isEqualTo("REALTIME_UNAVAILABLE");
    }

    @Test void openingGapIsNotABreakoutWhenPriceFallsBelowTheOpen() {
        var at = ZonedDateTime.parse("2026-11-23T10:05:00+09:00");
        var row = stock("장초반 갭 이후 밀림", "100000", "10400", "4.00", "400000");
        var kis = rankedSources(at, List.of(row));
        daily(kis, at, row.code(), 10300, 10700, 10400);

        assertThat(liveScanner(kis, at).scanNow().sourceStatus()).isEqualTo("REALTIME_EMPTY");
    }

    @Test void completedReviewThatRejectsAllCandidatesStaysEmpty() {
        var at = ZonedDateTime.parse("2026-11-23T10:05:00+09:00");
        var row = stock("검토 후보", "100000", "10400", "4.00", "400000");
        var kis = rankedSources(at, List.of(row));
        daily(kis, at, row.code(), 10300, 10400);
        var verifier = org.mockito.Mockito.mock(DeepSeekConditionVerifier.class);
        org.mockito.Mockito.when(verifier.isAvailable()).thenReturn(true);
        org.mockito.Mockito.when(verifier.verify(org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Map.of(
            row.code(), new com.bitman.justbuy.dto.condition.EvidenceVerdict(row.code(), 0, "REJECT", "위험", List.of(), List.of(), "", "")));
        var clock = java.time.Clock.fixed(at.toInstant(), at.getZone());
        var scanner = new ShortTermRealtimeScanner(kis, null, verifier, clock);
        scanner.configureLiquidity(new ConditionLiquidityService(kis, clock));

        var result = scanner.scanNow();

        assertThat(result.sourceStatus()).isEqualTo("REALTIME_EMPTY");
        assertThat(scanner.latestFresh()).hasValueSatisfying(snapshot -> assertThat(snapshot.signals()).isEmpty());
    }

    @Test void excludesOverheatedOrTooSmallPositiveMovesBeforeDailyRequests() {
        var at = ZonedDateTime.parse("2026-11-23T10:05:00+09:00");
        var kis = rankedSources(at, List.of(stock("과열", "100000", "11600", "16.00", "1200000"),
            stock("미달", "100001", "10299", "2.99", "1200000")));

        var result = liveScanner(kis, at).scanNow();

        assertThat(result.sourceStatus()).isEqualTo("REALTIME_EMPTY");
        org.mockito.Mockito.verify(kis, org.mockito.Mockito.never()).fetchVerifiedReadOnly(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyLong());
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

    @Test
    void successfulZeroMatchIsFreshWhileProviderFailureIsNot() {
        var at = ZonedDateTime.parse("2026-11-23T10:05:00+09:00");
        var clock = java.time.Clock.fixed(at.toInstant(), at.getZone());
        var kis = org.mockito.Mockito.mock(KisApiService.class);
        var complete = new KisApiService.SourceBatch<KisApiService.RankedStock>(List.of(), at.toInstant(), true);
        org.mockito.Mockito.when(kis.fetchRealtimeVolumeRankingSnapshot(80)).thenReturn(complete);
        org.mockito.Mockito.when(kis.fetchRealtimeGainersRankingSnapshot(80)).thenReturn(complete);
        org.mockito.Mockito.when(kis.fetchRealtimeTurnoverRankingSnapshot(30)).thenReturn(complete);
        org.mockito.Mockito.when(kis.fetchForeignNetBuyRankingSnapshot(40)).thenReturn(complete);
        var scanner = new ShortTermRealtimeScanner(kis, null, null, clock);

        assertThat(scanner.scanNow().sourceStatus()).isEqualTo("REALTIME_EMPTY");
        assertThat(scanner.latestFresh()).hasValueSatisfying(scan -> assertThat(scan.signals()).isEmpty());

        org.mockito.Mockito.when(kis.fetchRealtimeGainersRankingSnapshot(80))
            .thenReturn(new KisApiService.SourceBatch<>(List.of(), null, false));
        assertThat(scanner.scanNow().sourceStatus()).isEqualTo("REALTIME_UNAVAILABLE");
        assertThat(scanner.latestFresh()).isEmpty();
    }

    @Test
    void laterNegativeQuoteCannotQualifyFromAnEarlierPositivePercent() {
        var scanner = new ShortTermRealtimeScanner(null);
        var at = ZonedDateTime.parse("2026-11-23T10:05:00+09:00");
        var earlier = stock("테스트종목", "005930", "10000", "8.00", "1000000");
        var later = stock("테스트종목", "005930", "9100", "-1.00", "1200000");

        assertThat(scanner.buildCandidates(List.of(earlier), List.of(earlier), List.of(), at,
            3, false, List.of(later))).isEmpty();
    }

    @Test
    void displayAndScoringUseTheSameNewestKisQuoteEvenWithOutOfOrderBatches() {
        var at = ZonedDateTime.parse("2026-11-23T10:05:00+09:00");
        var kis = org.mockito.Mockito.mock(KisApiService.class);
        var old = stock("테스트종목", "005930", "10800", "8.00", "1000000");
        var current = stock("테스트종목", "005930", "10400", "4.00", "1200000");
        var oldBatch = new KisApiService.SourceBatch<>(List.of(old), at.toInstant().minusSeconds(30), true);
        var currentBatch = new KisApiService.SourceBatch<>(List.of(current), at.toInstant().minusSeconds(10), true);
        org.mockito.Mockito.when(kis.fetchRealtimeVolumeRankingSnapshot(80)).thenReturn(oldBatch);
        org.mockito.Mockito.when(kis.fetchRealtimeGainersRankingSnapshot(80)).thenReturn(currentBatch);
        org.mockito.Mockito.when(kis.fetchRealtimeTurnoverRankingSnapshot(30)).thenReturn(oldBatch);
        org.mockito.Mockito.when(kis.fetchForeignNetBuyRankingSnapshot(40))
            .thenReturn(new KisApiService.SourceBatch<>(List.of(), at.toInstant(), true));
        daily(kis, at, current.code(), 10300, 10200);
        var scanner = liveScanner(kis, at);

        var signal = scanner.scanNow().signals().getFirst();
        assertThat(signal.currentPrice()).isEqualTo("10,400");
        assertThat(signal.maxReturnPct()).isEqualTo("+4.00%");
        assertThat(signal.conditionChecks()).anySatisfy(check -> {
            assertThat(check.status()).isEqualTo("PASS");
            assertThat(check.observedValue()).isEqualTo("+4.00%");
            assertThat(check.observedAt()).isEqualTo("2026-11-23T10:04:50+09:00");
        });
    }

    private static KisApiService rankedSources(ZonedDateTime at, List<KisApiService.RankedStock> candidates) {
        var kis = org.mockito.Mockito.mock(KisApiService.class);
        var batch = new KisApiService.SourceBatch<>(candidates, at.toInstant().minusSeconds(10), true);
        org.mockito.Mockito.when(kis.fetchRealtimeVolumeRankingSnapshot(80)).thenReturn(batch);
        org.mockito.Mockito.when(kis.fetchRealtimeGainersRankingSnapshot(80)).thenReturn(batch);
        var empty = new KisApiService.SourceBatch<KisApiService.RankedStock>(List.of(), at.toInstant(), true);
        org.mockito.Mockito.when(kis.fetchRealtimeTurnoverRankingSnapshot(30)).thenReturn(empty);
        org.mockito.Mockito.when(kis.fetchForeignNetBuyRankingSnapshot(40)).thenReturn(empty);
        return kis;
    }

    private static ShortTermRealtimeScanner liveScanner(KisApiService kis, ZonedDateTime at) {
        var clock = java.time.Clock.fixed(at.toInstant(), at.getZone());
        var scanner = new ShortTermRealtimeScanner(kis, null, null, clock);
        scanner.configureLiquidity(new ConditionLiquidityService(kis, clock));
        return scanner;
    }

    private static void daily(KisApiService kis, ZonedDateTime at, String code, Integer previousHigh, int dailyPrice) {
        daily(kis, at, code, previousHigh, 10000, dailyPrice);
    }

    private static void daily(KisApiService kis, ZonedDateTime at, String code, Integer previousHigh, int open, int dailyPrice) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = mapper.createObjectNode().put("rt_cd", "0");
        body.putObject("output1").put("stck_shrn_iscd", code);
        var rows = body.putArray("output2");
        rows.addObject().put("stck_bsop_date", "20261123").put("acml_tr_pbmn", "12000000000")
            .put("stck_oprc", open).put("stck_hgpr", "12000").put("stck_lwpr", "9900")
            .put("stck_clpr", dailyPrice).put("acml_vol", "1200000");
        if (previousHigh != null) {
            rows.addObject().put("stck_bsop_date", "20261120").put("acml_tr_pbmn", "12000000000")
                .put("stck_oprc", "10000").put("stck_hgpr", previousHigh).put("stck_lwpr", "9900")
                .put("stck_clpr", "10000").put("acml_vol", "1000000");
        }
        org.mockito.Mockito.when(kis.fetchVerifiedReadOnly(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("FHKST03010100"),
            org.mockito.ArgumentMatchers.argThat(params -> params != null && code.equals(params.get("FID_INPUT_ISCD"))),
            org.mockito.ArgumentMatchers.anyLong())).thenReturn(
                new KisApiService.ReadOnlySnapshot("AVAILABLE", at.toInstant(), body));
    }

    private static KisApiService.RankedStock stock(String name, String code, String price, String change, String volume) {
        return new KisApiService.RankedStock(name, code, price, change, volume, 0);
    }

    private static KisApiService.RankedStock stock(String name, String code, String price, String change, String volume,
                                                  long foreignNetBuy) {
        return new KisApiService.RankedStock(name, code, price, change, volume, foreignNetBuy);
    }
}
