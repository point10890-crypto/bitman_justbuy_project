package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class PerplexityAgent extends OpenAiCompatibleAgent {

    private final AiProperties props;

    public PerplexityAgent(AiProperties props, RestTemplate restTemplate, ObjectMapper mapper) {
        super(restTemplate, mapper);
        this.props = props;
    }

    @Override public String name() { return "perplexity"; }
    @Override public boolean isAvailable() { return props.perplexityApiKey() != null && !props.perplexityApiKey().isBlank(); }
    @Override protected String baseUrl() { return "https://api.perplexity.ai"; }
    @Override protected String model() { return "sonar-pro"; }
    @Override protected String apiKey() { return props.perplexityApiKey(); }
}
