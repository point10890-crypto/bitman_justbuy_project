package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.performance.JonggaPerformanceResponse;
import com.bitman.justbuy.dto.performance.JonggaPerformanceResponse.PerformanceRow;
import com.bitman.justbuy.entity.AnalysisTrackRecord;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JonggaPerformanceServiceTest {

    @TempDir Path dataDir;

    @Mock TrackRecordRepository repository;

    private JonggaPerformanceService service;

    @BeforeEach
    void setUp() {
        JonggaV2SearchService searchService = new JonggaV2SearchService(new ObjectMapper(), dataDir.toString());
        service = new JonggaPerformanceService(searchService, repository);
    }

    @Test
    void mergesArchiveListWithTrackedPerformance() throws Exception {
        writeArchive("20260723", "2026-07-23");
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(
            anyString(), any(), any()))
            .thenReturn(List.of(verified("005930", LocalDate.of(2026, 7, 23), 80_000L, 82_000L, 2.5, 85_000L, 6.25)));

        JonggaPerformanceResponse response = service.getPerformance("2026-07-20", "2026-07-24");

        assertThat(response.days()).hasSize(1);
        assertThat(response.days().get(0).date()).isEqualTo("2026-07-23");

        List<PerformanceRow> rows = response.days().get(0).rows();
        assertThat(rows).hasSize(2);

        PerformanceRow tracked = rows.get(0);
        assertThat(tracked.stockName()).isEqualTo("삼성전자");
        assertThat(tracked.entryPrice()).isEqualTo("80,000");
        assertThat(tracked.closePrice()).isEqualTo("82,000");
        assertThat(tracked.closeReturnPct()).isEqualTo("+2.50%");
        assertThat(tracked.maxReturnPct()).isEqualTo("+6.25%");
        assertThat(tracked.result()).isEqualTo("승");
        assertThat(tracked.hitTarget()).isTrue();

        PerformanceRow untracked = rows.get(1);
        assertThat(untracked.stockCode()).isEqualTo("000660");
        assertThat(untracked.closePrice()).isEqualTo("-");
        assertThat(untracked.result()).isEqualTo("미검증");

        assertThat(response.days().get(0).verified()).isFalse();
    }

    @Test
    void aggregatesOnlyOverVerifiedRows() throws Exception {
        writeArchive("20260722", "2026-07-22");
        writeArchive("20260723", "2026-07-23");
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(
            anyString(), any(), any()))
            .thenReturn(List.of(
                verified("005930", LocalDate.of(2026, 7, 23), 80_000L, 82_000L, 2.5, 85_000L, 6.25),
                verified("005930", LocalDate.of(2026, 7, 22), 80_000L, 78_400L, -2.0, 80_800L, 1.0)
            ));

        JonggaPerformanceResponse response = service.getPerformance("2026-07-20", "2026-07-24");

        assertThat(response.totalSignals()).isEqualTo(4);
        assertThat(response.verifiedCount()).isEqualTo(2);
        assertThat(response.wins()).isEqualTo(1);
        assertThat(response.losses()).isEqualTo(1);
        assertThat(response.avgCloseReturnPct()).isEqualTo("+0.25%");
        assertThat(response.avgMaxReturnPct()).isEqualTo("+3.63%");
        assertThat(response.winRate()).isEqualTo("50%");
        assertThat(response.note()).contains("미검증");
    }

    @Test
    void datesWithoutArchiveAreOmitted() {
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(
            anyString(), any(), any()))
            .thenReturn(List.of());

        JonggaPerformanceResponse response = service.getPerformance("2026-07-20", "2026-07-24");

        assertThat(response.days()).isEmpty();
        assertThat(response.totalSignals()).isZero();
        assertThat(response.note()).contains("기록이 없습니다");
    }

    @Test
    void daysAreReturnedNewestFirst() throws Exception {
        writeArchive("20260722", "2026-07-22");
        writeArchive("20260723", "2026-07-23");
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(
            anyString(), any(), any()))
            .thenReturn(List.of());

        JonggaPerformanceResponse response = service.getPerformance("2026-07-20", "2026-07-24");

        assertThat(response.days()).extracting(JonggaPerformanceResponse.DayGroup::date)
            .containsExactly("2026-07-23", "2026-07-22");
    }

    @Test
    void aDayWithOnlyUnmeasurableReturnsIsNotVerifiedOrWaitingForMissingRecords() throws Exception {
        writeArchive("20260723", "2026-07-23");
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(anyString(), any(), any()))
            .thenReturn(List.of(
                verified("005930", LocalDate.of(2026, 7, 23), 80_000L, 40_000L, -50.0, 44_000L, -45.0),
                verified("000660", LocalDate.of(2026, 7, 23), 180_000L, 90_000L, -50.0, 99_000L, -45.0)));

        var response = service.getPerformance("2026-07-23", "2026-07-23");

        assertThat(response.days().getFirst().verified()).isFalse();
        assertThat(response.days().getFirst().rows()).extracting(PerformanceRow::result)
            .containsExactly("검증불가", "검증불가");
        assertThat(response.verifiedCount()).isZero();
        assertThat(response.avgCloseReturnPct()).isEqualTo("-");
        assertThat(response.note()).contains("검증불가").doesNotContain("검증 기록이 아직 없습니다");
    }

    @Test
    void anUnmeasurableRowKeepsTheDayIncompleteWithoutPollutingValidReturnStatistics() throws Exception {
        writeArchive("20260723", "2026-07-23");
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(anyString(), any(), any()))
            .thenReturn(List.of(
                verified("005930", LocalDate.of(2026, 7, 23), 80_000L, 82_000L, 2.5, 85_000L, 6.25),
                verified("000660", LocalDate.of(2026, 7, 23), 180_000L, 90_000L, -50.0, 99_000L, -45.0)));

        var response = service.getPerformance("2026-07-23", "2026-07-23");

        assertThat(response.days().getFirst().verified()).isFalse();
        assertThat(response.verifiedCount()).isEqualTo(1);
        assertThat(response.wins()).isEqualTo(1);
        assertThat(response.avgCloseReturnPct()).isEqualTo("+2.50%");
        assertThat(response.avgMaxReturnPct()).isEqualTo("+6.25%");
        assertThat(response.note()).contains("검증불가").doesNotContain("미검증으로 표시");
    }

    @Test
    void displayedEntryUsesTheRecordedReturnBaselineWhenTheArchiveWasLaterChanged() throws Exception {
        writeArchive("20260723", "2026-07-23");
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(anyString(), any(), any()))
            .thenReturn(List.of(verified("005930", LocalDate.of(2026, 7, 23), 90_000L, 94_500L, 5.0, 99_000L, 10.0)));

        var response = service.getPerformance("2026-07-23", "2026-07-23");
        var tracked = response.days().getFirst().rows().getFirst();

        assertThat(tracked.entryPrice()).isEqualTo("90,000");
        assertThat(tracked.closePrice()).isEqualTo("94,500");
        assertThat(tracked.closeReturnPct()).isEqualTo("+5.00%");
        assertThat(response.days().getFirst().rows().get(1).entryPrice()).isEqualTo("180,000");
    }

    @Test
    void aMissingRecordedBaselineCannotBeReplacedByTheArchiveToValidateReturns() throws Exception {
        writeArchive("20260723", "2026-07-23");
        for (Long invalidBaseline : java.util.Arrays.asList(null, 0L, -1L)) {
            var record = verified("005930", LocalDate.of(2026, 7, 23), 80_000L, 82_000L, 2.5, 85_000L, 6.25);
            record.setPriceAtAnalysis(invalidBaseline);
            when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(anyString(), any(), any()))
                .thenReturn(List.of(record));

            var response = service.getPerformance("2026-07-23", "2026-07-23");
            var row = response.days().getFirst().rows().getFirst();

            assertThat(row.entryPrice()).isEqualTo("-");
            assertThat(row.result()).isEqualTo("검증불가");
            assertThat(row.closeReturnPct()).isEqualTo("-");
            assertThat(row.maxReturnPct()).isEqualTo("-");
            assertThat(response.verifiedCount()).isZero();
        }
    }

    @Test
    void aDayWithOnlyMeasurableWinsOrFlatsRemainsVerified() throws Exception {
        writeArchive("20260723", "2026-07-23");
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(anyString(), any(), any()))
            .thenReturn(List.of(
                verified("005930", LocalDate.of(2026, 7, 23), 80_000L, 82_000L, 2.5, 85_000L, 6.25),
                verified("000660", LocalDate.of(2026, 7, 23), 180_000L, 180_000L, 0.0, 185_400L, 3.0)));

        var response = service.getPerformance("2026-07-23", "2026-07-23");

        assertThat(response.days().getFirst().verified()).isTrue();
        assertThat(response.days().getFirst().rows()).extracting(PerformanceRow::result).containsExactly("승", "보합");
        assertThat(response.verifiedCount()).isEqualTo(2);
        assertThat(response.flats()).isEqualTo(1);
    }

    @Test
    void displayedTargetAndStopUseTheSameRecordedLevelsAsTheHitFlags() throws Exception {
        writeArchive("20260723", "2026-07-23");
        var record = verified("005930", LocalDate.of(2026, 7, 23), 90_000L, 94_500L, 5.0, 99_000L, 10.0);
        record.setTargetPrice(100_000L);
        record.setStopLoss(87_000L);
        record.setHitTarget(false);
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(anyString(), any(), any()))
            .thenReturn(List.of(record));

        var row = service.getPerformance("2026-07-23", "2026-07-23").days().getFirst().rows().getFirst();

        assertThat(row.targetPrice()).isEqualTo("100,000");
        assertThat(row.stopLoss()).isEqualTo("87,000");
        assertThat(row.hitTarget()).isFalse();
        assertThat(row.hitStop()).isFalse();
    }

    private AnalysisTrackRecord verified(String code, LocalDate date, long entry, long close,
                                         double closeReturn, long high, double maxReturn) {
        AnalysisTrackRecord record = new AnalysisTrackRecord();
        record.setMode("JONGGA_V2");
        record.setAnalysisDate(date);
        record.setStockCode(code);
        record.setStockName(code);
        record.setPriceAtAnalysis(entry);
        record.setTargetPrice(84_000L);
        record.setStopLoss(77_600L);
        record.setClosePrice(close);
        record.setCloseReturn(closeReturn);
        record.setHighPrice1d(high);
        record.setMaxReturn1d(maxReturn);
        record.setHitTarget(high >= 84_000L);
        return record;
    }

    private void writeArchive(String fileDate, String date) throws Exception {
        Files.writeString(dataDir.resolve("jongga_v2_results_" + fileDate + ".json"), """
            {
              "date": "%s",
              "total_candidates": 2,
              "filtered_count": 2,
              "signals": [
                {
                  "stock_code": "005930", "stock_name": "삼성전자", "market": "KOSPI", "grade": "S",
                  "entry_price": 80000, "target_price": 84000, "stop_price": 77600,
                  "score": { "total": 12, "llm_reason": "히스토리 병합 테스트용 종가매매 후보 설명입니다." }
                },
                {
                  "stock_code": "000660", "stock_name": "SK하이닉스", "market": "KOSPI", "grade": "A",
                  "entry_price": 180000, "target_price": 189000, "stop_price": 174000,
                  "score": { "total": 9, "llm_reason": "히스토리 병합 테스트용 종가매매 후보 설명입니다." }
                }
              ]
            }
            """.formatted(date));
    }
}
