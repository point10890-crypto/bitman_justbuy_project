package com.bitman.justbuy.dto;

public record StockPick(
        String name,
        String code,
        String currentPrice,
        String targetPrice,
        String stopLoss,
        String action,
        String reason,
        Integer financialScore,
        String financialSummary,
        String candidateSource,
        String quoteSource,
        String quoteCheckedAt
) {
    public StockPick(String name, String code, String currentPrice, String targetPrice,
                     String stopLoss, String action, String reason, Integer financialScore, String financialSummary) {
        this(name, code, currentPrice, targetPrice, stopLoss, action, reason, financialScore, financialSummary,
            null, null, null);
    }
    /** 하위 호환 생성자 — 기존 7-인자 호출 유지 (financialScore=0, summary="") */
    public StockPick(String name, String code, String currentPrice, String targetPrice,
                     String stopLoss, String action, String reason) {
        this(name, code, currentPrice, targetPrice, stopLoss, action, reason, 0, "");
    }
}
