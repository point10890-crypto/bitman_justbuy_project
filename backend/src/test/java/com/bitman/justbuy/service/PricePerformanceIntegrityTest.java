package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.entity.AnalysisTrackRecord.TrackStatus;
import com.bitman.justbuy.repository.TrackRecordRepository;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PricePerformanceIntegrityTest {
    private static final ZonedDateTime CAPTURE = ZonedDateTime.parse("2026-05-18T10:00:00+09:00");
    private final TrackRecordRepository repository = mock(TrackRecordRepository.class);
    private final KisApiService kis = mock(KisApiService.class);
    private final TrackRecordService tracking = new TrackRecordService(repository, kis);

    @Test
    void losingCloseCannotHitPercentageMistakenForTargetPrice() throws Exception {
        ConditionSignalDto signal = signal("10,000", "+5.21%");
        tracking.recordShortTermRealtimeSignals(List.of(signal), CAPTURE);
        ArgumentCaptor<AnalysisTrackRecord> saved = ArgumentCaptor.forClass(AnalysisTrackRecord.class);
        verify(repository).save(saved.capture());
        AnalysisTrackRecord record = saved.getValue();
        Field field = AnalysisTrackRecord.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(record, CAPTURE.toInstant());
        when(repository.findByModeAndAnalysisDateOrderByCreatedAtDesc("BREAKOUT", CAPTURE.toLocalDate()))
            .thenReturn(List.of(record));
        when(kis.fetchCurrentPrice("005930")).thenReturn(Map.of("현재가", "9,700"));

        var verifyClose = TrackRecordService.class.getDeclaredMethod("verifyShortTermClose", LocalDate.class);
        verifyClose.setAccessible(true);
        verifyClose.invoke(tracking, CAPTURE.toLocalDate());

        assertThat(record.getCloseReturn()).isEqualTo(-3.0);
        assertThat(record.getTargetPrice()).isNull();
        assertThat(record.isHitTarget()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+5.21%", "NaN", "Infinity", "1e4", "10,00원", "10만원", "-10000", "10000usd", "10000원junk"})
    void malformedPriceIsNeverRecordedAsAnEntry(String price) {
        int recorded = tracking.recordShortTermRealtimeSignals(List.of(signal(price, "")), CAPTURE);
        assertThat(recorded).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void scannerRetainsFirstCapturePricePairedWithItsTime() {
        var scanner = new ShortTermRealtimeScanner(null);
        var first = scanner.buildSignals(List.of(ranked("10000")), List.of(ranked("10000")), CAPTURE).getFirst();
        var later = scanner.buildSignals(List.of(ranked("12000")), List.of(ranked("12000")), CAPTURE.plusMinutes(3)).getFirst();

        assertThat(first.capturePrice()).isEqualTo("10,000");
        assertThat(later.capturePrice()).isEqualTo("10,000");
        assertThat(later.currentPrice()).isEqualTo("12,000");
        assertThat(later.capturedAt()).isEqualTo(first.capturedAt());
        assertThat(later.highPrice()).isEqualTo("-");
    }

    @Test
    void leaderRetainsFirstCapturePricePairedWithItsTimeAndDoesNotInventTarget() {
        var engine = new LeaderConditionEngine(null);
        var first = engine.buildSignals(List.of(leader("10000")), CAPTURE).getFirst();
        var later = engine.buildSignals(List.of(leader("12000")), CAPTURE.plusMinutes(3)).getFirst();

        assertThat(later.capturePrice()).isEqualTo("10,000");
        assertThat(later.currentPrice()).isEqualTo("12,000");
        assertThat(later.capturedAt()).isEqualTo(first.capturedAt());
        assertThat(later.highPrice()).isEqualTo("-");
    }

    @Test
    void homeWithCandidatesButNoCompletedMeasurementsHasUnavailablePerformance() {
        ConditionSearchPipeline pipeline = mock(ConditionSearchPipeline.class);
        when(pipeline.getPrecomputed("REVERSAL_EDGE")).thenReturn(response(new StockPick(
            "삼성전자", "005930", "10,000", null, null, "주목", "candidate", 0, "",
            "KIS", "KIS", CAPTURE.toInstant().toString())));
        var main = new MainConditionService(pipeline).getMain();

        assertThat(main.trackRecord().totalSignals()).isZero();
        assertThat(main.trackRecord().avgMaxReturnPct()).isEqualTo("-");
        assertThat(main.trackRecord().winRate5d()).isEqualTo("-");
        assertThat(main.sections().swing().signals().getFirst().highPrice()).isEqualTo("-");
        assertThat(main.sections().swing().signals().getFirst().maxReturnPct()).isEqualTo("-");
        assertThat(main.sections().swing().signals().getFirst().ruleScore()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"candidate", "quote", "time", "future", "price"})
    void unverifiedStoredPicksCannotBecomeHomeSignals(String invalidField) {
        StockPick unverified = new StockPick("삼성전자", "005930",
            "price".equals(invalidField) ? "+5.21%" : "10,000", null, null, "주목", "legacy",
            0, "", "candidate".equals(invalidField) ? null : "KIS",
            "quote".equals(invalidField) ? null : "KIS",
            "time".equals(invalidField) ? "invalid" : "future".equals(invalidField)
                ? Instant.now().plusSeconds(60).toString() : CAPTURE.toInstant().toString());
        ConditionSearchPipeline pipeline = mock(ConditionSearchPipeline.class);
        when(pipeline.getStoredPrecomputed("REVERSAL_EDGE")).thenReturn(response(unverified));

        var main = new MainConditionService(pipeline).getMain();

        assertThat(main.sections().swing().sourceStatus()).isEqualTo("LEGACY_UNVERIFIED");
        assertThat(main.sections().swing().signals()).isEmpty();
        assertThat(main.sections().alerts().signals()).isEmpty();
        assertThat(main.sections().swing().asOf()).isEqualTo(CAPTURE.toString());
    }

    @Test
    void legacyPrecomputedResponseCannotBypassHomeProvenanceBoundary() {
        ConditionSearchPipeline pipeline = mock(ConditionSearchPipeline.class);
        when(pipeline.getPrecomputed("REVERSAL_EDGE")).thenReturn(response(new StockPick(
            "삼성전자", "005930", "10,000", null, null, "주목", "legacy")));

        var section = new MainConditionService(pipeline).getSection("swing");

        assertThat(section.sourceStatus()).isEqualTo("LEGACY_UNVERIFIED");
        assertThat(section.signals()).isEmpty();
    }

    @Test
    void provenStoredHistoryRemainsExplicitlyStaleAndPreservesItsObservationTime() {
        ConditionSearchPipeline pipeline = mock(ConditionSearchPipeline.class);
        when(pipeline.getStoredPrecomputed("REVERSAL_EDGE")).thenReturn(response(new StockPick(
            "삼성전자", "005930", "10,000", null, null, "주목", "observed", 0, "",
            "KIS", "KIS", CAPTURE.toInstant().toString())));

        var section = new MainConditionService(pipeline).getSection("swing");

        assertThat(section.sourceStatus()).isEqualTo("STALE_CACHE");
        assertThat(section.asOf()).isEqualTo(CAPTURE.toString());
        assertThat(section.signals()).extracting(ConditionSignalDto::stockCode).containsExactly("005930");
    }

    @Test
    void homeAggregatesOnlyCompletedRecordsWithObservedFiveDayPrices() {
        var completed = record(LocalDate.of(2026, 5, 18));
        completed.setPrice5d(9_700L);
        completed.setReturn5d(-3.0);
        completed.setStatus(TrackStatus.COMPLETED);
        var fabricated = record(LocalDate.of(2026, 5, 18));
        fabricated.setReturn5d(6.0);
        fabricated.setStatus(TrackStatus.COMPLETED);
        when(repository.findByStatus(TrackStatus.COMPLETED)).thenReturn(List.of(completed, fabricated));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("measurement-fixture",
                Map.of("bitman.performance.measured-since", "2026-05-18")));
            context.registerBean(ConditionSearchPipeline.class, () -> mock(ConditionSearchPipeline.class));
            context.registerBean(TrackRecordService.class, () -> tracking);
            context.registerBean(ConditionLiquidityService.class, () -> new ConditionLiquidityService(mock(KisApiService.class)));
            context.register(MainConditionService.class);
            context.refresh();

            var summary = context.getBean(MainConditionService.class).getMain().trackRecord();
            assertThat(summary.totalSignals()).isEqualTo(1);
            assertThat(summary.avgMaxReturnPct()).isEqualTo("-3.00%");
            assertThat(summary.winRate5d()).isEqualTo("0%");
        }
    }

    @Test
    void homeCannotCountFutureFiveDayObservationsEvenWithCompletedDatabaseStatus() {
        AnalysisTrackRecord future = record(LocalDate.now(ZoneId.of("Asia/Seoul")));
        future.setPrice5d(11_000L);
        future.setReturn5d(10.0);
        future.setStatus(TrackStatus.COMPLETED);
        when(repository.findByStatus(TrackStatus.COMPLETED)).thenReturn(List.of(future));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("measurement-fixture",
                Map.of("bitman.performance.measured-since", "2026-05-18")));
            context.registerBean(ConditionSearchPipeline.class, () -> mock(ConditionSearchPipeline.class));
            context.registerBean(TrackRecordService.class, () -> tracking);
            context.registerBean(ConditionLiquidityService.class, () -> new ConditionLiquidityService(mock(KisApiService.class)));
            context.register(MainConditionService.class);
            context.refresh();
            assertThat(context.getBean(MainConditionService.class).getMain().trackRecord().totalSignals()).isZero();
        }
    }

    @Test
    void legacyCompletedRowsBeforeTheMeasurementRolloutCannotClaimVerifiedHomePerformance() {
        AnalysisTrackRecord legacy = record(LocalDate.of(2026, 5, 18));
        legacy.setPrice1d(11_000L);
        legacy.setPrice3d(11_000L);
        legacy.setPrice5d(11_000L);
        legacy.setReturn1d(10.0);
        legacy.setReturn3d(10.0);
        legacy.setReturn5d(10.0);
        legacy.setStatus(TrackStatus.COMPLETED);
        when(repository.findByStatus(TrackStatus.COMPLETED)).thenReturn(List.of(legacy));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("measurement-fixture",
                Map.of("bitman.performance.measured-since", "2026-05-19")));
            context.registerBean(ConditionSearchPipeline.class, () -> mock(ConditionSearchPipeline.class));
            context.registerBean(TrackRecordService.class, () -> tracking);
            context.registerBean(ConditionLiquidityService.class, () -> new ConditionLiquidityService(mock(KisApiService.class)));
            context.register(MainConditionService.class);
            context.refresh();
            var summary = context.getBean(MainConditionService.class).getMain().trackRecord();
            assertThat(summary.totalSignals()).isZero();
            assertThat(summary.avgMaxReturnPct()).isEqualTo("-");
            assertThat(summary.winRate5d()).isEqualTo("-");
            assertThat(legacy.getStatus()).isEqualTo(TrackStatus.COMPLETED);
            assertThat(legacy.getPrice5d()).isEqualTo(11_000L);
        }
    }

    @Test
    void missedUpdatesUseEachHistoricalTradingDateCloseInsteadOfCurrentQuote() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate fifth = KoreanMarketCalendar.previousTradingDay(today);
        LocalDate analysis = fifth;
        for (int i = 0; i < 5; i++) analysis = KoreanMarketCalendar.previousTradingDay(analysis);
        LocalDate first = KoreanMarketCalendar.nextTradingDay(analysis);
        LocalDate third = KoreanMarketCalendar.nextTradingDay(KoreanMarketCalendar.nextTradingDay(first));
        AnalysisTrackRecord record = record(analysis);
        when(repository.findByStatus(TrackStatus.TRACKING)).thenReturn(List.of(record));
        when(kis.fetchCurrentPrice("005930")).thenReturn(Map.of("현재가", "11000"));
        when(kis.fetchDailyOhlc(eq("005930"), any(), any())).thenReturn(Map.of(
            first, candle(first, 10_100), third, candle(third, 9_700), fifth, candle(fifth, 10_300)));

        tracking.updatePrices();

        assertThat(record.getPrice1d()).isEqualTo(10_100L);
        assertThat(record.getPrice3d()).isEqualTo(9_700L);
        assertThat(record.getPrice5d()).isEqualTo(10_300L);
        assertThat(record.getReturn1d()).isEqualTo(1.0);
        assertThat(record.getReturn3d()).isEqualTo(-3.0);
        assertThat(record.getReturn5d()).isEqualTo(3.0);
        assertThat(record.getStatus()).isEqualTo(TrackStatus.COMPLETED);
    }

    @Test
    void missingHistoricalDataStaysUnobservedAndTracking() {
        AnalysisTrackRecord record = record(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(10));
        when(repository.findByStatus(TrackStatus.TRACKING)).thenReturn(List.of(record));
        when(kis.fetchCurrentPrice("005930")).thenReturn(Map.of("현재가", "11000"));
        when(kis.fetchDailyOhlc(eq("005930"), any(), any())).thenReturn(Map.of());

        tracking.updatePrices();

        assertThat(record.getPrice1d()).isNull();
        assertThat(record.getPrice3d()).isNull();
        assertThat(record.getPrice5d()).isNull();
        assertThat(record.getStatus()).isEqualTo(TrackStatus.TRACKING);
    }

    @Test
    void memberTargetHitRateIgnoresAnInvalidLegacyTargetBelowEntry() {
        AnalysisTrackRecord record = record(CAPTURE.toLocalDate());
        record.setClosePrice(9_700L);
        record.setCloseReturn(-3.0);
        record.setTargetPrice(521L);
        record.setHitTarget(true);
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(eq("BREAKOUT"), any(), any()))
            .thenReturn(List.of(record));
        var response = new MemberTrackRecordService(repository, null).getTrackRecord(365);
        var mode = response.modes().stream().filter(value -> "BREAKOUT".equals(value.mode())).findFirst().orElseThrow();
        assertThat(mode.avgReturnPct()).isEqualTo("-3.00%");
        assertThat(mode.targetHitRate()).isEqualTo("-");
    }

    @Test
    void memberDoesNotCountNonfiniteReturnsAsVerifiedMeasurements() {
        AnalysisTrackRecord record = record(CAPTURE.toLocalDate());
        record.setCloseReturn(Double.NaN);
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(eq("BREAKOUT"), any(), any()))
            .thenReturn(List.of(record));
        var response = new MemberTrackRecordService(repository, null).getTrackRecord(365);
        var mode = response.modes().stream().filter(value -> "BREAKOUT".equals(value.mode())).findFirst().orElseThrow();
        assertThat(mode.verifiedCount()).isZero();
        assertThat(mode.avgReturnPct()).isEqualTo("-");
    }

    private static AnalysisTrackRecord record(LocalDate analysis) {
        var record = new AnalysisTrackRecord();
        record.setMode("REVERSAL_EDGE");
        record.setStockCode("005930");
        record.setAnalysisDate(analysis);
        record.setPriceAtAnalysis(10_000L);
        return record;
    }

    private static KisApiService.DailyOhlc candle(LocalDate date, long close) {
        return new KisApiService.DailyOhlc(date, close, close, close, close, 1_000);
    }

    private static KisApiService.RankedStock ranked(String price) {
        return new KisApiService.RankedStock("삼성전자", "005930", price, "5.21", "1000000", 0);
    }

    private static KisApiService.ConditionCandidate leader(String price) {
        return new KisApiService.ConditionCandidate("삼성전자", "005930", price, "5.21",
            500_000, 500_000, 0, 1_000_000, "KIS", "observed flow");
    }

    private static ConditionSignalDto signal(String price, String highPrice) {
        return new ConditionSignalDto("shortTerm", "BREAKOUT", 1, "삼성전자", "005930", price,
            price, highPrice, "", "+5.21%", "실시간 포착", 90, 0, 90, "observed", List.of("KIS"),
            List.of(), "", CAPTURE.toString());
    }

    private static AnalysisResponse response(StockPick pick) {
        return new AnalysisResponse("REVERSAL_EDGE", "query", List.of(), null, "candidate",
            List.of(pick), null, CAPTURE.toString(), true, new AnalysisResponse.Metadata(1, 1, 1));
    }
}
