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

    /** 노출 순서 = 홈 화면 섹션 순서. */
    private static final Map<String, String> MODE_TITLES = new LinkedHashMap<>();
    static {
        MODE_TITLES.put("BREAKOUT", "단타");
        MODE_TITLES.put("REVERSAL_EDGE", "스윙");
        MODE_TITLES.put("FLOW_LEADER", "주도주");
        MODE_TITLES.put("CATALYST_BURST", "테마주");
        MODE_TITLES.put("JONGGA_V2", "종가매매");
    }

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

        for (Map.Entry<String, String> entry : MODE_TITLES.entrySet()) {
            List<Sample> samples = samplesFor(entry.getKey(), from, to, benchmark);
            all.addAll(samples);
            modes.add(aggregate(entry.getKey(), entry.getValue(), samples));
        }

        ModeRecord overall = aggregate("ALL", "전체", all);
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
        String base = "추천일 종가 진입 기준, 다음 거래일 결과입니다. 과거 성과는 미래 수익을 보장하지 않습니다.";
        return benchmarkMissing ? base + " 시장 지수 조회에 실패해 초과수익은 표시하지 않습니다." : base;
    }

    /** 집계에 쓸 수 있게 정제된 레코드 1건. */
    private record Sample(double ret, Double excess, boolean hitTarget, boolean hitStop, Double maxReturn) {}

    private List<Sample> samplesFor(String mode, LocalDate from, LocalDate to,
                                    Map<LocalDate, KisApiService.DailyOhlc> benchmark) {
        List<AnalysisTrackRecord> records =
            repository.findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(mode, from, to);

        List<Sample> samples = new ArrayList<>();
        for (AnalysisTrackRecord record : records) {
            Double ret = primaryReturn(record);
            if (ret == null) continue;
            // 하루에 나올 수 없는 수익률은 액면분할 등으로 기준이 어긋난 값이다. 성과로 세지 않는다.
            if (Math.abs(ret) > DAILY_PRICE_LIMIT_PCT) continue;

            Double excess = null;
            if (!benchmark.isEmpty() && record.getAnalysisDate() != null) {
                Double market = MarketBenchmarkService
                    .nextSessionReturnPct(benchmark, record.getAnalysisDate()).orElse(null);
                if (market != null) excess = round2(ret - market);
            }
            Double maxRet = record.getMaxReturn1d();
            if (maxRet != null && Math.abs(maxRet) > DAILY_PRICE_LIMIT_PCT) maxRet = null;

            samples.add(new Sample(ret, excess, record.isHitTarget(), record.isHitStop(), maxRet));
        }
        return samples;
    }

    /** 종가매매는 closeReturn, 나머지 모드는 return1d 로 채워진다. */
    private static Double primaryReturn(AnalysisTrackRecord record) {
        if (record.getCloseReturn() != null) return record.getCloseReturn();
        return record.getReturn1d();
    }

    private ModeRecord aggregate(String mode, String title, List<Sample> samples) {
        int total = countRecorded(mode);
        if (samples.isEmpty()) {
            return new ModeRecord(mode, title, total, 0, 0, 0,
                "-", "-", "-", "-", "-", "-", "-", "-");
        }

        int wins = 0, losses = 0, targetHits = 0, stopHits = 0, beats = 0;
        double retSum = 0, maxSum = 0, benchSum = 0, excessSum = 0;
        int maxCount = 0, excessCount = 0;

        for (Sample s : samples) {
            retSum += s.ret();
            if (s.ret() > 0) wins++; else if (s.ret() < 0) losses++;
            if (s.hitTarget()) targetHits++;
            if (s.hitStop()) stopHits++;
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
            mode, title,
            Math.max(total, n),
            n,
            wins,
            losses,
            pct(wins, n),
            signed(retSum / n),
            maxCount > 0 ? signed(maxSum / maxCount) : "-",
            pct(targetHits, n),
            pct(stopHits, n),
            excessCount > 0 ? signed(benchSum / excessCount) : "-",
            excessCount > 0 ? signed(excessSum / excessCount) : "-",
            excessCount > 0 ? pct(beats, excessCount) : "-"
        );
    }

    /** 검증 전 레코드까지 포함한 총 포착 건수. ALL 은 모드 합으로 채워지므로 0 을 준다. */
    private int countRecorded(String mode) {
        return 0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String signed(double v) {
        return String.format(Locale.KOREA, "%+.2f%%", v);
    }

    private static String pct(int numerator, int denominator) {
        if (denominator <= 0) return "-";
        return Math.round((double) numerator / denominator * 100) + "%";
    }
}
