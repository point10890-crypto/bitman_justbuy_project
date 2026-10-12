package com.bitman.justbuy.dto.condition;

import java.util.Map;

/** A scalar from the original packet, with immutable source and observation context. */
public record EvidenceFact(String factId, String stockCode, String field, Object value,
                           Map<String, String> context) {}
