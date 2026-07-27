package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시장 대비 초과수익 계산 — 추천일 종가 대비 다음 세션 종가.
 */
class MarketBenchmarkServiceTest {

    private static final LocalDate REC = LocalDate.of(2026, 7, 23);

    @Test
    void picksKosdaqProxyOnlyForKosdaqMarkets() {
        assertThat(MarketBenchmarkService.proxyFor("KOSDAQ")).isEqualTo(MarketBenchmarkService.KOSDAQ_PROXY);
        assertThat(MarketBenchmarkService.proxyFor("코스닥")).isEqualTo(MarketBenchmarkService.KOSDAQ_PROXY);
        assertThat(MarketBenchmarkService.proxyFor("KOSPI")).isEqualTo(MarketBenchmarkService.KOSPI_PROXY);
        assertThat(MarketBenchmarkService.proxyFor("")).isEqualTo(MarketBenchmarkService.KOSPI_PROXY);
        assertThat(MarketBenchmarkService.proxyFor(null)).isEqualTo(MarketBenchmarkService.KOSPI_PROXY);
    }

    @Test
    void computesReturnFromRecommendationCloseToNextSessionClose() {
        Map<LocalDate, KisApiService.DailyOhlc> series = series(Map.of(
            REC, 10_000L,
            REC.plusDays(1), 10_200L
        ));

        assertThat(MarketBenchmarkService.nextSessionReturnPct(series, REC))
            .contains(2.0);
    }

    @Test
    void skipsNonTradingDaysOnBothEnds() {
        // 추천일이 휴장(시리즈에 없음) -> 직전 세션이 기준, 다음 세션은 이틀 뒤
        Map<LocalDate, KisApiService.DailyOhlc> series = series(Map.of(
            REC.minusDays(1), 20_000L,
            REC.plusDays(3), 19_000L
        ));

        assertThat(MarketBenchmarkService.nextSessionReturnPct(series, REC))
            .contains(-5.0);
    }

    @Test
    void returnsEmptyWhenSeriesCannotCoverTheWindow() {
        assertThat(MarketBenchmarkService.nextSessionReturnPct(Map.of(), REC)).isEmpty();
        assertThat(MarketBenchmarkService.nextSessionReturnPct(null, REC)).isEmpty();
        // 다음 세션이 없으면 계산 불가
        assertThat(MarketBenchmarkService.nextSessionReturnPct(series(Map.of(REC, 10_000L)), REC)).isEmpty();
        // 직전 세션이 없으면 계산 불가
        assertThat(MarketBenchmarkService.nextSessionReturnPct(
            series(Map.of(REC.plusDays(1), 10_000L)), REC)).isEmpty();
    }

    private static Map<LocalDate, KisApiService.DailyOhlc> series(Map<LocalDate, Long> closes) {
        Map<LocalDate, KisApiService.DailyOhlc> out = new LinkedHashMap<>();
        closes.forEach((date, close) ->
            out.put(date, new KisApiService.DailyOhlc(date, close, close, close, close, 1L)));
        return out;
    }
}
