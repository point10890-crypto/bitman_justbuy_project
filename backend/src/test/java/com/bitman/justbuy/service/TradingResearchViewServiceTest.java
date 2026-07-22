package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.ConsensusResult;
import com.bitman.justbuy.dto.StockPick;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradingResearchViewServiceTest {

    private final TradingResearchViewService service = new TradingResearchViewService();

    @Test
    void buildsInformationalResearchWithoutExecutionPath() {
        var response = new AnalysisResponse(
            "BREAKOUT",
            "verified condition query",
            List.of(new AgentResult("market", "market evidence", "model", 10, 5, "success", null, 100)),
            new AgentResult("synthesis", "balanced synthesis", "model", 10, 5, "success", null, 100),
            "final evidence-backed assessment",
            List.of(new StockPick("Samsung", "005930", "70000", "75000", "67000", "buy", "volume breakout")),
            new ConsensusResult(List.of(), "bullish", 80, List.of(), 2),
            "2026-07-22T01:00:00Z",
            true,
            new AnalysisResponse.Metadata(200, 2, 2)
        );

        var result = service.build("job-1", response);

        assertThat(result.informationalRating()).isEqualTo("STRONG_INTEREST");
        assertThat(result.executionAllowed()).isFalse();
        assertThat(result.analystReports()).hasSize(2);
        assertThat(result.researchDebate().bullCase()).hasSize(1);
        assertThat(result.riskReview().invalidationConditions()).containsExactly(
            "Samsung(005930) stop reference: 67000"
        );
    }

    @Test
    void staleOrEmptyEvidenceFallsBackToWatchAndHighRisk() {
        var response = new AnalysisResponse(
            "BREAKOUT", "query", List.of(), null, "", List.of(), null,
            "2026-07-22T01:00:00Z", false, new AnalysisResponse.Metadata(0, 2, 0)
        );

        var result = service.build("job-2", response);

        assertThat(result.informationalRating()).isEqualTo("WATCH");
        assertThat(result.riskReview().level()).isEqualTo("HIGH");
        assertThat(result.riskReview().factors())
            .contains("STALE_SOURCE_DATA", "PARTIAL_AGENT_FAILURE", "CONSENSUS_UNAVAILABLE", "NO_VERIFIED_CANDIDATE");
    }
}
