package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.performance.MemberTrackRecordResponse;
import com.bitman.justbuy.dto.performance.MemberTrackRecordResponse.ModeRecord;
import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.repository.TrackRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 회원 트랙레코드 집계 — 상품의 본체는 "성적표"이므로 숫자가 정직해야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberTrackRecordServiceTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Mock TrackRecordRepository repository;
    @Mock MarketBenchmarkService benchmark;

    private MemberTrackRecordService service() {
        when(benchmark.series(anyString(), any(), any())).thenReturn(Map.of());
        return new MemberTrackRecordService(repository, benchmark);
    }

    private void stub(String mode, AnalysisTrackRecord... records) {
        when(repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(
            eq(mode), any(), any())).thenReturn(List.of(records));
    }

    @Test
    void aggregatesWinRateAndAverageReturnPerMode() {
        stub("BREAKOUT",
            record(TODAY.minusDays(2), 5.0, 7.0, true, false),
            record(TODAY.minusDays(3), -3.0, 1.0, false, true),
            record(TODAY.minusDays(4), 1.0, 2.0, false, false));

        MemberTrackRecordResponse res = service().getTrackRecord(30);
        ModeRecord breakout = res.modes().stream().filter(m -> m.mode().equals("BREAKOUT")).findFirst().orElseThrow();

        assertThat(breakout.title()).isEqualTo("단타");
        assertThat(breakout.verifiedCount()).isEqualTo(3);
        assertThat(breakout.wins()).isEqualTo(2);
        assertThat(breakout.losses()).isEqualTo(1);
        assertThat(breakout.winRate()).isEqualTo("67%");
        assertThat(breakout.avgReturnPct()).isEqualTo("+1.00%");
        assertThat(breakout.targetHitRate()).isEqualTo("33%");
        assertThat(breakout.stopHitRate()).isEqualTo("33%");
    }

    @Test
    void unverifiedRecordsAreExcludedNotCountedAsZero() {
        // 수익률이 아직 안 채워진 레코드를 0% 로 세면 평균이 실제보다 좋아 보인다
        AnalysisTrackRecord pending = record(TODAY.minusDays(1), null, null, false, false);
        stub("FLOW_LEADER", record(TODAY.minusDays(2), 4.0, 4.0, false, false), pending);

        ModeRecord leaders = service().getTrackRecord(30).modes().stream()
            .filter(m -> m.mode().equals("FLOW_LEADER")).findFirst().orElseThrow();

        assertThat(leaders.verifiedCount()).isEqualTo(1);
        assertThat(leaders.avgReturnPct()).isEqualTo("+4.00%");
    }

    @Test
    void corporateActionOutliersAreDropped() {
        // 하루 -51% 는 가격제한(±30%)상 불가능 — 액면분할 등으로 기준이 어긋난 값
        stub("JONGGA_V2",
            record(TODAY.minusDays(2), -51.35, -46.0, false, true),
            record(TODAY.minusDays(3), 2.0, 3.0, false, false));

        ModeRecord jongga = service().getTrackRecord(30).modes().stream()
            .filter(m -> m.mode().equals("JONGGA_V2")).findFirst().orElseThrow();

        assertThat(jongga.verifiedCount()).isEqualTo(1);
        assertThat(jongga.avgReturnPct()).isEqualTo("+2.00%");
    }

    @Test
    void emptyModeReportsDashNotZeroPercent() {
        ModeRecord swing = service().getTrackRecord(30).modes().stream()
            .filter(m -> m.mode().equals("REVERSAL_EDGE")).findFirst().orElseThrow();

        assertThat(swing.verifiedCount()).isZero();
        assertThat(swing.winRate()).isEqualTo("-");
        assertThat(swing.avgReturnPct()).isEqualTo("-");
    }

    @Test
    void excessReturnIsComputedAgainstTheBenchmarkWhenAvailable() {
        LocalDate d = TODAY.minusDays(2);
        LocalDate next = TODAY.minusDays(1);
        Map<LocalDate, KisApiService.DailyOhlc> series = Map.of(
            d, new KisApiService.DailyOhlc(d, 100, 100, 100, 100, 1L),
            next, new KisApiService.DailyOhlc(next, 101, 101, 101, 101, 1L)   // 시장 +1.00%
        );
        when(benchmark.series(anyString(), any(), any())).thenReturn(series);
        stub("BREAKOUT", record(d, 5.0, 6.0, false, false));   // 종목 +5.00%

        MemberTrackRecordResponse res = new MemberTrackRecordService(repository, benchmark).getTrackRecord(30);
        ModeRecord breakout = res.modes().stream().filter(m -> m.mode().equals("BREAKOUT")).findFirst().orElseThrow();

        assertThat(breakout.avgBenchmarkReturnPct()).isEqualTo("+1.00%");
        assertThat(breakout.avgExcessReturnPct()).isEqualTo("+4.00%");
        assertThat(breakout.marketBeatRate()).isEqualTo("100%");
        assertThat(res.benchmarkLabel()).isNotNull();
    }

    @Test
    void overallRollsUpEveryMode() {
        stub("BREAKOUT", record(TODAY.minusDays(2), 10.0, 10.0, false, false));
        stub("JONGGA_V2", record(TODAY.minusDays(2), -2.0, 1.0, false, false));

        ModeRecord overall = service().getTrackRecord(30).overall();

        assertThat(overall.verifiedCount()).isEqualTo(2);
        assertThat(overall.avgReturnPct()).isEqualTo("+4.00%");
        assertThat(overall.winRate()).isEqualTo("50%");
    }

    private static AnalysisTrackRecord record(LocalDate date, Double closeReturn, Double maxReturn,
                                              boolean hitTarget, boolean hitStop) {
        AnalysisTrackRecord r = new AnalysisTrackRecord();
        r.setAnalysisDate(date);
        r.setCloseReturn(closeReturn);
        r.setMaxReturn1d(maxReturn);
        r.setHitTarget(hitTarget);
        r.setHitStop(hitStop);
        return r;
    }
}
