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
    List<ConditionSignalDto> signals,
    LatestAnalysis latestAnalysis
) {
    public ConditionSectionResponse(String key, String slug, String title, String mode, String endpoint,
                                    String asOf, String sourceStatus, List<ConditionSignalDto> signals) {
        this(key, slug, title, mode, endpoint, asOf, sourceStatus, signals, null);
    }

    public record LatestAnalysis(String checkedAt, String outcome, int agentsUsed,
                                 int agentsSucceeded, int publishedCount) {}
}
