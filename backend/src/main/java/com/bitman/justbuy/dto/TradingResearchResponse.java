package com.bitman.justbuy.dto;

import java.util.List;

/**
 * TradingAgents-inspired structured research view.
 * This is informational output only and is never an order instruction.
 */
public record TradingResearchResponse(
    String jobId,
    String informationalRating,
    boolean executionAllowed,
    List<AnalystReport> analystReports,
    DebateReview researchDebate,
    RiskReview riskReview,
    String finalAssessment,
    Freshness freshness,
    Usage usage
) {
    public record AnalystReport(
        String analyst,
        String status,
        String model,
        String report
    ) {}

    public record DebateReview(
        List<String> bullCase,
        List<String> bearCase,
        int agreementScore,
        String conclusion
    ) {}

    public record RiskReview(
        String level,
        List<String> factors,
        List<String> invalidationConditions
    ) {}

    public record Freshness(
        boolean fresh,
        String asOf
    ) {}

    public record Usage(
        long durationMs,
        int agentsUsed,
        int agentsSucceeded
    ) {}
}
