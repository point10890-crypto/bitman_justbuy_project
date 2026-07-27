package com.bitman.justbuy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * 시장 벤치마크 수익률.
 *
 * <p>추천 성과가 "전략이 좋아서"인지 "장이 좋아서"인지 구분하려면 같은 구간의 시장
 * 수익률을 빼야 한다. 종가매매는 추천일 종가에 진입해 다음 세션 종가에 청산하므로
 * 벤치마크도 같은 구간(<b>추천일 종가 -&gt; 다음 세션 종가</b>)으로 잡는다.
 *
 * <p>지수 전용 API 대신 지수 추종 ETF 를 프록시로 쓴다. 이미 검증된
 * {@link KisApiService#fetchDailyOhlc}(종목 일봉)를 그대로 재사용하므로 새 TR 을 붙이지 않는다.
 *
 * <p>조회는 <b>구간 전체를 한 번</b>만 한다. 날짜마다 호출하면 조회 1건에 수십 번의 KIS
 * 요청이 나가 레이트리밋에 걸린다. 실패 시 빈 시리즈 → 초과수익은 "-" 로 표시된다(fail-open).
 */
@Service
public class MarketBenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(MarketBenchmarkService.class);

    /** KODEX 200 — KOSPI 프록시. */
    public static final String KOSPI_PROXY = "069500";
    /** KODEX 코스닥150 — KOSDAQ 프록시. */
    public static final String KOSDAQ_PROXY = "229200";

    private final KisApiService kisApiService;

    public MarketBenchmarkService(KisApiService kisApiService) {
        this.kisApiService = kisApiService;
    }

    /** 아카이브의 market 문자열로 벤치마크 종목코드를 고른다. 알 수 없으면 KOSPI. */
    public static String proxyFor(String market) {
        if (market == null) return KOSPI_PROXY;
        String upper = market.toUpperCase();
        if (upper.contains("KOSDAQ") || market.contains("코스닥")) return KOSDAQ_PROXY;
        return KOSPI_PROXY;
    }

    /**
     * 구간 일봉 시리즈. 앞뒤로 여유를 둬 구간 경계일의 직전/다음 세션까지 포함한다.
     *
     * @return 일자 -&gt; 일봉. 실패 시 빈 맵.
     */
    public Map<LocalDate, KisApiService.DailyOhlc> series(String proxyCode, LocalDate from, LocalDate to) {
        if (proxyCode == null || from == null || to == null) return Map.of();
        try {
            return kisApiService.fetchDailyOhlc(proxyCode, from.minusDays(10), to.plusDays(10));
        } catch (Exception e) {
            log.debug("[Benchmark] 시리즈 조회 실패 {}: {}", proxyCode, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 추천일 종가 대비 <b>다음 세션</b> 종가의 수익률(%).
     *
     * <p>종목 검증과 같은 방식으로 실제 일봉에서 세션을 잡으므로 휴장일 달력에 의존하지 않는다.
     */
    public static Optional<Double> nextSessionReturnPct(Map<LocalDate, KisApiService.DailyOhlc> series,
                                                        LocalDate recommendedDate) {
        if (series == null || series.isEmpty() || recommendedDate == null) return Optional.empty();

        KisApiService.DailyOhlc base = lastSessionOnOrBefore(series, recommendedDate);
        KisApiService.DailyOhlc next = firstSessionAfter(series, recommendedDate);
        if (base == null || next == null) return Optional.empty();
        if (base.close() <= 0 || next.close() <= 0) return Optional.empty();

        double pct = ((double) next.close() - base.close()) / base.close() * 100.0;
        return Optional.of(Math.round(pct * 100.0) / 100.0);
    }

    /** 기준일 이하에서 가장 늦은 세션. 추천일이 휴장이어도 직전 세션을 잡는다. */
    static KisApiService.DailyOhlc lastSessionOnOrBefore(Map<LocalDate, KisApiService.DailyOhlc> series,
                                                        LocalDate date) {
        if (series == null || series.isEmpty() || date == null) return null;
        return series.entrySet().stream()
            .filter(e -> e.getKey() != null && !e.getKey().isAfter(date))
            .filter(e -> e.getValue() != null && e.getValue().close() > 0)
            .max(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .orElse(null);
    }

    /** 기준일 이후 첫 세션. */
    static KisApiService.DailyOhlc firstSessionAfter(Map<LocalDate, KisApiService.DailyOhlc> series,
                                                    LocalDate date) {
        if (series == null || series.isEmpty() || date == null) return null;
        return series.entrySet().stream()
            .filter(e -> e.getKey() != null && e.getKey().isAfter(date))
            .filter(e -> e.getValue() != null && e.getValue().close() > 0)
            .min(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .orElse(null);
    }
}
