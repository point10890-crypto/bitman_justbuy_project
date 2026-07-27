package com.bitman.justbuy.dto.performance;

import java.util.List;

/**
 * 종가매매 추천종목 히스토리 + 익일 성과.
 *
 * <p>목록은 아카이브에서, 성과는 추적 DB에서 온다. 추적 이전 날짜는 목록만 채워지고
 * {@code result} 가 "미검증" 으로 표시된다.
 */
public record JonggaPerformanceResponse(
    String from,
    String to,
    String mode,
    String title,
    int totalSignals,
    int verifiedCount,
    int wins,
    int losses,
    int flats,
    String avgCloseReturnPct,
    String avgMaxReturnPct,
    String winRate,
    String targetHitRate,
    String stopHitRate,
    List<DayGroup> days,
    String note
) {

    /** 추천일 1일치. */
    public record DayGroup(
        String date,
        boolean verified,
        String avgCloseReturnPct,
        List<PerformanceRow> rows
    ) {}

    public record PerformanceRow(
        int rank,
        String stockName,
        String stockCode,
        String grade,
        int score,
        String entryPrice,
        String targetPrice,
        String stopLoss,
        String closePrice,
        String closeReturnPct,
        String maxReturnPct,
        boolean hitTarget,
        boolean hitStop,
        String result
    ) {}
}
