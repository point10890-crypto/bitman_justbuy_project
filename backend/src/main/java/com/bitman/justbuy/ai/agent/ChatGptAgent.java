package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI GPT-4o 에이전트.
 * gpt-4o-search-preview 모델로 한국 금융 도메인 웹검색 포함 분석.
 * 담당: L1 Macro + L2 Currency + L4 기술적분석 + L6 펀더멘탈
 */
@Component
public class ChatGptAgent implements AiAgent {

    private static final String BASE_URL = "https://api.openai.com/v1";
    // 분석용: 웹검색 포함 모델 / 합성용: 순수 추론 모델
    static final String ANALYSIS_MODEL = "gpt-4o-search-preview";
    static final String SYNTHESIS_MODEL = "gpt-4o";

    private final AiProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public ChatGptAgent(AiProperties props, RestTemplate restTemplate, ObjectMapper mapper) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    @Override
    public String name() { return "chatgpt"; }

    @Override
    public boolean isAvailable() {
        return props.openaiApiKey() != null && !props.openaiApiKey().isBlank();
    }

    /** R1 분석용 — 웹검색 활성화 (gpt-4o-search-preview) */
    @Override
    public AgentResult analyze(String systemPrompt, String userMessage) {
        return callOpenAi(ANALYSIS_MODEL, systemPrompt, userMessage, true);
    }

    /** R3 합성용 — 웹검색 없이 순수 추론 (gpt-4o) */
    public AgentResult synthesize(String systemPrompt, String userMessage) {
        return callOpenAi(SYNTHESIS_MODEL, systemPrompt, userMessage, false);
    }

    private AgentResult callOpenAi(String model, String systemPrompt, String userMessage, boolean enableWebSearch) {
        long start = System.currentTimeMillis();

        if (!isAvailable()) {
            return AgentResult.skipped(name(), "API key not configured");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + props.openaiApiKey());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 4096);
            body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
            ));

            // 웹검색 옵션 (분석 모드에서만 활성화)
            if (enableWebSearch) {
                body.put("web_search_options", Map.of(
                    "search_context_size", "medium",
                    "user_location", Map.of(
                        "type", "approximate",
                        "approximate", Map.of("country", "KR", "city", "Seoul")
                    )
                ));
            }

            HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                BASE_URL + "/chat/completions", HttpMethod.POST, request, String.class);

            JsonNode root = mapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            String actualModel = root.path("model").asText(model);
            int inputTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = root.path("usage").path("completion_tokens").asInt(0);

            return new AgentResult(name(), content, actualModel, inputTokens, outputTokens,
                "success", null, System.currentTimeMillis() - start);

        } catch (Exception e) {
            return AgentResult.error(name(), model, e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
