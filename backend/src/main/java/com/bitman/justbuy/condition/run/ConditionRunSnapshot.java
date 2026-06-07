package com.bitman.justbuy.condition.run;

import java.time.Instant;
import java.util.UUID;

public record ConditionRunSnapshot(
    UUID runId,
    String traceId,
    ConditionRunTrigger trigger,
    String mode,
    String query,
    ConditionRunStatus status,
    int attempt,
    Instant startedAt,
    Instant updatedAt,
    Instant finishedAt,
    String errorCode,
    String errorMessage,
    Integer pickCount,
    Integer agentsUsed,
    Integer agentsSucceeded
) {}
