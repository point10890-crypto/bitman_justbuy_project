package com.bitman.justbuy.dto;

import java.util.List;

public record AnalysisResponse(
        String mode,
        String query,
        List<AgentResult> round1,
        AgentResult synthesis,
        String finalContent,
        List<StockPick> stockPicks,
        ConsensusResult consensus,
        String updatedAt,
        boolean isFresh,
        Metadata metadata
) {
    public record Metadata(long totalDurationMs, int agentsUsed, int agentsSucceeded) {}
}
