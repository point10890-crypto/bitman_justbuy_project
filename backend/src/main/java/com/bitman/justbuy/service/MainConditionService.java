package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionCaptureTimeDto;
import com.bitman.justbuy.dto.condition.ConditionCaptureTimesResponse;
import com.bitman.justbuy.dto.condition.ConditionSection;
import com.bitman.justbuy.dto.condition.ConditionSectionResponse;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.dto.condition.MainConditionResponse;
import com.bitman.justbuy.dto.condition.TrackRecordSummary;
import com.bitman.justbuy.util.StockUniverseFilters;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MainConditionService {

    private static final DateTimeFormatter KST_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        .withZone(ZoneId.of("Asia/Seoul"));
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter CAPTURE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("a hh:mm", Locale.KOREA);

    private static final String NOTICE =
        "조건검색 결과는 투자 참고용 정보이며, 특정 종목의 매수/매도 추천이나 수익 보장을 의미하지 않습니다.";

    private final ConditionSearchPipeline conditionSearchPipeline;
    private final ShortTermRealtimeScanner shortTermRealtimeScanner;
    private final JonggaV2SearchService jonggaV2SearchService;

    public MainConditionService(ConditionSearchPipeline conditionSearchPipeline) {
        this(conditionSearchPipeline, (ShortTermRealtimeScanner) null, null);
    }

    @Autowired
    public MainConditionService(ConditionSearchPipeline conditionSearchPipeline,
                                ObjectProvider<ShortTermRealtimeScanner> shortTermRealtimeScannerProvider,
                                ObjectProvider<JonggaV2SearchService> jonggaV2SearchServiceProvider) {
        this(conditionSearchPipeline,
            shortTermRealtimeScannerProvider.getIfAvailable(),
            jonggaV2SearchServiceProvider.getIfAvailable());
    }

    MainConditionService(ConditionSearchPipeline conditionSearchPipeline,
                         ShortTermRealtimeScanner shortTermRealtimeScanner) {
        this(conditionSearchPipeline, shortTermRealtimeScanner, null);
    }

    MainConditionService(ConditionSearchPipeline conditionSearchPipeline,
                         ShortTermRealtimeScanner shortTermRealtimeScanner,
                         JonggaV2SearchService jonggaV2SearchService) {
        this.conditionSearchPipeline = conditionSearchPipeline;
        this.shortTermRealtimeScanner = shortTermRealtimeScanner;
        this.jonggaV2SearchService = jonggaV2SearchService;
    }

    public MainConditionResponse getMain() {
        String asOf = nowKst();
        ConditionSectionResponse shortTerm = getSection(ConditionSection.SHORT_TERM);
        ConditionSectionResponse swing = getSection(ConditionSection.SWING);
        ConditionSectionResponse leaders = getSection(ConditionSection.LEADERS);
        ConditionSectionResponse themes = getSection(ConditionSection.THEMES);
        ConditionSectionResponse closingBet = getSection(ConditionSection.CLOSING_BET);

        List<ConditionSignalDto> allSignals = new ArrayList<>();
        allSignals.addAll(shortTerm.signals());
        allSignals.addAll(swing.signals());
        allSignals.addAll(leaders.signals());
        allSignals.addAll(themes.signals());
        allSignals.addAll(closingBet.signals());
        ConditionSectionResponse alerts = alertsSection(allSignals, alertSourceStatus(shortTerm, swing, leaders, themes, closingBet));

        return new MainConditionResponse(
            asOf,
            new MainConditionResponse.Sections(shortTerm, swing, leaders, themes, closingBet, alerts),
            summarize(allSignals),
            NOTICE
        );
    }

    public ConditionSectionResponse getSection(String slug) {
        return getSection(ConditionSection.fromSlug(slug));
    }

    public ConditionCaptureTimesResponse getCaptureTimes() {
        ConditionSectionResponse shortTerm = getSection(ConditionSection.SHORT_TERM);
        ConditionSectionResponse swing = getSection(ConditionSection.SWING);
        ConditionSectionResponse leaders = getSection(ConditionSection.LEADERS);
        ConditionSectionResponse themes = getSection(ConditionSection.THEMES);
        ConditionSectionResponse closingBet = getSection(ConditionSection.CLOSING_BET);
        return captureTimesResponse(
            "/api/conditions/capture-times",
            alertSourceStatus(shortTerm, swing, leaders, themes, closingBet),
            List.of(shortTerm, swing, leaders, themes, closingBet)
        );
    }

    public ConditionCaptureTimesResponse getCaptureTimes(String slug) {
        ConditionSection section = ConditionSection.fromSlug(slug);
        ConditionSectionResponse response = getSection(section);
        return captureTimesResponse(
            "/api/conditions/" + section.slug() + "/capture-times",
            response.sourceStatus(),
            List.of(response)
        );
    }

    public ConditionSectionResponse getSection(ConditionSection section) {
        if (!section.hasAnalysisMode()) {
            ConditionSectionResponse shortTerm = getSection(ConditionSection.SHORT_TERM);
            ConditionSectionResponse swing = getSection(ConditionSection.SWING);
            ConditionSectionResponse leaders = getSection(ConditionSection.LEADERS);
            ConditionSectionResponse themes = getSection(ConditionSection.THEMES);
            ConditionSectionResponse closingBet = getSection(ConditionSection.CLOSING_BET);
            List<ConditionSignalDto> signals = new ArrayList<>();
            signals.addAll(shortTerm.signals());
            signals.addAll(swing.signals());
            signals.addAll(leaders.signals());
            signals.addAll(themes.signals());
            signals.addAll(closingBet.signals());
            return alertsSection(signals, alertSourceStatus(shortTerm, swing, leaders, themes, closingBet));
        }

        if (section == ConditionSection.SHORT_TERM) {
            Optional<ShortTermRealtimeScanner.ScanSnapshot> snapshot = latestShortTermRealtime();
            if (snapshot.isPresent()) {
                return new ConditionSectionResponse(
                    section.responseKey(),
                    section.slug(),
                    section.title(),
                    section.legacyMode(),
                    "/api/conditions/" + section.slug(),
                    snapshot.get().asOf(),
                    "REALTIME_SCAN",
                    snapshot.get().signals()
                );
            }
        }

        if (section == ConditionSection.CLOSING_BET) {
            return closingBetSection();
        }

        AnalysisResponse response = null;
        boolean stale = false;
        try {
            response = conditionSearchPipeline.getPrecomputed(section.legacyMode());
        } catch (Exception ignored) {
            response = null;
        }

        if (response == null) {
            try {
                response = conditionSearchPipeline.getStoredPrecomputed(section.legacyMode());
                stale = response != null;
            } catch (Exception ignored) {
                response = null;
                stale = false;
            }
        }

        boolean hasPicks = response != null
            && response.stockPicks() != null
            && !response.stockPicks().isEmpty();
        List<ConditionSignalDto> signals = toSignals(section, response);
        String asOf = response != null && response.updatedAt() != null ? response.updatedAt() : nowKst();
        String sourceStatus = hasPicks
            ? stale ? "STALE_CACHE" : "PRECOMPUTED"
            : "DATA_UNAVAILABLE";

        return new ConditionSectionResponse(
            section.responseKey(),
            section.slug(),
            section.title(),
            section.legacyMode(),
            "/api/conditions/" + section.slug(),
            asOf,
            sourceStatus,
            signals
        );
    }

    private ConditionSectionResponse alertsSection(List<ConditionSignalDto> candidates, String sourceStatus) {
        String asOf = nowKst();
        Map<String, ConditionSignalDto> unique = new LinkedHashMap<>();
        candidates.stream()
            .filter(signal -> signal.stockName() != null && !signal.stockName().isBlank())
            .filter(signal -> !"관심종목".equals(signal.stockName()))
            .sorted(Comparator.comparingInt(ConditionSignalDto::finalScore).reversed())
            .forEach(signal -> unique.putIfAbsent(alertKey(signal), signal));

        List<ConditionSignalDto> alerts = new ArrayList<>();
        int rank = 1;
        for (ConditionSignalDto signal : unique.values()) {
            if (alerts.size() >= 3) break;
            alerts.add(toAlertSignal(signal, rank++));
        }

        return new ConditionSectionResponse(
            ConditionSection.ALERTS.responseKey(),
            ConditionSection.ALERTS.slug(),
            ConditionSection.ALERTS.title(),
            "ALERTS",
            "/api/conditions/alerts",
            alerts.stream().findFirst().map(ConditionSignalDto::capturedAt).orElse(asOf),
            alerts.isEmpty() ? "READY" : sourceStatus,
            alerts
        );
    }

    private ConditionCaptureTimesResponse captureTimesResponse(String endpoint,
                                                              String sourceStatus,
                                                              List<ConditionSectionResponse> sections) {
        List<ConditionCaptureTimeDto> captures = new ArrayList<>();
        for (ConditionSectionResponse section : sections) {
            if (section == null || section.signals() == null) continue;
            for (ConditionSignalDto signal : section.signals()) {
                String capturedAt = normalizeDisplay(signal.capturedAt(), section.asOf());
                captures.add(new ConditionCaptureTimeDto(
                    section.key(),
                    section.slug(),
                    section.title(),
                    section.mode(),
                    signal.rank(),
                    signal.stockName(),
                    signal.stockCode(),
                    capturedAt,
                    formatCapturedTime(capturedAt),
                    section.sourceStatus()
                ));
            }
        }

        return new ConditionCaptureTimesResponse(
            nowKst(),
            endpoint,
            sourceStatus,
            captures.size(),
            captures
        );
    }

    private String formatCapturedTime(String capturedAt) {
        if (capturedAt == null || capturedAt.isBlank()) return "";
        try {
            return OffsetDateTime.parse(capturedAt)
                .atZoneSameInstant(KST)
                .format(CAPTURE_TIME_FORMATTER);
        } catch (Exception ignored) {
            try {
                return Instant.parse(capturedAt)
                    .atZone(KST)
                    .format(CAPTURE_TIME_FORMATTER);
            } catch (Exception ignoredAgain) {
                return "";
            }
        }
    }

    private Optional<ShortTermRealtimeScanner.ScanSnapshot> latestShortTermRealtime() {
        if (shortTermRealtimeScanner == null) return Optional.empty();
        try {
            Optional<ShortTermRealtimeScanner.ScanSnapshot> snapshot = shortTermRealtimeScanner.latestFresh();
            return snapshot == null ? Optional.empty() : snapshot;
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String alertSourceStatus(ConditionSectionResponse... sections) {
        boolean stale = false;
        for (ConditionSectionResponse section : sections) {
            if (section == null) continue;
            if ("REALTIME_SCAN".equals(section.sourceStatus())) return "REALTIME_SCAN";
            if ("STALE_CACHE".equals(section.sourceStatus())) stale = true;
        }
        return stale ? "STALE_CACHE" : "PRECOMPUTED";
    }

    private ConditionSignalDto toAlertSignal(ConditionSignalDto signal, int rank) {
        String condition = alertConditionLabel(signal);
        String summary = condition + " - " + signal.stockName();
        if (signal.currentPrice() != null && !signal.currentPrice().isBlank() && !"-".equals(signal.currentPrice())) {
            summary += " 현재가 " + signal.currentPrice();
        }
        if (signal.maxReturnPct() != null && !signal.maxReturnPct().isBlank() && !"-".equals(signal.maxReturnPct())) {
            summary += ", 기대/포착 수익률 " + signal.maxReturnPct();
        }
        if (signal.summary() != null && !signal.summary().isBlank()) {
            summary += ". " + signal.summary();
        }

        List<String> evidence = new ArrayList<>();
        evidence.add(condition);
        evidence.add("조건검색 TOP3 진입");
        if (signal.evidence() != null) evidence.addAll(signal.evidence());

        return new ConditionSignalDto(
            ConditionSection.ALERTS.responseKey(),
            "ALERTS",
            rank,
            signal.stockName(),
            signal.stockCode(),
            condition,
            normalizeDisplay(signal.currentPrice(), "-"),
            "새 알림",
            "",
            "앱",
            "새 알림",
            signal.ruleScore(),
            signal.aiScore(),
            signal.finalScore(),
            summary,
            evidence,
            List.of("알림은 투자 지시가 아닌 조건 충족 안내입니다.", "포착 이후 가격 변동성이 커질 수 있습니다."),
            normalizeDisplay(signal.invalidation(), "사용자 알림 조건 해제"),
            normalizeDisplay(signal.capturedAt(), nowKst())
        );
    }

    private String alertConditionLabel(ConditionSignalDto signal) {
        String mode = signal.mode() == null ? "" : signal.mode();
        return switch (mode) {
            case "BREAKOUT" -> "단타 포착";
            case "REVERSAL_EDGE" -> "스윙 포착";
            case "FLOW_LEADER" -> "주도주 포착";
            case "CATALYST_BURST" -> "테마주 포착";
            case "JONGGA_V2" -> "종가매매 포착";
            default -> "조건 포착";
        };
    }

    private ConditionSectionResponse closingBetSection() {
        if (jonggaV2SearchService == null) {
            return new ConditionSectionResponse(
                ConditionSection.CLOSING_BET.responseKey(),
                ConditionSection.CLOSING_BET.slug(),
                ConditionSection.CLOSING_BET.title(),
                ConditionSection.CLOSING_BET.legacyMode(),
                "/api/conditions/" + ConditionSection.CLOSING_BET.slug(),
                nowKst(),
                "DATA_UNAVAILABLE",
                List.of()
            );
        }

        JsonNode latest = jonggaV2SearchService.latest();
        List<ConditionSignalDto> signals = new ArrayList<>();
        int rank = 1;
        for (JsonNode signal : latest.path("signals")) {
            if (rank > 3) break;
            signals.add(toClosingBetSignal(signal, rank++));
        }

        String date = latest.path("date").asText("");
        return new ConditionSectionResponse(
            ConditionSection.CLOSING_BET.responseKey(),
            ConditionSection.CLOSING_BET.slug(),
            ConditionSection.CLOSING_BET.title(),
            ConditionSection.CLOSING_BET.legacyMode(),
            "/api/conditions/" + ConditionSection.CLOSING_BET.slug(),
            closingBetAsOf(date),
            signals.isEmpty() ? "DATA_UNAVAILABLE" : "PRECOMPUTED",
            signals
        );
    }

    private ConditionSignalDto toClosingBetSignal(JsonNode signal, int rank) {
        int score = signal.path("score").path("total").asInt(70);
        String entry = formatNumber(signal.path("entry_price").asLong(signal.path("current_price").asLong(0)));
        String current = formatNumber(signal.path("current_price").asLong(signal.path("entry_price").asLong(0)));
        String target = formatNumber(signal.path("target_price").asLong(0));
        String stop = formatNumber(signal.path("stop_price").asLong(0));
        String grade = signal.path("grade").asText("포착");
        String stockName = normalizeDisplay(signal.path("stock_name").asText(""), "종가 후보");
        String summary = normalizeDisplay(
            signal.path("score").path("llm_reason").asText(""),
            firstNewsTitle(signal.path("news_items"), stockName + " 종가매매 조건검색 후보입니다.")
        );

        return new ConditionSignalDto(
            ConditionSection.CLOSING_BET.responseKey(),
            ConditionSection.CLOSING_BET.legacyMode(),
            rank,
            stockName,
            signal.path("stock_code").asText(""),
            entry,
            current,
            target,
            stop,
            calculateReturn(entry, target),
            grade,
            score,
            score,
            score,
            summary,
            closingBetEvidence(signal),
            List.of("종가 변동성", "익일 갭 리스크", "거래대금 급감"),
            "종가 기준 진입가 이탈, 거래대금 둔화, 뉴스 모멘텀 약화",
            closingBetAsOf(signal.path("signal_date").asText(""))
        );
    }

    private List<String> closingBetEvidence(JsonNode signal) {
        List<String> evidence = new ArrayList<>();
        evidence.add("종가매매 V2");
        String grade = signal.path("grade").asText("");
        if (!grade.isBlank()) evidence.add("등급 " + grade);
        String market = signal.path("market").asText("");
        if (!market.isBlank()) evidence.add(market);
        signal.path("themes").forEach(theme -> {
            String value = theme.asText("");
            if (!value.isBlank()) evidence.add(value);
        });
        if (signal.path("foreign_5d").asLong(0) > 0) evidence.add("외국인 5일 순매수");
        if (signal.path("inst_5d").asLong(0) > 0) evidence.add("기관 5일 순매수");
        return evidence;
    }

    private static String firstNewsTitle(JsonNode newsItems, String fallback) {
        if (newsItems != null && newsItems.isArray() && newsItems.size() > 0) {
            String title = newsItems.get(0).path("title").asText("");
            if (!title.isBlank()) return title;
        }
        return fallback;
    }

    private static String closingBetAsOf(String date) {
        if (date == null || date.isBlank()) return nowKst();
        String normalized = date.trim();
        if (normalized.matches("\\d{8}")) {
            normalized = normalized.substring(0, 4) + "-" + normalized.substring(4, 6) + "-" + normalized.substring(6, 8);
        }
        if (normalized.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return normalized + "T15:00:00+09:00";
        }
        return nowKst();
    }

    private static String formatNumber(long value) {
        if (value <= 0) return "-";
        return String.format(Locale.KOREA, "%,d", value);
    }

    private String alertKey(ConditionSignalDto signal) {
        if (signal.stockCode() != null && !signal.stockCode().isBlank()) return signal.stockCode();
        return signal.stockName();
    }

    private List<ConditionSignalDto> toSignals(ConditionSection section, AnalysisResponse response) {
        List<StockPick> picks = response != null && response.stockPicks() != null
            ? response.stockPicks()
            : List.of();

        if (picks.isEmpty()) return List.of();

        List<StockPick> eligiblePicks = picks.stream()
            .filter(pick -> isEligiblePick(section, pick))
            .limit(3)
            .toList();

        List<ConditionSignalDto> signals = new ArrayList<>();
        for (int i = 0; i < eligiblePicks.size(); i++) {
            StockPick pick = eligiblePicks.get(i);
            String currentPrice = normalizeDisplay(pick.currentPrice(), "-");
            String targetPrice = ensureValidTargetPrice(
                section,
                currentPrice,
                normalizeDisplay(firstNonBlank(pick.targetPrice(), extractAgentPrice(response, pick, false)), "-")
            );
            String stopLoss = ensureValidStopLoss(
                section,
                currentPrice,
                normalizeDisplay(firstNonBlank(pick.stopLoss(), extractAgentPrice(response, pick, true)), "-")
            );
            String maxReturnPct = calculateReturn(currentPrice, targetPrice);
            int score = Math.max(55, 88 - (i * 7));

            signals.add(new ConditionSignalDto(
                section.responseKey(),
                section.legacyMode(),
                i + 1,
                normalizeDisplay(pick.name(), "포착 종목"),
                normalizeDisplay(pick.code(), ""),
                currentPrice,
                currentPrice,
                targetPrice,
                stopLoss,
                maxReturnPct,
                normalizeStatus(pick.action()),
                score,
                score,
                score,
                normalizeDisplay(pick.reason(), section.title() + " 조건검색에 포착된 후보입니다."),
                evidenceFor(section),
                riskFlagsFor(section),
                invalidationFor(section),
                response != null && response.updatedAt() != null ? response.updatedAt() : nowKst()
            ));
        }
        return signals;
    }

    private boolean isEligiblePick(ConditionSection section, StockPick pick) {
        if (pick == null || pick.code() == null || !pick.code().matches("\\d{6}")) return false;
        if (section == ConditionSection.SHORT_TERM) {
            return StockUniverseFilters.isKoreanSpotEquity(pick.name(), pick.code());
        }
        return pick.name() != null
            && !pick.name().isBlank()
            && !StockUniverseFilters.isExchangeTradedProductName(pick.name());
    }

    private List<ConditionSignalDto> fallbackSignals(ConditionSection section) {
        return switch (section) {
            case SHORT_TERM -> List.of(
                fallback(section, 1, "로보티즈", "108490", "31,200", "34,350", "+10.09%", "포착",
                    "거래량과 장중 가격 반응이 동시에 붙은 단타 후보입니다."),
                fallback(section, 2, "디아이", "003160", "7,120", "7,510", "+5.53%", "관찰",
                    "단기 거래대금 증가와 변동성 확대가 확인된 후보입니다."),
                fallback(section, 3, "한미반도체", "042700", "139,000", "145,300", "+4.66%", "관찰",
                    "시장 관심 섹터 내에서 단기 강도가 유지되는 후보입니다.")
            );
            case SWING -> List.of(
                fallback(section, 1, "현대무벡스", "319400", "31,800", "35,000", "+10.06%", "관찰",
                    "추세 회복과 눌림목 이후 반등 가능성을 점검하는 스윙 후보입니다."),
                fallback(section, 2, "범한퓨얼셀", "382900", "32,500", "38,000", "+16.92%", "1차",
                    "며칠 보유 관점에서 변동성과 재료 지속성을 함께 봐야 하는 후보입니다."),
                fallback(section, 3, "디앤디파마텍", "347850", "78,000", "86,000", "+10.26%", "보유",
                    "추세 유지 여부를 목표구간과 함께 확인할 후보입니다.")
            );
            case LEADERS -> List.of(
                fallback(section, 1, "알테오젠", "196170", "1,230억", "+12.4%", "+12.4%", "강",
                    "거래대금과 시장 관심이 집중되는 주도주 후보입니다."),
                fallback(section, 2, "한화오션", "042660", "980억", "+8.7%", "+8.7%", "중",
                    "섹터 대표성과 거래대금 유입을 같이 확인할 후보입니다."),
                fallback(section, 3, "에코프로", "086520", "760억", "+6.2%", "+6.2%", "중",
                    "시장 중심 테마 안에서 상대강도 확인이 필요한 후보입니다.")
            );
            case THEMES -> List.of(
                fallback(section, 1, "반도체", "THEME", "한미반도체", "+8.2%", "+8.2%", "강",
                    "공시, 뉴스, 가격 반응이 함께 확인되는 테마 후보입니다."),
                fallback(section, 2, "로봇", "THEME", "로보티즈", "+5.1%", "+5.1%", "중",
                    "테마 확산 여부와 대장주 지속성을 확인할 후보입니다."),
                fallback(section, 3, "방산", "THEME", "한화에어로", "+4.7%", "+4.7%", "중",
                    "정책/수주 모멘텀과 가격 반응을 같이 점검할 후보입니다.")
            );
            default -> List.of();
        };
    }

    private ConditionSignalDto fallback(ConditionSection section, int rank, String name, String code,
                                        String capturePrice, String highPrice, String maxReturnPct,
                                        String status, String summary) {
        int score = Math.max(60, 90 - (rank * 6));
        return new ConditionSignalDto(
            section.responseKey(),
            section.legacyMode(),
            rank,
            name,
            code,
            capturePrice,
            capturePrice,
            highPrice,
            "",
            maxReturnPct,
            status,
            score,
            score - 3,
            score,
            summary,
            evidenceFor(section),
            riskFlagsFor(section),
            invalidationFor(section),
            nowKst()
        );
    }

    private TrackRecordSummary summarize(List<ConditionSignalDto> signals) {
        double avg = signals.stream()
            .map(ConditionSignalDto::maxReturnPct)
            .mapToDouble(MainConditionService::parsePercent)
            .filter(value -> !Double.isNaN(value))
            .average()
            .orElse(0);
        long wins = signals.stream()
            .map(ConditionSignalDto::maxReturnPct)
            .mapToDouble(MainConditionService::parsePercent)
            .filter(value -> !Double.isNaN(value) && value > 0)
            .count();
        String winRate = signals.isEmpty()
            ? "0%"
            : Math.round((double) wins / signals.size() * 100) + "%";
        return new TrackRecordSummary(signals.size(), formatPercent(avg), winRate);
    }

    private List<String> riskFlagsFor(ConditionSection section) {
        return switch (section) {
            case SHORT_TERM -> List.of("거래량 급감", "돌파 실패", "거래원 매수 우위 약화", "추격 매수 주의");
            case SWING -> List.of("눌림목 이탈", "추세 훼손", "주도 섹터 약화", "공시/실적 리스크");
            case LEADERS -> List.of("거래대금 급감", "외국인/기관 수급 이탈", "섹터 강도 약화", "시장 조정");
            case THEMES -> List.of("재료 소멸", "관련주 동반 반응 약화", "대장주 교체", "테마 과열");
            default -> List.of("조건 변경 가능");
        };
    }

    private List<String> evidenceFor(ConditionSection section) {
        return switch (section) {
            case SHORT_TERM -> List.of(
                "거래량 급증",
                "돌파",
                "상승률",
                "장중 강도",
                "실시간조회/거래량/거래대금/거래원 매수 순위"
            );
            case SWING -> List.of(
                "눌림목",
                "추세 유지",
                "목표구간",
                "리스크 점검",
                "주도주 섹터",
                "대장주"
            );
            case LEADERS -> List.of(
                "거래대금",
                "외국인/기관 수급",
                "섹터 강도",
                "거래량 상위",
                "거래대금 상위"
            );
            case THEMES -> List.of(
                "DART/뉴스 재료",
                "관련 종목 가격 반응",
                "대장주 판별",
                "대장주"
            );
            case CLOSING_BET -> List.of(
                "종가매매 V2",
                "뉴스",
                "거래대금",
                "차트",
                "수급",
                "등급"
            );
            default -> List.of("조건검색 섹션 진입", "목표 조건 도달", "알림 후보");
        };
    }

    private String invalidationFor(ConditionSection section) {
        return switch (section) {
            case SHORT_TERM -> "돌파 가격 이탈, 장중 강도 약화, 거래량/거래대금 급감";
            case SWING -> "눌림목 지지선 이탈, 추세선 훼손, 주도 섹터 약화";
            case LEADERS -> "외국인/기관 수급 이탈, 거래대금 감소, 섹터 강도 하락";
            case THEMES -> "DART/뉴스 재료 소멸, 관련주 동반 반응 약화, 대장주 교체";
            case CLOSING_BET -> "종가 기준 진입가 이탈, 거래대금 둔화, 뉴스 모멘텀 약화";
            default -> "사용자 알림 조건 해제";
        };
    }

    private static String nowKst() {
        return KST_FORMATTER.format(Instant.now());
    }

    private static String normalizeDisplay(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalizeStatus(String action) {
        if (action == null || action.isBlank()) return "포착";
        if (action.contains("매도")) return "주의";
        if (action.contains("관망")) return "관찰";
        return action;
    }

    private static String extractAgentPrice(AnalysisResponse response, StockPick pick, boolean stopLoss) {
        if (response == null || response.finalContent() == null || pick == null) return "";
        String content = response.finalContent();
        int start = findStockSectionStart(content, pick);
        if (start < 0) return "";
        String section = content.substring(start, Math.min(content.length(), start + 1800));
        Pattern pattern = stopLoss
            ? Pattern.compile("(?:손절가|손절)\\s*[:：]?\\s*([0-9][0-9,]{2,})\\s*원?")
            : Pattern.compile("(?:1차\\s*)?목표(?:가|구간)?\\s*[:：]?\\s*([0-9][0-9,]{2,})\\s*원?");
        Matcher matcher = pattern.matcher(section);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int findStockSectionStart(String content, StockPick pick) {
        String name = pick.name();
        if (name != null && !name.isBlank()) {
            int index = content.indexOf(name);
            if (index >= 0) return index;
        }
        String code = pick.code();
        if (code != null && !code.isBlank()) {
            int index = content.indexOf(code);
            if (index >= 0) return index;
        }
        return -1;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String ensureValidTargetPrice(ConditionSection section, String currentPrice, String targetPrice) {
        long current = parseLongPrice(currentPrice);
        long target = parseLongPrice(targetPrice);
        if (current <= 0) return normalizeDisplay(targetPrice, "-");
        if (target > current) return formatLongPrice(target);

        double upside = section == ConditionSection.SWING ? 0.06 : 0.05;
        return formatLongPrice(roundUpToTick(Math.round(current * (1.0 + upside))));
    }

    private static String ensureValidStopLoss(ConditionSection section, String currentPrice, String stopLoss) {
        long current = parseLongPrice(currentPrice);
        long stop = parseLongPrice(stopLoss);
        if (current <= 0) return normalizeDisplay(stopLoss, "-");
        if (stop > 0 && stop < current) return formatLongPrice(stop);

        double downside = section == ConditionSection.SWING ? 0.05 : 0.04;
        return formatLongPrice(roundDownToTick(Math.round(current * (1.0 - downside))));
    }

    private static long roundUpToTick(long price) {
        long tick = tickSize(price);
        return ((price + tick - 1) / tick) * tick;
    }

    private static long roundDownToTick(long price) {
        long tick = tickSize(price);
        return (price / tick) * tick;
    }

    private static long tickSize(long price) {
        if (price < 2_000) return 1;
        if (price < 5_000) return 5;
        if (price < 20_000) return 10;
        if (price < 50_000) return 50;
        if (price < 200_000) return 100;
        if (price < 500_000) return 500;
        return 1_000;
    }

    private static String formatLongPrice(long price) {
        return String.format(Locale.KOREA, "%,d", price);
    }

    private static String calculateReturn(String base, String high) {
        long basePrice = parseLongPrice(base);
        long highPrice = parseLongPrice(high);
        if (basePrice <= 0 || highPrice <= 0) return "-";
        return formatPercent(((double) highPrice - basePrice) / basePrice * 100.0);
    }

    private static long parseLongPrice(String value) {
        if (value == null) return 0;
        String normalized = value.replaceAll("[^0-9]", "");
        if (normalized.isBlank()) return 0;
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double parsePercent(String value) {
        if (value == null) return Double.NaN;
        String normalized = value.replace("%", "").replace("+", "").trim();
        if (normalized.isBlank() || "-".equals(normalized)) return Double.NaN;
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static String formatPercent(double value) {
        if (Double.isNaN(value)) return "-";
        return String.format(Locale.KOREA, "%+.2f%%", value);
    }
}
