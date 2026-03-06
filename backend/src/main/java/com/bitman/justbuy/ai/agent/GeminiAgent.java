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
public class GeminiAgent implements AiAgent {

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
        return props.googleApiKey() != null && !props.googleApiKey().isBlank();
    }

    @Override
    public AgentResult analyze(String systemPrompt, String userMessage) {
        long start = System.currentTimeMillis();
        String model = "gemini-2.5-flash";

        if (!isAvailable()) {
            return AgentResult.skipped("gemini", "API key not configured");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", userMessage))
                )),
                "generationConfig", Map.of("maxOutputTokens", 4096)
            );

            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + props.googleApiKey();

            HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);

            JsonNode root = mapper.readTree(response.getBody());
            String content = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            int inputTokens = root.path("usageMetadata").path("promptTokenCount").asInt(0);
            int outputTokens = root.path("usageMetadata").path("candidatesTokenCount").asInt(0);

            return new AgentResult("gemini", content, model, inputTokens, outputTokens,
                "success", null, System.currentTimeMillis() - start);

        } catch (Exception e) {
            return AgentResult.error("gemini", model, e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
