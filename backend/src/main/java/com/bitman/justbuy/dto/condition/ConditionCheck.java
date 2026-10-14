package com.bitman.justbuy.dto.condition;

import java.util.ArrayList;
import java.util.List;

/** A measured check is distinct from AI commentary or the name of a screening rule. */
public record ConditionCheck(String id, String category, String criterion, String status,
                             String observedValue, String source, String observedAt) {
    public static List<ConditionCheck> withDailyChangeText(String mode, String change, String source, String at) {
        Double value = null;
        try { value = Double.valueOf(change.trim().replace("%", "").replace(",", "")); }
        catch (RuntimeException ignored) { /* Missing or malformed observations remain unknown. */ }
        return withDailyChange(mode, value, source, at);
    }

    public static List<ConditionCheck> withDailyChange(String mode, Double change, String source, String at) {
        List<ConditionCheck> checks = new ArrayList<>(unknown(mode));
        if (!("BREAKOUT".equals(mode) || "JONGGA_V2".equals(mode)) || change == null || !Double.isFinite(change)
            || at == null || at.isBlank() || source == null || source.isBlank()) return checks;
        try { java.time.OffsetDateTime.parse(at); } catch (RuntimeException ignored) { return checks; }
        String id = "BREAKOUT".equals(mode) ? "technical.2" : "technical.3";
        double minimum = "BREAKOUT".equals(mode) ? 3 : 5;
        checks.replaceAll(c -> c.id().equals(id) ? new ConditionCheck(c.id(), c.category(), c.criterion(),
            change >= minimum && change <= 15 ? "PASS" : "FAIL",
            String.format(java.util.Locale.ROOT, "%+.2f%%", change), source, at) : c);
        return List.copyOf(checks);
    }

    public static List<ConditionCheck> unknown(String mode) {
        List<ConditionCheck> checks = new ArrayList<>();
        add(checks, "liquidity", "섹션별 거래대금 기준");
        switch (mode == null ? "" : mode) {
            case "BREAKOUT" -> {
                add(checks, "technical", "20일 평균 대비 거래량 급증", "전일 고가·당일 시가 돌파", "상승률 3~15% 초입 (15% 초과 과열 감점)", "VWAP 위", "체결강도 100 초과");
                add(checks, "catalyst", "당일 수주·공급계약·특허·임상 공시", "뉴스 증가", "동일 업종 동반 상승");
                add(checks, "fundamental", "관리·투자경고·지속 적자·감사의견 위험 제외");
                add(checks, "supply", "외국인·기관 당일 순매수", "프로그램 매수");
            }
            case "REVERSAL_EDGE" -> {
                add(checks, "technical", "이동평균 정배열 5 > 20 > 60", "20일선 눌림 후 양봉 반등", "눌림 거래량 감소 후 반등 거래량 증가");
                add(checks, "catalyst", "최근 1~2주 실적·수주 공시", "테마 지속");
                add(checks, "fundamental", "영업이익 흑자·개선", "매출 성장");
                add(checks, "supply", "외국인·기관 5거래일 누적 순매수");
            }
            case "FLOW_LEADER" -> {
                add(checks, "technical", "52주 신고가 근접", "상대강도 상위", "업종 내 거래대금 1위");
                add(checks, "catalyst", "정책·수출 등 업종 이슈");
                add(checks, "fundamental", "분기 이익 성장", "시가총액 규모");
                add(checks, "supply", "외국인·기관 20거래일 누적 순매수 상위");
            }
            case "CATALYST_BURST" -> {
                add(checks, "technical", "테마 종목 동반 상승", "테마 내 상승 선행·상승률·거래대금으로 대장주·2등주 확인");
                add(checks, "catalyst", "뉴스·공시 키워드와 종목의 테마 연관성");
                add(checks, "fundamental", "위험 종목 제외");
                add(checks, "supply", "테마 내 수급 집중");
            }
            case "JONGGA_V2" -> {
                add(checks, "technical", "고가권 마감", "장 막판 거래량·체결강도", "신고가 근접", "당일 상승률 5~15%");
                add(checks, "catalyst", "당일 뉴스·확인된 공시 (미발표 공시 기대는 미확인)");
                add(checks, "fundamental", "위험 종목 제외");
                add(checks, "supply", "기관·외국인 당일 순매수 (5일 누적과 구분)");
            }
            default -> {
                for (String category : List.of("technical", "catalyst", "fundamental", "supply"))
                    add(checks, category, "해당 검출기의 구조화된 조건 판정");
            }
        }
        return List.copyOf(checks);
    }

    private static void add(List<ConditionCheck> checks, String category, String... criteria) {
        int index = 0;
        for (String criterion : criteria) checks.add(new ConditionCheck(category + "." + index++, category,
            criterion, "UNKNOWN", "판정에 필요한 관측값이 저장되지 않았습니다.", "미확인", null));
    }
}
