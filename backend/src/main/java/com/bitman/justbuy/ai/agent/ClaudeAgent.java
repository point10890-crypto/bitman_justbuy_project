package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class ClaudeAgent implements AiAgent {

    private final AiProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public ClaudeAgent(AiProperties props, RestTemplate restTemplate, ObjectMapper mapper) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    @Override
    public String name() { return "claude"; }

    @Override
    public boolean isAvailable() {
        return props.anthropicApiKey() != null && !props.anthropicApiKey().isBlank();
    }

    @Override
    public AgentResult analyze(String systemPrompt, String userMessage) {
        long start = System.currentTimeMillis();
        String model = "claude-sonnet-4-20250514";

        if (!isAvailable()) {
            return AgentResult.skipped("claude", "API key not configured");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", props.anthropicApiKey());
            headers.set("anthropic-version", "2023-06-01");

            Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 4096,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userMessage))
            );

            HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                "https://api.anthropic.com/v1/messages",
                HttpMethod.POST, request, String.class
            );

            JsonNode root = mapper.readTree(response.getBody());
            String content = "";
            JsonNode contentArray = root.path("content");
            if (contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.path("type").asText())) {
                        content = block.path("text").asText();
                        break;
                    }
                }
            }

            String actualModel = root.path("model").asText(model);
            int inputTokens = root.path("usage").path("input_tokens").asInt(0);
            int outputTokens = root.path("usage").path("output_tokens").asInt(0);

            return new AgentResult("claude", content, actualModel, inputTokens, outputTokens,
                "success", null, System.currentTimeMillis() - start);

        } catch (Exception e) {
            return AgentResult.error("claude", model, e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
