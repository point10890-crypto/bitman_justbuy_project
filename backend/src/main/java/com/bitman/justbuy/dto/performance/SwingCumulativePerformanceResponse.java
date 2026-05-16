package com.bitman.justbuy.dto.performance;

import java.util.List;

public record SwingCumulativePerformanceResponse(
    String from,
    String to,
    String mode,
    String title,
    int totalSignals,
    int trackingCount,
    int completedCount,
    String avgReturn1d,
    String avgReturn3d,
    String avgReturn5d,
    String winRate1d,
    String winRate3d,
    String winRate5d,
    String targetHitRate,
    String stopHitRate,
    List<SwingRow> rows,
    String note
) {
    public record SwingRow(
        int rank,
        String stockName,
        String stockCode,
        String action,
        String capturedDate,
        String entryPrice,
        String currentStatus,
        String return1d,
        String return3d,
        String return5d,
        String targetPrice,
        String stopLoss,
        boolean hitTarget,
        boolean hitStop,
        String capturedAt
    ) {}
}
