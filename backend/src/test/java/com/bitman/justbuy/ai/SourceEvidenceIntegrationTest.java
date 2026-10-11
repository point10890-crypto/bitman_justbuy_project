package com.bitman.justbuy.ai;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.dto.*;
import com.bitman.justbuy.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceEvidenceIntegrationTest {
    private final MarketDataService market = mock(MarketDataService.class);
    private final DartApiService dart = mock(DartApiService.class);
    private final KisExtendedEvidenceService kisEvidence = mock(KisExtendedEvidenceService.class);
    private final AtomicReference<String> receivedPrompt = new AtomicReference<>();

    private AnalysisResponse run(String aiCode, String query) {
        var scorer = mock(FinancialScorer.class);
        when(scorer.score(anyString())).thenReturn(FinancialScore.empty());
        var http = mock(RestTemplate.class);
        when(http.getForEntity(anyString(), eq(String.class))).thenReturn(ResponseEntity.ok("{\"gate\":\"GREEN\"}"));
        String fence = String.valueOf((char) 96).repeat(3);
        String content = fence + "json:analysis\n{\"stocks\":[{\"name\":\"fixture\",\"code\":\"" + aiCode
            + "\",\"confidence\":0.8,\"action\":\"관망\",\"reasons\":[\"확인한 입력\"]}]}\n" + fence;
        AiAgent agent = new AiAgent() {
            public String name() { return "chatgpt"; }
            public boolean isAvailable() { return true; }
            public AgentResult analyze(String system, String user) {
                receivedPrompt.set(user);
                return new AgentResult(name(), content, "fixture", 1, 2, "success", null, 0);
            }
        };
        var orchestrator = new MultiAgentOrchestrator(List.of(agent), market, new ConsensusEngine(), dart, scorer,
            mock(KisApiService.class), mock(TrackRecordService.class), http, new ObjectMapper());
        orchestrator.configureSourceEvidence(kisEvidence);
        return orchestrator.runAnalysis(query, "BREAKOUT");
    }

    @Test void boundedCandidateFactsReachTheAgentWithQueryIdentityFirst() {
        when(market.fetchFormattedMarketData()).thenReturn(
            "후보1(111111): 10,000원\n후보2(222222): 10,000원\n후보3(333333): 10,000원\n"
            + "후보4(444444): 10,000원\n후보5(555555): 10,000원\n후보6(666666): 10,000원\n");
        when(market.fetchStockPrices(anyList())).thenReturn(Map.of("005930", "70,000"));
        when(dart.fetchFormattedEventEvidence(anyList())).thenReturn("VERIFIED_DART_EVENT_FACT");
        when(kisEvidence.fetchFormattedExtendedEvidence(anyList())).thenReturn("VERIFIED_KIS_MINUTE_PROGRAM_FACT");
        var result = run("005930", "삼성전자 분석");
        assertThat(receivedPrompt.get()).contains("VERIFIED_DART_EVENT_FACT", "VERIFIED_KIS_MINUTE_PROGRAM_FACT");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> codes = ArgumentCaptor.forClass(List.class);
        verify(kisEvidence).fetchFormattedExtendedEvidence(codes.capture());
        assertThat(codes.getValue()).hasSize(5).startsWith("005930").doesNotHaveDuplicates();
        verify(dart).fetchFormattedEventEvidence(codes.getValue());
        assertThat(result.stockPicks()).extracting(StockPick::code).containsExactly("005930");
    }

    @Test void oneUnavailableSourceDoesNotPreventTheOtherSourceOrQuoteGate() {
        when(market.fetchFormattedMarketData()).thenReturn("삼성전자(005930): 70,000원");
        when(market.fetchStockPrices(anyList())).thenReturn(Map.of("005930", "70,000"));
        when(kisEvidence.fetchFormattedExtendedEvidence(anyList())).thenThrow(new IllegalStateException("fixture unavailable"));
        when(dart.fetchFormattedEventEvidence(anyList())).thenReturn("VERIFIED_DART_EVENT_FACT");
        assertThat(run("005930", "시장 분석").stockPicks()).hasSize(1);
        assertThat(receivedPrompt.get()).contains("VERIFIED_DART_EVENT_FACT", "KIS 확장 증거 확인 불가");
    }

    @Test void evidenceTextCannotCreateANewCandidateEvenIfItIncludesAQuotedCode() {
        when(market.fetchFormattedMarketData()).thenReturn("삼성전자(005930): 70,000원");
        when(market.fetchStockPrices(anyList())).thenReturn(Map.of("999999", "90,000"));
        when(kisEvidence.fetchFormattedExtendedEvidence(anyList())).thenReturn("허구(999999): 90,000원");
        when(dart.fetchFormattedEventEvidence(anyList())).thenReturn("허구(999999): 90,000원");
        var result = run("999999", "시장 분석");
        assertThat(result.stockPicks()).isEmpty();
        assertThat(result.finalContent()).doesNotContain("999999");
    }

    @Test void noDeterministicCandidatesSkipBothExpansionAdapters() {
        when(market.fetchFormattedMarketData()).thenReturn("후보 확인 불가");
        when(market.fetchStockPrices(anyList())).thenReturn(Map.of());
        assertThat(run("999999", "시장 분석").stockPicks()).isEmpty();
        verifyNoInteractions(kisEvidence);
        verify(dart, never()).fetchFormattedEventEvidence(anyList());
    }
}
