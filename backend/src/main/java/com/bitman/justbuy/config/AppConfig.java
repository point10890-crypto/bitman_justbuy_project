package com.bitman.justbuy.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Arrays;

@Configuration
@EnableCaching
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        return new RestTemplate(factory);
    }

    @Bean
    public CacheManager cacheManager() {
        var manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofHours(4)));

        // 여러 캐시 영역 정의 (TTL 다양화)
        manager.setCacheNames(Arrays.asList(
            "marketData",      // 시장 데이터: 30분
            "analysis",        // 분석 결과: 1시간
            "userData",        // 사용자 데이터: 30분
            "health",          // 헬스체크: 5분
            "static"           // 정적 데이터: 4시간
        ));

        return manager;
    }

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("BitMan JustBuy API")
                        .version("2.7.0")
                        .description("KR/US 시장 데이터 및 AI 분석 API")
                        .contact(new Contact()
                                .name("BitMan Team")
                                .email("support@bitman.ai")));
    }
}
