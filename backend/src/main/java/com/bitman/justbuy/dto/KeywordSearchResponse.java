package com.bitman.justbuy.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Source observations and deterministic matches; no model-generated stock facts. */
public record KeywordSearchResponse(
    String query, String outcome, List<String> appliedConditions, List<String> unsupportedTerms,
    Scope scope, Instant fetchedAt, String sourceStatus, List<String> warnings, List<Match> matches
) {
    public record Scope(String label, int examinedCount, int eligibleCount, int matchedCount, boolean truncated) {}
    public record Match(String code, String name, String market, BigDecimal currentPrice,
        BigDecimal changePct, BigDecimal volume, BigDecimal tradingValueKrw, String quoteSource,
        Instant quoteCheckedAt, Instant observedAt, List<Evidence> evidence) {}
    public record Evidence(String condition, String observedValue, String unit, String source, String observedAt) {}
}
