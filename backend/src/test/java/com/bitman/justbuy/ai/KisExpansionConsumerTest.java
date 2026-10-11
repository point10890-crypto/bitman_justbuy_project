package com.bitman.justbuy.ai;
import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.dto.*;
import com.bitman.justbuy.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KisExpansionConsumerTest {
    @Test void actualExpandedCandidateAndEvidenceReachAgentAndSurvivePublicationMetadata() {
        var market=mock(MarketDataService.class);var expansion=mock(KisSourceExpansionService.class);
        when(market.fetchFormattedMarketData()).thenReturn("기존 후보 없음");
        when(market.fetchStockPrices(anyList())).thenReturn(Map.of("005930","71,000"));
        var quote=new KeywordSearchDataService.Quote("005930","삼성전자","KOSPI",new BigDecimal("70000"),BigDecimal.ONE,BigDecimal.TEN,BigDecimal.TEN,"KIS_OPENAPI",Instant.now(),null);
        when(expansion.candidateSnapshot()).thenReturn(new KeywordSearchDataService.Snapshot("PARTIAL",List.of(quote),Instant.now(),"bounded",List.of()));
        var summary=Map.<String,Object>of("status","PARTIAL","requestedCodes",1,"enrichedCodes",1,"availableFacts",2,"realtimeFreshQuotes",0);
        when(expansion.enrich(List.of("005930"))).thenReturn(new KisSourceExpansionService.Enrichment("VERIFIED_TECHNICAL_FINANCIAL_ESTIMATE_WS_FACTS",summary));
        AtomicReference<String> received=new AtomicReference<>();String fence=String.valueOf((char)96).repeat(3);
        AiAgent agent=new AiAgent() {
            public String name(){return "chatgpt";} public boolean isAvailable(){return true;}
            public AgentResult analyze(String system,String user){received.set(user);return new AgentResult(name(),fence+"json:analysis\n{\"stocks\":[{\"name\":\"invented\",\"code\":\"005930\",\"action\":\"관망\",\"confidence\":0.8,\"reasons\":[\"source facts\"]}]}\n"+fence,"fixture",1,1,"success",null,0);}
        };
        var scorer=mock(FinancialScorer.class);when(scorer.score(anyString())).thenReturn(FinancialScore.empty());
        var orchestrator=new MultiAgentOrchestrator(List.of(agent),market,new ConsensusEngine(),mock(DartApiService.class),scorer,mock(KisApiService.class),mock(TrackRecordService.class),mock(RestTemplate.class),new ObjectMapper().findAndRegisterModules());
        orchestrator.configureSourceExpansion(expansion);
        var result=AnalysisOutcome.describe(orchestrator.runAnalysis("시장 분석","BREAKOUT"));
        assertThat(received.get()).contains("삼성전자","VERIFIED_TECHNICAL_FINANCIAL_ESTIMATE_WS_FACTS","관측시각이 null");
        assertThat(result.stockPicks()).hasSize(1);
        assertThat(result.stockPicks().getFirst().name()).isEqualTo("삼성전자");
        assertThat(result.stockPicks().getFirst().currentPrice()).isEqualTo("71,000");
        assertThat(result.stockPicks().getFirst().quoteSource()).isEqualTo("NAVER_FINANCE");
        assertThat(result.metadata().sourceExpansion()).isEqualTo(summary);
    }
    @Test void enabledKeywordDataUsesExpandedRealSourceAndKeepsDisabledExistingFallback() {
        var gateway=mock(KisSourceGateway.class);var source=mock(KisCandidateSourceService.class);
        var data=new KeywordSearchDataService(mock(KisApiService.class),new ObjectMapper());
        data.configureExpandedSources(source,gateway);when(gateway.isEnabled()).thenReturn(true);
        var snapshot=new KeywordSearchDataService.Snapshot("UNAVAILABLE",List.of(),Instant.now(),"KIS outage",List.of("actual source unavailable"));
        when(source.snapshot()).thenReturn(snapshot);
        assertThat(data.snapshot()).isSameAs(snapshot);
        assertThat(data.lookup("005930")).isNull();verify(source).lookup("005930");
    }
}
