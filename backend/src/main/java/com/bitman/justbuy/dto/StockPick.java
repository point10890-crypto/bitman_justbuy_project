package com.bitman.justbuy.dto;

public record StockPick(
        String name,
        String code,
        String currentPrice,
        String targetPrice,
        String stopLoss,
        String action,
        String reason
) {}
