package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.service.RuntimeAiConfigService;
import com.bitman.justbuy.service.RuntimeAiConfigService.DeepSeekConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek OpenAI-compatible agent.
 * Uses server-side DEEPSEEK_API_KEY only; the key is never exposed to clients.
 */
@Component
public class DeepSeekAgent implements AiAgent {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    private final AiProperties props;
    private final RuntimeAiConfigService runtimeAiConfigService;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public DeepSeekAgent(AiProperties props,
                         RuntimeAiConfigService runtimeAiConfigService,
                         RestTemplate restTemplate,
                         ObjectMapper mapper) {
        this.props = props;
        this.runtimeAiConfigService = runtimeAiConfigService;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public boolean isAvailable() {
        return runtimeAiConfigService.getDeepSeekConfig().configured();
    }

    @Override
    public AgentResult analyze(String systemPrompt, String userMessage) {
        long start = System.currentTimeMillis();
        DeepSeekConfig config = runtimeAiConfigService.getDeepSeekConfig();
        String model = model(config);

        if (!config.configured()) {
            return AgentResult.skipped(name(), "API key not configured");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(config.apiKey());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 4096);
            body.put("thinking", Map.of("type", "disabled"));
            body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
            ));

            HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                baseUrl(config) + "/chat/completions",
                HttpMethod.POST,
                request,
                String.class
            );

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

    private String baseUrl(DeepSeekConfig config) {
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return config.baseUrl().replaceAll("/+$", "");
    }

    private String model(DeepSeekConfig config) {
        if (config.model() == null || config.model().isBlank()) {
            return DEFAULT_MODEL;
        }
        return config.model();
    }
}
