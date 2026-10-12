package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DART(금융감독원 전자공시) API 서비스.
 * 재무제표, 공시, 기업개황을 조회하여 AI 분석에 실제 재무 데이터를 제공.
 */
@Service
public class DartApiService {

    private static final Logger log = LoggerFactory.getLogger(DartApiService.class);
    private static final String DART_BASE = "https://opendart.fss.or.kr/api";

    private final DartProperties dartProperties;
    private final DartCorpCodeMapper corpCodeMapper;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private DartEventEvidenceService eventEvidence;
    private DartFinancialEvidenceService financialEvidence;

    // 내부 캐시 (TTL별 관리)
    private record CachedEntry(String data, Instant expiry) {
        boolean isValid() { return Instant.now().isBefore(expiry); }
    }
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public DartApiService(DartProperties dartProperties, DartCorpCodeMapper corpCodeMapper,
                           RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.dartProperties = dartProperties;
        this.corpCodeMapper = corpCodeMapper;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public DartApiService(DartProperties properties, DartCorpCodeMapper corpMapper,
        RestTemplate client, ObjectMapper mapper, DartEventEvidenceService eventEvidence) {
        this(properties, corpMapper, client, mapper);
        this.eventEvidence = eventEvidence;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DartApiService(DartProperties properties, DartCorpCodeMapper corpMapper,
        RestTemplate client, ObjectMapper mapper, DartEventEvidenceService eventEvidence,
        DartFinancialEvidenceService financialEvidence) {
        this(properties, corpMapper, client, mapper, eventEvidence);
        this.financialEvidence = financialEvidence;
    }

    public DartFinancialEvidenceService.EvidenceSnapshot fetchFinancialEvidence(List<String> stockCodes) {
        return financialEvidence == null
            ? new DartFinancialEvidenceService.EvidenceSnapshot("UNAVAILABLE", Instant.now(), List.of())
            : financialEvidence.fetchEvidence(stockCodes);
    }

    public String formatFinancialEvidence(DartFinancialEvidenceService.EvidenceSnapshot snapshot) {
        return financialEvidence == null ? "" : financialEvidence.formatEvidence(snapshot);
    }

    public String fetchFormattedEventEvidence(List<String> stockCodes) {
        return eventEvidence == null ? "" : eventEvidence.fetchFormattedEvidence(stockCodes);
    }

    public boolean isAvailable() {
        return dartProperties.apiKey() != null && !dartProperties.apiKey().isBlank();
    }

    // ══════════════════════════════════════════════════
    // 통합 메서드: 재무 + 공시 + 기업개황 → 포맷 텍스트
    // ══════════════════════════════════════════════════

    /**
     * 특정 종목의 DART 재무/공시 데이터를 AI 프롬프트용 텍스트로 포맷.
     * 실패 시 빈 문자열 반환 (fail-open).
     */
    public String fetchFormattedDartData(String stockCode) {
        if (!isAvailable() || stockCode == null || !stockCode.matches("[0-9]{6}")) return "";

        String corpCode;
        try { corpCode = corpCodeMapper.getCachedCorpCode(stockCode); }
        catch (RuntimeException error) {
            log.debug("[DART] 기업 매핑 조회 실패: {}", error.getClass().getSimpleName());
            return "";
        }
        if (corpCode == null || !corpCode.matches("[0-9]{8}")) return "";

        // 캐시 확인 (통합 결과 캐시, 4시간)
        String cacheKey = "dart_formatted_" + stockCode + ":" + corpCode;
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) return cached.data();

        try {
            StringBuilder text = new StringBuilder();
            String stockName = fetchStockName(corpCode);
            text.append("\n━━━ DART 공시 재무 데이터 (")
                .append(stockName != null ? stockName : stockCode)
                .append(", ").append(stockCode).append(") ━━━\n");

            // 재무제표
            String financials = formatFinancialEvidence(fetchFinancialEvidence(List.of(stockCode)));
            if (!financials.isEmpty()) text.append(financials);

            // 최근 공시
            String disclosures = fetchDisclosureText(corpCode);
            if (!disclosures.isEmpty()) text.append(disclosures);

            text.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            String result = text.toString();
            cache.put(cacheKey, new CachedEntry(result, Instant.now().plus(4, ChronoUnit.HOURS)));
            return result;
        } catch (Exception e) {
            log.warn("[DART] {} 데이터 조회 실패: {}", stockCode, e.getClass().getSimpleName());
            return "";
        }
    }

    // Recent disclosures remain contextual text and never earn a financial score.

    private String fetchDisclosureText(String corpCode) {
        String cacheKey = "dart_disc_" + corpCode;
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) return cached.data();

        try {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(30);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");

            String url = DART_BASE + "/list.json"
                + "?crtfc_key=" + dartProperties.apiKey()
                + "&corp_code=" + corpCode
                + "&bgn_de=" + start.format(fmt)
                + "&end_de=" + end.format(fmt)
                + "&page_count=5";

            String body = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(body);

            if (!"000".equals(root.path("status").asText(""))) return "";

            JsonNode list = root.path("list");
            if (!list.isArray() || list.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("📋 최근 공시 (30일)\n");

            for (int i = 0; i < Math.min(5, list.size()); i++) {
                JsonNode item = list.get(i);
                String date = item.path("rcept_dt").asText("");
                String reportName = item.path("report_nm").asText("");
                if (!date.isEmpty() && date.length() == 8) {
                    date = date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8);
                }
                sb.append("  - [").append(date).append("] ").append(reportName).append("\n");
            }

            String result = sb.toString();
            cache.put(cacheKey, new CachedEntry(result, Instant.now().plus(4, ChronoUnit.HOURS)));
            return result;
        } catch (Exception e) {
            log.debug("[DART] 공시 조회 실패: {}", e.getClass().getSimpleName());
            return "";
        }
    }

    // ══════════════════════════════════════════════════
    // 기업개황 (company)
    // ══════════════════════════════════════════════════

    private String fetchStockName(String corpCode) {
        try {
            String url = DART_BASE + "/company.json"
                + "?crtfc_key=" + dartProperties.apiKey()
                + "&corp_code=" + corpCode;

            String body = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(body);

            if ("000".equals(root.path("status").asText(""))) {
                return root.path("corp_name").asText(null);
            }
        } catch (Exception e) {
            log.debug("[DART] 기업개황 조회 실패: {}", e.getClass().getSimpleName());
        }
        return null;
    }

    // ══════════════════════════════════════════════════
    // 유틸리티
    // ══════════════════════════════════════════════════

}
