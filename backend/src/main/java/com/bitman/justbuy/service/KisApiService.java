package com.bitman.justbuy.service;

import com.bitman.justbuy.config.KisProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.springframework.scheduling.annotation.Scheduled;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KIS (한국투자증권) OpenAPI 서비스.
 * 실시간 시세, 외국인/기관 수급 데이터를 조회하여 AI 분석에 실제 시장 데이터를 제공.
 */
@Service
public class KisApiService {

    private static final Logger log = LoggerFactory.getLogger(KisApiService.class);
    private static final String KIS_BASE = "https://openapi.koreainvestment.com:9443";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final KisProperties kisProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 토큰 캐시
    private volatile String accessToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;
    private volatile Instant tokenErrorCooldown = Instant.EPOCH; // 토큰 실패 시 2분 쿨다운

    // 데이터 캐시 (10분 TTL)
    private record CachedEntry(String data, Instant expiry) {
        boolean isValid() { return Instant.now().isBefore(expiry); }
    }
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public KisApiService(KisProperties kisProperties, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.kisProperties = kisProperties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return kisProperties.appKey() != null && !kisProperties.appKey().isBlank()
            && kisProperties.appSecret() != null && !kisProperties.appSecret().isBlank();
    }

    /** 만료된 캐시 엔트리 정리 (5분마다) */
    @Scheduled(fixedDelay = 300_000)
    public void cleanExpiredCache() {
        int before = cache.size();
        cache.entrySet().removeIf(e -> !e.getValue().isValid());
        int removed = before - cache.size();
        if (removed > 0) {
            log.debug("[KIS] Cache cleanup: removed {} expired entries ({} remaining)", removed, cache.size());
        }
    }

    // ══════════════════════════════════════════════════
    // 토큰 관리
    // ══════════════════════════════════════════════════

