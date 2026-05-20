package com.bitman.justbuy.dto.condition;

import java.util.List;

public record ConditionCaptureTimesResponse(
    String asOf,
    String endpoint,
    String sourceStatus,
    int totalCount,
    List<ConditionCaptureTimeDto> captures
) {}
