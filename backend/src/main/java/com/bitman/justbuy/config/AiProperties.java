package com.bitman.justbuy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "bitman.ai")
public record AiProperties(
        String openaiApiKey,
        String grokApiKey,
        String geminiApiKey,
        String deepseekApiKey,
        String deepseekBaseUrl,
        String deepseekModel
) {
    @ConstructorBinding
    public AiProperties {
    }

    public AiProperties(String openaiApiKey, String grokApiKey) {
        this(openaiApiKey, grokApiKey, "", "", "", "");
    }

    public AiProperties(String openaiApiKey, String grokApiKey, String geminiApiKey) {
        this(openaiApiKey, grokApiKey, geminiApiKey, "", "", "");
    }
}
