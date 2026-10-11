package com.bitman.justbuy.service;

import com.bitman.justbuy.config.KisProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KisExtendedEvidenceTest {
    final Instant now = Instant.parse("2026-10-12T01:03:30Z");
    final ObjectMapper mapper = new ObjectMapper();
    final KisApiService kis = mock(KisApiService.class);

    KisExtendedEvidenceService service(boolean enabled) {
        return new KisExtendedEvidenceService(kis, enabled, mapper, Clock.fixed(now, ZoneOffset.UTC));
    }

    KisApiService.ExtendedSnapshot snapshot(String code, String rows) throws Exception {
        return new KisApiService.ExtendedSnapshot("AVAILABLE", code, now, mapper.readTree(rows));
    }

    String bar(String date, String time, String price, String volume) {
        return "{\"stck_bsop_date\":\"" + date + "\",\"stck_cntg_hour\":\"" + time
            + "\",\"stck_prpr\":\"" + price + "\",\"stck_oprc\":\"" + price
            + "\",\"stck_hgpr\":\"" + price + "\",\"stck_lwpr\":\"" + price
            + "\",\"cntg_vol\":\"" + volume + "\"}";
    }

    void stubMinute(String rows) throws Exception {
        when(kis.isAvailable()).thenReturn(true);
        when(kis.fetchMinuteEvidence(anyString(), any(), anyLong())).thenAnswer(i -> snapshot(i.getArgument(0), rows));
        when(kis.fetchProgramEvidence(anyString(), any(), anyLong())).thenAnswer(i -> snapshot(i.getArgument(0), "[]"));
    }

    @Test void disabledFeatureHasNoProviderOrTokenWork() {
        assertThat(service(false).fetchEvidence(List.of("005930")).status()).isEqualTo("DISABLED");
        verifyNoInteractions(kis);
    }

    @Test void onlyCurrentDayCompleteValidBarsEnterCoverageWeightedClose() throws Exception {
        stubMinute("[" + String.join(",",
            bar("20261012", "100000", "70000", "10"), bar("20261012", "100100", "71000", "30"),
            bar("20261012", "100100", "1", "999"), bar("20261012", "100300", "90000", "100"),
            bar("20261011", "100000", "1", "100"), bar("20261012", "110000", "1", "100"),
            bar("20261012", "100200", "+5.21%", "100"), bar("20261012", "100200", "72000", "NaN")) + "]");
        var result = service(true).fetchEvidence(List.of("005930"));
        var minute = result.candidates().getFirst().minute();
        assertThat(minute.get("status")).isEqualTo("AVAILABLE");
        assertThat(minute.get("observationDate")).isEqualTo("2026-10-12");
        assertThat(minute.get("barCount")).isEqualTo(2);
        assertThat(minute.get("volume")).isEqualTo(40L);
        assertThat(minute.get("recentWeightedClose").toString()).isEqualTo("70750.00");
        String text = service(true).fetchFormattedExtendedEvidence(List.of("005930"));
        assertThat(text).contains("KIS OpenAPI", "fetchedAt", "최근 완료 분봉", "전체 세션 VWAP 아님");
    }

    @Test void programTimeAndSignedQuantitiesDoNotFabricateBusinessDate() throws Exception {
        stubMinute("[]");
        when(kis.fetchProgramEvidence(anyString(), any(), anyLong())).thenReturn(snapshot("005930", """
            [{"bsop_hour":"100200","stck_prpr":"71000","acml_vol":"1000",
              "whol_smtn_ntby_qty":"-200","whol_smtn_ntby_tr_pbmn":"-14000000"}]
            """));
        var result = service(true).fetchEvidence(List.of("005930"));
        var program = result.candidates().getFirst().program();
        assertThat(program.get("status")).isEqualTo("DATE_UNKNOWN");
        assertThat(program.get("observationDate")).isEqualTo("UNKNOWN");
        assertThat(program.get("providerTime")).isEqualTo("100200");
        assertThat(program.get("netBuyQuantity")).isEqualTo(-200L);
        assertThat(program.get("netBuyAmount")).isEqualTo(-14000000L);
        assertThat(result.status()).isEqualTo("PARTIAL");
    }

    @Test void providerErrorsStaleCaptureAndWrongIdentityRemainUnavailable() throws Exception {
        stubMinute("[" + bar("20261012", "100100", "70000", "10") + "]");
        var rows = mapper.readTree("[" + bar("20261012", "100100", "70000", "10") + "]");
        when(kis.fetchMinuteEvidence(eq("005930"), any(), anyLong())).thenReturn(
            new KisApiService.ExtendedSnapshot("PROVIDER_ERROR", "005930", now, rows));
        assertThat(service(true).fetchEvidence(List.of("005930")).candidates().getFirst().minute().get("status")).isEqualTo("PROVIDER_ERROR");
        when(kis.fetchMinuteEvidence(eq("005930"), any(), anyLong())).thenReturn(
            new KisApiService.ExtendedSnapshot("AVAILABLE", "005930", now.minusSeconds(600), rows));
        assertThat(service(true).fetchEvidence(List.of("005930")).candidates().getFirst().minute().get("status")).isEqualTo("STALE");
        when(kis.fetchMinuteEvidence(eq("005930"), any(), anyLong())).thenReturn(snapshot("999999", rows.toString()));
        assertThat(service(true).fetchEvidence(List.of("005930")).candidates().getFirst().minute().get("status")).isEqualTo("INVALID_IDENTITY");
    }

    @Test void candidateAndMinuteRowWorkIsBounded() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(bar("20261012", LocalTime.of(9, 24).plusMinutes(i).format(DateTimeFormatter.ofPattern("HHmmss")), "70000", "10"));
        stubMinute("[" + String.join(",", rows) + "]");
        var result = service(true).fetchEvidence(List.of("bad", "005930", "005930", "000660", "035420", "035720", "068270", "005380"));
        assertThat(result.candidates()).extracting(KisExtendedEvidenceService.CandidateEvidence::stockCode)
            .containsExactly("005930", "000660", "035420", "035720", "068270");
        assertThat(result.candidates().getFirst().minute().get("barCount")).isEqualTo(30);
        verify(kis, times(5)).fetchMinuteEvidence(anyString(), any(), anyLong());
        verify(kis, times(5)).fetchProgramEvidence(anyString(), any(), anyLong());
    }

    @Test void interruptionAndBatchBudgetStopLaterRequests() throws Exception {
        Thread.currentThread().interrupt();
        try { assertThat(service(true).fetchEvidence(List.of("005930")).status()).isEqualTo("CANCELLED"); }
        finally { Thread.interrupted(); }
        verifyNoInteractions(kis);
        stubMinute("[]");
        when(kis.fetchMinuteEvidence(anyString(), any(), anyLong())).thenAnswer(i -> {
            Thread.sleep(20);
            return snapshot(i.getArgument(0), "[]");
        });
        var bounded = service(true);
        ReflectionTestUtils.setField(bounded, "budgetMs", 5L);
        assertThat(bounded.fetchEvidence(List.of("005930", "000660")).status()).isEqualTo("BUDGET_EXCEEDED");
        verify(kis, times(1)).fetchMinuteEvidence(anyString(), any(), anyLong());
        verify(kis, never()).fetchProgramEvidence(anyString(), any(), anyLong());
    }

    @Test void rawHelpersUseOfficialRequestsAndDedicatedShortClient() {
        var primary = mock(RestTemplate.class);
        var extended = mock(RestTemplate.class);
        var adapter = new KisApiService(new KisProperties("fixture-key", "fixture-secret"), primary, mapper);
        adapter.configureExtendedClient(extended);
        ReflectionTestUtils.setField(adapter, "accessToken", "fixture-token");
        ReflectionTestUtils.setField(adapter, "tokenExpiry", Instant.now().plusSeconds(3600));
        when(extended.exchange(anyString(), any(), any(), eq(String.class))).thenReturn(ResponseEntity.ok("{\"rt_cd\":\"0\",\"output2\":[],\"output\":[]}"));
        long deadline = System.nanoTime() + 2_000_000_000L;
        assertThat(adapter.fetchMinuteEvidence("005930", now, deadline).status()).isEqualTo("AVAILABLE");
        assertThat(adapter.fetchProgramEvidence("005930", now, deadline).status()).isEqualTo("AVAILABLE");
        verify(extended).exchange(argThat((String url) -> url.contains("inquire-time-itemchartprice") && url.contains("FID_COND_MRKT_DIV_CODE=J")
            && url.contains("FID_INPUT_ISCD=005930") && url.contains("FID_INPUT_HOUR_1=100330") && url.contains("FID_PW_DATA_INCU_YN=Y")),
            eq(HttpMethod.GET), argThat(entity -> "FHKST03010200".equals(((HttpEntity<?>) entity).getHeaders().getFirst("tr_id"))), eq(String.class));
        verify(extended).exchange(contains("program-trade-by-stock"), eq(HttpMethod.GET),
            argThat(entity -> "FHPPG04650101".equals(((HttpEntity<?>) entity).getHeaders().getFirst("tr_id"))), eq(String.class));
        verifyNoInteractions(primary);
    }

    @Test void rawHelpersRejectInputDeadlineAndMissingProviderSuccessMarker() {
        var http = mock(RestTemplate.class);
        var adapter = new KisApiService(new KisProperties("fixture-key", "fixture-secret"), http, mapper);
        adapter.configureExtendedClient(http);
        ReflectionTestUtils.setField(adapter, "accessToken", "fixture-token");
        ReflectionTestUtils.setField(adapter, "tokenExpiry", Instant.now().plusSeconds(3600));
        assertThat(adapter.fetchMinuteEvidence("ABCDEF", now, Long.MAX_VALUE).status()).isEqualTo("INVALID_INPUT");
        assertThat(adapter.fetchMinuteEvidence("005930", now, System.nanoTime() - 1).status()).isEqualTo("BUDGET_EXCEEDED");
        verifyNoInteractions(http);
        when(http.exchange(anyString(), any(), any(), eq(String.class))).thenReturn(ResponseEntity.ok("{\"output2\":[]}"));
        assertThat(adapter.fetchMinuteEvidence("005930", now, System.nanoTime() + 2_000_000_000L).status()).isEqualTo("PROVIDER_ERROR");
    }
}
