package com.bitman.justbuy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bitman.kis")
public record KisProperties(String appKey, String appSecret) {}
