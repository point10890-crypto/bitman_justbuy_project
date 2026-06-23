package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import com.bitman.justbuy.dto.condition.DisclosureEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenDART 공시검색(list.json) 서비스.
 *
 * 시장 전체의 최근 공시 이벤트를 조회하여 report_nm 키워드 기반으로 태그를 부여한다.
 * 테마주(CATALYST_BURST) 섹션의 미래 후보 수집을 위한 휴면(dormant) 기반 컴포넌트로,
 * 현재는 어떤 컨트롤러/서비스에도 연결되어 있지 않다.
 *
 * <p>fail-open 원칙: API 키 미설정 또는 HTTP 오류 시 빈 리스트를 반환한다.</p>
 */
@Service
public class DartDisclosureSearchService {

    private static final Logger log = LoggerFactory.getLogger(DartDisclosureSearchService.class);
    private static final String DART_LIST_URL = "https://opendart.fss.or.kr/api/list.json";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 인메모리 캐시 TTL (15분) — DART 최근 공시는 10~30분 단위 갱신으로 충분. */
    private static final long CACHE_TTL_MINUTES = 15;
    private static final int PAGE_COUNT = 100;

    private final DartProperties dartProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 날짜 범위 + corpCls 키 기반 캐시
    private record CachedEntry(List<DisclosureEvent> data, Instant expiry) {
        boolean isValid() { return Instant.now().isBefore(expiry); }
    }
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public DartDisclosureSearchService(DartProperties dartProperties,
                                       RestTemplate restTemplate,
                                       ObjectMapper objectMapper) {
        this.dartProperties = dartProperties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** DartApiService와 동일한 가용성 판정: API 키 존재 여부. */
    public boolean isAvailable() {
        return dartProperties.apiKey() != null && !dartProperties.apiKey().isBlank();
    }

    /**
     * 지정 기간의 시장 전체 공시 이벤트를 조회한다 (corp_cls 미지정 = 전체).
     *
     * @param from 시작일 (inclusive)
     * @param to   종료일 (inclusive)
     * @return 종목코드가 있는 공시 이벤트 목록. 실패 시 빈 리스트.
     */
    public List<DisclosureEvent> fetchRecentDisclosureEvents(LocalDate from, LocalDate to) {
        return fetchRecentDisclosureEvents(from, to, null);
    }

    /**
     * 지정 기간의 공시 이벤트를 조회한다.
     *
     * @param from    시작일 (inclusive)
     * @param to      종료일 (inclusive)
     * @param corpCls 법인구분 필터 (Y=KOSPI, K=KOSDAQ, null/blank=전체)
     * @return 종목코드가 있는 공시 이벤트 목록. 실패 시 빈 리스트.
     */
    public List<DisclosureEvent> fetchRecentDisclosureEvents(LocalDate from, LocalDate to, String corpCls) {
        if (!isAvailable()) {
            log.debug("[DartDisclosure] DART API 키 미설정 — 빈 결과 반환");
            return List.of();
        }
        if (from == null || to == null) {
            return List.of();
        }

        String bgnDe = from.format(DATE_FMT);
        String endDe = to.format(DATE_FMT);
        String clsParam = (corpCls != null && !corpCls.isBlank()) ? corpCls.trim() : null;
        String cacheKey = bgnDe + "_" + endDe + "_" + (clsParam != null ? clsParam : "ALL");

        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            return cached.data();
        }

        try {
            StringBuilder url = new StringBuilder(DART_LIST_URL)
                .append("?crtfc_key=").append(dartProperties.apiKey())
                .append("&bgn_de=").append(bgnDe)
                .append("&end_de=").append(endDe)
                .append("&page_count=").append(PAGE_COUNT)
                .append("&page_no=1");
            if (clsParam != null) {
                url.append("&corp_cls=").append(clsParam);
            }

            String body = restTemplate.getForObject(url.toString(), String.class);
            JsonNode root = objectMapper.readTree(body);

            String status = root.path("status").asText("");
            // "000" = 정상, "013" = 조회 결과 없음 (둘 다 정상 흐름)
            if (!"000".equals(status)) {
                if (!"013".equals(status)) {
                    log.warn("[DartDisclosure] 비정상 응답 status={} message={}",
                        status, root.path("message").asText(""));
                }
                cache.put(cacheKey, new CachedEntry(List.of(),
                    Instant.now().plus(CACHE_TTL_MINUTES, ChronoUnit.MINUTES)));
                return List.of();
            }

            JsonNode list = root.path("list");
            List<DisclosureEvent> events = new ArrayList<>();
            if (list.isArray()) {
                for (JsonNode item : list) {
                    String stockCode = item.path("stock_code").asText("").trim();
                    if (stockCode.isBlank()) continue; // 상장사(종목코드 보유)만 대상

                    String reportName = item.path("report_nm").asText("");
                    events.add(new DisclosureEvent(
                        item.path("corp_code").asText(""),
                        stockCode,
                        item.path("corp_name").asText(""),
                        reportName,
                        item.path("rcept_no").asText(""),
                        item.path("rcept_dt").asText(""),
                        tagReport(reportName)
                    ));
                }
            }

            List<DisclosureEvent> result = List.copyOf(events);
            cache.put(cacheKey, new CachedEntry(result,
                Instant.now().plus(CACHE_TTL_MINUTES, ChronoUnit.MINUTES)));
            return result;
        } catch (Exception e) {
            log.warn("[DartDisclosure] 공시검색 조회 실패 ({}~{}, cls={}): {}",
                bgnDe, endDe, clsParam, e.getMessage());
            return List.of();
        }
    }

    /**
     * 공시 보고서명(report_nm)을 키워드 매칭하여 태그 목록을 만든다.
     *
     * 하나의 공시가 여러 태그를 가질 수 있으며, 매칭이 없으면 빈 리스트를 반환한다.
     * HTTP 없이 단위 테스트 가능하도록 package-visible static 메서드로 분리한다.
     */
    static List<String> tagReport(String reportName) {
        List<String> tags = new ArrayList<>();
        if (reportName == null || reportName.isBlank()) {
            return tags;
        }
        String r = reportName;

        if (r.contains("공급계약") || r.contains("수주")) addUnique(tags, "수주");
        if (r.contains("단일판매ㆍ공급계약") || r.contains("단일판매")) addUnique(tags, "공급계약");
        if (r.contains("투자")) addUnique(tags, "투자");
        if (r.contains("유상증자")) addUnique(tags, "유상증자");
        if (r.contains("무상증자")) addUnique(tags, "무상증자");
        if (r.contains("전환사채") || r.contains("CB")) addUnique(tags, "CB");
        if (r.contains("신주인수권부사채") || r.contains("BW")) addUnique(tags, "BW");
        if (r.contains("자기주식") || r.contains("자사주")) addUnique(tags, "자사주");
        if (r.contains("합병")) addUnique(tags, "합병");
        if (r.contains("분할")) addUnique(tags, "분할");
        if (r.contains("임상")) addUnique(tags, "임상");
        if (r.contains("실적") || r.contains("영업(잠정)") || r.contains("잠정실적")) addUnique(tags, "실적");
        if (r.contains("배당")) addUnique(tags, "배당");
        if (r.contains("소송")) addUnique(tags, "소송");

        return tags;
    }

    private static void addUnique(List<String> tags, String tag) {
        if (!tags.contains(tag)) tags.add(tag);
    }
}
