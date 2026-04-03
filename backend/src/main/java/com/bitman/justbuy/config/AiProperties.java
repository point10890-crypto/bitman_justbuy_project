package com.bitman.justbuy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bitman.ai")
public record AiProperties(
        String openaiApiKey,
        String grokApiKey
) {}
