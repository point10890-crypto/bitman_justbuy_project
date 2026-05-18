package com.bitman.justbuy.util;

import java.util.List;
import java.util.Locale;

public final class StockUniverseFilters {

    private static final List<String> EXCHANGE_TRADED_PREFIXES = List.of(
        "KODEX",
        "TIGER",
        "ACE",
        "KBSTAR",
        "RISE",
        "ARIRANG",
        "HANARO",
        "SOL",
        "KOSEF",
        "TIMEFOLIO",
        "TREX",
        "WOORI",
        "PLUS",
        "히어로즈",
        "마이티"
    );

    private static final List<String> PRODUCT_KEYWORDS = List.of(
        "ETF",
        "ETN",
        "인버스",
        "레버리지",
        "선물",
        "합성",
        "커버드콜",
        "액티브",
        "채권",
        "국고채",
        "국채",
        "회사채",
        "통안채",
        "원유",
        "금현물",
        "금선물",
        "은선물",
        "구리",
        "달러",
        "나스닥",
        "NASDAQ",
        "S&P",
        "다우",
        "NIKKEI",
        "차이나",
        "미국",
        "일본",
        "중국",
        "인도",
        "베트남",
        "스팩",
        "SPAC"
    );

    private StockUniverseFilters() {
    }

    public static boolean isKoreanSpotEquity(String name, String code) {
        return code != null
            && code.matches("\\d{6}")
            && name != null
            && !name.isBlank()
            && !isExchangeTradedProductName(name);
    }

    public static boolean isExchangeTradedProductName(String name) {
        if (name == null || name.isBlank()) return false;
        String normalized = name.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");

        for (String prefix : EXCHANGE_TRADED_PREFIXES) {
            if (normalized.startsWith(prefix.toUpperCase(Locale.ROOT))) return true;
        }
        for (String keyword : PRODUCT_KEYWORDS) {
            if (normalized.contains(keyword.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
