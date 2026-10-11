package com.bitman.justbuy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class KisExtendedEvidenceConfig {
    @Bean("kisExtendedEvidence")
    public RestTemplate kisExtendedEvidenceRestTemplate() {
        return newClient();
    }

    public static RestTemplate newClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(1));
        return new RestTemplate(factory);
    }
}
