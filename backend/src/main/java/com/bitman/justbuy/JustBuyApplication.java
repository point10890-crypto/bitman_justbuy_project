package com.bitman.justbuy;

import com.bitman.justbuy.config.AiProperties;
import com.bitman.justbuy.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties({AiProperties.class, JwtProperties.class})
public class JustBuyApplication {
    public static void main(String[] args) {
        SpringApplication.run(JustBuyApplication.class, args);
    }
}
