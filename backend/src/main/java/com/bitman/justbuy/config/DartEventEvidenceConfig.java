package com.bitman.justbuy.config;

import com.bitman.justbuy.service.DartEventEvidenceService.Options;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class DartEventEvidenceConfig {
    @Bean
    public Options dartEventEvidenceOptions(
        @Value("${bitman.dart.event-evidence.enabled:${BITMAN_DART_EVENT_EVIDENCE_ENABLED:false}}") boolean enabled) {
        return new Options(enabled);
    }

    @Bean("dartEventEvidence")
    public RestTemplate dartEventEvidenceRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(factory);
    }
}
