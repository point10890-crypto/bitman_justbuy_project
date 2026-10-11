package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import java.time.Duration;
import java.time.Instant;

/** Publication outcome shared by live jobs, periodic runs and stored metadata. */
public enum AnalysisOutcome {
    COMPLETE, PARTIAL, EMPTY, FAILED;

    public static AnalysisOutcome classify(AnalysisResponse response) {
        var metadata = response != null ? response.metadata() : null;
        if (metadata == null || metadata.agentsUsed() <= 0 || metadata.agentsSucceeded() <= 0) return FAILED;
        if (response.stockPicks() == null || response.stockPicks().isEmpty()) return EMPTY;
        return metadata.agentsSucceeded() < metadata.agentsUsed() ? PARTIAL : COMPLETE;
    }

    public static AnalysisResponse describe(AnalysisResponse response) {
        if (response == null) throw new IllegalStateException("Analysis returned no response");
        var outcome = classify(response);
        var metadata = response.metadata();
        return new AnalysisResponse(response.mode(), response.query(), response.round1(), response.synthesis(),
            response.finalContent(), response.stockPicks(), response.consensus(), response.updatedAt(),
            response.isFresh() && outcome != EMPTY && outcome != FAILED,
            new AnalysisResponse.Metadata(metadata != null ? metadata.totalDurationMs() : 0,
                metadata != null ? metadata.agentsUsed() : 0, metadata != null ? metadata.agentsSucceeded() : 0,
                outcome.name()));
    }

    public static void requireSuccessfulAgents(AnalysisResponse response) {
        if (classify(response) == FAILED) throw new IllegalStateException("Analysis failed: no successful agents");
    }

    /** Verified source identity plus a real positive quote; stale history may pass an unlimited age. */
    public static boolean isVerifiedPick(StockPick pick, Instant now, long ttlMinutes) {
        if (pick == null || now == null || pick.code() == null || !pick.code().matches("[0-9]{6}")
            || pick.name() == null || pick.name().isBlank() || pick.candidateSource() == null || pick.candidateSource().isBlank()
            || pick.quoteSource() == null || pick.quoteSource().isBlank() || pick.quoteCheckedAt() == null) return false;
        String price = pick.currentPrice() != null ? pick.currentPrice().trim() : "";
        if (!price.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)\\s*원?")) return false;
        try {
            if (Long.parseLong(price.replace(",", "").replace("원", "").trim()) <= 0) return false;
            Instant quoteTime = Instant.parse(pick.quoteCheckedAt());
            Duration age = Duration.between(quoteTime, now);
            return !age.isNegative() && age.toMinutes() <= ttlMinutes;
        } catch (RuntimeException e) { return false; }
    }
}
