package com.bitman.justbuy.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 에이전트 응답에서 구조화 JSON 블록을 파싱한다.
 * {@code ```json:analysis ... ```} 블록을 추출하여 StructuredAnalysis로 변환.
 */
public final class StructuredAnalysisParser {

    private static final Logger log = LoggerFactory.getLogger(StructuredAnalysisParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private StructuredAnalysisParser() {}

    // 패턴: ```json:analysis\n{...}\n```  또는 ```json\n{...}\n```
    private static final Pattern[] JSON_BLOCK_PATTERNS = {
        Pattern.compile("```json:analysis\\s*\\n([\\s\\S]*?)```"),
        Pattern.compile("```json\\s*\\n([\\s\\S]*?)```"),
    };

    /** 구조화된 종목 데이터 */
    public record StructuredStock(
        String name,
        String code,
        String action,       // 매수|매도|관망|주목
        double confidence,   // 0.0-1.0
        Long currentPrice,
        Long targetPrice,
        Long stopLoss,
        ScenarioSet scenario,
        List<String> reasons
    ) {}

    /** Bull/Base/Bear 시나리오 세트 */
    public record ScenarioSet(
        Scenario bull,
        Scenario base,
        Scenario bear
    ) {}

    public record Scenario(double probability, Long target) {}

    /** 에이전트 하나의 구조화 분석 결과 */
    public record StructuredAnalysis(
        List<StructuredStock> stocks,
        String marketSentiment,  // bullish|neutral|bearish
        List<String> keyRisks
    ) {}

    /**
     * AI 응답 텍스트에서 구조화 JSON 블록을 추출하여 파싱.
     * 블록이 없거나 파싱 실패 시 null 반환.
     */
    public static StructuredAnalysis parse(String content) {
        if (content == null || content.isBlank()) return null;

        for (Pattern pattern : JSON_BLOCK_PATTERNS) {
            Matcher m = pattern.matcher(content);
            if (!m.find()) continue;

            try {
                JsonNode root = mapper.readTree(m.group(1).trim());
                return parseRoot(root);
            } catch (Exception e) {
                log.debug("JSON 블록 파싱 실패: {}", e.getMessage());
            }
        }
        return null;
    }

    private static StructuredAnalysis parseRoot(JsonNode root) {
        if (!root.has("stocks") || !root.get("stocks").isArray()) return null;

        List<StructuredStock> stocks = new ArrayList<>();
        for (JsonNode stockNode : root.get("stocks")) {
            StructuredStock stock = parseStock(stockNode);
            if (stock != null) stocks.add(stock);
        }
        if (stocks.isEmpty()) return null;

        String sentiment = "neutral";
        if (root.has("marketSentiment")) {
            String raw = root.get("marketSentiment").asText("neutral").toLowerCase();
            if (raw.contains("bull") || raw.contains("강세")) sentiment = "bullish";
            else if (raw.contains("bear") || raw.contains("약세")) sentiment = "bearish";
        }

        List<String> risks = new ArrayList<>();
        if (root.has("keyRisks") && root.get("keyRisks").isArray()) {
            for (JsonNode r : root.get("keyRisks")) {
                if (r.isTextual() && !r.asText().isBlank()) risks.add(r.asText());
            }
        }

        return new StructuredAnalysis(stocks, sentiment, risks);
    }

    private static StructuredStock parseStock(JsonNode node) {
        String name = node.path("name").asText("").trim();
        String code = node.path("code").asText("").trim();
        if (name.isEmpty() || code.length() != 6) return null;

        String action = normalizeAction(node.path("action").asText("주목"));
        double confidence = clamp(node.path("confidence").asDouble(0.5), 0, 1);

        ScenarioSet scenario = null;
        if (node.has("scenario") && node.get("scenario").isObject()) {
            JsonNode sc = node.get("scenario");
            Scenario bull = parseScenario(sc.get("bull"));
            Scenario base = parseScenario(sc.get("base"));
            Scenario bear = parseScenario(sc.get("bear"));
            if (bull != null && base != null && bear != null) {
                double total = bull.probability() + base.probability() + bear.probability();
                if (total > 0) {
                    bull = new Scenario(bull.probability() / total, bull.target());
                    base = new Scenario(base.probability() / total, base.target());
                    bear = new Scenario(bear.probability() / total, bear.target());
                }
                scenario = new ScenarioSet(bull, base, bear);
            }
        }

        List<String> reasons = new ArrayList<>();
        if (node.has("reasons") && node.get("reasons").isArray()) {
            for (JsonNode r : node.get("reasons")) {
                if (r.isTextual() && !r.asText().isBlank()) reasons.add(r.asText());
            }
        }

        return new StructuredStock(
            name, code, action, confidence,
            toLongOrNull(node, "currentPrice"),
            toLongOrNull(node, "targetPrice"),
            toLongOrNull(node, "stopLoss"),
            scenario, reasons
        );
    }

    private static Scenario parseScenario(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        double prob = node.path("probability").asDouble(0);
        if (Double.isNaN(prob)) return null;
        return new Scenario(clamp(prob, 0, 1), toLongOrNull(node, "target"));
    }

    private static String normalizeAction(String action) {
        String lower = action.toLowerCase();
        if (lower.contains("매수") || lower.equals("buy")) return "매수";
        if (lower.contains("매도") || lower.equals("sell")) return "매도";
        if (lower.contains("관망") || lower.equals("hold")) return "관망";
        return "주목";
    }

    private static Long toLongOrNull(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) return null;
        long val = parent.get(field).asLong(0);
        return val > 0 ? val : null;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
