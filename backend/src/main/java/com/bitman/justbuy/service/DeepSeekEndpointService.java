package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.agent.DeepSeekAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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
        return envelope(deepSeekAgent.analyze(system, user), true);
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
        return envelope(deepSeekAgent.analyze(system, toJson(request)), true);
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
            This is risk filtering, not financial advice.
            """;
        return envelope(deepSeekAgent.analyze(system, toJson(request)), true);
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
