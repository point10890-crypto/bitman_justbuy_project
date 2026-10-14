package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.MultiAgentOrchestrator;
import com.bitman.justbuy.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisLiquidityTest {
    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    @TempDir Path data;
    @Test void liveAndSavedResultsCannotPublishRejectedLiquidityInAnyRepresentation() throws Exception {
        var orchestrator = mock(MultiAgentOrchestrator.class);
        var response = response();
        when(orchestrator.runAnalysis(anyString(), anyString())).thenReturn(response);
        var service = new AnalysisService(orchestrator, new ConditionSearchFormulaCatalog(), null, new ObjectMapper(), data.toString(),
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        service.configureLiquidity(new ConditionLiquidityService(mock(KisApiService.class)));
        var live = service.runAnalysisForPublication("query", "REVERSAL_EDGE");
        assertEmpty(live);
        new ObjectMapper().writeValue(data.resolve("REVERSAL_EDGE.json").toFile(), response);
        assertEmpty(service.getStoredPrecomputed("REVERSAL_EDGE"));
        assertEmpty(service.getPrecomputed("REVERSAL_EDGE"));
    }
    private void assertEmpty(AnalysisResponse response) {
        assertThat(response.stockPicks()).isEmpty();
        assertThat(response.finalContent()).doesNotContain("005930");
        assertThat(response.round1()).allSatisfy(agent -> assertThat(agent.content()).doesNotContain("005930"));
    }
    private AnalysisResponse response() {
        return new AnalysisResponse("REVERSAL_EDGE", "query", List.of(new AgentResult("chatgpt",
            "📌 삼성전자 (005930)\n근거: 후보", "fixture", 0, 0, "success", null, 0)), null, "005930",
            List.of(new StockPick("삼성전자", "005930", "100000", "110000", "90000", "주목", "측정", 0, "",
                "KIS", "KIS", NOW.toString())), null, NOW.toString(), true,
            new AnalysisResponse.Metadata(1, 1, 1));
    }
}
