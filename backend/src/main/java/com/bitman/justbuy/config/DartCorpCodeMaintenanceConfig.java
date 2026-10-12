package com.bitman.justbuy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;

@Configuration
public class DartCorpCodeMaintenanceConfig {
    @Bean("dartCorpCodeMaintenance")
    public RestTemplate dartCorpCodeMaintenanceRestTemplate() {
        var factory = new DartFinancialEvidenceConfig.DisconnectingRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        var client = new RestTemplate(factory);
        client.setErrorHandler(new NoOpResponseErrorHandler());
        return client;
    }
}
