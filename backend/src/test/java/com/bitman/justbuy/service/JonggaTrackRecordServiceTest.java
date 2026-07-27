package com.bitman.justbuy.service;

import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.entity.AnalysisTrackRecord.TrackStatus;
import com.bitman.justbuy.repository.TrackRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JonggaTrackRecordServiceTest {

    private static final LocalDate RECOMMENDED = LocalDate.of(2026, 7, 23);

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
    void recordsOnlyTopThreeSignalsFromArchive() throws Exception {
        writeArchive();
        when(repository.existsByModeAndAnalysisDateAndStockCode(anyString(), any(), anyString())).thenReturn(false);

        int recorded = service.recordArchiveSignals(RECOMMENDED);

        assertThat(recorded).isEqualTo(3);

        ArgumentCaptor<AnalysisTrackRecord> captor = ArgumentCaptor.forClass(AnalysisTrackRecord.class);
        verify(repository, times(3)).save(captor.capture());

        assertThat(captor.getAllValues()).extracting(AnalysisTrackRecord::getStockCode)
            .containsExactly("005930", "000660", "042700");

        AnalysisTrackRecord first = captor.getAllValues().get(0);
        assertThat(first.getMode()).isEqualTo("JONGGA_V2");
        assertThat(first.getAnalysisDate()).isEqualTo(RECOMMENDED);
        assertThat(first.getStockName()).isEqualTo("삼성전자");
        assertThat(first.getAction()).isEqualTo("S");
        assertThat(first.getConsensusScore()).isEqualTo(12);
        assertThat(first.getPriceAtAnalysis()).isEqualTo(80_000L);
        assertThat(first.getTargetPrice()).isEqualTo(84_000L);
        assertThat(first.getStopLoss()).isEqualTo(77_600L);
    }

    @Test
    void skipsSignalsAlreadyRecordedForThatDate() throws Exception {
        writeArchive();
        when(repository.existsByModeAndAnalysisDateAndStockCode("JONGGA_V2", RECOMMENDED, "005930")).thenReturn(true);
        when(repository.existsByModeAndAnalysisDateAndStockCode("JONGGA_V2", RECOMMENDED, "000660")).thenReturn(false);
        when(repository.existsByModeAndAnalysisDateAndStockCode("JONGGA_V2", RECOMMENDED, "042700")).thenReturn(false);

        assertThat(service.recordArchiveSignals(RECOMMENDED)).isEqualTo(2);
        verify(repository, times(2)).save(any());
    }

    @Test
    void recordingIsSkippedWhenArchiveIsMissing() {
        assertThat(service.recordArchiveSignals(RECOMMENDED)).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void verifiesCloseAndMaxReturnFromNextDayQuote() {
        AnalysisTrackRecord record = record("005930", 80_000L, 84_000L, 77_600L);
        when(repository.findByModeAndAnalysisDateOrderByCreatedAtDesc("JONGGA_V2", RECOMMENDED))
            .thenReturn(List.of(record));
        when(kisApiService.fetchCurrentPrice("005930")).thenReturn(quote("82000", "85000", "79000"));

        assertThat(service.verifyRecordedSignals(RECOMMENDED)).isEqualTo(1);

        assertThat(record.getClosePrice()).isEqualTo(82_000L);
        assertThat(record.getCloseReturn()).isEqualTo(2.5);
        assertThat(record.getHighPrice1d()).isEqualTo(85_000L);
        assertThat(record.getMaxReturn1d()).isEqualTo(6.25);
        assertThat(record.isHitTarget()).isTrue();
        assertThat(record.isHitStop()).isFalse();
        assertThat(record.getStatus()).isEqualTo(TrackStatus.COMPLETED);
        assertThat(record.getCloseVerifiedAt()).isNotNull();
        verify(repository).save(record);
    }

    @Test
    void marksStopHitWhenNextDayLowBreaksStopLoss() {
        AnalysisTrackRecord record = record("000660", 100_000L, 105_000L, 96_000L);
        when(repository.findByModeAndAnalysisDateOrderByCreatedAtDesc("JONGGA_V2", RECOMMENDED))
            .thenReturn(List.of(record));
        when(kisApiService.fetchCurrentPrice("000660")).thenReturn(quote("97000", "101000", "95000"));

        service.verifyRecordedSignals(RECOMMENDED);

        assertThat(record.getCloseReturn()).isEqualTo(-3.0);
        assertThat(record.isHitStop()).isTrue();
        assertThat(record.isHitTarget()).isFalse();
    }

    @Test
    void leavesRecordUnverifiedWhenQuoteIsUnavailable() {
        AnalysisTrackRecord record = record("042700", 139_000L, 145_000L, 133_000L);
        when(repository.findByModeAndAnalysisDateOrderByCreatedAtDesc("JONGGA_V2", RECOMMENDED))
            .thenReturn(List.of(record));
        when(kisApiService.fetchCurrentPrice("042700")).thenReturn(Map.of());

        assertThat(service.verifyRecordedSignals(RECOMMENDED)).isZero();

        assertThat(record.getClosePrice()).isNull();
        assertThat(record.getStatus()).isEqualTo(TrackStatus.TRACKING);
        verify(repository, never()).save(any());
    }

    @Test
    void alreadyVerifiedRecordsAreNotQuotedAgain() {
        AnalysisTrackRecord record = record("005930", 80_000L, 84_000L, 77_600L);
        record.setClosePrice(82_000L);
        when(repository.findByModeAndAnalysisDateOrderByCreatedAtDesc("JONGGA_V2", RECOMMENDED))
            .thenReturn(List.of(record));

        assertThat(service.verifyRecordedSignals(RECOMMENDED)).isZero();
        verify(kisApiService, never()).fetchCurrentPrice(eq("005930"));
    }

    private AnalysisTrackRecord record(String code, long entry, long target, long stop) {
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

    private Map<String, String> quote(String close, String high, String low) {
        return Map.of("현재가", close, "고가", high, "저가", low);
    }

    private void writeArchive() throws Exception {
        Files.writeString(dataDir.resolve("jongga_v2_results_20260723.json"), """
            {
              "date": "2026-07-23",
              "total_candidates": 5,
              "filtered_count": 5,
              "signals": [
                {
                  "stock_code": "005930", "stock_name": "삼성전자", "market": "KOSPI", "grade": "S",
                  "entry_price": 80000, "current_price": 79800, "target_price": 84000, "stop_price": 77600,
                  "score": { "total": 12, "llm_reason": "테스트용 종가매매 후보 설명입니다." }
                },
                {
                  "stock_code": "000660", "stock_name": "SK하이닉스", "market": "KOSPI", "grade": "A",
                  "entry_price": 180000, "target_price": 189000, "stop_price": 174000,
                  "score": { "total": 9, "llm_reason": "테스트용 종가매매 후보 설명입니다." }
                },
                {
                  "stock_code": "042700", "stock_name": "한미반도체", "market": "KOSPI", "grade": "A",
                  "entry_price": 139000, "target_price": 145000, "stop_price": 133000,
                  "score": { "total": 8, "llm_reason": "테스트용 종가매매 후보 설명입니다." }
                },
                {
                  "stock_code": "108490", "stock_name": "로보티즈", "market": "KOSDAQ", "grade": "B",
                  "entry_price": 31200, "target_price": 34350, "stop_price": 29900,
                  "score": { "total": 6, "llm_reason": "네 번째 후보라 추적 대상에서 제외되어야 합니다." }
                },
                {
                  "stock_code": "THEME", "stock_name": "잘못된코드", "market": "KOSPI", "grade": "B",
                  "entry_price": 1000, "target_price": 1100, "stop_price": 950,
                  "score": { "total": 5, "llm_reason": "종목코드 형식이 아니므로 제외되어야 합니다." }
                }
              ]
            }
            """);
    }
}
