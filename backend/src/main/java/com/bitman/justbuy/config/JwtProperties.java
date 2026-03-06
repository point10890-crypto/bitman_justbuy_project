package com.bitman.justbuy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bitman.jwt")
public record JwtProperties(
    String secret,
    long expirationMs
) {}
