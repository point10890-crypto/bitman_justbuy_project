package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.agent.DeepSeekAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KeywordPublicationIntegrityTest {
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
        analyst("{\"signals\":[{\"stockCode\":\"005930\",\"aiScore\":82,\"verdict\":\"WATCH\",\"summary\":\"입력 수급 근거 확인\",\"evidence\":[\"동시 순매수\"],\"riskFlags\":[\"수급 반전\"],\"invalidation\":\"순매도 전환\",\"displayStatus\":\"관찰\"}]}");
        var result = service.analyze("쌍끌이 조건검색", "수급분석");
        var pick = mapper.valueToTree(result.stockPicks().getFirst());
        assertThat(pick.path("candidateSource").asText()).isEqualTo("KIS_DOUBLE_NET_BUY");
        assertThat(pick.path("quoteSource").asText()).isEqualTo("KIS OpenAPI");
        assertThat(pick.path("quoteCheckedAt").asText()).isNotBlank();
        assertThat(result.round1().get(1).status()).isEqualTo("success");
        assertThat(result.finalContent()).contains("입력 수급 근거 확인", "수급 반전", "순매도 전환");
    }
    @Test void optionalExpansionEnrichesOnlyVerifiedCandidatesAndPreservesCoverageInPublication() {
        when(kis.fetchDoubleNetBuyCandidates(5,3)).thenReturn(List.of(candidate("005930","70,000"),candidate("ABCDEF","10000")));
        var expansion=mock(KisSourceExpansionService.class);
        var coverage=Map.<String,Object>of("status","PARTIAL","requestedCodes",1,"enrichedCodes",1,"realtimeFreshQuotes",0);
        when(expansion.enrich(List.of("005930"))).thenReturn(new KisSourceExpansionService.Enrichment("\n[검증된 추가 KIS 근거: PERIOD_ONLY]\n",coverage));
        service.configureSourceExpansion(expansion);
        analyst("{\"signals\":[{\"stockCode\":\"005930\",\"aiScore\":82,\"verdict\":\"WATCH\",\"summary\":\"입력 수급 근거 확인\",\"evidence\":[\"동시 순매수\"],\"riskFlags\":[\"수급 반전\"],\"invalidation\":\"순매도 전환\",\"displayStatus\":\"관찰\"}]}");
        var result=service.analyze("쌍끌이 조건검색","수급분석");
        assertThat(result.stockPicks()).extracting(p->p.code()).containsExactly("005930");
        assertThat(result.metadata().sourceExpansion()).isEqualTo(coverage);
        verify(expansion).enrich(List.of("005930"));
        verify(agent).analyze(anyString(),contains("PERIOD_ONLY"));
        assertThat(AnalysisOutcome.describe(result).metadata().sourceExpansion()).isEqualTo(coverage);
    }
}
