package com.bitman.justbuy.config;

import com.github.benmanes.caffeine.cache.Policy;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorHealthCacheTest {
    @Test
    void serverHealthExpiresWithinThirtySecondsInsteadOfQuoteAnalysisLifetime() {
        var manager = new AppConfig().cacheManager();
        var health = (CaffeineCache) manager.getCache("health");
        var policy = health.getNativeCache().policy().expireAfterWrite().orElseThrow();
        assertThat(policy.getExpiresAfter(TimeUnit.SECONDS)).isLessThanOrEqualTo(30);
        var analysis = (CaffeineCache) manager.getCache("analysis");
        assertThat(analysis.getNativeCache().policy().expireAfterWrite().orElseThrow()
            .getExpiresAfter(TimeUnit.SECONDS)).isGreaterThan(30);
    }
}
