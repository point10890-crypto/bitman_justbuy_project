package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.repository.TrackRecordRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackRecordServiceTest {

    private final TrackRecordRepository repository = mock(TrackRecordRepository.class);
    private final KisApiService kisApiService = mock(KisApiService.class);
    private final TrackRecordService service = new TrackRecordService(repository, kisApiService);

    @Test
    void recordAnalysisSkipsSameStockSameDayEvenWhenPriceChanges() {
        when(repository.existsByModeAndAnalysisDateAndStockCode(eq("REVERSAL_EDGE"), any(LocalDate.class), eq("005930")))
            .thenReturn(false, true);

        service.recordAnalysis(response(
            "REVERSAL_EDGE",
            stock("삼성전자", "005930", "78,100"),
            stock("삼성전자", "005930", "78,500")
        ));

        verify(repository).save(any(AnalysisTrackRecord.class));
    }

    @Test
    void recordShortTermRealtimeSignalsRecordsOncePerStockBeforeCutoff() {
        ZonedDateTime capturedAt = ZonedDateTime.of(2026, 5, 18, 10, 5, 0, 0, ZoneId.of("Asia/Seoul"));
        when(repository.existsByModeAndAnalysisDateAndStockCode(eq("BREAKOUT"), eq(LocalDate.of(2026, 5, 18)), any()))
            .thenReturn(false);

        int recorded = service.recordShortTermRealtimeSignals(List.of(
            signal("로보티즈", "108490", "31,200", 94),
            signal("로보티즈", "108490", "31,500", 91),
            signal("한미반도체", "042700", "139,000", 88)
        ), capturedAt);

        assertThat(recorded).isEqualTo(2);
    }

    @Test
    void recordShortTermRealtimeSignalsIgnoresAfterCutoff() {
        ZonedDateTime capturedAt = ZonedDateTime.of(2026, 5, 18, 15, 21, 0, 0, ZoneId.of("Asia/Seoul"));

        int recorded = service.recordShortTermRealtimeSignals(List.of(
            signal("로보티즈", "108490", "31,200", 94)
        ), capturedAt);

        assertThat(recorded).isZero();
        verify(repository, never()).save(any(AnalysisTrackRecord.class));
    }

    @Test
    void dailyCloseExcludesAfterCutoffRecordsAndDuplicateStockRows() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 18);
        AnalysisTrackRecord first = record("108490", "로보티즈", date, "2026-05-18T01:05:00Z", 31_200L, 32_000L);
        AnalysisTrackRecord duplicate = record("108490", "로보티즈", date, "2026-05-18T02:05:00Z", 31_500L, 32_000L);
        AnalysisTrackRecord other = record("042700", "한미반도체", date, "2026-05-18T04:20:00Z", 139_000L, 141_000L);
        AnalysisTrackRecord afterCutoff = record("005930", "삼성전자", date, "2026-05-18T06:21:00Z", 78_100L, 78_500L);
        when(repository.findByModeAndAnalysisDateOrderByCreatedAtDesc("BREAKOUT", date))
            .thenReturn(List.of(afterCutoff, other, duplicate, first));

        var result = service.getShortTermDailyClose(date, false);

        assertThat(result.totalSignals()).isEqualTo(2);
        assertThat(result.rows()).extracting(row -> row.stockCode())
            .containsExactly("042700", "108490");
        assertThat(result.rows().get(1).entryPrice()).isEqualTo("31,200원");
    }

    private static AnalysisResponse response(String mode, StockPick... picks) {
        return new AnalysisResponse(
            mode,
            mode + " query",
            List.of(),
            null,
            mode + " content",
            List.of(picks),
            null,
            "2026-05-18T10:00:00+09:00",
            true,
            new AnalysisResponse.Metadata(100, 1, 1)
        );
    }

    private static StockPick stock(String name, String code, String currentPrice) {
        return new StockPick(name, code, currentPrice, "", "", "주목", "test");
    }

    private static ConditionSignalDto signal(String name, String code, String price, int score) {
        return new ConditionSignalDto(
            "shortTerm",
            "BREAKOUT",
            1,
            name,
            code,
            price,
            price,
            price,
            "",
            "+1.00%",
            "실시간 포착",
            score,
            0,
            score,
            "test",
            List.of("KIS"),
            List.of(),
            "",
            "2026-05-18T10:05:00+09:00"
        );
    }

    private static AnalysisTrackRecord record(String code, String name, LocalDate date, String createdAt,
                                              long entry, long close) throws Exception {
        AnalysisTrackRecord record = new AnalysisTrackRecord();
        record.setMode("BREAKOUT");
        record.setAnalysisDate(date);
        record.setStockCode(code);
        record.setStockName(name);
        record.setAction("실시간 포착");
        record.setPriceAtAnalysis(entry);
        record.setClosePrice(close);
        record.setCloseReturn(Math.round(((double) close - entry) / entry * 10_000.0) / 100.0);
        setCreatedAt(record, Instant.parse(createdAt));
        return record;
    }

    private static void setCreatedAt(AnalysisTrackRecord record, Instant createdAt) throws Exception {
        Field field = AnalysisTrackRecord.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(record, createdAt);
    }
}
