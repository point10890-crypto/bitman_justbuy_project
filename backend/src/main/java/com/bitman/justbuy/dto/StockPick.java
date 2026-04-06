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
        String financialSummary
) {
    /** 하위 호환 생성자 — 기존 7-인자 호출 유지 (financialScore=0, summary="") */
    public StockPick(String name, String code, String currentPrice, String targetPrice,
                     String stopLoss, String action, String reason) {
        this(name, code, currentPrice, targetPrice, stopLoss, action, reason, 0, "");
    }
}
