package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.agent.DeepSeekAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.math.BigDecimal;
import com.bitman.justbuy.dto.KeywordSearchResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KeywordPublicationIntegrityTest {
    @Test void sharedKeywordRouteUsesKiwoomFlowButRetainsActualPrimaryQuoteProvenance() throws Exception {
        var shared=mock(KeywordConditionSearchService.class);service.configureKeywordSearch(shared);
        Instant at=Instant.now().minusSeconds(5);
        var evidence=List.of(new KeywordSearchResponse.Evidence("외국인 순매수","300","주","KIWOOM_REST_MOCK","2026-10-06 ~ 2026-10-08","MOCK",at),
            new KeywordSearchResponse.Evidence("기관 순매수","600","주","KIWOOM_REST_MOCK","2026-10-06 ~ 2026-10-08","MOCK",at));
        var match=new KeywordSearchResponse.Match("005930","삼성전자","KOSPI",new BigDecimal("254000"),BigDecimal.ONE,
            BigDecimal.TEN,new BigDecimal("2540000"),"NAVER_FINANCE",at,at,evidence,"REAL",List.of());
        var providers=List.of(new KeywordSearchResponse.ProviderStatus("KIWOOM_REST_MOCK","MOCK","PARTIAL",at,"3거래일"));
        when(shared.search(anyString())).thenReturn(new KeywordSearchResponse("query","COMPLETE",List.of(),List.of(),
            new KeywordSearchResponse.Scope("검증 범위",1,1,1,false),at,"COMPLETE",List.of(),List.of(match),providers));
        analyst(claimResponse().replace("\"value\":100", "\"value\":300"));
        var result=service.analyze("쌍끌이 종목 검색","수급분석");
        assertThat(result.stockPicks()).hasSize(1);var pick=result.stockPicks().getFirst();
        assertThat(pick.quoteSource()).isEqualTo("NAVER_FINANCE");assertThat(pick.quoteCheckedAt()).isEqualTo(at.toString());
        assertThat(pick.reason()).contains("KIWOOM_REST_MOCK","MOCK");
        assertThat(result.metadata().sourceExpansion().get("providers")).isEqualTo(providers);
        assertThat(result.round1().get(1).status()).isEqualTo("success");
        var message=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(agent,times(1)).analyze(anyString(),message.capture());
        String packetJson=message.getValue().substring(0,message.getValue().indexOf("\n[사용자 검색 조건"));
        var facts=mapper.readTree(packetJson).path("candidates").get(0).path("facts");
        var indexed=new java.util.LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        facts.forEach(fact->indexed.put(fact.path("factId").asText(),fact));
        var flow=indexed.get("005930:/metrics/foreignNet");
        assertThat(flow.path("value").asInt()).isEqualTo(300);
        assertThat(flow.path("context").path("source").asText()).isEqualTo("KIWOOM_REST_MOCK");
        assertThat(flow.path("context").path("environment").asText()).isEqualTo("MOCK");
        assertThat(flow.path("context").path("period").asText()).isEqualTo("2026-10-06 ~ 2026-10-08");
        assertThat(flow.path("context").path("unit").asText()).isEqualTo("shares");
        var quote=indexed.get("005930:/metrics/primaryQuote/currentPrice");
        assertThat(quote.path("value").asInt()).isEqualTo(254000);
        assertThat(quote.path("context").path("source").asText()).isEqualTo("NAVER_FINANCE");
        assertThat(quote.path("context").path("environment").asText()).isEqualTo("REAL");
        assertThat(quote.path("context").path("observedDate").asText()).isEqualTo(at.toString());
        assertThat(quote.path("context").path("period").asText()).isEqualTo("POINT_IN_TIME");
        assertThat(quote.path("context").path("unit").asText()).isEqualTo("KRW");
        verifyNoInteractions(kis);verify(shared).search("쌍끌이 종목 검색");
    }
    final KisApiService kis = mock(KisApiService.class);
    final DeepSeekAgent agent = mock(DeepSeekAgent.class);
    final ObjectMapper mapper = new ObjectMapper();
    final KeywordConditionAnalysisService service = new KeywordConditionAnalysisService(kis, agent, mapper);

    KisApiService.ConditionCandidate candidate(String code, String price) {
        return new KisApiService.ConditionCandidate("삼성전자", code, price, "+5.21", 100, 200, -300, 300,
            "KIS OpenAPI", "외국인·기관 3일 동시 순매수");
    }

    void analyst(String content) {
        when(agent.isAvailable()).thenReturn(true);
        when(agent.analyze(anyString(), anyString())).thenReturn(new AgentResult("deepseek", content, "fixture-model", 20, 30, "success", null, 0));
    }

    @Test void malformedCandidatePricesAndCodesCannotBePublishedOrTriggerPaidAnalysis() {
        when(kis.fetchDoubleNetBuyCandidates(5,3)).thenReturn(List.of(candidate("005930","+5.21%"), candidate("ABCDEF","10000"), candidate("000660","NaN")));
        var result = service.analyze("쌍끌이 조건검색", "수급분석");
        assertThat(result.stockPicks()).isEmpty();
        assertThat(result.isFresh()).isFalse();
        verifyNoInteractions(agent);
    }

    @Test void outsideCandidateNarrativeIsRemovedFromAllPublicContentAndUsagePreserved() {
        when(kis.fetchDoubleNetBuyCandidates(5,3)).thenReturn(List.of(candidate("005930","70000")));
        analyst("📌 가짜종목(999999) 현재가 10000원. 매수 추천");
        var result = service.analyze("쌍끌이 조건검색", "수급분석");
        assertThat(result.stockPicks()).extracting(p -> p.code()).containsExactly("005930");
        assertThat(result.finalContent()).doesNotContain("999999");
        assertThat(result.round1()).allSatisfy(r -> assertThat(r.content()).doesNotContain("999999"));
        assertThat(result.round1().get(1).status()).isEqualTo("error");
        assertThat(result.round1().get(1).inputTokens() + result.round1().get(1).outputTokens()).isEqualTo(50);
    }

    @Test void verifiedKeywordPublicationRetainsSourceAndBoundedAnalystEvidence() {
        when(kis.fetchDoubleNetBuyCandidates(5,3)).thenReturn(List.of(candidate("005930","70,000")));
        analyst(claimResponse());
        var result = service.analyze("쌍끌이 조건검색", "수급분석");
        var pick = mapper.valueToTree(result.stockPicks().getFirst());
        assertThat(pick.path("candidateSource").asText()).isEqualTo("KIS_DOUBLE_NET_BUY");
        assertThat(pick.path("quoteSource").asText()).isEqualTo("KIS OpenAPI");
        assertThat(pick.path("quoteCheckedAt").asText()).isNotBlank();
        assertThat(result.round1().get(1).status()).isEqualTo("success");
        assertThat(result.finalContent()).contains("입력 수급 근거 확인", "수급 반전", "순매도 전환");
        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        var user = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(agent,times(1)).analyze(system.capture(),user.capture());
        assertThat(system.getValue()).contains("claims", "factId", "숫자", "반증");
        assertThat(user.getValue()).contains("facts", "005930:/metrics/foreignNet", "005930:/currentPrice");
    }
    @Test void optionalExpansionEnrichesOnlyVerifiedCandidatesAndPreservesCoverageInPublication() {
        when(kis.fetchDoubleNetBuyCandidates(5,3)).thenReturn(List.of(candidate("005930","70,000"),candidate("ABCDEF","10000")));
        var expansion=mock(KisSourceExpansionService.class);
        var coverage=Map.<String,Object>of("status","PARTIAL","requestedCodes",1,"enrichedCodes",1,"realtimeFreshQuotes",0);
        when(expansion.enrich(List.of("005930"))).thenReturn(new KisSourceExpansionService.Enrichment("\n[검증된 추가 KIS 근거: PERIOD_ONLY]\n",coverage));
        service.configureSourceExpansion(expansion);
        analyst(claimResponse());
        var result=service.analyze("쌍끌이 조건검색","수급분석");
        assertThat(result.stockPicks()).extracting(p->p.code()).containsExactly("005930");
        assertThat(result.metadata().sourceExpansion()).isEqualTo(coverage);
        verify(expansion).enrich(List.of("005930"));
        verify(agent).analyze(anyString(),contains("PERIOD_ONLY"));
        assertThat(AnalysisOutcome.describe(result).metadata().sourceExpansion()).isEqualTo(coverage);
    }

    @Test void legacyTextOnlyAnalystResponseFallsBackWithoutSuppressingActualMatches() {
        when(kis.fetchDoubleNetBuyCandidates(5,3)).thenReturn(List.of(candidate("005930","70,000")));
        analyst("{\"signals\":[{\"stockCode\":\"005930\",\"aiScore\":82,\"verdict\":\"WATCH\",\"summary\":\"입력 수급 근거 확인\",\"evidence\":[\"동시 순매수\"],\"riskFlags\":[\"수급 반전\"],\"invalidation\":\"순매도 전환\"}]}");
        var result=service.analyze("쌍끌이 조건검색","수급분석");
        assertThat(result.stockPicks()).hasSize(1);
        assertThat(result.round1().get(1).status()).isEqualTo("error");
        assertThat(result.finalContent()).doesNotContain("입력 수급 근거 확인");
        verify(agent,times(1)).analyze(anyString(),anyString());
    }

    @Test void injectedVerifierRetainsOptionalJournalForKeywordInputsAndDecisions() {
        var verifier=new DeepSeekConditionVerifier(agent,mapper);
        var journal=mock(AnalysisEvidenceJournalService.class);
        verifier.configureEvidenceJournal(journal);
        service.configureEvidenceVerifier(verifier);
        when(kis.fetchDoubleNetBuyCandidates(5,3)).thenReturn(List.of(candidate("005930","70,000")));
        analyst(claimResponse());
        assertThat(service.analyze("쌍끌이 조건검색","수급분석").round1().get(1).status()).isEqualTo("success");
        verify(journal,times(2)).capture(eq("CONDITION_VERIFY"),eq("KEYWORD_DUAL_NET_BUY"),eq(List.of("005930")),anyMap(),anyList());
    }

    private static String claimResponse() {
        return "{\"signals\":[{\"stockCode\":\"005930\",\"aiScore\":82,\"verdict\":\"WATCH\",\"summary\":\"입력 수급 근거 확인\",\"claims\":[{\"factId\":\"005930:/metrics/foreignNet\",\"value\":100}],\"riskFlags\":[\"수급 반전\"],\"invalidation\":\"순매도 전환\",\"displayStatus\":\"관찰\"}]}";
    }
}
