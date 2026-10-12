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
    private FinancialScorer suppliedScorer;

    private AnalysisResponse run(String aiCode, String query) {
        var scorer = mock(FinancialScorer.class);
        suppliedScorer=scorer;
        when(scorer.score(anyString())).thenReturn(FinancialScore.empty());
        when(scorer.scoreFromEvidence(any())).thenReturn(new FinancialScore(55,"기간이 확인된 실측 재무",Map.of()));
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

    @Test void scheduledCandidatesReceiveOneFinancialBatchBeforeAiAndCardsReuseTheSameFacts() {
        when(market.fetchFormattedMarketData()).thenReturn("삼성전자(005930): 70,000원\n후보2(222222): 10,000원\n후보3(333333): 10,000원\n후보4(444444): 10,000원\n후보5(555555): 10,000원\n후보6(666666): 10,000원\n");
        when(market.fetchStockPrices(anyList())).thenReturn(Map.of("005930","70,000"));
        var facts=new DartFinancialEvidenceService.FinancialFacts(2026,"11012","CFS","KRW","20260814000001",java.time.LocalDate.parse("2026-08-14"),"HALF_YEAR_YTD",
            java.math.BigDecimal.valueOf(100),java.math.BigDecimal.valueOf(10),null,java.math.BigDecimal.valueOf(12),java.math.BigDecimal.valueOf(50),java.math.BigDecimal.valueOf(20),
            java.math.BigDecimal.valueOf(90),null,java.math.BigDecimal.valueOf(10),java.math.BigDecimal.valueOf(40),"https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814000001",List.of(),"HALF_YEAR_YTD","2026-06-30","LATEST_ELIGIBLE_PERIOD");
        var stock=new DartFinancialEvidenceService.StockEvidence("005930","00126380","COMPLETE",java.time.Instant.parse("2026-10-12T05:00:00Z"),facts,List.of());
        var snapshot=new DartFinancialEvidenceService.EvidenceSnapshot("PARTIAL",stock.capturedAt(),List.of(stock));
        when(dart.fetchFinancialEvidence(anyList())).thenReturn(snapshot);
        when(dart.formatFinancialEvidence(snapshot)).thenReturn("STRUCTURED_DART_CASH_FLOW_WITH_PERIOD");
        var result=run("005930","조건에 맞는 후보 분석");
        assertThat(receivedPrompt.get()).contains("STRUCTURED_DART_CASH_FLOW_WITH_PERIOD");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> codes=ArgumentCaptor.forClass(List.class);
        verify(dart).fetchFinancialEvidence(codes.capture());
        assertThat(codes.getValue()).hasSize(5).startsWith("005930").doesNotHaveDuplicates();
        verify(suppliedScorer).scoreFromEvidence(stock); verify(suppliedScorer,never()).score(anyString());
        assertThat(result.stockPicks().getFirst().financialScore()).isEqualTo(55);
        assertThat(result.metadata().sourceExpansion()).containsKey("dartFinancial");
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
