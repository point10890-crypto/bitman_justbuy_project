package com.bitman.justbuy.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockUniverseFiltersTest {

    @Test
    void rejectsExchangeTradedAndDerivativeProducts() {
        assertThat(StockUniverseFilters.isKoreanSpotEquity("KODEX 200선물인버스2X", "252670")).isFalse();
        assertThat(StockUniverseFilters.isKoreanSpotEquity("KODEX 인버스", "114800")).isFalse();
        assertThat(StockUniverseFilters.isKoreanSpotEquity("TIGER 미국나스닥100", "133690")).isFalse();
        assertThat(StockUniverseFilters.isKoreanSpotEquity("KBSTAR 국고채", "114260")).isFalse();
        assertThat(StockUniverseFilters.isKoreanSpotEquity("신한 레버리지 WTI원유 선물 ETN", "500019")).isFalse();
        assertThat(StockUniverseFilters.isKoreanSpotEquity("삼성스팩9호", "468510")).isFalse();
    }

    @Test
    void acceptsOrdinaryKoreanEquities() {
        assertThat(StockUniverseFilters.isKoreanSpotEquity("삼성전자", "005930")).isTrue();
        assertThat(StockUniverseFilters.isKoreanSpotEquity("로보티즈", "108490")).isTrue();
        assertThat(StockUniverseFilters.isKoreanSpotEquity("한미반도체", "042700")).isTrue();
    }
}
