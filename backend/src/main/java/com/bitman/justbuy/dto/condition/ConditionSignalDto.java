package com.bitman.justbuy.dto.condition;

import java.util.List;

public record ConditionSignalDto(
    String section,
    String mode,
    int rank,
    String stockName,
    String stockCode,
    String capturePrice,
    String currentPrice,
    String highPrice,
    String stopLoss,
    String maxReturnPct,
    String status,
    int ruleScore,
    int aiScore,
    int finalScore,
    String summary,
    List<String> evidence,
    List<String> riskFlags,
    String invalidation,
    String capturedAt,
    List<ConditionCheck> conditionChecks
) {
    public ConditionSignalDto {
        conditionChecks = conditionChecks == null ? ConditionCheck.unknown(mode) : List.copyOf(conditionChecks);
    }

    // Existing producers and stored snapshots remain compatible; missing evidence is never a pass.
    public ConditionSignalDto(String section, String mode, int rank, String stockName, String stockCode,
        String capturePrice, String currentPrice, String highPrice, String stopLoss, String maxReturnPct,
        String status, int ruleScore, int aiScore, int finalScore, String summary, List<String> evidence,
        List<String> riskFlags, String invalidation, String capturedAt) {
        this(section, mode, rank, stockName, stockCode, capturePrice, currentPrice, highPrice, stopLoss,
            maxReturnPct, status, ruleScore, aiScore, finalScore, summary, evidence, riskFlags, invalidation,
            capturedAt, null);
    }

    public ConditionSignalDto withChecks(List<ConditionCheck> checks) {
        return new ConditionSignalDto(section, mode, rank, stockName, stockCode, capturePrice, currentPrice,
            highPrice, stopLoss, maxReturnPct, status, ruleScore, aiScore, finalScore, summary, evidence,
            riskFlags, invalidation, capturedAt, checks);
    }
}
