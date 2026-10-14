package com.bitman.justbuy.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Source observations and deterministic matches; no model-generated stock facts. */
public record KeywordSearchResponse(
    String query, String outcome, List<String> appliedConditions, List<String> unsupportedTerms,
    Scope scope, Instant fetchedAt, String sourceStatus, List<String> warnings, List<Match> matches,
    List<ProviderStatus> providers,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) FlowCoverage flowCoverage,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Progress progress
) {
    public KeywordSearchResponse(String query,String outcome,List<String> appliedConditions,List<String> unsupportedTerms,
        Scope scope,Instant fetchedAt,String sourceStatus,List<String> warnings,List<Match> matches,List<ProviderStatus> providers) {
        this(query,outcome,appliedConditions,unsupportedTerms,scope,fetchedAt,sourceStatus,warnings,matches,providers,null,null);
    }
    public KeywordSearchResponse(String query,String outcome,List<String> appliedConditions,List<String> unsupportedTerms,
        Scope scope,Instant fetchedAt,String sourceStatus,List<String> warnings,List<Match> matches) {
        this(query,outcome,appliedConditions,unsupportedTerms,scope,fetchedAt,sourceStatus,warnings,matches,List.of());
    }
    public record FlowCoverage(int requestedCount,int availableCount,int pendingCount,int unavailableCount) {}
    public record Progress(String id,Instant expiresAt,int retryAfterMs) {}
    public record ProviderStatus(String source,String environment,String status,Instant checkedAt,String scope,
        Integer requestedCount,Integer availableCount,Integer pendingCount) {
        public ProviderStatus(String source,String environment,String status,Instant checkedAt,String scope) {
            this(source,environment,status,checkedAt,scope,null,null,null);
        }
    }
    public record QuoteObservation(String source,String environment,BigDecimal currentPrice,BigDecimal changePct,
        BigDecimal volume,BigDecimal tradingValueKrw,Instant fetchedAt,Instant observedAt) {}
    public record Scope(String label, int examinedCount, int eligibleCount, int matchedCount, boolean truncated) {}
    public record Match(String code, String name, String market, BigDecimal currentPrice,
        BigDecimal changePct, BigDecimal volume, BigDecimal tradingValueKrw, String quoteSource,
        Instant quoteCheckedAt, Instant observedAt, List<Evidence> evidence,String quoteEnvironment,List<QuoteObservation> sourceObservations) {
        public Match(String code,String name,String market,BigDecimal currentPrice,BigDecimal changePct,BigDecimal volume,
            BigDecimal tradingValueKrw,String quoteSource,Instant quoteCheckedAt,Instant observedAt,List<Evidence> evidence) {
            this(code,name,market,currentPrice,changePct,volume,tradingValueKrw,quoteSource,quoteCheckedAt,observedAt,evidence,null,List.of());
        }
    }
    public record Evidence(String condition,String observedValue,String unit,String source,String observedAt,String environment,Instant fetchedAt) {
        public Evidence(String condition,String observedValue,String unit,String source,String observedAt) {
            this(condition,observedValue,unit,source,observedAt,null,null);
        }
    }
}
