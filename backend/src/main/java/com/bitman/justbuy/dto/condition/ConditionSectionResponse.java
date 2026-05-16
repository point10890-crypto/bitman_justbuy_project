package com.bitman.justbuy.dto.condition;

import java.util.List;

public record ConditionSectionResponse(
    String key,
    String slug,
    String title,
    String mode,
    String endpoint,
    String asOf,
    String sourceStatus,
    List<ConditionSignalDto> signals
) {}
