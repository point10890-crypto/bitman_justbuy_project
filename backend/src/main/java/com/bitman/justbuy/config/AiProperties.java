package com.bitman.justbuy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bitman.ai")
public record AiProperties(
        String anthropicApiKey,
        String openaiApiKey,
        String googleApiKey,
        String perplexityApiKey,
        String grokApiKey
) {}
