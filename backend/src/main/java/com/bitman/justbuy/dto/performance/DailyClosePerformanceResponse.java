package com.bitman.justbuy.dto.performance;

import java.util.List;

public record DailyClosePerformanceResponse(
    String date,
    String mode,
    String title,
    boolean marketClosed,
    boolean verified,
    String asOf,
    int totalSignals,
    int winCount,
    int lossCount,
    int flatCount,
    String avgReturnPct,
    String bestReturnPct,
    String worstReturnPct,
    List<DailyCloseRow> rows,
    String note
) {
    public record DailyCloseRow(
        int rank,
        String stockName,
        String stockCode,
        String action,
        String entryPrice,
        String closePrice,
        String returnPct,
        String result,
        String targetPrice,
        String stopLoss,
        boolean hitTarget,
        boolean hitStop,
        String capturedAt,
        String verifiedAt
    ) {}
}
