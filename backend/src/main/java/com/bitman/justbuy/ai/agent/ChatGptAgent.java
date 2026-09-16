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
 * OpenAI 에이전트. Responses API의 웹검색으로 최신 근거를 수집한다.
 * 담당: L1 Macro + L2 Currency + L4 기술적분석 + L6 펀더멘탈
 */
@Component
public class ChatGptAgent implements AiAgent {

    private static final String BASE_URL = "https://api.openai.com/v1";
    // Search preview was shut down; this model is verified against the production project.
    static final String ANALYSIS_MODEL = "gpt-5.5";
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

    /** R1 분석용 — 웹검색 필수, 저장 비활성화. */
    @Override
    public AgentResult analyze(String systemPrompt, String userMessage) {
        long start = System.currentTimeMillis();
        if (!isAvailable()) return AgentResult.skipped(name(), "API key not configured");
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(props.openaiApiKey());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", ANALYSIS_MODEL);
            body.put("max_output_tokens", 8192);
            body.put("store", false);
            body.put("reasoning", Map.of("effort", "low"));
            body.put("input", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)));
            body.put("tools", List.of(Map.of("type", "web_search",
                "search_context_size", "medium",
                "user_location", Map.of("type", "approximate", "country", "KR", "city", "Seoul"))));
            body.put("tool_choice", "required");
            var response = restTemplate.exchange(BASE_URL + "/responses", HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);
            JsonNode root = mapper.readTree(response.getBody());
            if (!"completed".equals(root.path("status").asText())) {
                return AgentResult.error(name(), ANALYSIS_MODEL,
                    "OpenAI response " + root.path("status").asText("missing status") + ": "
                        + root.path("incomplete_details").path("reason").asText("not completed"),
                    System.currentTimeMillis() - start);
            }
            StringBuilder content = new StringBuilder();
            var citations = new java.util.LinkedHashSet<String>();
            for (JsonNode item : root.path("output")) {
                if (!"message".equals(item.path("type").asText())) continue;
                for (JsonNode part : item.path("content")) {
                    if (!"output_text".equals(part.path("type").asText())) continue;
                    if (!content.isEmpty()) content.append('\n');
                    content.append(part.path("text").asText(""));
                    for (JsonNode annotation : part.path("annotations")) {
                        String url = annotation.path("url").asText("");
                        if ("url_citation".equals(annotation.path("type").asText())
                                && (url.startsWith("https://") || url.startsWith("http://"))) {
                            citations.add(url);
                        }
                    }
                }
            }
            if (content.toString().isBlank()) {
                return AgentResult.error(name(), ANALYSIS_MODEL, "Empty OpenAI analysis response",
                    System.currentTimeMillis() - start);
            }
            if (!citations.isEmpty()) {
                content.append("\n\n**검색 출처**\n");
                citations.forEach(url -> content.append("- ").append(url).append('\n'));
            }
            return new AgentResult(name(), content.toString(), root.path("model").asText(ANALYSIS_MODEL),
                root.path("usage").path("input_tokens").asInt(0),
                root.path("usage").path("output_tokens").asInt(0), "success", null,
                System.currentTimeMillis() - start);
        } catch (Exception e) {
            return AgentResult.error(name(), ANALYSIS_MODEL, e.getMessage(), System.currentTimeMillis() - start);
        }
    }

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
