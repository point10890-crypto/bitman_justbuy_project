package com.bitman.justbuy.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Arrays;

@Configuration
@EnableCaching
public class AppConfig {

    /** 기본 RestTemplate — AI/시장 데이터 API용 (connect 10s / read 120s) */
    @Bean
    @Primary
    public RestTemplate restTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        return new RestTemplate(factory);
    }

    /** 텔레그램 전용 RestTemplate — connect 30s / read 30s (간헐적 타임아웃 대응) */
    @Bean
    @Qualifier("telegram")
    public RestTemplate telegramRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(30));
        factory.setReadTimeout(Duration.ofSeconds(30));
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
}
