package com.bitman.justbuy.dto.condition;

public record TrackRecordSummary(
    int totalSignals,
    String avgMaxReturnPct,
    String winRate5d
) {}