    private synchronized String getAccessToken() {
        if (accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
        // 토큰 발급 실패 후 쿨다운 (2분) — 연속 호출 방지
        if (Instant.now().isBefore(tokenErrorCooldown)) {
            return null;
        }

        try {
            String url = KIS_BASE + "/oauth2/tokenP";
            Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", kisProperties.appKey(),
                "appsecret", kisProperties.appSecret()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            accessToken = root.path("access_token").asText(null);

            if (accessToken == null || accessToken.isBlank()) {
                log.error("[KIS] 토큰 발급 실패: {}", response.getBody());
                return null;
            }

            // 토큰 유효기간: 약 24시간, 안전하게 23시간으로 설정
            tokenExpiry = Instant.now().plus(23, ChronoUnit.HOURS);
            log.info("[KIS] 토큰 발급 성공 (만료: {})", tokenExpiry);
            return accessToken;
        } catch (Exception e) {
            log.error("[KIS] 토큰 발급 오류 (2분 쿨다운): {}", e.getMessage());
            tokenErrorCooldown = Instant.now().plus(2, ChronoUnit.MINUTES);
            return null;
        }
    }

    private HttpHeaders buildHeaders(String trId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", "Bearer " + getAccessToken());
        headers.set("appkey", kisProperties.appKey());
        headers.set("appsecret", kisProperties.appSecret());
        headers.set("tr_id", trId);
        return headers;
    }

    // ══════════════════════════════════════════════════
    // 현재가 시세 조회
    // ══════════════════════════════════════════════════

    /**
     * 종목 현재가 시세 조회 (FHKST01010100)
     */
    public Map<String, String> fetchCurrentPrice(String stockCode) {
        if (!isAvailable()) return Map.of();

        String cacheKey = "kis_price_" + stockCode;
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            try {
                return objectMapper.readValue(cached.data(), Map.class);
            } catch (Exception ignored) {}
        }

        try {
            String token = getAccessToken();
            if (token == null) return Map.of();

            String url = KIS_BASE + "/uapi/domestic-stock/v1/quotations/inquire-price"
                + "?FID_COND_MRKT_DIV_CODE=J"
                + "&FID_INPUT_ISCD=" + stockCode;

            HttpHeaders headers = buildHeaders("FHKST01010100");
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("[KIS] 현재가 HTTP 에러: {} ({})", resp.getStatusCode(), stockCode);
                return Map.of();
            }
            JsonNode output = objectMapper.readTree(resp.getBody()).path("output");

            Map<String, String> result = new LinkedHashMap<>();
            result.put("현재가", output.path("stck_prpr").asText(""));
            result.put("전일대비", output.path("prdy_vrss").asText(""));
            result.put("등락률", output.path("prdy_ctrt").asText("") + "%");
            result.put("거래량", output.path("acml_vol").asText(""));
            result.put("거래대금", output.path("acml_tr_pbmn").asText(""));
            result.put("시가", output.path("stck_oprc").asText(""));
            result.put("고가", output.path("stck_hgpr").asText(""));
            result.put("저가", output.path("stck_lwpr").asText(""));
            result.put("52주최고", output.path("stck_mxpr").asText(""));
            result.put("52주최저", output.path("stck_llam").asText(""));
            result.put("PER", output.path("per").asText(""));
            result.put("PBR", output.path("pbr").asText(""));
            result.put("시가총액", output.path("hts_avls").asText(""));

            cache.put(cacheKey, new CachedEntry(objectMapper.writeValueAsString(result),
                Instant.now().plus(10, ChronoUnit.MINUTES)));

            return result;
        } catch (Exception e) {
            log.warn("[KIS] 현재가 조회 실패 ({}): {}", stockCode, e.getMessage());
            return Map.of();
        }
    }

    // ══════════════════════════════════════════════════
    // 투자자별 매매 동향 (외국인/기관 수급)
    // ══════════════════════════════════════════════════

    /**
     * 투자자별 매매 동향 조회 (FHKST01010900) — 최근 N일
     */
    public String fetchInvestorTrend(String stockCode, int days) {
        if (!isAvailable()) return "";

        String cacheKey = "kis_investor_" + stockCode + "_" + days;
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) return cached.data();

        try {
            String token = getAccessToken();
            if (token == null) return "";

            String url = KIS_BASE + "/uapi/domestic-stock/v1/quotations/inquire-investor"
                + "?FID_COND_MRKT_DIV_CODE=J"
                + "&FID_INPUT_ISCD=" + stockCode;

            HttpHeaders headers = buildHeaders("FHKST01010900");
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return "";
            JsonNode outputArr = objectMapper.readTree(resp.getBody()).path("output");

            if (!outputArr.isArray() || outputArr.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("📈 투자자별 매매 동향 (최근 ").append(days).append("일)\n");

            long foreignTotal = 0, institutionTotal = 0, individualTotal = 0;
            int count = 0;

            for (JsonNode day : outputArr) {
                if (count >= days) break;
                String date = day.path("stck_bsop_date").asText("");
                long foreign = parseLong(day.path("frgn_ntby_qty").asText("0"));
                long institution = parseLong(day.path("orgn_ntby_qty").asText("0"));
                long individual = parseLong(day.path("prsn_ntby_qty").asText("0"));

                if (!date.isEmpty() && date.length() == 8) {
                    date = date.substring(4, 6) + "/" + date.substring(6, 8);
                }

                sb.append(String.format("  %s: 외국인 %+,d | 기관 %+,d | 개인 %+,d\n",
                    date, foreign, institution, individual));

                foreignTotal += foreign;
                institutionTotal += institution;
                individualTotal += individual;
                count++;
            }

            if (count > 0) {
                sb.append(String.format("  ── 합계: 외국인 %+,d | 기관 %+,d | 개인 %+,d\n",
                    foreignTotal, institutionTotal, individualTotal));

                // 수급 판단
                String verdict;
                if (foreignTotal > 0 && institutionTotal > 0) verdict = "🟢 쌍끌이 매수 (강세 수급)";
                else if (foreignTotal > 0) verdict = "🟡 외국인 매수 우위";
                else if (institutionTotal > 0) verdict = "🟡 기관 매수 우위";
                else if (foreignTotal < 0 && institutionTotal < 0) verdict = "🔴 쌍끌이 매도 (약세 수급)";
                else verdict = "⚪ 혼조";
                sb.append("  수급 판단: ").append(verdict).append("\n");
            }

            String result = sb.toString();
            cache.put(cacheKey, new CachedEntry(result, Instant.now().plus(10, ChronoUnit.MINUTES)));
            return result;
        } catch (Exception e) {
            log.warn("[KIS] 투자자 동향 조회 실패 ({}): {}", stockCode, e.getMessage());
            return "";
        }
    }

    // ══════════════════════════════════════════════════
    // 주요 지수 조회 (코스피/코스닥)
    // ══════════════════════════════════════════════════

    public String fetchMarketIndices() {
        if (!isAvailable()) return "";

        String cacheKey = "kis_indices";
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) return cached.data();

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("📊 KIS 실시간 시장 지수\n");

            // 코스피
            Map<String, String> kospi = fetchIndexPrice("0001");
            if (!kospi.isEmpty()) {
                sb.append("  KOSPI: ").append(kospi.get("현재가"))
                  .append(" (").append(kospi.get("등락률")).append(")")
                  .append(" 거래대금: ").append(formatBillion(kospi.get("거래대금"))).append("\n");
            }

            // 코스닥
            Map<String, String> kosdaq = fetchIndexPrice("1001");
            if (!kosdaq.isEmpty()) {
                sb.append("  KOSDAQ: ").append(kosdaq.get("현재가"))
                  .append(" (").append(kosdaq.get("등락률")).append(")")
                  .append(" 거래대금: ").append(formatBillion(kosdaq.get("거래대금"))).append("\n");
            }

            String now = LocalDate.now(KST).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            sb.append("  조회시각: ").append(now).append(" KST\n");

            String result = sb.toString();
            cache.put(cacheKey, new CachedEntry(result, Instant.now().plus(10, ChronoUnit.MINUTES)));
            return result;
        } catch (Exception e) {
            log.warn("[KIS] 지수 조회 실패: {}", e.getMessage());
            return "";
        }
    }

    private Map<String, String> fetchIndexPrice(String indexCode) {
        try {
            String url = KIS_BASE + "/uapi/domestic-stock/v1/quotations/inquire-index-price"
                + "?FID_COND_MRKT_DIV_CODE=U"
                + "&FID_INPUT_ISCD=" + indexCode;

            HttpHeaders headers = buildHeaders("FHPUP02100000");
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return Map.of();
            JsonNode output = objectMapper.readTree(resp.getBody()).path("output");

            Map<String, String> result = new LinkedHashMap<>();
            result.put("현재가", output.path("bstp_nmix_prpr").asText(""));
            result.put("등락률", output.path("bstp_nmix_prdy_ctrt").asText("") + "%");
            result.put("거래대금", output.path("acml_tr_pbmn").asText(""));
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ══════════════════════════════════════════════════
    // 통합: 종목 KIS 데이터 → AI 프롬프트용 텍스트
    // ══════════════════════════════════════════════════

    /**
     * 특정 종목의 KIS 실시간 시세 + 수급 데이터를 AI 프롬프트용 텍스트로 포맷.
     */
    public String fetchFormattedKisData(String stockCode) {
        if (!isAvailable()) return "";

        String cacheKey = "kis_formatted_" + stockCode;
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) return cached.data();

        try {
            StringBuilder text = new StringBuilder();
            text.append("\n━━━ KIS 실시간 시장 데이터 (").append(stockCode).append(") ━━━\n");

            // 현재가
            Map<String, String> price = fetchCurrentPrice(stockCode);
            if (!price.isEmpty()) {
                text.append("💰 현재가: ").append(formatPrice(price.get("현재가"))).append("원");
                text.append(" (전일대비: ").append(formatPrice(price.get("전일대비"))).append("원, ");
                text.append(price.get("등락률")).append(")\n");
                text.append("  시가: ").append(formatPrice(price.get("시가"))).append("원");
                text.append(" / 고가: ").append(formatPrice(price.get("고가"))).append("원");
                text.append(" / 저가: ").append(formatPrice(price.get("저가"))).append("원\n");
                text.append("  거래량: ").append(formatVolume(price.get("거래량")));
                text.append(" / 거래대금: ").append(formatBillion(price.get("거래대금"))).append("\n");
                if (!price.get("PER").isEmpty()) text.append("  PER: ").append(price.get("PER"));
                if (!price.get("PBR").isEmpty()) text.append(" / PBR: ").append(price.get("PBR"));
                if (!price.get("시가총액").isEmpty()) text.append(" / 시총: ").append(formatBillion(price.get("시가총액")));
                text.append("\n");
                text.append("  52주 최고: ").append(formatPrice(price.get("52주최고"))).append("원");
                text.append(" / 52주 최저: ").append(formatPrice(price.get("52주최저"))).append("원\n");
            }

            // 투자자별 매매 동향 (5일)
            String investor = fetchInvestorTrend(stockCode, 5);
            if (!investor.isEmpty()) text.append(investor);

            text.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            String result = text.toString();
            cache.put(cacheKey, new CachedEntry(result, Instant.now().plus(10, ChronoUnit.MINUTES)));
            return result;
        } catch (Exception e) {
            log.warn("[KIS] {} 통합 데이터 조회 실패: {}", stockCode, e.getMessage());
            return "";
        }
    }

    // ══════════════════════════════════════════════════
    // 시총 상위 종목 수급 요약 (수급분석용)
    // ══════════════════════════════════════════════════

    /**
     * 주요 종목들의 수급 요약 — 수급분석 모드에서 AI 프롬프트에 주입
     */
    public String fetchTopStocksSupplySummary(List<String> stockCodes) {
        if (!isAvailable() || stockCodes == null || stockCodes.isEmpty()) return "";

        String cacheKey = "kis_supply_summary";
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) return cached.data();

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n━━━ KIS 실시간 수급 데이터 (주요 종목) ━━━\n");
            sb.append("⚠️ 출처: 한국투자증권 OpenAPI (실제 거래소 데이터)\n\n");

            int success = 0;
            for (String code : stockCodes) {
                if (success >= 20) break; // 최대 20종목

                try {
                    Map<String, String> price = fetchCurrentPrice(code);
                    String investor = fetchInvestorTrend(code, 3);

                    if (price.isEmpty()) continue;

                    sb.append("▶ ").append(code);
                    sb.append(" | ").append(formatPrice(price.get("현재가"))).append("원");
                    sb.append(" (").append(price.get("등락률")).append(")\n");

                    if (!investor.isEmpty()) {
                        // 투자자 동향에서 합계 라인만 추출
                        for (String line : investor.split("\n")) {
                            if (line.contains("합계") || line.contains("수급 판단")) {
                                sb.append("  ").append(line.trim()).append("\n");
                            }
                        }
                    }
                    success++;

                    // API 호출 간 간격 (초당 20건 제한)
                    Thread.sleep(100);
                } catch (Exception e) {
                    log.debug("[KIS] {} 수급 조회 실패: {}", code, e.getMessage());
                }
            }

            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            String result = sb.toString();
            cache.put(cacheKey, new CachedEntry(result, Instant.now().plus(10, ChronoUnit.MINUTES)));
            return result;
        } catch (Exception e) {
            log.warn("[KIS] 수급 요약 조회 실패: {}", e.getMessage());
            return "";
        }
    }

    // ══════════════════════════════════════════════════
    // 유틸리티
    // ══════════════════════════════════════════════════

    private long parseLong(String s) {
        try { return Long.parseLong(s.replace(",", "").trim()); }
        catch (Exception e) { return 0; }
    }

    private String formatPrice(String raw) {
        if (raw == null || raw.isBlank()) return "?";
        try {
            long val = Long.parseLong(raw.replace(",", "").trim());
            return NumberFormat.getInstance(Locale.KOREA).format(val);
        } catch (Exception e) { return raw; }
    }

    private String formatVolume(String raw) {
        if (raw == null || raw.isBlank()) return "?";
        try {
            long val = Long.parseLong(raw.replace(",", "").trim());
            if (val >= 1_000_000) return String.format("%.1f백만주", val / 1_000_000.0);
            if (val >= 10_000) return String.format("%.1f만주", val / 10_000.0);
            return NumberFormat.getInstance(Locale.KOREA).format(val) + "주";
        } catch (Exception e) { return raw; }
    }

    private String formatBillion(String raw) {
        if (raw == null || raw.isBlank()) return "?";
        try {
            long val = Long.parseLong(raw.replace(",", "").trim());
            if (val >= 1_000_000_000_000L) return String.format("%.1f조원", val / 1_000_000_000_000.0);
            if (val >= 100_000_000L) return String.format("%.0f억원", val / 100_000_000.0);
            return NumberFormat.getInstance(Locale.KOREA).format(val) + "원";
        } catch (Exception e) { return raw; }
    }
}
