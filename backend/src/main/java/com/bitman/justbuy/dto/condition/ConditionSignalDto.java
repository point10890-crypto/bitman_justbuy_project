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
    String maxReturnPct,
    String status,
    int ruleScore,
    int aiScore,
    int finalScore,
    String summary,
    List<String> evidence,
    List<String> riskFlags,
    String invalidation,
    String capturedAt
) {}
