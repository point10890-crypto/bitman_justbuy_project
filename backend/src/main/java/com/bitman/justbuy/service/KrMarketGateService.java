package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * KR 시장 국면(Market Gate) — JustBuy 자체 계산.
 *
 * <p>예전에는 MarketFlow Flask 의 {@code /api/kr/market-gate} 를 호출했다. 두 서비스는 별개이고, 2026-11-02 그 API 가
 * 로그인을 요구하도록 바뀌면서 매번 401 → YELLOW 로 대체돼 모든 AI 픽 점수가 0.85배로 깎였다. 이제 JustBuy 가
 * KOSPI·KOSDAQ 일별 지수(네이버, 최근 60거래일 · 당일 실시간 값 포함)로 직접 판정한다.
 *
 * <p>지수별 추세: 상승 = 종가 &gt; 20일선 &gt; 60일선, 하락 = 종가 &lt; 20일선 &lt; 60일선, 그 밖은 중립.
 * <ul>
 *   <li>GREEN — 두 지수 모두 상승 추세</li>
 *   <li>RED — 한 지수 이상 하락 추세이고 상승 추세인 지수가 없음</li>
 *   <li>YELLOW — 그 밖 (엇갈림·중립)</li>
 * </ul>
 * 10분 캐시. 조회 실패 시 하루 이내의 마지막 판정을 쓰고, 그것도 없으면 YELLOW (기존 동작과 같다).
 */
@Service
public class KrMarketGateService {

    private static final Logger log = LoggerFactory.getLogger(KrMarketGateService.class);
    static final Duration CACHE = Duration.ofMinutes(10);
    static final Duration STALE_LIMIT = Duration.ofDays(1);
    private static final String URL = "https://m.stock.naver.com/api/index/%s/price?pageSize=60&page=1";

    public enum Trend { UP, DOWN, NEUTRAL }

    public record IndexState(String index, double close, double ma20, double ma60, Trend trend) {}

    public record Gate(String gate, List<IndexState> indices, Instant computedAt, String source) {}

    private final Function<String, JsonNode> fetcher;
    private final Clock clock;
    private volatile Gate last;
    private volatile boolean failing;

    @Autowired
    public KrMarketGateService(RestTemplate restTemplate, ObjectMapper mapper) {
        this(index -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            try {
                var resp = restTemplate.exchange(String.format(URL, index), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
                return resp.getBody() == null ? null : mapper.readTree(resp.getBody());
            } catch (Exception e) {
                throw new IllegalStateException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
        }, Clock.systemUTC());
    }

    KrMarketGateService(Function<String, JsonNode> fetcher, Clock clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    /** GREEN / YELLOW / RED. Never throws. */
    public String gate() {
        return current().gate();
    }

    public synchronized Gate current() {
        Instant now = clock.instant();
        if (last != null && "NAVER_INDEX".equals(last.source()) && Duration.between(last.computedAt(), now).compareTo(CACHE) < 0) {
            return last;
        }
        try {
            List<IndexState> states = List.of(state("KOSPI"), state("KOSDAQ"));
            Gate gate = new Gate(decide(states), states, now, "NAVER_INDEX");
            if (failing || last == null || !gate.gate().equals(last.gate())) {
                log.info("[MarketGate] {} — {}", gate.gate(), states);
            }
            failing = false;
            last = gate;
            return gate;
        } catch (RuntimeException e) {
            if (!failing) log.warn("[MarketGate] index fetch failed, keeping last gate: {}", e.getMessage());
            failing = true;
            if (last != null && Duration.between(last.computedAt(), now).compareTo(STALE_LIMIT) < 0) {
                return new Gate(last.gate(), last.indices(), last.computedAt(), "LAST_KNOWN");
            }
            return new Gate("YELLOW", List.of(), now, "UNAVAILABLE");
        }
    }

    static String decide(List<IndexState> states) {
        boolean allUp = states.stream().allMatch(s -> s.trend() == Trend.UP);
        boolean anyUp = states.stream().anyMatch(s -> s.trend() == Trend.UP);
        boolean anyDown = states.stream().anyMatch(s -> s.trend() == Trend.DOWN);
        if (allUp) return "GREEN";
        if (anyDown && !anyUp) return "RED";
        return "YELLOW";
    }

    IndexState state(String index) {
        JsonNode rows = fetcher.apply(index);
        if (rows == null || !rows.isArray()) throw new IllegalStateException(index + " index rows missing");
        List<Double> closes = new ArrayList<>();             // newest first
        for (JsonNode row : rows) {
            String text = row.path("closePrice").asText("").replace(",", "");
            if (!text.isBlank()) closes.add(Double.parseDouble(text));
        }
        if (closes.size() < 60) throw new IllegalStateException(index + " has " + closes.size() + " of 60 sessions");
        double close = closes.get(0);
        double ma20 = closes.subList(0, 20).stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double ma60 = closes.subList(0, 60).stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        Trend trend = close > ma20 && ma20 > ma60 ? Trend.UP
            : close < ma20 && ma20 < ma60 ? Trend.DOWN : Trend.NEUTRAL;
        return new IndexState(index, close, round(ma20), round(ma60), trend);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
