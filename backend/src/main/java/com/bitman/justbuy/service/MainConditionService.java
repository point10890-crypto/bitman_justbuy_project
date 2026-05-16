package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionSection;
import com.bitman.justbuy.dto.condition.ConditionSectionResponse;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.dto.condition.MainConditionResponse;
import com.bitman.justbuy.dto.condition.TrackRecordSummary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class MainConditionService {

    private static final DateTimeFormatter KST_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        .withZone(ZoneId.of("Asia/Seoul"));

    private static final String NOTICE =
        "조건검색 결과는 투자 참고용 정보이며, 특정 종목의 매수/매도 추천이나 수익 보장을 의미하지 않습니다.";

    private final ConditionSearchPipeline conditionSearchPipeline;

    public MainConditionService(ConditionSearchPipeline conditionSearchPipeline) {
        this.conditionSearchPipeline = conditionSearchPipeline;
    }

    public MainConditionResponse getMain() {
        String asOf = nowKst();
        ConditionSectionResponse shortTerm = getSection(ConditionSection.SHORT_TERM);
        ConditionSectionResponse swing = getSection(ConditionSection.SWING);
        ConditionSectionResponse leaders = getSection(ConditionSection.LEADERS);
        ConditionSectionResponse themes = getSection(ConditionSection.THEMES);
        ConditionSectionResponse alerts = getSection(ConditionSection.ALERTS);

        List<ConditionSignalDto> allSignals = new ArrayList<>();
        allSignals.addAll(shortTerm.signals());
        allSignals.addAll(swing.signals());
        allSignals.addAll(leaders.signals());
        allSignals.addAll(themes.signals());

        return new MainConditionResponse(
            asOf,
            new MainConditionResponse.Sections(shortTerm, swing, leaders, themes, alerts),
            summarize(allSignals),
            NOTICE
        );
    }

    public ConditionSectionResponse getSection(String slug) {
        return getSection(ConditionSection.fromSlug(slug));
    }

    public ConditionSectionResponse getSection(ConditionSection section) {
        if (!section.hasAnalysisMode()) {
            return alertsSection();
        }

        AnalysisResponse response = null;
        try {
            response = conditionSearchPipeline.getPrecomputed(section.legacyMode());
        } catch (Exception ignored) {
            response = null;
        }

        boolean hasPicks = response != null
            && response.stockPicks() != null
            && !response.stockPicks().isEmpty();
        List<ConditionSignalDto> signals = toSignals(section, response);
        String asOf = response != null && response.updatedAt() != null ? response.updatedAt() : nowKst();
        String sourceStatus = hasPicks
            ? "PRECOMPUTED"
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

    private ConditionSectionResponse alertsSection() {
        String asOf = nowKst();
        List<ConditionSignalDto> alerts = List.of(
            new ConditionSignalDto(
                ConditionSection.ALERTS.responseKey(),
                "ALERTS",
                1,
                "관심종목",
                "",
                "-",
                "-",
                "-",
                "-",
                "대기",
                0,
                0,
                0,
                "관심 종목이 단타, 스윙, 주도주, 테마주 조건에 진입하면 알림 후보로 표시됩니다.",
                List.of("조건검색 섹션 진입", "목표 조건 도달", "공시 발생 감지"),
                List.of("알림은 투자 지시가 아닌 조건 충족 안내입니다."),
                "사용자가 알림 조건을 해제하면 중단",
                asOf
            )
        );

        return new ConditionSectionResponse(
            ConditionSection.ALERTS.responseKey(),
            ConditionSection.ALERTS.slug(),
            ConditionSection.ALERTS.title(),
            "ALERTS",
            "/api/conditions/alerts",
            asOf,
            "READY",
            alerts
        );
    }

    private List<ConditionSignalDto> toSignals(ConditionSection section, AnalysisResponse response) {
        List<StockPick> picks = response != null && response.stockPicks() != null
            ? response.stockPicks()
            : List.of();

        if (picks.isEmpty()) return List.of();

        List<ConditionSignalDto> signals = new ArrayList<>();
        for (int i = 0; i < Math.min(3, picks.size()); i++) {
            StockPick pick = picks.get(i);
            String currentPrice = normalizeDisplay(pick.currentPrice(), "-");
            String targetPrice = normalizeDisplay(pick.targetPrice(), currentPrice);
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
            default -> List.of("조건검색 섹션 진입", "목표 조건 도달", "알림 후보");
        };
    }

    private String invalidationFor(ConditionSection section) {
        return switch (section) {
            case SHORT_TERM -> "돌파 가격 이탈, 장중 강도 약화, 거래량/거래대금 급감";
            case SWING -> "눌림목 지지선 이탈, 추세선 훼손, 주도 섹터 약화";
            case LEADERS -> "외국인/기관 수급 이탈, 거래대금 감소, 섹터 강도 하락";
            case THEMES -> "DART/뉴스 재료 소멸, 관련주 동반 반응 약화, 대장주 교체";
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
