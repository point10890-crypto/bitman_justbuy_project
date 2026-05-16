package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini 에이전트.
 * Google AI Studio API 사용 (https://generativelanguage.googleapis.com).
 * 담당: L3 Capital Flow + L5 Derivatives + Google Search Grounding (실시간 웹 정보).
 *
 * v2.8.6 (2026-04-29): xAI Grok team_blocked 사태로 Grok 대체.
 *  - 모델: gemini-2.5-flash (분석용 — 토큰 효율 + Grounding 지원)
 *  - 도구: googleSearch (Grok 의 web_search 등가물)
 *
 * 활성 조건: bitman.ai.gemini-api-key 가 비어있지 않을 때만 빈 등록.
 */
@Component
@ConditionalOnProperty(name = "bitman.ai.gemini-api-key", matchIfMissing = false)
public class GeminiAgent implements AiAgent {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models";
    // 분석용: 빠르고 저렴, Grounding 지원 (실시간 검색)
    static final String ANALYSIS_MODEL = "gemini-2.5-flash";
    // 추론용 (사용 시): 더 강력하지만 비쌈
    static final String REASONING_MODEL = "gemini-2.5-pro";

    private final AiProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public GeminiAgent(AiProperties props, RestTemplate restTemplate, ObjectMapper mapper) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    @Override
    public String name() { return "gemini"; }

    @Override
    public boolean isAvailable() {
        return props.geminiApiKey() != null && !props.geminiApiKey().isBlank();
    }

    /**
     * R1 분석용 — Grounding(googleSearch) 활성화.
     * GrokAgent 의 web_search 와 동등한 역할 — 실시간 시장·뉴스·공시 정보 검색 후 분석.
     */
    @Override
    public AgentResult analyze(String systemPrompt, String userMessage) {
        return callGemini(ANALYSIS_MODEL, systemPrompt, userMessage, true);
    }

    /**
     * Google Gemini API 호출.
     * grounding=true 이면 googleSearch tool 활성 (실시간 검색 결과 포함).
     */
    private AgentResult callGemini(String model, String systemPrompt, String userMessage, boolean grounding) {
        long start = System.currentTimeMillis();

        if (!isAvailable()) {
            return AgentResult.skipped(name(), "API key not configured");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Gemini API 요청 본문
            //  - systemInstruction: 시스템 프롬프트
            //  - contents: 사용자 메시지
            //  - generationConfig: 출력 길이/temperature
            //  - tools: googleSearch (grounding)
            Map<String, Object> body = new LinkedHashMap<>();

            body.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", systemPrompt))
            ));

            body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userMessage))
            )));

            body.put("generationConfig", Map.of(
                "maxOutputTokens", 4096,
                "temperature", 0.7
            ));

            // Grounding (googleSearch) 활성 — 실시간 정보 필요한 분석에서
            if (grounding) {
                List<Map<String, Object>> tools = new ArrayList<>();
                tools.add(Map.of("googleSearch", Map.of()));
                body.put("tools", tools);
            }

            // URL: ?key={API_KEY} (헤더 인증 아님)
            String url = BASE_URL + "/" + model + ":generateContent?key=" + props.geminiApiKey();

            HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, request, String.class);

            return parseResponse(response.getBody(), model, start);

        } catch (Exception e) {
            return AgentResult.error(name(), model, e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    /**
     * Gemini 응답 파싱.
     * 응답 구조: { candidates: [{ content: { parts: [{ text: "..." }] }, groundingMetadata: {...} }],
     *              usageMetadata: { promptTokenCount, candidatesTokenCount } }
     */
    private AgentResult parseResponse(String responseBody, String model, long start) throws Exception {
        JsonNode root = mapper.readTree(responseBody);

        // 텍스트 추출 — candidates[0].content.parts[*].text 모두 합침
        StringBuilder content = new StringBuilder();
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && candidates.size() > 0) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode p : parts) {
                    String text = p.path("text").asText("");
                    if (!text.isEmpty()) content.append(text);
                }
            }
        }

        String contentStr = content.toString();

        // Grounding 인용 추출 (groundingMetadata.groundingChunks[].web.uri)
        List<String> citations = new ArrayList<>();
        JsonNode chunks = candidates.path(0).path("groundingMetadata").path("groundingChunks");
        if (chunks.isArray()) {
            for (JsonNode chunk : chunks) {
                String uri = chunk.path("web").path("uri").asText("");
                if (!uri.isEmpty()) citations.add(uri);
            }
        }

        // 인용이 있으면 콘텐츠 끝에 append (GrokAgent 와 동일 포맷)
        if (!citations.isEmpty()) {
            StringBuilder sb = new StringBuilder(contentStr);
            sb.append("\n\n**📎 Google 검색 출처 (").append(citations.size()).append("건)**\n");
            for (int i = 0; i < Math.min(citations.size(), 10); i++) {
                sb.append("- ").append(citations.get(i)).append("\n");
            }
            contentStr = sb.toString();
        }

        // 토큰 사용량
        int inputTokens = root.path("usageMetadata").path("promptTokenCount").asInt(0);
        int outputTokens = root.path("usageMetadata").path("candidatesTokenCount").asInt(0);

        // 빈 응답 처리 (safety filter 등)
        if (contentStr.isBlank()) {
            JsonNode finishReason = candidates.path(0).path("finishReason");
            String reason = finishReason.asText("EMPTY");
            return AgentResult.error(name(), model, "Empty response (finish=" + reason + ")", System.currentTimeMillis() - start);
        }

        return new AgentResult(name(), contentStr, model, inputTokens, outputTokens,
            "success", null, System.currentTimeMillis() - start);
    }
}
