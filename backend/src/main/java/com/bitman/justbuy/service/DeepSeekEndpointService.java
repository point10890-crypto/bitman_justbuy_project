package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.agent.DeepSeekAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

@Service
public class DeepSeekEndpointService {

    private final DeepSeekAgent deepSeekAgent;
    private final RuntimeAiConfigService runtimeAiConfigService;
    private final ObjectMapper mapper;

    public DeepSeekEndpointService(DeepSeekAgent deepSeekAgent,
                                   RuntimeAiConfigService runtimeAiConfigService,
                                   ObjectMapper mapper) {
        this.deepSeekAgent = deepSeekAgent;
        this.runtimeAiConfigService = runtimeAiConfigService;
        this.mapper = mapper;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>(runtimeAiConfigService.deepSeekStatus());
        status.put("available", deepSeekAgent.isAvailable());
        return status;
    }

    public Map<String, Object> test(String message) {
        String prompt = "Return a compact Korean response confirming DeepSeek connectivity. Do not reveal secrets.";
        String user = message == null || message.isBlank() ? "ping" : message.trim();
        return envelope(deepSeekAgent.analyze(prompt, user), false);
    }

    public Map<String, Object> structuredSignal(String mode, String rawContent) {
        String system = """
            You convert Korean stock analysis text into strict JSON only.
            Output schema:
            {
              "mode": string,
              "stockPicks": [
                {
                  "name": string,
                  "code": string,
                  "currentPrice": string,
                  "targetPrice": string,
                  "stopLoss": string,
                  "action": string,
                  "reason": string,
                  "risk": string,
                  "confidence": number
                }
              ],
              "warnings": string[]
            }
            If data is missing, use "미확인". Do not invent stock codes.
            """;
        String user = "mode=" + safe(mode) + "\n\nrawContent:\n" + safe(rawContent);
        return validatedEnvelope(deepSeekAgent.analyze(system, user), "signal", codesInText(rawContent));
    }

    public Map<String, Object> validatePicks(Map<String, Object> request) {
        String system = """
            You are a risk validator for JustBuy Korean stock picks.
            Use only the provided data. Do not browse. Return strict JSON only:
            {
              "verdict": "pass|caution|reject",
              "summary": string,
              "validatedPicks": [
                {
                  "name": string,
                  "code": string,
                  "decision": "priority|watch|caution|exclude",
                  "riskLevel": "low|medium|high",
                  "confidence": number,
                  "validations": string[],
                  "warnings": string[]
                }
              ],
              "missingData": string[]
            }
            Focus on code/name mismatch, weak evidence, overheat, liquidity, disclosure/news gaps, and stop-loss clarity.
            """;
        return validatedEnvelope(deepSeekAgent.analyze(system, toJson(request)), "validation", requestCodes(request));
    }

    public Map<String, Object> riskBrief(Map<String, Object> request) {
        String system = """
            You create a concise risk brief for JustBuy candidate stocks.
            Use only provided data. Return strict JSON only:
            {
              "overallRisk": "low|medium|high",
              "priority": string[],
              "watch": string[],
              "caution": string[],
              "exclude": string[],
              "brief": string,
              "riskFactors": string[]
            }
            Each priority/watch/caution/exclude entry must be one six-digit code from the input candidates.
            Do not repeat a code across buckets. Use an empty array when a bucket has no candidates.
            This is risk filtering, not financial advice.
            """;
        return validatedEnvelope(deepSeekAgent.analyze(system, toJson(request)), "risk", requestCodes(request));
    }

