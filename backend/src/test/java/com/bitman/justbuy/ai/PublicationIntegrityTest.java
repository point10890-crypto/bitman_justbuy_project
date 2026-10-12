package com.bitman.justbuy.ai;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.dto.*;
import com.bitman.justbuy.service.*;
import com.bitman.justbuy.util.StructuredAnalysisParser.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicationIntegrityTest {
    final MarketDataService market = mock(MarketDataService.class);
    final TrackRecordService track = mock(TrackRecordService.class);
    String row(String code) {
        return "📌 fixture (" + code + ") — 현재가 10,000원\n투자판단: 매수\n목표가: 12,000원\n손절가: 9,000원\n근거: 확인한 후보\n"
            + "```json:analysis\n{\"stocks\":[{\"name\":\"fixture\",\"code\":\"" + code + "\",\"confidence\":0.8,\"action\":\"매수\",\"reasons\":[\"확인한 후보\"]}]}\n```";
    }
    AnalysisResponse run(String code, Map<String,String> prices) {
        return run(code,prices,"BREAKOUT",null);
    }
    AnalysisResponse run(String code, Map<String,String> prices, String mode, KisSourceExpansionService expansion) {
        when(market.fetchFormattedMarketData()).thenReturn("Verified input candidate: 삼성전자(005930), current price 10,000 KRW.");
        when(market.fetchStockPrices(anyList())).thenReturn(prices);
        FinancialScorer financial = mock(FinancialScorer.class);
        when(financial.score(anyString())).thenReturn(FinancialScore.empty());
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForEntity(anyString(), eq(String.class))).thenReturn(ResponseEntity.ok("{\"gate\":\"GREEN\"}"));
        List<AiAgent> agents = List.of(agent("chatgpt", row(code)), agent("deepseek", row(code)));
        var orchestrator=new MultiAgentOrchestrator(agents, market, new ConsensusEngine(), mock(DartApiService.class), financial,
            mock(KisApiService.class), track, rest, new ObjectMapper());
        if(expansion!=null) orchestrator.configureSourceExpansion(expansion);
        return orchestrator.runAnalysis("fixture request", mode);
    }
    AiAgent agent(String name, String content) {
        return new AiAgent() {
            public String name(){return name;}
            public boolean isAvailable(){return true;}
            public AgentResult analyze(String system,String user){return new AgentResult(name, content,"fixture",1,2,"success",null,0);}
        };
    }
    @Test void outsideCandidateCannotSurviveInPicksTextOrConsensusEvenWithQuote() {
        AnalysisResponse result = run("999999", Map.of("999999", "10,000"));
        assertThat(result.stockPicks()).isEmpty();
        assertThat(result.finalContent()).doesNotContain("999999");
        assertThat(result.round1()).allSatisfy(r -> assertThat(r.content()).doesNotContain("999999"));
        assertThat(result.consensus() == null ? List.of() : result.consensus().stocks()).isEmpty();
        verifyNoInteractions(track);
    }
    @Test void quoteMissingOrNotPositivePriceDropsCandidateInsteadOfLlmPrice() {
        for (Map<String,String> prices : List.of(Map.<String,String>of(), Map.of("005930","0"), Map.of("005930","NaN"), Map.of("005930","+5.21%"))) {
            AnalysisResponse result = run("005930", prices);
            assertThat(result.stockPicks()).isEmpty();
            assertThat(result.finalContent()).doesNotContain("005930");
        }
    }
    @Test void verifiedPickUsesQuoteAndPreservesPublicationProvenance() {
        AnalysisResponse result = run("005930", Map.of("005930", "70,000"));
        assertThat(result.stockPicks()).hasSize(1);
        assertThat(result.stockPicks().getFirst().currentPrice()).isEqualTo("70,000");
        var json = new ObjectMapper().valueToTree(result.stockPicks().getFirst());
        assertThat(json.path("quoteSource").asText()).isEqualTo("NAVER_FINANCE");
        assertThat(json.path("quoteCheckedAt").asText()).isNotBlank();
        assertThat(json.path("candidateSource").asText()).isEqualTo("MARKET_DATA");
        verifyNoInteractions(track);
    }

    @Test void verifiedCodeDoesNotAuthorizeAnInventedCompanyName() {
        AnalysisResponse result = run("005930", Map.of("005930", "70,000"));
        assertThat(result.stockPicks().getFirst().name()).isEqualTo("삼성전자");
        assertThat(result.finalContent()).doesNotContain("fixture");
        assertThat(result.consensus().stocks().getFirst().name()).isEqualTo("삼성전자");
    }
    @Test void verifiedSwingAndLeaderPublishWithSourceExpansionDisabled() {
        var gateway=mock(KisSourceGateway.class);
        when(gateway.isEnabled()).thenReturn(false);
        when(gateway.readiness()).thenReturn(Map.of("status","DISABLED"));
        var expansion=new KisSourceExpansionService(gateway,null,null,null,null,new ObjectMapper());
        for(String mode:List.of("REVERSAL_EDGE","FLOW_LEADER")) {
            AnalysisResponse result=run("005930",Map.of("005930","70,000"),mode,expansion);
            assertThat(result.mode()).isEqualTo(mode);
            assertThat(result.stockPicks()).extracting(StockPick::code).containsExactly("005930");
            assertThat(result.stockPicks().getFirst().name()).isEqualTo("삼성전자");
            assertThat(result.stockPicks().getFirst().currentPrice()).isEqualTo("70,000");
            assertThat(AnalysisOutcome.classify(result)).isEqualTo(AnalysisOutcome.COMPLETE);
            assertThat(result.metadata().sourceExpansion()).containsEntry("status","DISABLED");
        }
        verify(gateway,never()).fetch(anyString(),anyString(),anyMap(),anyLong());
        verifyNoInteractions(track);
    }
    @Test void swingAndLeaderPromptsUseVerifiedInputSourcesWithoutRequiringAnUnavailableAgent() {
        for(String mode:List.of("FLOW_LEADER","REVERSAL_EDGE")) {
            assertThat(MultiAgentOrchestrator.MODE_PROMPTS.get(mode))
                .doesNotContain("Grok 검색 결과만 사용","Grok 검색 기준","Grok 실시간 섹터/뉴스")
                .contains("KIS","Naver","DART","미확인","보류");
        }
    }
    @Test void duplicateRowsHaveOneVotePerIndependentAgent() {
        var stock = new StructuredStock("fixture","005930","관망",.8,10000L,null,null,null,List.of("reason"));
        var another = new StructuredStock("fixture2","000660","관망",.8,10000L,null,null,null,List.of("reason"));
        var result = new ConsensusEngine().calculateConsensus(Map.of("chatgpt", new StructuredAnalysis(List.of(stock,stock),"neutral",List.of()),
            "deepseek", new StructuredAnalysis(List.of(another),"neutral",List.of())), Map.of(),"GREEN");
        var vote = result.stocks().stream().filter(s -> s.code().equals("005930")).findFirst().orElseThrow();
        assertThat(vote.consensusScore()).isEqualTo(50);
        assertThat(vote.mentionCount()).isEqualTo(1);
    }

    @Test void interruptedOrchestrationStopsInsteadOfPublishingLateResults() throws Exception {
        when(market.fetchFormattedMarketData()).thenReturn("삼성전자(005930)");
        var entered = new CountDownLatch(1);
        AiAgent slow = new AiAgent() {
            public String name(){return "chatgpt";}
            public boolean isAvailable(){return true;}
            public AgentResult analyze(String s,String u){
                entered.countDown();
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new AgentResult("chatgpt",row("005930"),"fixture",1,2,"success",null,0);
            }
        };
        var orchestrator = new MultiAgentOrchestrator(List.of(slow), market, new ConsensusEngine(), mock(DartApiService.class),
            mock(FinancialScorer.class), mock(KisApiService.class), track, mock(RestTemplate.class), new ObjectMapper());
        var task = new FutureTask<>(() -> orchestrator.runAnalysis("fixture", "BREAKOUT"));
        var runner = new Thread(task);
        runner.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        runner.interrupt();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> task.get(1, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CancellationException.class);
        verifyNoInteractions(track);
    }
}
