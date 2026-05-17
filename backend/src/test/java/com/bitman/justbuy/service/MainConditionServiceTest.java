package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MainConditionServiceTest {

    @Mock ConditionSearchPipeline conditionSearchPipeline;

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
            name + " 조건검색 포착"
        );
    }
}
