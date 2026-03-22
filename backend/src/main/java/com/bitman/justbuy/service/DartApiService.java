package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.text.NumberFormat;
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
        if (!isAvailable()) return "";

        String corpCode = corpCodeMapper.getCorpCode(stockCode);
        if (corpCode == null) return "";

        // 캐시 확인 (통합 결과 캐시, 4시간)
        String cacheKey = "dart_formatted_" + stockCode;
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) return cached.data();

        try {
            StringBuilder text = new StringBuilder();
            String stockName = fetchStockName(corpCode);
            text.append("\n━━━ DART 공시 재무 데이터 (")
                .append(stockName != null ? stockName : stockCode)
                .append(", ").append(stockCode).append(") ━━━\n");

            // 재무제표
            String financials = fetchFinancialText(corpCode, stockCode);
            if (!financials.isEmpty()) text.append(financials);

            // 최근 공시
            String disclosures = fetchDisclosureText(corpCode);
            if (!disclosures.isEmpty()) text.append(disclosures);

            text.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            String result = text.toString();
            cache.put(cacheKey, new CachedEntry(result, Instant.now().plus(4, ChronoUnit.HOURS)));
            return result;
        } catch (Exception e) {
            log.warn("[DART] {} 데이터 조회 실패: {}", stockCode, e.getMessage());
            return "";
        }
    }

    // ══════════════════════════════════════════════════
    // 재무제표 (fnlttSinglAcnt)
    // ══════════════════════════════════════════════════

    private String fetchFinancialText(String corpCode, String stockCode) {
        String cacheKey = "dart_fin_" + stockCode;
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) return cached.data();

        int currentYear = LocalDate.now().getYear();

        // 분기별 시도: 최신 보고서부터 역순 조회
        String[][] attempts = {
            {String.valueOf(currentYear), "11014", "3분기"},   // 당해 3Q
            {String.valueOf(currentYear), "11012", "반기"},     // 당해 반기
            {String.valueOf(currentYear), "11013", "1분기"},   // 당해 1Q
            {String.valueOf(currentYear - 1), "11011", "사업보고서"}, // 전년 사업보고서
            {String.valueOf(currentYear - 1), "11014", "3분기"},  // 전년 3Q
        };

        for (String[] attempt : attempts) {
            String year = attempt[0];
            String reprtCode = attempt[1];
            String reportName = attempt[2];

            try {
                String url = DART_BASE + "/fnlttSinglAcnt.json"
                    + "?crtfc_key=" + dartProperties.apiKey()
                    + "&corp_code=" + corpCode
                    + "&bsns_year=" + year
                    + "&reprt_code=" + reprtCode;

                String body = restTemplate.getForObject(url, String.class);
                JsonNode root = objectMapper.readTree(body);

                String status = root.path("status").asText("");
                if (!"000".equals(status)) continue; // 데이터 없음

                JsonNode list = root.path("list");
                if (!list.isArray() || list.isEmpty()) continue;

                // 연결재무제표(CFS) 우선, 없으면 개별(OFS)
                Map<String, Long> accounts = extractAccounts(list, "CFS");
                if (accounts.isEmpty()) accounts = extractAccounts(list, "OFS");
                if (accounts.isEmpty()) continue;

                String result = formatFinancials(accounts, year, reportName);
                cache.put(cacheKey, new CachedEntry(result, Instant.now().plus(24, ChronoUnit.HOURS)));
                return result;
            } catch (Exception e) {
                log.debug("[DART] 재무제표 조회 실패 ({}/{}): {}", year, reprtCode, e.getMessage());
            }
        }

        return "";
    }

    private Map<String, Long> extractAccounts(JsonNode list, String fsDiv) {
        Map<String, Long> accounts = new LinkedHashMap<>();
        for (JsonNode item : list) {
            if (!fsDiv.equals(item.path("fs_div").asText())) continue;

            String accountName = item.path("account_nm").asText("");
            String amountStr = item.path("thstrm_amount").asText("0").replace(",", "").trim();

            if (accountName.isEmpty() || amountStr.isEmpty() || "-".equals(amountStr)) continue;

            try {
                long amount = Long.parseLong(amountStr);
                accounts.put(accountName, amount);
            } catch (NumberFormatException ignored) {}
        }
        return accounts;
    }

    private String formatFinancials(Map<String, Long> accounts, String year, String reportName) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 최근 재무제표 (").append(year).append("년 ").append(reportName).append(")\n");

        Long revenue = findAccount(accounts, "매출액", "수익(매출액)", "영업수익");
        Long opProfit = findAccount(accounts, "영업이익", "영업이익(손실)");
        Long netIncome = findAccount(accounts, "당기순이익", "당기순이익(손실)");
        Long totalAssets = findAccount(accounts, "자산총계");
        Long totalDebt = findAccount(accounts, "부채총계");
        Long totalEquity = findAccount(accounts, "자본총계");

        if (revenue != null) sb.append("  매출액: ").append(formatKrw(revenue)).append("\n");
        if (opProfit != null) {
            sb.append("  영업이익: ").append(formatKrw(opProfit));
            if (revenue != null && revenue != 0) {
                double margin = (double) opProfit / revenue * 100;
                sb.append(String.format(" (영업이익률: %.1f%%)", margin));
            }
            sb.append("\n");
        }
        if (netIncome != null) sb.append("  당기순이익: ").append(formatKrw(netIncome)).append("\n");

        if (totalAssets != null || totalDebt != null || totalEquity != null) {
            sb.append("  ");
            if (totalAssets != null) sb.append("자산총계: ").append(formatKrw(totalAssets));
            if (totalDebt != null) sb.append(" / 부채총계: ").append(formatKrw(totalDebt));
            if (totalEquity != null) sb.append(" / 자본총계: ").append(formatKrw(totalEquity));
            sb.append("\n");

            // 파생 비율
            List<String> ratios = new ArrayList<>();
            if (totalDebt != null && totalEquity != null && totalEquity != 0) {
                ratios.add(String.format("부채비율: %.1f%%", (double) totalDebt / totalEquity * 100));
            }
            if (netIncome != null && totalEquity != null && totalEquity != 0) {
                ratios.add(String.format("ROE: %.1f%%", (double) netIncome / totalEquity * 100));
            }
            if (!ratios.isEmpty()) {
                sb.append("  ").append(String.join(" | ", ratios)).append("\n");
            }
        }

        return sb.toString();
    }

    private Long findAccount(Map<String, Long> accounts, String... names) {
        for (String name : names) {
            for (var entry : accounts.entrySet()) {
                if (entry.getKey().contains(name)) return entry.getValue();
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════
    // 최근 공시 (list)
    // ══════════════════════════════════════════════════

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
            log.debug("[DART] 공시 조회 실패: {}", e.getMessage());
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
            log.debug("[DART] 기업개황 조회 실패: {}", e.getMessage());
        }
        return null;
    }

    // ══════════════════════════════════════════════════
    // 유틸리티
    // ══════════════════════════════════════════════════

    /**
     * 원 단위 금액을 "X조 Y억원" 형식으로 포맷.
     */
    private String formatKrw(long amount) {
        long absAmount = Math.abs(amount);
        String sign = amount < 0 ? "-" : "";
        NumberFormat fmt = NumberFormat.getInstance(Locale.KOREA);

        if (absAmount >= 1_000_000_000_000L) {
            long jo = absAmount / 1_000_000_000_000L;
            long eok = (absAmount % 1_000_000_000_000L) / 100_000_000L;
            if (eok > 0) {
                return sign + jo + "조 " + fmt.format(eok) + "억원";
            }
            return sign + jo + "조원";
        } else if (absAmount >= 100_000_000L) {
            long eok = absAmount / 100_000_000L;
            return sign + fmt.format(eok) + "억원";
        } else if (absAmount >= 10_000L) {
            long man = absAmount / 10_000L;
            return sign + fmt.format(man) + "만원";
        } else {
            return sign + fmt.format(absAmount) + "원";
        }
    }
}
