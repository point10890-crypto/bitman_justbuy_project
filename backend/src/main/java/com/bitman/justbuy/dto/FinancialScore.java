package com.bitman.justbuy.dto;

import java.util.Map;

/**
 * DART 재무/공시 데이터 기반 종목 재무 스코어.
 * score: 0-100, summary: 60자 이내 한국어 요약, raw: 파싱된 원시 지표
 */
public record FinancialScore(int score, String summary, Map<String, Object> raw) {

    public static FinancialScore empty() {
        return new FinancialScore(0, "재무데이터 없음", Map.of());
    }
}
