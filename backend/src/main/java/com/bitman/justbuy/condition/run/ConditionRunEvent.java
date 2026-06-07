package com.bitman.justbuy.condition.run;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ConditionRunEvent(
    UUID eventId,
    UUID runId,
    ConditionRunEventType type,
    ConditionRunStatus status,
    Instant createdAt,
    String message,
    Map<String, Object> metrics
) {}
