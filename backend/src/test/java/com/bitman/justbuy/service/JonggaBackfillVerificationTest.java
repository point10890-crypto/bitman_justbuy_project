package com.bitman.justbuy.service;

import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.entity.AnalysisTrackRecord.TrackStatus;
import com.bitman.justbuy.repository.TrackRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 과거 추천의 소급 성과검증 — KIS 일봉 기반.
 *
 * 추천일 2026-07-23(목) → 평가일 2026-07-24(금).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JonggaBackfillVerificationTest {

    private static final LocalDate RECOMMENDED = LocalDate.of(2026, 7, 23);
    private static final LocalDate EVAL = LocalDate.of(2026, 7, 24);

    @TempDir Path dataDir;

    @Mock TrackRecordRepository repository;
    @Mock KisApiService kisApiService;

    private JonggaTrackRecordService service;

    @BeforeEach
    void setUp() {
        JonggaV2SearchService searchService = new JonggaV2SearchService(new ObjectMapper(), dataDir.toString());
        service = new JonggaTrackRecordService(repository, searchService, kisApiService);
    }

    @Test
    void verifiesWinFromDailyCandleAndMarksTargetHit() {
        AnalysisTrackRecord record = record("005930", 80_000L, 84_000L, 77_600L);
        when(repository.findByModeAndAnalysisDateOrderByCreatedAtDesc("JONGGA_V2", RECOMMENDED))
            .thenReturn(List.of(record));
        stubCandle("005930", 79_000L, 85_000L, 78_500L, 83_000L);

        int verified = service.verifyWithDailyCandles(RECOMMENDED, EVAL);

        assertThat(verified).isEqualTo(1);
        assertThat(record.getClosePrice()).isEqualTo(83_000L);
        assertThat(record.getCloseReturn()).isEqualTo(3.75);
        assertThat(record.getHighPrice1d()).isEqualTo(85_000L);
        assertThat(record.getMaxReturn1d()).isEqualTo(6.25);
        assertThat(record.isHitTarget()).isTrue();
        assertThat(record.getStatus()).isEqualTo(TrackStatus.COMPLETED);
        assertThat(record.getCloseVerifiedAt()).isNotNull();
        verify(repository).save(record);
    }

    @Test
    void marksStopHitWhenDailyLowBreachesStopLoss() {
        AnalysisTrackRecord record = record("000660", 180_000L, 189_000L, 174_000L);
        when(repository.findByModeAndAnalysisDateOrderByCreatedAtDesc("JONGGA_V2", RECOMMENDED))
            .thenReturn(List.of(record));
        stubCandle("000660", 179_000L, 181_000L, 172_000L, 175_000L);

        service.verifyWithDailyCandles(RECOMMENDED, EVAL);

        assertThat(record.isHitStop()).isTrue();
        assertThat(record.isHitTarget()).isFalse();
        assertThat(record.getCloseReturn()).isEqualTo(-2.78);
        assertThat(record.getStatus()).isEqualTo(TrackStatus.COMPLETED);
    }

    @Test
    void skipsRecordsAlreadyVerified() {
        AnalysisTrackRecord record = record("005930", 80_000L, 84_000L, 77_600L);
        record.setClosePrice(81_000L);
        when(repository.findByModeAndAnalysisDateOrderByCreatedAtDesc("JONGGA_V2", RECOMMENDED))
            .thenReturn(List.of(record));

        int verified = service.verifyWithDailyCandles(RECOMMENDED, EVAL);

        assertThat(verified).isZero();
        assertThat(record.getClosePrice()).isEqualTo(81_000L);
        verify(repository, never()).save(any());
        verify(kisApiService, never()).fetchDailyOhlc(anyString(), any(), any());
    }

    @Test
    void leavesRecordUnverifiedWhenCandleMissing() {
        AnalysisTrackRecord record = record("005930", 80_000L, 84_000L, 77_600L);
        when(repository.findByModeAndAnalysisDateOrderByCreatedAtDesc("JONGGA_V2", RECOMMENDED))
            .thenReturn(List.of(record));
        when(kisApiService.fetchDailyOhlc(eq("005930"), any(), any())).thenReturn(Map.of());

        int verified = service.verifyWithDailyCandles(RECOMMENDED, EVAL);

        assertThat(verified).isZero();
        assertThat(record.getClosePrice()).isNull();
        assertThat(record.getStatus()).isNotEqualTo(TrackStatus.COMPLETED);
        verify(repository, never()).save(any());
    }

    @Test
    void backfillSkipsDatesWhoseEvalDayHasNotClosed() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

        // 오늘과 미래 구간은 확정 일봉이 없으므로 KIS 를 호출하지 않는다.
        int verified = service.backfillVerification(today, today.plusDays(3));

        assertThat(verified).isZero();
        verify(kisApiService, never()).fetchDailyOhlc(anyString(), any(), any());
    }

    @Test
    void evalDataIsAvailableForPastDaysAndForTodayOnlyAfterCandleIsFixed() {
        LocalDate today = LocalDate.of(2026, 7, 27);
        java.time.LocalTime beforeClose = java.time.LocalTime.of(11, 0);
        java.time.LocalTime afterClose = java.time.LocalTime.of(17, 30);

        // 과거 평가일 — 시각과 무관하게 확정 일봉 존재
        assertThat(JonggaTrackRecordService.evalDataAvailable(today.minusDays(1), today, beforeClose)).isTrue();

        // 평가일이 오늘 — 장중에는 불가, 마감 후에는 가능
        assertThat(JonggaTrackRecordService.evalDataAvailable(today, today, beforeClose)).isFalse();
        assertThat(JonggaTrackRecordService.evalDataAvailable(today, today, afterClose)).isTrue();

        // 미래 평가일 — 언제나 불가
        assertThat(JonggaTrackRecordService.evalDataAvailable(today.plusDays(1), today, afterClose)).isFalse();
        assertThat(JonggaTrackRecordService.evalDataAvailable(null, today, afterClose)).isFalse();
    }

    private void stubCandle(String code, long open, long high, long low, long close) {
        when(kisApiService.fetchDailyOhlc(eq(code), any(), any()))
            .thenReturn(Map.of(EVAL, new KisApiService.DailyOhlc(EVAL, open, high, low, close, 1_000_000L)));
    }

    private static AnalysisTrackRecord record(String code, Long entry, Long target, Long stop) {
        AnalysisTrackRecord record = new AnalysisTrackRecord();
        record.setMode("JONGGA_V2");
        record.setAnalysisDate(RECOMMENDED);
        record.setStockCode(code);
        record.setStockName(code);
        record.setPriceAtAnalysis(entry);
        record.setTargetPrice(target);
        record.setStopLoss(stop);
        return record;
    }
}
