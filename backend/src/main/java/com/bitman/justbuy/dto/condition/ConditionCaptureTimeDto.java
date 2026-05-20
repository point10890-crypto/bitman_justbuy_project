package com.bitman.justbuy.dto.condition;

public record ConditionCaptureTimeDto(
    String section,
    String slug,
    String title,
    String mode,
    int rank,
    String stockName,
    String stockCode,
    String capturedAt,
    String capturedTime,
    String sourceStatus
) {}