    private Map<String, Object> validatedEnvelope(AgentResult result, String schema, Set<String> candidates) {
        Map<String, Object> response = envelope(result, false);
        response.remove("content");
        if (!"success".equals(result.status())) return response;
        try {
            JsonNode root = mapper.readTree(cleanJson(result.content()));
            if (!validSchema(root, schema, candidates)) throw new IllegalArgumentException("invalid result schema or candidate identity");
            response.put("result", mapper.convertValue(root, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            response.put("status", "error");
            response.put("error", "DeepSeek result failed schema and candidate validation.");
        }
        return response;
    }

    private boolean validSchema(JsonNode root, String schema, Set<String> candidates) {
        if (root == null || !root.isObject() || candidates.isEmpty()) return false;
        if ("risk".equals(schema)) {
            if (!Set.of("low", "medium", "high").contains(root.path("overallRisk").asText())
                    || !text(root, "brief") || !strings(root.path("riskFactors"), true)) return false;
            Set<String> seen = new LinkedHashSet<>();
            for (String bucket : new String[]{"priority", "watch", "caution", "exclude"}) {
                if (!root.path(bucket).isArray()) return false;
                for (JsonNode code : root.path(bucket)) {
                    if (!code.isTextual() || !candidates.contains(code.asText()) || !seen.add(code.asText())) return false;
                }
            }
            return true;
        }
        boolean signal = "signal".equals(schema);
        if (signal ? !text(root, "mode") || !strings(root.path("warnings"), false)
                : !Set.of("pass", "caution", "reject").contains(root.path("verdict").asText())
                    || !text(root, "summary") || !strings(root.path("missingData"), false)) return false;
        JsonNode rows = root.path(signal ? "stockPicks" : "validatedPicks");
        if (!rows.isArray()) return false;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String code = row.path("code").asText("");
            if (!row.isObject() || !code.matches("[0-9]{6}") || !candidates.contains(code) || !seen.add(code)
                    || !text(row, "name") || !row.path("confidence").isNumber()) return false;
            double confidence = row.path("confidence").asDouble();
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) return false;
            if (signal) {
                for (String field : new String[]{"currentPrice", "targetPrice", "stopLoss", "action", "reason", "risk"}) {
                    if (!text(row, field)) return false;
                }
                if (!Set.of("매수", "매도", "관망", "주목", "buy", "sell", "hold", "watch").contains(row.path("action").asText())) return false;
            } else if (!Set.of("priority", "watch", "caution", "exclude").contains(row.path("decision").asText())
                    || !Set.of("low", "medium", "high").contains(row.path("riskLevel").asText())
                    || !strings(row.path("validations"), true) || !strings(row.path("warnings"), false)) return false;
        }
        return true;
    }

    private static boolean text(JsonNode root, String field) {
        return root.path(field).isTextual() && !root.path(field).asText().isBlank();
    }

    private static boolean strings(JsonNode value, boolean nonempty) {
        if (!value.isArray() || (nonempty && value.isEmpty())) return false;
        for (JsonNode item : value) if (!item.isTextual() || item.asText().isBlank()) return false;
        return true;
    }

    private Set<String> requestCodes(Map<String, Object> request) {
        Set<String> codes = new LinkedHashSet<>();
        collectCodes(mapper.valueToTree(request), codes);
        return codes;
    }

    private void collectCodes(JsonNode node, Set<String> codes) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (Set.of("code", "stockCode").contains(entry.getKey()) && entry.getValue().isTextual()
                        && entry.getValue().asText().matches("[0-9]{6}")) codes.add(entry.getValue().asText());
                else collectCodes(entry.getValue(), codes);
            });
        } else if (node.isArray()) node.forEach(child -> collectCodes(child, codes));
    }

    private static Set<String> codesInText(String value) {
        Set<String> codes = new LinkedHashSet<>();
        var matcher = java.util.regex.Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])").matcher(value == null ? "" : value);
        while (matcher.find()) codes.add(matcher.group());
        return codes;
    }

    private Map<String, Object> envelope(AgentResult result, boolean parseJson) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", "deepseek");
        response.put("timestamp", Instant.now().toString());
        response.put("status", result.status());
        response.put("model", result.model());
        response.put("latencyMs", result.durationMs());
        response.put("inputTokens", result.inputTokens());
        response.put("outputTokens", result.outputTokens());

        if (result.error() != null && !result.error().isBlank()) {
            response.put("error", result.error());
        }

        if (parseJson && "success".equals(result.status())) {
            Object parsed = parseJsonObject(result.content());
            if (parsed != null) {
                response.put("result", parsed);
            } else {
                response.put("content", result.content());
                response.put("parseWarning", "DeepSeek response was not valid JSON.");
            }
        } else {
            response.put("content", result.content());
        }

        return response;
    }

    private Object parseJsonObject(String content) {
        String cleaned = cleanJson(content);
        try {
            return mapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private String cleanJson(String content) {
        if (content == null) return "";
        String cleaned = content.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return cleaned.trim();
    }

    private String toJson(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
