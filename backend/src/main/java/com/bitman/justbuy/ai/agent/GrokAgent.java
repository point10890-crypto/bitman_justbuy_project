package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class GrokAgent extends OpenAiCompatibleAgent {

    private final AiProperties props;

    public GrokAgent(AiProperties props, RestTemplate restTemplate, ObjectMapper mapper) {
        super(restTemplate, mapper);
        this.props = props;
    }

    @Override public String name() { return "grok"; }
    @Override public boolean isAvailable() { return props.grokApiKey() != null && !props.grokApiKey().isBlank(); }
    @Override protected String baseUrl() { return "https://api.x.ai/v1"; }
    @Override protected String model() { return "grok-4-latest"; }
    @Override protected String apiKey() { return props.grokApiKey(); }
}
