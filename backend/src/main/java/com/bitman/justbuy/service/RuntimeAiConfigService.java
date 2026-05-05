package com.bitman.justbuy.service;

import com.bitman.justbuy.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class RuntimeAiConfigService {

    private static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String DEEPSEEK_API_KEY = "deepseek.apiKey";
    private static final String DEEPSEEK_BASE_URL = "deepseek.baseUrl";
    private static final String DEEPSEEK_MODEL = "deepseek.model";
    private static final String DEEPSEEK_UPDATED_AT = "deepseek.updatedAt";

    private final AiProperties aiProperties;
    private final ObjectMapper mapper;
    private final Path configPath;

    public RuntimeAiConfigService(AiProperties aiProperties,
                                  ObjectMapper mapper,
                                  @Value("${bitman.storage.data-dir:./data}") String dataDir) {
        this.aiProperties = aiProperties;
        this.mapper = mapper;
        this.configPath = Path.of(dataDir).resolve("runtime-ai-config.json");
    }

    public synchronized DeepSeekConfig getDeepSeekConfig() {
        Map<String, String> stored = readConfig();
        String storedKey = clean(stored.get(DEEPSEEK_API_KEY));
        String envKey = clean(aiProperties.deepseekApiKey());
        String apiKey = storedKey != null ? storedKey : envKey;
        String source = storedKey != null ? "runtime" : envKey != null ? "environment" : "none";

        String baseUrl = firstNonBlank(stored.get(DEEPSEEK_BASE_URL), aiProperties.deepseekBaseUrl(), DEFAULT_DEEPSEEK_BASE_URL);
        String model = firstNonBlank(stored.get(DEEPSEEK_MODEL), aiProperties.deepseekModel(), DEFAULT_DEEPSEEK_MODEL);

        return new DeepSeekConfig(apiKey, baseUrl, model, source, stored.get(DEEPSEEK_UPDATED_AT));
    }

    public synchronized DeepSeekConfig saveDeepSeekConfig(String apiKey, String baseUrl, String model) {
        String cleanKey = clean(apiKey);
        if (cleanKey == null) {
            throw new IllegalArgumentException("DeepSeek API key is required.");
        }

        Map<String, String> config = readConfig();
        config.put(DEEPSEEK_API_KEY, cleanKey);
        config.put(DEEPSEEK_BASE_URL, firstNonBlank(baseUrl, aiProperties.deepseekBaseUrl(), DEFAULT_DEEPSEEK_BASE_URL));
        config.put(DEEPSEEK_MODEL, firstNonBlank(model, aiProperties.deepseekModel(), DEFAULT_DEEPSEEK_MODEL));
        config.put(DEEPSEEK_UPDATED_AT, Instant.now().toString());
        writeConfig(config);
        return getDeepSeekConfig();
    }

    public synchronized DeepSeekConfig deleteRuntimeDeepSeekConfig() {
        Map<String, String> config = readConfig();
        config.remove(DEEPSEEK_API_KEY);
        config.remove(DEEPSEEK_BASE_URL);
        config.remove(DEEPSEEK_MODEL);
        config.remove(DEEPSEEK_UPDATED_AT);
        writeConfig(config);
        return getDeepSeekConfig();
    }

    public Map<String, Object> deepSeekStatus() {
        DeepSeekConfig config = getDeepSeekConfig();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("provider", "deepseek");
        status.put("configured", config.configured());
        status.put("source", config.source());
        status.put("baseUrl", config.baseUrl());
        status.put("model", config.model());
        status.put("maskedKey", mask(config.apiKey()));
        status.put("updatedAt", config.updatedAt());
        return status;
    }

    private Map<String, String> readConfig() {
        if (!Files.exists(configPath)) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(configPath.toFile(),
                mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read runtime AI config.", e);
        }
    }

    private void writeConfig(Map<String, String> config) {
        try {
            Files.createDirectories(configPath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write runtime AI config.", e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String cleaned = clean(value);
            if (cleaned != null) return cleaned;
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String mask(String value) {
        return Optional.ofNullable(clean(value))
            .map(v -> v.length() <= 8 ? "****" : v.substring(0, 4) + "..." + v.substring(v.length() - 4))
            .orElse(null);
    }

    public record DeepSeekConfig(String apiKey, String baseUrl, String model, String source, String updatedAt) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
