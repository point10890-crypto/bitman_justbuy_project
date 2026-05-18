package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermRealtimeScannerTest {

    @Test
    void buildSignalsRanksStocksThatOverlapVolumeAndGainerLists() {
        ShortTermRealtimeScanner scanner = new ShortTermRealtimeScanner(null);
        ZonedDateTime now = ZonedDateTime.of(2026, 5, 18, 9, 10, 0, 0, ZoneId.of("Asia/Seoul"));

        List<KisApiService.RankedStock> volume = List.of(
            stock("삼성전자", "005930", "78100", "1.20", "900000"),
            stock("로보티즈", "108490", "31200", "7.50", "2500000"),
            stock("한미반도체", "042700", "139000", "3.10", "1200000")
        );
        List<KisApiService.RankedStock> gainers = List.of(
            stock("로보티즈", "108490", "31200", "7.50", "2500000"),
            stock("디아이", "003160", "7120", "5.53", "1800000"),
            stock("한미반도체", "042700", "139000", "3.10", "1200000")
        );

        List<ConditionSignalDto> signals = scanner.buildSignals(volume, gainers, now);

        assertThat(signals).hasSize(3);
        assertThat(signals.getFirst().stockName()).isEqualTo("로보티즈");
        assertThat(signals.getFirst().status()).isEqualTo("실시간 포착");
        assertThat(signals.getFirst().section()).isEqualTo("shortTerm");
        assertThat(signals.getFirst().mode()).isEqualTo("BREAKOUT");
        assertThat(signals.getFirst().capturedAt()).startsWith("2026-05-18T09:10:00");
        assertThat(signals.getFirst().evidence()).contains("KIS 정규장 실시간 스캔");
    }

    @Test
    void buildSignalsExcludesEtfEtfLikeProductsFromShortTermPicks() {
        ShortTermRealtimeScanner scanner = new ShortTermRealtimeScanner(null);
        ZonedDateTime now = ZonedDateTime.of(2026, 5, 18, 9, 20, 0, 0, ZoneId.of("Asia/Seoul"));

        List<KisApiService.RankedStock> volume = List.of(
            stock("KODEX 200선물인버스2X", "252670", "120", "1.69", "9000000"),
            stock("KODEX 인버스", "114800", "1146", "0.97", "8000000"),
            stock("TIGER 200선물인버스2X", "252710", "127", "1.60", "7000000"),
            stock("로보티즈", "108490", "31200", "7.50", "2500000"),
            stock("디아이", "003160", "7120", "5.53", "1800000"),
            stock("한미반도체", "042700", "139000", "3.10", "1200000")
        );
        List<KisApiService.RankedStock> gainers = List.of(
            stock("KODEX 200선물인버스2X", "252670", "120", "1.69", "9000000"),
            stock("로보티즈", "108490", "31200", "7.50", "2500000"),
            stock("디아이", "003160", "7120", "5.53", "1800000"),
            stock("한미반도체", "042700", "139000", "3.10", "1200000")
        );

        List<ConditionSignalDto> signals = scanner.buildSignals(volume, gainers, now);

        assertThat(signals).extracting(ConditionSignalDto::stockName)
            .containsExactly("로보티즈", "디아이", "한미반도체");
    }

    private static KisApiService.RankedStock stock(String name, String code, String price, String change, String volume) {
        return new KisApiService.RankedStock(name, code, price, change, volume, 0);
    }
}
