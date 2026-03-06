package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Base class for OpenAI-compatible API agents (ChatGPT, Perplexity, Grok).
 */
public abstract class OpenAiCompatibleAgent implements AiAgent {

    protected final RestTemplate restTemplate;
    protected final ObjectMapper mapper;

    protected OpenAiCompatibleAgent(RestTemplate restTemplate, ObjectMapper mapper) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    protected abstract String baseUrl();
    protected abstract String model();
    protected abstract String apiKey();

    @Override
    public AgentResult analyze(String systemPrompt, String userMessage) {
        long start = System.currentTimeMillis();

        if (!isAvailable()) {
            return AgentResult.skipped(name(), "API key not configured");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey());

            Map<String, Object> body = Map.of(
                "model", model(),
                "max_tokens", 4096,
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userMessage)
                )
            );

            HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/chat/completions",
                HttpMethod.POST, request, String.class
            );

            JsonNode root = mapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            String actualModel = root.path("model").asText(model());
            int inputTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = root.path("usage").path("completion_tokens").asInt(0);

            return new AgentResult(name(), content, actualModel, inputTokens, outputTokens,
                "success", null, System.currentTimeMillis() - start);

        } catch (Exception e) {
            return AgentResult.error(name(), model(), e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
