package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.FinancialScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DART 재무/공시 포맷 텍스트를 파싱하여 재무 스코어(0-100)를 산출한다.
 *
 * 스코어 규칙 (Base 30):
 *  - 영업이익률 > 10%           → +20
 *  - 부채비율 < 100%            → +15
 *  - ROE > 15%                  → +20
 *  - 매출 성장률 > 10%          → +20
 *  - 호재 공시 (자기주식취득/무상증자/배당/합병) → +25
 *  - 악재 공시 (감자/유상증자/전환사채)        → -30
 *  → clamp(0, 100)
 */
@Service
public class FinancialScorer {

    private static final Logger log = LoggerFactory.getLogger(FinancialScorer.class);

    // 영업이익률: "영업이익률: 12.3%"
    private static final Pattern OP_MARGIN = Pattern.compile("\uC601\uC5C5\uC774\uC775\uB960\\s*:?[\\s]*(-?\\d+(?:\\.\\d+)?)\\s*%");
    // 부채비율
    private static final Pattern DEBT_RATIO = Pattern.compile("\uBD80\uCC44\uBE44\uC728\\s*:?[\\s]*(-?\\d+(?:\\.\\d+)?)\\s*%");
    // ROE
    private static final Pattern ROE = Pattern.compile("ROE\\s*:?[\\s]*(-?\\d+(?:\\.\\d+)?)\\s*%");

    // 호재 공시 키워드
    private static final String[] POSITIVE_KEYWORDS = {
        "\uC790\uAE30\uC8FC\uC2DD\uCDE8\uB4DD", "\uC790\uC0AC\uC8FC", "\uBB34\uC0C1\uC99D\uC790", "\uBC30\uB2F9", "\uD569\uBCD1"
    };
    // 악재 공시 키워드
    private static final String[] NEGATIVE_KEYWORDS = {
        "\uAC10\uC790", "\uC720\uC0C1\uC99D\uC790", "\uC804\uD658\uC0AC\uCC44"
    };

    private final DartApiService dartApiService;

    public FinancialScorer(DartApiService dartApiService) {
        this.dartApiService = dartApiService;
    }

    /**
     * 종목코드로 DART 재무 스코어를 산출한다.
     * DART 실패 또는 데이터 없음 → FinancialScore.empty().
     */
    public FinancialScore score(String stockCode) {
        if (stockCode == null || stockCode.length() != 6) return FinancialScore.empty();

        try {
            String dartText = dartApiService.fetchFormattedDartData(stockCode);
            if (dartText == null || dartText.isBlank()) return FinancialScore.empty();
            return scoreFromText(dartText);
        } catch (Exception e) {
            log.debug("[FinancialScorer] {} 스코어 산출 실패: {}", stockCode, e.getMessage());
            return FinancialScore.empty();
        }
    }

    /** 포맷 텍스트만으로 스코어 산출 (테스트/재사용 용이). */
    public FinancialScore scoreFromText(String dartText) {
        if (dartText == null || dartText.isBlank()) return FinancialScore.empty();

        int score = 30; // base
        Map<String, Object> raw = new LinkedHashMap<>();

        Double opMargin = extractPercent(OP_MARGIN, dartText);
        if (opMargin != null) {
            raw.put("opMarginPct", opMargin);
            if (opMargin > 10) score += 20;
            else if (opMargin > 5) score += 10;
            else if (opMargin < 0) score -= 10;
        }

        Double debtRatio = extractPercent(DEBT_RATIO, dartText);
        if (debtRatio != null) {
            raw.put("debtRatioPct", debtRatio);
            if (debtRatio < 100) score += 15;
            else if (debtRatio > 200) score -= 10;
        }

        Double roe = extractPercent(ROE, dartText);
        if (roe != null) {
            raw.put("roePct", roe);
            if (roe > 15) score += 20;
            else if (roe > 8) score += 10;
            else if (roe < 0) score -= 10;
        }

        // 매출 성장률 추정: 현재 재무 포맷엔 전년대비가 없으므로 공시 키워드만 활용.
        // (확장 여지: DART 다분기 호출 시 매출 성장률 계산 가능)
        boolean hasPositive = false;
        for (String kw : POSITIVE_KEYWORDS) {
            if (dartText.contains(kw)) { hasPositive = true; break; }
        }
        boolean hasNegative = false;
        for (String kw : NEGATIVE_KEYWORDS) {
            if (dartText.contains(kw)) { hasNegative = true; break; }
        }
        if (hasPositive) { score += 25; raw.put("positiveDisclosure", true); }
        if (hasNegative) { score -= 30; raw.put("negativeDisclosure", true); }

        // clamp
        int finalScore = Math.max(0, Math.min(100, score));

        String summary = buildSummary(opMargin, debtRatio, roe, hasPositive, hasNegative);
        return new FinancialScore(finalScore, summary, raw);
    }

    private static Double extractPercent(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String buildSummary(Double opMargin, Double debtRatio, Double roe,
                                       boolean pos, boolean neg) {
        StringBuilder sb = new StringBuilder();
        if (opMargin != null) sb.append(String.format("\uC601\uC5C5\uC774\uC775\uB960 %.1f%%", opMargin));
        if (debtRatio != null) {
            if (sb.length() > 0) sb.append("\u00B7");
            sb.append(String.format("\uBD80\uCC44\uBE44\uC728 %.0f%%", debtRatio));
        }
        if (roe != null) {
            if (sb.length() > 0) sb.append("\u00B7");
            sb.append(String.format("ROE %.1f%%", roe));
        }
        if (pos) {
            if (sb.length() > 0) sb.append("\u00B7");
            sb.append("\uD638\uC7AC\uACF5\uC2DC");
        }
        if (neg) {
            if (sb.length() > 0) sb.append("\u00B7");
            sb.append("\uC545\uC7AC\uACF5\uC2DC");
        }
        if (sb.length() == 0) return "\uC7AC\uBB34\uB370\uC774\uD130 \uC5C6\uC74C";
        String s = sb.toString();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}
