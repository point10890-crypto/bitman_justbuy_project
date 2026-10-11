package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KisSupplementalEvidenceServiceTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Instant now = Instant.parse("2026-10-12T01:00:00Z");
    KisSourceGateway gateway;
    KisSupplementalEvidenceService service;
    @BeforeEach void setup() {
        gateway = mock(KisSourceGateway.class);
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.fetch(anyString(), anyString(), anyMap(), anyLong()))
            .thenReturn(new KisSourceGateway.Snapshot("UNAVAILABLE", now, mapper.createObjectNode()));
        service = new KisSupplementalEvidenceService(gateway, mapper, Clock.fixed(now, ZoneOffset.UTC));
    }
    ObjectNode body(String output, String... fields) {
        ObjectNode root=mapper.createObjectNode().put("rt_cd", "0");
        ObjectNode row=root.putArray(output).addObject();
        for(int i=0;i<fields.length;i+=2) row.put(fields[i],fields[i+1]);
        return root;
    }
    void stub(String endpoint,ObjectNode value) {
        when(gateway.fetch(endsWith(endpoint),anyString(),anyMap(),anyLong()))
            .thenReturn(new KisSourceGateway.Snapshot("AVAILABLE",now,value));
    }
    @SuppressWarnings("unchecked") Map<String,Object> facts(String name) {
        Map<String,Object> result=service.fetchEvidence(List.of("005930"));
        Map<String,Object> candidates=(Map<String,Object>)result.get("candidates");
        return (Map<String,Object>)((Map<String,Object>)candidates.get("005930")).get(name);
    }
    @Test void rightsGateDoesNotCallAnyEndpoint() {
        when(gateway.isEnabled()).thenReturn(false);
        assertThat(service.fetchEvidence(List.of("005930")).get("status")).isEqualTo("DISABLED");
        verify(gateway,never()).fetch(anyString(),anyString(),anyMap(),anyLong());
    }
    @Test void exposesAllNineContractsWithAtMostTwoValidUniqueCandidates() {
        Map<String,Object> result=service.fetchEvidence(List.of("005930","000660","035420","invalid","005930"));
        assertThat((Map<?,?>)result.get("candidates")).hasSize(2);
        assertThat(result.get("registeredSources")).isEqualTo(9);
        assertThat(result.get("status")).isEqualTo("UNAVAILABLE");
        verify(gateway).fetch(endsWith("growth-ratio"),eq("FHKST66430800"),argThat(p->"1".equals(p.get("fid_div_cls_code"))&&"005930".equals(p.get("fid_input_iscd"))),anyLong());
        verify(gateway,times(2)).fetch(endsWith("investor-trade-by-stock-daily"),eq("FHPTJ04160001"),argThat(p->"".equals(p.get("FID_ORG_ADJ_PRC"))&&"".equals(p.get("FID_ETC_CLS_CODE"))),anyLong());
        verify(gateway,times(2)).fetch(endsWith("program-trade-by-stock-daily"),eq("FHPPG04650201"),anyMap(),anyLong());
        verify(gateway).fetch(endsWith("chk-holiday"),eq("CTCA0903R"),anyMap(),anyLong());
    }
    @Test void financePreservesPeriodAndOnlyWhitelistedActualNumbers() {
        stub("growth-ratio",body("output","stac_yymm","202606","grs","-3.4","bsop_prfi_inrt","","secret","123"));
        Map<String,Object> facts=facts("growthRatio");
        assertThat(facts.get("status")).isEqualTo("AVAILABLE");
        assertThat(facts.get("reportingPeriod")).isEqualTo("2026-06");
        assertThat(facts.get("periodType")).isEqualTo("QUARTERLY");
        assertThat(facts.get("providerUnit")).isEqualTo("UNCONFIRMED");
        Map<?,?> fields=(Map<?,?>)facts.get("rawFields");
        assertThat(fields.containsKey("grs")).isTrue();
        assertThat(fields.containsKey("secret")).isFalse();
        assertThat(fields.containsKey("bsop_prfi_inrt")).isFalse();
    }
    @Test void quarterIncomeStatementIsCumulativeYtdNotStandaloneQuarterProfit() {
        stub("income-statement",body("output","stac_yymm","202606","thtr_ntin","100"));
        assertThat(facts("incomeStatement").get("periodBasis")).isEqualTo("YEAR_TO_DATE_CUMULATIVE");
    }
    @Test void futureAndDuplicateFinancialPeriodsAreRejected() {
        ObjectNode value=body("output","stac_yymm","202612","grs","20");
        stub("growth-ratio",value);
        assertThat(facts("growthRatio").get("status")).isEqualTo("INVALID_DATA");
        service=new KisSupplementalEvidenceService(gateway,mapper,Clock.fixed(now,ZoneOffset.UTC));
        value=body("output","stac_yymm","202606","grs","20");
        value.withArray("output").add(value.path("output").get(0).deepCopy());
        stub("growth-ratio",value);
        assertThat(facts("growthRatio").get("status")).isEqualTo("INVALID_DATA");
    }
    @Test void dailyInvestorFlowPreservesSignedSharesAndDateWithoutInventedTimestamp() {
        stub("investor-trade-by-stock-daily",body("output2","stck_bsop_date","20261008","frgn_ntby_qty","-200","orgn_ntby_qty","0","frgn_ntby_tr_pbmn","999"));
        Map<String,Object> facts=facts("investorDaily");
        assertThat(facts.get("status")).isEqualTo("AVAILABLE");
        assertThat(facts.get("observedDate")).isEqualTo("2026-10-08");
        assertThat(facts.get("observationTimeBasis")).isEqualTo("DATE_ONLY");
        assertThat(facts).doesNotContainKeys("observedAt","score","doubleBuy");
        assertThat(((Map<?,?>)facts.get("rawFields")).get("frgn_ntby_qty").toString()).isEqualTo("-200");
        assertThat(facts.get("amountUnit")).isEqualTo("UNCONFIRMED");
    }
    @Test void creditBalanceSeparatesTradeDateAndSettlementDate() {
        stub("daily-credit-balance",body("output","deal_date","20261008","stlm_date","20261012","whol_loan_rmnd_stcn","0"));
        Map<String,Object> facts=facts("creditBalance");
        assertThat(facts.get("tradeDate")).isEqualTo("2026-10-08");
        assertThat(facts.get("settlementDate")).isEqualTo("2026-10-12");
        assertThat(((Map<?,?>)facts.get("rawFields")).get("whol_loan_rmnd_stcn").toString()).isEqualTo("0");
    }
    @Test void calendarPreservesDifferentOpenTradingBusinessAndSettlementFlagsAndCachesOneCallDaily() {
        stub("chk-holiday",body("output","bass_dt","20261012","wday_dvsn_cd","02","bzdy_yn","Y","tr_day_yn","N","opnd_yn","N","sttl_day_yn","Y"));
        Map<String,Object> result=service.fetchEvidence(List.of("005930"));
        assertThat(((Map<?,?>)result.get("calendar")).get("status")).isEqualTo("AVAILABLE");
        service.fetchEvidence(List.of("000660"));
        verify(gateway,times(1)).fetch(endsWith("chk-holiday"),anyString(),anyMap(),anyLong());
    }
    @Test void failedCalendarRequestIsAlsoSuppressedForTheRestOfTheDay() {
        service.fetchEvidence(List.of("005930")); service.fetchEvidence(List.of("000660"));
        verify(gateway,times(1)).fetch(endsWith("chk-holiday"),anyString(),anyMap(),anyLong());
    }
    @Test void invalidDailyDatesAndMissingNumbersCannotBecomeConfirmedZeroFlow() {
        stub("program-trade-by-stock-daily",body("output","stck_bsop_date","20261013","whol_smtn_ntby_qty","0"));
        stub("investor-trade-by-stock-daily",body("output2","stck_bsop_date","20261008","frgn_ntby_qty",""));
        assertThat(facts("programDaily").get("status")).isEqualTo("INVALID_DATA");
        assertThat(facts("investorDaily").get("status")).isEqualTo("NO_VALID_FIELDS");
    }
    @Test void successfulCacheKeepsOriginalFetchedTimeAndUndatedSectorContextIsNotCountedAsConfirmed() {
        stub("stability-ratio",body("output","stac_yymm","202606","lblt_rate","70"));
        stub("inquire-index-category-price",body("output2","bstp_cls_code","0001","hts_kor_isnm","코스피","bstp_nmix_prpr","3000"));
        Map<String,Object> first=service.fetchEvidence(List.of("005930"));
        Map<String,Object> second=service.fetchEvidence(List.of("005930"));
        assertThat(first.get("availableSources")).isEqualTo(1);
        assertThat(second.get("availableSources")).isEqualTo(1);
        verify(gateway,times(1)).fetch(endsWith("stability-ratio"),anyString(),anyMap(),anyLong());
        assertThat(facts("stabilityRatio").get("fetchedAt")).isEqualTo(now.toString());
    }
    @Test void responseFetchedAfterRequestStartIsComparedWithResponseReceiptTime() {
        class MutableClock extends Clock {
            Instant value=now;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return Clock.fixed(value,zone); }
            public Instant instant() { return value; }
        }
        MutableClock progressing=new MutableClock();
        service=new KisSupplementalEvidenceService(gateway,mapper,progressing);
        when(gateway.fetch(endsWith("growth-ratio"),anyString(),anyMap(),anyLong())).thenAnswer(call->{
            progressing.value=now.plusSeconds(1);
            return new KisSourceGateway.Snapshot("AVAILABLE",progressing.value,body("output","stac_yymm","202606","grs","4"));
        });
        Map<String,Object> facts=facts("growthRatio");
        assertThat(facts.get("status")).isEqualTo("AVAILABLE");
        assertThat(facts.get("fetchedAt")).isEqualTo(now.plusSeconds(1).toString());
    }
    @Test void availableSourceCountCountsFamiliesRatherThanInflatingForEachCandidate() {
        stub("growth-ratio",body("output","stac_yymm","202606","grs","4"));
        Map<String,Object> result=service.fetchEvidence(List.of("005930","000660"));
        assertThat(result.get("availableSources")).isEqualTo(1);
        assertThat(result.get("availableObservations")).isEqualTo(2);
    }
}
