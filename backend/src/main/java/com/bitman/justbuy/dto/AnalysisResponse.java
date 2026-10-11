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
    public record Metadata(long totalDurationMs, int agentsUsed, int agentsSucceeded, String publicationStatus,
                           java.util.Map<String,Object> sourceExpansion) {
        public Metadata(long totalDurationMs,int agentsUsed,int agentsSucceeded,String publicationStatus) {
            this(totalDurationMs,agentsUsed,agentsSucceeded,publicationStatus,null);
        }
        public Metadata(long totalDurationMs, int agentsUsed, int agentsSucceeded) {
            this(totalDurationMs, agentsUsed, agentsSucceeded, null, null);
        }
    }
}
