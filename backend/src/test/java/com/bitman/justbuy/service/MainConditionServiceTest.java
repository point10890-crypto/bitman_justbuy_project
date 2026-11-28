package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionCheck;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MainConditionServiceTest {

    @Mock ConditionSearchPipeline conditionSearchPipeline;
    @Mock ShortTermRealtimeScanner shortTermRealtimeScanner;
    @Mock JonggaV2SearchService jonggaV2SearchService;

    @Test
    void mainAlertsAreDerivedFromActualConditionSignals() {
        when(conditionSearchPipeline.getPrecomputed("BREAKOUT"))
            .thenReturn(response("BREAKOUT", stock("삼성전자", "005930", "78,100", "82,000", "주목")));
        when(conditionSearchPipeline.getPrecomputed("REVERSAL_EDGE"))
            .thenReturn(response("REVERSAL_EDGE"));
        when(conditionSearchPipeline.getPrecomputed("FLOW_LEADER"))
            .thenReturn(response("FLOW_LEADER", stock("한미반도체", "042700", "139,000", "145,000", "주목")));
        when(conditionSearchPipeline.getPrecomputed("CATALYST_BURST"))
            .thenReturn(response("CATALYST_BURST", stock("로보티즈", "108490", "31,200", "34,350", "관찰")));

        MainConditionService service = new MainConditionService(conditionSearchPipeline);

        List<ConditionSignalDto> alerts = service.getMain().sections().alerts().signals();

        assertThat(alerts).hasSize(3);
        assertThat(alerts).extracting(ConditionSignalDto::stockName)
            .containsExactly("삼성전자", "한미반도체", "로보티즈");
        assertThat(alerts).extracting(ConditionSignalDto::capturePrice)
            .containsExactly("단타 포착", "주도주 포착", "테마주 포착");
        assertThat(alerts).extracting(ConditionSignalDto::highPrice)
            .containsOnly("새 알림");
        assertThat(alerts).extracting(ConditionSignalDto::maxReturnPct)
            .containsOnly("앱");
    }

    @Test
    void shortTermSectionUsesFreshRealtimeScanBeforeStoredCache() {
        ConditionSignalDto liveSignal = new ConditionSignalDto(
            "shortTerm",
            "BREAKOUT",
            1,
            "로보티즈",
            "108490",
            "31,200",
            "31,200",
            "+7.50%",
            "",
            "+7.50%",
            "실시간 포착",
            94,
            0,
            94,
            "KIS 실시간 랭킹 기반 단타 포착",
            List.of("KIS 정규장 실시간 스캔"),
            List.of("단타 변동성 확대"),
            "거래량 순위 이탈",
            "2026-05-18T09:10:00+09:00"
        ).withChecks(ConditionCheck.withDailyChange("BREAKOUT", 7.5, "KIS OpenAPI", "2026-05-18T09:10:00+09:00"));
        when(shortTermRealtimeScanner.latestFresh())
            .thenReturn(Optional.of(new ShortTermRealtimeScanner.ScanSnapshot(
                "2026-05-18T09:10:00+09:00",
                java.time.Instant.parse("2026-05-18T00:10:00Z"),
                "REALTIME_SCAN",
                true,
                List.of(liveSignal)
            )));

        MainConditionService service = new MainConditionService(conditionSearchPipeline, shortTermRealtimeScanner);

        var section = service.getSection("short-term");

        assertThat(section.sourceStatus()).isEqualTo("REALTIME_SCAN");
        assertThat(section.signals()).containsExactly(liveSignal);
        assertThat(service.getMain().sections().alerts().signals()).singleElement()
            .satisfies(alert -> assertThat(alert.summary()).contains("KIS OpenAPI 당일 등락률 +7.50%"));
    }

    @Test
    void alertsDoNotPresentTargetDistanceAsAnObservedReturn() {
        when(conditionSearchPipeline.getPrecomputed("BREAKOUT"))
            .thenReturn(response("BREAKOUT", stock("삼성전자", "005930", "78,100", "82,000", "주목")));

        MainConditionService service = new MainConditionService(conditionSearchPipeline);

        assertThat(service.getMain().sections().alerts().signals()).singleElement()
            .satisfies(alert -> assertThat(alert.summary()).doesNotContain("기대/포착 수익률"));
    }

    @Test
    void shortTermPrecomputedSectionExcludesEtfLikeProducts() {
        when(conditionSearchPipeline.getPrecomputed("BREAKOUT"))
            .thenReturn(response("BREAKOUT",
                stock("KODEX 200선물인버스2X", "252670", "120", "125", "주목"),
                stock("KODEX 인버스", "114800", "1,146", "1,160", "주목"),
                stock("TIGER 200선물인버스2X", "252710", "127", "130", "주목"),
                stock("로보티즈", "108490", "31,200", "34,350", "주목"),
                stock("디아이", "003160", "7,120", "7,510", "주목"),
                stock("한미반도체", "042700", "139,000", "145,300", "주목")
            ));

        MainConditionService service = new MainConditionService(conditionSearchPipeline);

        var section = service.getSection("short-term");

        assertThat(section.signals()).extracting(ConditionSignalDto::stockName)
            .containsExactly("로보티즈", "디아이", "한미반도체");
    }

    @Test
    void captureTimeEndpointReturnsPerSignalCaptureTimes() {
        when(conditionSearchPipeline.getPrecomputed("BREAKOUT"))
            .thenReturn(response("BREAKOUT",
                stock("Robotis", "108490", "31,200", "34,350", "watch"),
                stock("DI", "003160", "7,120", "7,510", "watch")
            ));

        MainConditionService service = new MainConditionService(conditionSearchPipeline);

        var response = service.getCaptureTimes("short-term");

        assertThat(response.endpoint()).isEqualTo("/api/conditions/short-term/capture-times");
        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.captures()).extracting(capture -> capture.stockCode())
            .containsExactly("108490", "003160");
        assertThat(response.captures()).extracting(capture -> capture.capturedAt())
            .containsOnly("2026-05-17T09:10:00+09:00");
        assertThat(response.captures()).extracting(capture -> capture.capturedTime())
            .containsOnly("\uC624\uC804 09:10");
    }

    @Test
    void closingSectionUsesArchiveUpdateButOnlyActualRowCaptureTimestamp() {
        var archive = new ObjectMapper().createObjectNode()
            .put("date", "2026-03-03").put("updated_at", "2026-03-03T08:17:25.139040Z");
        var signals = archive.putArray("signals");
        signals.addObject().put("stock_code", "005930").put("signal_date", "2026-03-03")
            .put("captured_at", "2026-03-03T15:17:00+09:00");
        signals.addObject().put("stock_code", "000660").put("signal_date", "20260303").put("change_pct", 7.5);
        when(jonggaV2SearchService.latest()).thenReturn(archive);
        var service = new MainConditionService(conditionSearchPipeline, shortTermRealtimeScanner, jonggaV2SearchService, null);

        var section = service.getSection("closing-bet");

        assertThat(section.sourceStatus()).isEqualTo("PRECOMPUTED");
        assertThat(section.asOf()).isEqualTo("2026-03-03T17:17:25.13904+09:00");
        assertThat(section.signals()).extracting(ConditionSignalDto::capturedAt)
            .containsExactly("2026-03-03T15:17:00+09:00", "2026-03-03");
        assertThat(section.signals().get(1).conditionChecks()).anySatisfy(check -> {
            assertThat(check.id()).isEqualTo("technical.3");
            assertThat(check.status()).isEqualTo("PASS");
            assertThat(check.observedAt()).isEqualTo("2026-03-03");
        });
        assertThat(service.getCaptureTimes("closing-bet").captures().get(1).capturedTime()).isEmpty();
    }

    @Test
    void closingArchiveKeepsSessionDateWhenTimestampsDisagreeAndOmitsWrongSessionRows() {
        var archive = new ObjectMapper().createObjectNode()
            .put("date", "2026-03-03").put("updated_at", "2026-03-04T01:12:00+09:00");
        var signals = archive.putArray("signals");
        signals.addObject().put("stock_code", "005930").put("signal_date", "2026-03-02");
        signals.addObject().put("stock_code", "000660").put("captured_at", "2999-03-03T13:17:00+09:00");
        when(jonggaV2SearchService.latest()).thenReturn(archive);
        var service = new MainConditionService(conditionSearchPipeline, shortTermRealtimeScanner, jonggaV2SearchService, null);

        var section = service.getSection("closing-bet");

        assertThat(section.asOf()).isEqualTo("2026-03-03");
        assertThat(section.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.stockCode()).isEqualTo("000660");
            assertThat(signal.rank()).isOne();
            assertThat(signal.capturedAt()).isEqualTo("2026-03-03");
        });
    }

    @Test
    void futureClosingArchiveDoesNotPublishRowsOrCurrentTime() {
        var archive = new ObjectMapper().createObjectNode().put("date", "2999-03-03");
        archive.putArray("signals").addObject().put("stock_code", "005930");
        when(jonggaV2SearchService.latest()).thenReturn(archive);
        var service = new MainConditionService(conditionSearchPipeline, shortTermRealtimeScanner, jonggaV2SearchService, null);

        var section = service.getSection("closing-bet");

        assertThat(section.asOf()).isEmpty();
        assertThat(section.sourceStatus()).isEqualTo("DATA_UNAVAILABLE");
        assertThat(section.signals()).isEmpty();
    }

    @Test
    void swingLeaderAndThemeSectionsExcludeExchangeTradedProducts() {
        when(conditionSearchPipeline.getPrecomputed("REVERSAL_EDGE"))
            .thenReturn(response("REVERSAL_EDGE",
                stock("KODEX 200선물인버스2X", "252670", "120", "125", "주목"),
                stock("삼성전자", "005930", "78,100", "82,000", "주목")
            ));
        when(conditionSearchPipeline.getPrecomputed("FLOW_LEADER"))
            .thenReturn(response("FLOW_LEADER",
                stock("신한 레버리지 WTI원유 선물 ETN", "500019", "1,200", "1,260", "주목"),
                stock("로보티즈", "108490", "31,200", "34,350", "주목")
            ));
        when(conditionSearchPipeline.getPrecomputed("CATALYST_BURST"))
            .thenReturn(response("CATALYST_BURST",
                stock("삼성스팩9호", "468510", "2,100", "2,300", "주목"),
                stock("한미반도체", "042700", "139,000", "145,300", "주목")
            ));

        MainConditionService service = new MainConditionService(conditionSearchPipeline);

        assertThat(service.getSection("swing").signals()).extracting(ConditionSignalDto::stockName)
            .containsExactly("삼성전자");
        assertThat(service.getSection("leaders").signals()).extracting(ConditionSignalDto::stockName)
            .containsExactly("로보티즈");
        assertThat(service.getSection("themes").signals()).extracting(ConditionSignalDto::stockName)
            .containsExactly("한미반도체");
    }

    @Test
    void alertsDoNotIncludeExchangeTradedProductsFromAnySection() {
        when(conditionSearchPipeline.getPrecomputed("BREAKOUT"))
            .thenReturn(response("BREAKOUT"));
        when(conditionSearchPipeline.getPrecomputed("REVERSAL_EDGE"))
            .thenReturn(response("REVERSAL_EDGE",
                stock("KODEX 200선물인버스2X", "252670", "120", "125", "주목"),
                stock("삼성전자", "005930", "78,100", "82,000", "주목")
            ));
        when(conditionSearchPipeline.getPrecomputed("FLOW_LEADER"))
            .thenReturn(response("FLOW_LEADER"));
        when(conditionSearchPipeline.getPrecomputed("CATALYST_BURST"))
            .thenReturn(response("CATALYST_BURST"));

        MainConditionService service = new MainConditionService(conditionSearchPipeline);

        assertThat(service.getMain().sections().alerts().signals())
            .extracting(ConditionSignalDto::stockName)
            .containsExactly("삼성전자");
    }

    @Test
    void swingSectionLeavesAbsentRiskPricesUnavailableInsteadOfParsingUnverifiedProse() {
        AnalysisResponse cached = new AnalysisResponse(
            "REVERSAL_EDGE",
            "REVERSAL_EDGE query",
            List.of(),
            null,
            "Robotis\n1\uCC28 \uBAA9\uD45C: 34,350\uC6D0\n\uC190\uC808\uAC00: 29,000\uC6D0",
            List.of(new StockPick("Robotis", "108490", "31,200", null, null, "watch", "swing setup",
                0, "", "KIS", "KIS", "2026-05-17T00:10:00Z")),
            null,
            "2026-05-17T09:10:00+09:00",
            true,
            new AnalysisResponse.Metadata(100, 1, 1)
        );
        when(conditionSearchPipeline.getPrecomputed("REVERSAL_EDGE")).thenReturn(cached);

        MainConditionService service = new MainConditionService(conditionSearchPipeline);

        ConditionSignalDto signal = service.getSection("swing").signals().get(0);
        assertThat(signal.highPrice()).isEqualTo("-");
        assertThat(signal.stopLoss()).isEqualTo("-");
    }

    @Test
    void swingSectionLeavesMissingOrInvalidRiskPricesUnavailable() {
        when(conditionSearchPipeline.getPrecomputed("REVERSAL_EDGE"))
            .thenReturn(response("REVERSAL_EDGE",
                new StockPick("LG Display", "034220", "16,690", "16,690", null, "watch", "swing setup",
                    0, "", "KIS", "KIS", "2026-05-17T00:10:00Z")
            ));

        MainConditionService service = new MainConditionService(conditionSearchPipeline);

        ConditionSignalDto signal = service.getSection("swing").signals().get(0);
        assertThat(signal.highPrice()).isEqualTo("-");
        assertThat(signal.stopLoss()).isEqualTo("-");
        assertThat(signal.highPrice()).isNotEqualTo(signal.capturePrice());
    }

    private static AnalysisResponse response(String mode, StockPick... picks) {
        return new AnalysisResponse(
            mode,
            mode + " query",
            List.of(),
            null,
            mode + " content",
            List.of(picks),
            null,
            "2026-05-17T09:10:00+09:00",
            true,
            new AnalysisResponse.Metadata(100, 1, 1)
        );
    }

    private static StockPick stock(String name, String code, String currentPrice, String targetPrice, String action) {
        return new StockPick(
            name,
            code,
            currentPrice,
            targetPrice,
            null,
            action,
            name + " 조건검색 포착",
            0, "", "KIS", "KIS", "2026-05-17T00:10:00Z"
        );
    }
}
