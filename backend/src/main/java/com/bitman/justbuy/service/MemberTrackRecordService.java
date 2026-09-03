package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.performance.MemberTrackRecordResponse;
import com.bitman.justbuy.dto.performance.MemberTrackRecordResponse.ModeRecord;
import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.repository.TrackRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 회원용 모드별 트랙레코드 집계.
 *
 * <p>이미 쌓여 있는 {@link AnalysisTrackRecord} 를 모드·기간으로 묶어 승률·평균수익·
 * 시장 대비 초과수익을 낸다. 새로 수집하는 데이터는 없다 — 화면이 없었을 뿐이다.
 *
 * <p>수익률 기준은 모드마다 다르게 채워진다. 종가매매는 익일 종가({@code closeReturn}),
 * 나머지 모드는 가격 갱신 잡이 채우는 {@code return1d} 다. 둘 다 "추천일 → 다음 세션"
 * 이라 같은 축으로 비교할 수 있으므로 closeReturn 을 우선 쓰고 없으면 return1d 를 쓴다.
 */
@Service
@Transactional(readOnly = true)
public class MemberTrackRecordService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 365;

    /**
     * 모드별 성과 기준. <b>모드마다 수익률의 의미가 다르다</b> — 하나로 뭉뚱그리면 거짓말이 된다.
     *
     * <p>단타는 장중 포착가 대비 <b>같은 날</b> 종가({@code verifyTodayShortTermClose} 가 당일을 검증),
     * 종가매매는 추천일 종가 대비 <b>다음 세션</b> 종가, 나머지 프리컴퓨트 모드는 가격 갱신 잡이
     * 캘린더 1일 이후 현재가로 채우는 값이다.
     *
     * <p>{@code benchmarkComparable} 은 시장 대비 초과수익을 계산해도 되는지다. 벤치마크는
     * "추천일 종가 → 다음 세션 종가" 창이라, 장중 진입(단타)이나 창이 불분명한 모드에 빼면
     * 겹치지도 않는 구간을 뺀 숫자가 나온다. 그런 모드는 초과수익을 내지 않는다.
     */
    private record ModeSpec(String mode, String title, String returnBasis,
                            String hitRateBasis, boolean benchmarkComparable) {}

    private static final List<ModeSpec> MODE_SPECS = List.of(
        new ModeSpec("BREAKOUT", "단타", "장중 포착가 → 당일 종가", "추적 기간 중 도달", false),
        new ModeSpec("REVERSAL_EDGE", "스윙", "추천가 → 1일 후 시세", "추적 기간(최대 5일) 중 도달", false),
        new ModeSpec("FLOW_LEADER", "주도주", "추천가 → 1일 후 시세", "추적 기간(최대 5일) 중 도달", false),
        new ModeSpec("CATALYST_BURST", "테마주", "추천가 → 1일 후 시세", "추적 기간(최대 5일) 중 도달", false),
        new ModeSpec("JONGGA_V2", "종가매매", "추천일 종가 → 익일 종가", "익일 고가/저가 기준", true)
    );

    /** 국내 일일 가격제한폭(±30%) + 반올림 여유. 초과 = corporate action 오염. */
    private static final double DAILY_PRICE_LIMIT_PCT = 30.5;

    private final TrackRecordRepository repository;
    private final MarketBenchmarkService benchmarkService;

    public MemberTrackRecordService(TrackRecordRepository repository,
                                    MarketBenchmarkService benchmarkService) {
        this.repository = repository;
        this.benchmarkService = benchmarkService;
    }

    public MemberTrackRecordResponse getTrackRecord(Integer daysParam) {
        int days = daysParam == null ? DEFAULT_DAYS : Math.max(1, Math.min(daysParam, MAX_DAYS));
        LocalDate to = LocalDate.now(KST);
        LocalDate from = to.minusDays(days);

        // 벤치마크는 구간 전체를 한 번만 조회한다. 레코드마다 부르면 KIS 레이트리밋에 걸린다.
        Map<LocalDate, KisApiService.DailyOhlc> benchmark = benchmarkService == null
            ? Map.of()
            : benchmarkService.series(MarketBenchmarkService.KOSPI_PROXY, from, to);

        List<ModeRecord> modes = new ArrayList<>();
        List<Sample> all = new ArrayList<>();

        for (ModeSpec spec : MODE_SPECS) {
            List<Sample> samples = samplesFor(spec, from, to, benchmark);
            all.addAll(samples);
            modes.add(aggregate(spec, samples));
        }

        // 전체는 기준이 다른 모드를 합친 값이라 단일 지표로 읽히면 안 된다. 라벨로 명시한다.
        ModeRecord overall = aggregate(
            new ModeSpec("ALL", "전체", "모드별 기준 혼합", "모드별 기준 혼합", false), all);
        String benchmarkLabel = benchmark.isEmpty() ? null : "KOSPI200 ETF 대비";

        return new MemberTrackRecordResponse(
            from.toString(), to.toString(), days, modes, overall, benchmarkLabel, note(overall, benchmark.isEmpty()));
    }

    private static String note(ModeRecord overall, boolean benchmarkMissing) {
        if (overall.totalSignals() == 0) {
            return "해당 기간에 기록된 포착이 없습니다.";
        }
        if (overall.verifiedCount() == 0) {
            return "포착 기록은 있으나 익일 성과 검증이 아직 채워지지 않았습니다.";
        }
        // 모드마다 수익률 기준이 다르므로 한 문장으로 뭉뚱그리지 않는다.
        String base = "모드마다 성과 기준이 다릅니다. 각 카드의 기준 표기를 함께 확인하세요."
            + " 시장 대비 초과수익은 진입·청산 구간이 벤치마크와 일치하는 종가매매에만 표시됩니다."
            + " 과거 성과는 미래 수익을 보장하지 않습니다.";
        return benchmarkMissing ? base + " 현재 시장 지수 조회에 실패해 초과수익은 표시하지 않습니다." : base;
    }

    /** 집계에 쓸 수 있게 정제된 레코드 1건. */
    private record Sample(double ret, Double excess, boolean hitTarget, boolean hitStop,
                          boolean hasTarget, boolean hasStop, Double maxReturn) {}

    private List<Sample> samplesFor(ModeSpec spec, LocalDate from, LocalDate to,
                                    Map<LocalDate, KisApiService.DailyOhlc> benchmark) {
        List<AnalysisTrackRecord> records =
            repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(spec.mode(), from, to);

        List<Sample> samples = new ArrayList<>();
        for (AnalysisTrackRecord record : records) {
            Double ret = primaryReturn(record);
            if (ret == null) continue;
            // 하루에 나올 수 없는 수익률은 액면분할 등으로 기준이 어긋난 값이다. 성과로 세지 않는다.
            if (Math.abs(ret) > DAILY_PRICE_LIMIT_PCT) continue;

            Double excess = null;
            // 창이 맞는 모드에서만 초과수익을 낸다.
            if (spec.benchmarkComparable() && !benchmark.isEmpty() && record.getAnalysisDate() != null) {
                Double market = MarketBenchmarkService
                    .nextSessionReturnPct(benchmark, record.getAnalysisDate()).orElse(null);
                if (market != null) excess = round2(ret - market);
            }
            Double maxRet = record.getMaxReturn1d();
            if (maxRet != null && Math.abs(maxRet) > DAILY_PRICE_LIMIT_PCT) maxRet = null;

            samples.add(new Sample(ret, excess, record.isHitTarget(), record.isHitStop(),
                record.getTargetPrice() != null, record.getStopLoss() != null, maxRet));
        }
        return samples;
    }

    /** 종가매매는 closeReturn, 나머지 모드는 return1d 로 채워진다. */
    private static Double primaryReturn(AnalysisTrackRecord record) {
        if (record.getCloseReturn() != null) return record.getCloseReturn();
        return record.getReturn1d();
    }

    private ModeRecord aggregate(ModeSpec spec, List<Sample> samples) {
        if (samples.isEmpty()) {
            return new ModeRecord(spec.mode(), spec.title(), spec.returnBasis(), spec.hitRateBasis(),
                0, 0, 0, 0, "-", "-", "-", "-", "-", "-", "-", "-");
        }

        int wins = 0, losses = 0, targetHits = 0, stopHits = 0, beats = 0;
        int targetSet = 0, stopSet = 0;
        double retSum = 0, maxSum = 0, benchSum = 0, excessSum = 0;
        int maxCount = 0, excessCount = 0;

        for (Sample s : samples) {
            retSum += s.ret();
            if (s.ret() > 0) wins++; else if (s.ret() < 0) losses++;
            // 목표가/손절가가 애초에 설정된 건에 대해서만 도달률을 센다.
            // 값이 없는 모드까지 분모에 넣으면 "손절 0%" 같은 착시가 생긴다.
            if (s.hasTarget()) { targetSet++; if (s.hitTarget()) targetHits++; }
            if (s.hasStop())   { stopSet++;  if (s.hitStop())   stopHits++; }
            if (s.maxReturn() != null) { maxSum += s.maxReturn(); maxCount++; }
            if (s.excess() != null) {
                excessSum += s.excess();
                benchSum += s.ret() - s.excess();
                excessCount++;
                if (s.excess() > 0) beats++;
            }
        }

        int n = samples.size();
        return new ModeRecord(
            spec.mode(), spec.title(), spec.returnBasis(), spec.hitRateBasis(),
            n,            // 총 포착 건수는 검증된 건수와 같은 값만 정직하게 셀 수 있다
            n,
            wins,
            losses,
            pct(wins, n),
            signed(retSum / n),
            maxCount > 0 ? signed(maxSum / maxCount) : "-",
            pct(targetHits, targetSet),
            pct(stopHits, stopSet),
            excessCount > 0 ? signed(benchSum / excessCount) : "-",
            excessCount > 0 ? signed(excessSum / excessCount) : "-",
            excessCount > 0 ? pct(beats, excessCount) : "-"
        );
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String signed(double v) {
        // -0.005 ~ 0 구간이 "-0.00%" 로 찍혀 손실색으로 렌더되는 것을 막는다.
        double normalized = Math.abs(v) < 0.005 ? 0.0 : v;
        return String.format(Locale.KOREA, "%+.2f%%", normalized);
    }

    private static String pct(int numerator, int denominator) {
        if (denominator <= 0) return "-";
        return Math.round((double) numerator / denominator * 100) + "%";
    }
}
