package com.bitman.justbuy.ai;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.FinancialScore;
import com.bitman.justbuy.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KiwoomSupplementConsumerTest {
    private static final String CODE = "005930";
    private static final Map<String, String> NAMES = Map.of(
        CODE, "삼성전자", "000660", "SK하이닉스", "035420", "NAVER", "005380", "현대차");
    private final MarketDataService market = mock(MarketDataService.class);
    private final KisSourceExpansionService primary = mock(KisSourceExpansionService.class);
    private final KiwoomReadOnlyService supplementary = mock(KiwoomReadOnlyService.class);
    private final AtomicReference<String> prompt = new AtomicReference<>();
    private String environment = "REAL";

    private Map<String, Object> metadata(List<String> codes, String technical, String valuation, String daily) {
        var states = new LinkedHashMap<String, Object>();
        for (String code : codes) {
            states.put(code, Map.of("technical", technical, "valuation", valuation));
            states.put(code + "Supplemental", Map.of("investorDaily", daily));
        }
        return Map.of("status", "PARTIAL", "requestedCodes", codes.size(), "enrichedCodes", codes.size(),
            "availableFacts", codes.size(), "realtimeFreshQuotes", 0, "sourceStates", states);
    }

    private AnalysisResponse run(List<String> candidates, List<String> aiCodes,
                                 Map<String, String> verifiedPrices, Map<String, Object> primaryMetadata) {
        StringBuilder marketText = new StringBuilder();
        for (String code : candidates) marketText.append(NAMES.get(code)).append('(').append(code).append(")\n");
        when(market.fetchFormattedMarketData()).thenReturn(marketText.toString());
        when(market.fetchStockPrices(anyList())).thenReturn(verifiedPrices);
        when(primary.candidateSnapshot()).thenReturn(new KeywordSearchDataService.Snapshot(
            "UNAVAILABLE", List.of(), Instant.now(), "fixture", List.of()));
        when(primary.enrich(anyList())).thenReturn(new KisSourceExpansionService.Enrichment(
            "KIS_PRIMARY_VALUATION_FACT_KEEP", primaryMetadata));
        when(supplementary.isEnabled()).thenReturn(true);
        when(supplementary.readiness()).thenReturn(Map.of("environment", environment));
        StringBuilder stocks = new StringBuilder();
        for (String code : aiCodes) {
            if (!stocks.isEmpty()) stocks.append(',');
            stocks.append("{\"name\":\"AI suggested label\",\"code\":\"").append(code)
                .append("\",\"confidence\":0.8,\"reasons\":[\"source facts\"]}");
        }
        String content = "```json:analysis\n{\"stocks\":[" + stocks + "]}\n```";
        AiAgent agent = new AiAgent() {
            public String name() { return "chatgpt"; }
            public boolean isAvailable() { return true; }
            public AgentResult analyze(String system, String user) {
                prompt.set(user);
                return new AgentResult(name(), content, "fixture", 1, 1, "success", null, 0);
            }
        };
        var scorer = mock(FinancialScorer.class);
        when(scorer.score(anyString())).thenReturn(FinancialScore.empty());
        var orchestrator = new MultiAgentOrchestrator(List.of(agent), market, new ConsensusEngine(),
            mock(DartApiService.class), scorer, mock(KisApiService.class), mock(TrackRecordService.class),
            mock(RestTemplate.class), new ObjectMapper().findAndRegisterModules());
        orchestrator.configureSourceExpansion(primary);
        orchestrator.configureKiwoomSupplement(supplementary);
        return orchestrator.runAnalysis("fixture market analysis", "REVERSAL_EDGE");
    }

    private KeywordSearchDataService.Quote quote(String code, String name, Instant fetchedAt, String source) {
        return new KeywordSearchDataService.Quote(code, name, "KOSPI", new BigDecimal("72000"),
            BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("720000"), source, fetchedAt, null);
    }

    private Map<String,Object> fact(String part,String status,String marker) {
        var value=new LinkedHashMap<String,Object>();
        value.put("status",status);value.put("source","MOCK".equals(environment)?"KIWOOM_REST_MOCK":"KIWOOM_REST");
        value.put("environment",environment);value.put("requestedStockCode",CODE);value.put("fetchedAt",Instant.now().toString());
        value.put("fixtureEvidence",marker);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate completed=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(today);
        if("technical".equals(part) && com.bitman.justbuy.util.KoreanMarketCalendar.isTradingDay(today)
            && LocalTime.now(ZoneId.of("Asia/Seoul")).isAfter(LocalTime.of(15,35))) completed=today;
        value.put("observedDate",completed.toString());value.put("bars",60);value.put("days",5);
        return value;
    }
    private Map<String,Object> evidence(Map<String,Object> groups) {
        return Map.of("status","PARTIAL","source","MOCK".equals(environment)?"KIWOOM_REST_MOCK":"KIWOOM_REST",
            "environment",environment,"candidates",Map.of(CODE,groups));
    }

    @Test void completePrimaryFactsStillRequestIndependentKiwoomEvidenceWithoutReplacingNaverQuote() {
        var result = run(List.of(CODE), List.of(CODE), Map.of(CODE, "71,000"),
            metadata(List.of(CODE), "AVAILABLE", "AVAILABLE", "AVAILABLE"));
        assertThat(result.stockPicks()).hasSize(1);
        assertThat(result.stockPicks().getFirst().quoteSource()).isEqualTo("NAVER_FINANCE");
        verify(supplementary).fetchEvidence(eq(List.of(CODE)), eq(Map.of(CODE,
            List.of("quote", "valuation", "technical", "investorDaily"))));
        verify(supplementary, never()).lookupAll(anyList());
    }

    @Test void bothProvidersAreRequestedAndPrimaryFactsRemainInPromptAndMetadata() {
        var original = metadata(List.of(CODE), "UNAVAILABLE", "AVAILABLE", "UNAVAILABLE");
        when(supplementary.fetchEvidence(anyList(), anyMap())).thenReturn(evidence(Map.of(
            "technical",fact("technical","AVAILABLE","KIWOOM_TECHNICAL_ONLY"),
            "investorDaily",fact("investorDaily","AVAILABLE","KIWOOM_DAILY_ONLY"))));
        var result = run(List.of(CODE), List.of(CODE), Map.of(CODE, "71,000"), original);
        verify(supplementary).fetchEvidence(eq(List.of(CODE)), eq(Map.of(CODE, List.of("quote", "valuation", "technical", "investorDaily"))));
        verify(supplementary, never()).lookupAll(anyList());
        assertThat(prompt.get()).contains("KIS_PRIMARY_VALUATION_FACT_KEEP", "KIWOOM_TECHNICAL_ONLY", "KIWOOM_DAILY_ONLY");
        assertThat(result.metadata().sourceExpansion().get("sourceStates")).isInstanceOf(Map.class);
        var retainedStates = (Map<?, ?>) result.metadata().sourceExpansion().get("sourceStates");
        ((Map<?, ?>) original.get("sourceStates")).forEach((key, value) -> assertThat(retainedStates.get(key)).isEqualTo(value));
        assertThat(result.stockPicks().getFirst().quoteSource()).isEqualTo("NAVER_FINANCE");
    }

    @Test void partiallyConfirmedSupplementMustRemainPartialEvenWhenOneFactIsAvailableForEveryCandidate() {
        when(supplementary.fetchEvidence(anyList(), anyMap())).thenReturn(evidence(Map.of(
            "technical",fact("technical","AVAILABLE","KIWOOM_CHART"),
            "investorDaily",Map.of("status","UNAVAILABLE"))));
        var result = run(List.of(CODE), List.of(CODE), Map.of(CODE, "71,000"),
            metadata(List.of(CODE), "UNAVAILABLE", "AVAILABLE", "UNAVAILABLE"));
        var coverage = (Map<?,?>) result.metadata().sourceExpansion().get("kiwoomSupplement");
        assertThat(coverage.get("status")).isEqualTo("PARTIAL");
        assertThat(coverage.get("enrichedCodes")).isEqualTo(1);
    }

    @Test void validNaverFinalPricePreventsAlternativeQuoteLookupsEvenWithIncompletePrimaryEvidence() {
        when(supplementary.fetchEvidence(anyList(), anyMap())).thenReturn(Map.of("status", "UNAVAILABLE"));
        var result = run(List.of(CODE), List.of(CODE), Map.of(CODE, "71,000"),
            metadata(List.of(CODE), "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"));
        verify(supplementary, never()).lookupAll(anyList());
        assertThat(result.stockPicks().getFirst().currentPrice()).isEqualTo("71,000");
    }

    @Test void naverFailureAllowsOnlyThreeExistingCandidatesAndKeepsActualProviderProvenance() {
        var candidates = List.of(CODE, "000660", "035420", "005380");
        when(supplementary.lookupAll(anyList())).thenAnswer(call -> {
            List<String> requested = call.getArgument(0);
            var found = new LinkedHashMap<String, KeywordSearchDataService.Quote>();
            for (String code : requested) found.put(code, quote(code, NAMES.get(code), Instant.now(), "KIWOOM_REST"));
            return found;
        });
        var aiCodes = new ArrayList<>(candidates); aiCodes.add("999999");
        var result = run(candidates, aiCodes, Map.of(), metadata(candidates, "AVAILABLE", "AVAILABLE", "AVAILABLE"));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> requests = ArgumentCaptor.forClass(List.class);
        verify(supplementary).lookupAll(requests.capture());
        assertThat(requests.getValue()).hasSize(3).allMatch(candidates::contains).doesNotContain("999999");
        assertThat(result.stockPicks()).hasSize(3).allSatisfy(pick -> {
            assertThat(pick.name()).isEqualTo(NAMES.get(pick.code()));
            assertThat(pick.currentPrice()).isEqualTo("72,000");
            assertThat(pick.quoteSource()).isEqualTo("KIWOOM_REST");
            assertThat(pick.candidateSource()).isEqualTo("MARKET_DATA");
            assertThat(Instant.parse(pick.quoteCheckedAt())).isBeforeOrEqualTo(Instant.now());
        });
        assertThat(result.finalContent()).doesNotContain("999999");
    }

    @Test void wrongCodeWrongNameFutureOldTimestampAndUnverifiedProviderCannotPublish() {
        for (var invalid : List.of(
            quote("000660", NAMES.get(CODE), Instant.now(), "KIWOOM_REST"),
            quote(CODE, "different company", Instant.now(), "KIWOOM_REST"),
            quote(CODE, NAMES.get(CODE), Instant.now().plusSeconds(60), "KIWOOM_REST"),
            quote(CODE, NAMES.get(CODE), Instant.now().minusSeconds(3600), "KIWOOM_REST"),
            quote(CODE, NAMES.get(CODE), Instant.now(), "AI_GUESS"),
            quote(CODE, NAMES.get(CODE), null, "KIWOOM_REST"),
            new KeywordSearchDataService.Quote(CODE, NAMES.get(CODE), "KOSPI", BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, "KIWOOM_REST", Instant.now(), null))) {
            when(supplementary.lookupAll(anyList())).thenReturn(Map.of(CODE, invalid));
            var result = run(List.of(CODE), List.of(CODE), Map.of(),
                metadata(List.of(CODE), "AVAILABLE", "AVAILABLE", "AVAILABLE"));
            assertThat(result.stockPicks()).isEmpty();
            assertThat(result.finalContent()).doesNotContain(CODE);
            assertThat(result.round1()).allSatisfy(agent -> assertThat(agent.content()).doesNotContain(CODE));
        }
    }

    @Test void enabledMockEnvironmentQueriesAlongsidePrimaryAndKeepsVerifiedPrimaryPublication() {
        environment = "MOCK";
        when(supplementary.fetchEvidence(anyList(), anyMap())).thenReturn(evidence(Map.of(
            "technical",fact("technical","AVAILABLE","MOCK_SERVER_VERIFIED_MARKET_CHART"))));
        when(supplementary.lookupAll(anyList())).thenReturn(Map.of(CODE,
            quote(CODE, NAMES.get(CODE), Instant.now(), "KIWOOM_REST")));

        var result = run(List.of(CODE), List.of(CODE), Map.of(CODE, "71,000"),
            metadata(List.of(CODE), "UNAVAILABLE", "AVAILABLE", "UNAVAILABLE"));

        verify(supplementary).fetchEvidence(eq(List.of(CODE)), eq(Map.of(CODE,
            List.of("quote", "valuation", "technical", "investorDaily"))));
        verify(supplementary, never()).lookupAll(anyList());
        assertThat(prompt.get()).contains("KIS_PRIMARY_VALUATION_FACT_KEEP")
            .contains("MOCK_SERVER_VERIFIED_MARKET_CHART", "KIWOOM_REST_MOCK", "KIS·키움 동시 조회 근거");
        assertThat(result.stockPicks()).hasSize(1);
        assertThat(result.stockPicks().getFirst().currentPrice()).isEqualTo("71,000");
        assertThat(result.stockPicks().getFirst().quoteSource()).isEqualTo("NAVER_FINANCE");
        var coverage=(Map<?,?>)result.metadata().sourceExpansion().get("kiwoomSupplement");
        assertThat(coverage.get("source")).isEqualTo("KIWOOM_REST_MOCK");
        assertThat(coverage.get("environment")).isEqualTo("MOCK");
    }

    @Test void enabledMockEnvironmentCanSupplyVerifiedQuoteWhenPrimaryQuoteIsUnavailable() {
        environment = "MOCK";
        when(supplementary.lookupAll(anyList())).thenReturn(Map.of(CODE,
            quote(CODE, NAMES.get(CODE), Instant.now(), "KIWOOM_REST_MOCK")));

        var result = run(List.of(CODE), List.of(CODE), Map.of(),
            metadata(List.of(CODE), "UNAVAILABLE", "AVAILABLE", "UNAVAILABLE"));

        verify(supplementary).fetchEvidence(anyList(), anyMap());
        verify(supplementary).lookupAll(eq(List.of(CODE)));
        assertThat(result.stockPicks()).hasSize(1);
        assertThat(result.stockPicks().getFirst().quoteSource()).isEqualTo("KIWOOM_REST_MOCK");
        assertThat(result.stockPicks().getFirst().name()).isEqualTo(NAMES.get(CODE));
        assertThat(result.stockPicks().getFirst().currentPrice()).isEqualTo("72,000");
    }

    @Test void mockSessionRejectsWronglyLabelledRealQuoteWithoutLosingPrimaryFacts() {
        environment="MOCK";
        when(supplementary.lookupAll(anyList())).thenReturn(Map.of(CODE,quote(CODE,NAMES.get(CODE),Instant.now(),"KIWOOM_REST")));
        var result=run(List.of(CODE),List.of(CODE),Map.of(),metadata(List.of(CODE),"AVAILABLE","AVAILABLE","AVAILABLE"));
        assertThat(result.stockPicks()).isEmpty();
        assertThat(prompt.get()).contains("KIS_PRIMARY_VALUATION_FACT_KEEP");
    }
}
