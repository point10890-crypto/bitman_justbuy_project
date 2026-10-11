package com.bitman.justbuy.service;

import com.bitman.justbuy.config.KisProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KisRankingIntegrityTest {
    private RestTemplate http;
    private KisApiService service;

    @BeforeEach
    void setUp() {
        http = mock(RestTemplate.class);
        service = new KisApiService(new KisProperties("fixture-key", "fixture-secret"), http, new ObjectMapper());
        when(http.exchange(contains("/oauth2/tokenP"), eq(HttpMethod.POST), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"access_token\":\"fixture-token\",\"expires_in\":86400}"));
    }

    @Test
    void gainersUseIndependentOfficialRankingAndItsCodeField() {
        when(http.exchange(contains("/ranking/fluctuation"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"rt_cd":"0","output":[{"hts_kor_isnm":"상승후보","stck_shrn_iscd":"123456",
                "stck_prpr":"12000","prdy_ctrt":"20","acml_vol":"5000"}]}
                """));
        when(http.exchange(contains("/quotations/volume-rank"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"rt_cd":"0","output":[{"hts_kor_isnm":"거래량후보","mksc_shrn_iscd":"005930",
                "stck_prpr":"70000","prdy_ctrt":"1","acml_vol":"1000000"}]}
                """));

        assertThat(service.fetchRealtimeGainersRankingStocks(10)).extracting(KisApiService.RankedStock::code)
            .containsExactly("123456");
        verify(http).exchange(contains("FID_COND_SCR_DIV_CODE=20170"), eq(HttpMethod.GET),
            argThat(entity -> "FHPST01700000".equals(((org.springframework.http.HttpEntity<?>) entity)
                .getHeaders().getFirst("tr_id"))), eq(String.class));
    }

    @Test
    void formattedGainersUseTheSameIndependentCandidateSet() {
        when(http.exchange(contains("/ranking/fluctuation"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"rt_cd":"0","output":[{"hts_kor_isnm":"상승후보","stck_shrn_iscd":"123456",
                "stck_prpr":"12000","prdy_ctrt":"20","acml_vol":"5000"}]}
                """));
        assertThat(service.fetchGainersRanking()).contains("상승후보(123456)");
    }

    @Test
    void providerFailureCannotBeAcceptedAsRankingData() {
        when(http.exchange(contains("volume-rank"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"rt_cd":"1","msg_cd":"EGW00201","output":[{"hts_kor_isnm":"오류응답",
                "mksc_shrn_iscd":"005930","stck_prpr":"10000","prdy_ctrt":"1","acml_vol":"5000"}]}
                """));
        assertThat(service.fetchVolumeRankingStocks(10)).isEmpty();
    }

    @Test
    void invalidIdentityAndPercentagePriceAreExcludedFromRanks() {
        when(http.exchange(contains("volume-rank"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"rt_cd":"0","output":[{"hts_kor_isnm":"가짜","mksc_shrn_iscd":"ABCDEF",
                "stck_prpr":"10000","prdy_ctrt":"1"},{"hts_kor_isnm":"잘못된가격",
                "mksc_shrn_iscd":"005930","stck_prpr":"+5.21%","prdy_ctrt":"1"}]}
                """));
        assertThat(service.fetchVolumeRankingStocks(10)).isEmpty();
    }

    @Test
    void strongestFlowCandidateIsChosenAfterScanningTheBoundedPool() {
        KisApiService spyService = spy(service);
        var first = new KisApiService.RankedStock("약한후보", "005930", "10000", "1", "5000", 10);
        var stronger = new KisApiService.RankedStock("강한후보", "000660", "10000", "1", "5000", 1000);
        doReturn(List.of(first, stronger)).when(spyService).fetchForeignNetBuyRankingStocks(anyInt());
        doReturn(List.of()).when(spyService).fetchVolumeRankingStocks(anyInt());
        doReturn(List.of()).when(spyService).fetchGainersRankingStocks(anyInt());
        doReturn(new KisApiService.InvestorFlowSnapshot("005930", 10, 10, -20, 3, "쌍끌이"))
            .when(spyService).fetchInvestorFlowSnapshot("005930", 3);
        doReturn(new KisApiService.InvestorFlowSnapshot("000660", 1000, 1000, -2000, 3, "쌍끌이"))
            .when(spyService).fetchInvestorFlowSnapshot("000660", 3);
        doReturn(Map.of("currentPrice", "10000", "changeRate", "1%"))
            .when(spyService).fetchCurrentPrice(anyString());

        assertThat(spyService.fetchDoubleNetBuyCandidates(1, 3))
            .extracting(KisApiService.ConditionCandidate::code).containsExactly("000660");
    }

    @Test
    void currentPriceRequiresSuccessfulNumericProviderData() {
        when(http.exchange(contains("inquire-price"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"rt_cd\":\"1\",\"output\":{\"stck_prpr\":\"+5.21%\"}}"));
        assertThat(service.fetchCurrentPrice("005930")).isEmpty();
    }

    @Test
    void fiftyTwoWeekRangeUsesObservedAnnualHighAndLowInsteadOfDailyPriceLimits() {
        when(http.exchange(contains("inquire-price"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"rt_cd":"0","output":{"stck_prpr":"70000","stck_mxpr":"91000",
                "stck_llam":"49000","w52_hgpr":"98000","w52_lwpr":"42000"}}
                """));

        Map<String, String> quote = service.fetchCurrentPrice("005930");

        assertThat(quote.get("52주최고")).isEqualTo("98000");
        assertThat(quote.get("52주최저")).isEqualTo("42000");
    }

    @Test
    void absentFiftyTwoWeekRangeStaysUnknownDespiteAvailableDailyPriceLimits() {
        when(http.exchange(contains("inquire-price"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"rt_cd":"0","output":{"stck_prpr":"70000","stck_mxpr":"91000","stck_llam":"49000"}}
                """));

        Map<String, String> quote = service.fetchCurrentPrice("005930");

        assertThat(quote).isNotEmpty();
        assertThat(quote.get("52주최고")).isEmpty();
        assertThat(quote.get("52주최저")).isEmpty();
    }

    @Test
    void missingSuccessMarkerDoesNotVerifyAQuoteOrRanking() {
        when(http.exchange(contains("inquire-price"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"output\":{\"stck_prpr\":\"10000\"}}"));
        when(http.exchange(contains("volume-rank"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"output":[{"hts_kor_isnm":"미검증","mksc_shrn_iscd":"005930","stck_prpr":"10000"}]}
                """));
        assertThat(service.fetchCurrentPrice("005930")).isEmpty();
        assertThat(service.fetchVolumeRankingStocks(10)).isEmpty();
    }

    @Test
    void foreignRankingRejectsProviderErrorsAndTheFormattedPathSharesTheGuard() {
        when(http.exchange(contains("foreign-institution-total"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"rt_cd":"1","output":[{"hts_kor_isnm":"오류행","mksc_shrn_iscd":"005930",
                "stck_prpr":"10000","frgn_ntby_qty":"9000"}]}
                """));
        assertThat(service.fetchForeignNetBuyRankingStocks(1)).isEmpty();
        assertThat(service.fetchForeignNetBuyRanking()).isEmpty();
    }

    @Test
    void foreignRankingValidatesRowsAndSortsBeforeLimiting() {
        when(http.exchange(contains("foreign-institution-total"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"rt_cd":"0","output":[
                {"hts_kor_isnm":"약한후보","mksc_shrn_iscd":"005930","stck_prpr":"10000","frgn_ntby_qty":"10"},
                {"hts_kor_isnm":"가짜","mksc_shrn_iscd":"ABCDEF","stck_prpr":"10000","frgn_ntby_qty":"9999"},
                {"hts_kor_isnm":"강한후보","mksc_shrn_iscd":"000660","stck_prpr":"12000","frgn_ntby_qty":"1000"},
                {"hts_kor_isnm":"백분율","mksc_shrn_iscd":"123456","stck_prpr":"+5.21%","frgn_ntby_qty":"9999"}]}
                """));
        assertThat(service.fetchForeignNetBuyRankingStocks(1)).extracting(KisApiService.RankedStock::code)
            .containsExactly("000660");
    }

    @Test
    void formattedVolumeRankingAlsoRejectsAnUnsuccessfulResponse() {
        when(http.exchange(contains("volume-rank"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("""
                {"rt_cd":"1","output":[{"hts_kor_isnm":"오류거래량","mksc_shrn_iscd":"005930",
                "stck_prpr":"10000","prdy_ctrt":"1","acml_vol":"10000"}]}
                """));
        assertThat(service.fetchVolumeRanking()).isEmpty();
    }
}
