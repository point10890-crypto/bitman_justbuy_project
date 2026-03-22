package com.bitman.justbuy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bitman.dart")
public record DartProperties(String apiKey) {}
