package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class ChatGptAgent extends OpenAiCompatibleAgent {

    private final AiProperties props;

    public ChatGptAgent(AiProperties props, RestTemplate restTemplate, ObjectMapper mapper) {
        super(restTemplate, mapper);
        this.props = props;
    }

    @Override public String name() { return "chatgpt"; }
    @Override public boolean isAvailable() { return props.openaiApiKey() != null && !props.openaiApiKey().isBlank(); }
    @Override protected String baseUrl() { return "https://api.openai.com/v1"; }
    @Override protected String model() { return "gpt-4o"; }
    @Override protected String apiKey() { return props.openaiApiKey(); }
}
