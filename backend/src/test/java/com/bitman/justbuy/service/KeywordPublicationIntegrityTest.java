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
    @Test void sharedKeywordRouteUsesKiwoomFlowButRetainsActualPrimaryQuoteProvenance() {
        var shared=mock(KeywordConditionSearchService.class);service.configureKeywordSearch(shared);
        Instant at=Instant.now().minusSeconds(5);
        var evidence=List.of(new KeywordSearchResponse.Evidence("외국인 순매수","300","주","KIWOOM_REST_MOCK","2026-10-06 ~ 2026-10-08","MOCK",at),
            new KeywordSearchResponse.Evidence("기관 순매수","600","주","KIWOOM_REST_MOCK","2026-10-06 ~ 2026-10-08","MOCK",at));
        var match=new KeywordSearchResponse.Match("005930","삼성전자","KOSPI",new BigDecimal("254000"),BigDecimal.ONE,
            BigDecimal.TEN,new BigDecimal("2540000"),"NAVER_FINANCE",at,at,evidence,"REAL",List.of());
        var providers=List.of(new KeywordSearchResponse.ProviderStatus("KIWOOM_REST_MOCK","MOCK","PARTIAL",at,"3거래일"));
        when(shared.search(anyString())).thenReturn(new KeywordSearchResponse("query","COMPLETE",List.of(),List.of(),
            new KeywordSearchResponse.Scope("검증 범위",1,1,1,false),at,"COMPLETE",List.of(),List.of(match),providers));
        var result=service.analyze("쌍끌이 종목 검색","수급분석");
        assertThat(result.stockPicks()).hasSize(1);var pick=result.stockPicks().getFirst();
        assertThat(pick.quoteSource()).isEqualTo("NAVER_FINANCE");assertThat(pick.quoteCheckedAt()).isEqualTo(at.toString());
        assertThat(pick.reason()).contains("KIWOOM_REST_MOCK","MOCK");
        assertThat(result.metadata().sourceExpansion().get("providers")).isEqualTo(providers);
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
