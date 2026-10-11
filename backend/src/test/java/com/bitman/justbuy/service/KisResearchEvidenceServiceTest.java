package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KisResearchEvidenceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-10-12T01:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private KisSourceGateway gateway;
    private KisResearchEvidenceService service;

    @BeforeEach void setup() {
        gateway = mock(KisSourceGateway.class);
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.fetch(anyString(), anyString(), anyMap(), anyLong()))
            .thenReturn(new KisSourceGateway.Snapshot("UNAVAILABLE", now, mapper.createObjectNode()));
        service = new KisResearchEvidenceService(gateway, mapper, clock);
    }

    void stub(String endpoint, JsonNode body) {
        when(gateway.fetch(endsWith(endpoint), anyString(), anyMap(), anyLong()))
            .thenReturn(new KisSourceGateway.Snapshot("AVAILABLE", now, body));
    }

    ObjectNode daily(int count) {
        ObjectNode body = mapper.createObjectNode().put("rt_cd", "0");
        body.putObject("output1").put("stck_shrn_iscd", "005930");
        ArrayNode rows = body.putArray("output2");
        LocalDate date = LocalDate.of(2026, 10, 8);
        for (int i = 0; i < count; i++) {
            while (!KoreanMarketCalendar.isTradingDay(date)) date = date.minusDays(1);
            rows.addObject().put("stck_bsop_date", date.toString().replace("-", ""))
                .put("stck_oprc", "100").put("stck_hgpr", "120").put("stck_lwpr", "90")
                .put("stck_clpr", "110").put("acml_vol", "1000");
            date = date.minusDays(1);
        }
        return body;
    }

    Map<String,Object> technical() { return service.fetchEvidence(List.of("005930")).candidates().getFirst().technical(); }

    @Test void disabledFeatureDoesNoNetworkOrTokenWork() {
        when(gateway.isEnabled()).thenReturn(false);
        assertThat(service.fetchEvidence(List.of("005930")).status()).isEqualTo("DISABLED");
        assertThat(service.readiness().get("status")).isEqualTo("DISABLED");
        verify(gateway, never()).fetch(anyString(), anyString(), anyMap(), anyLong());
    }

    @Test void validSixtyDayCoverageComputesMovingAveragesAndNeverClaimsAnnualCoverage() {
        stub("inquire-daily-itemchartprice", daily(60));
        Map<String,Object> facts = technical();
        assertThat(facts.get("status")).isEqualTo("AVAILABLE");
        assertThat(facts.get("barCount")).isEqualTo(60);
        assertThat(facts.get("ma5").toString()).isEqualTo("110.00");
        assertThat(facts.get("ma20").toString()).isEqualTo("110.00");
        assertThat(facts.get("ma60").toString()).isEqualTo("110.00");
        assertThat(facts.get("averageVolume20").toString()).isEqualTo("1000.00");
        assertThat(facts.get("volumeRatio20").toString()).isEqualTo("1.0000");
        assertThat(facts.get("coverageEnd")).isEqualTo("2026-10-08");
        assertThat(facts).doesNotContainKeys("fiftyTwoWeekHigh", "annualHigh", "52주최고");
        verify(gateway).fetch(endsWith("inquire-daily-itemchartprice"), eq("FHKST03010100"),
            argThat(p -> "D".equals(p.get("FID_PERIOD_DIV_CODE")) && "0".equals(p.get("FID_ORG_ADJ_PRC"))), anyLong());
    }

    @Test void nineteenBarsCannotPretendToBeTwentyDayEvidence() {
        stub("inquire-daily-itemchartprice", daily(19));
        assertThat(technical().get("status")).isEqualTo("INSUFFICIENT_COVERAGE");
        assertThat(technical()).doesNotContainKeys("ma20", "ma60", "volumeRatio20");
    }

    @Test void twentyBarsDoNotInventSixtyDayMovingAverage() {
        stub("inquire-daily-itemchartprice", daily(20));
        assertThat(technical().get("status")).isEqualTo("PARTIAL_COVERAGE");
        assertThat(technical()).containsKeys("ma5", "ma20").doesNotContainKey("ma60");
    }

    @Test void duplicateDailyDatesFailClosed() {
        ObjectNode body = daily(60);
        ((ArrayNode)body.get("output2")).add(body.get("output2").get(0).deepCopy());
        stub("inquire-daily-itemchartprice", body);
        assertThat(technical().get("status")).isEqualTo("INVALID_RESPONSE");
    }

    @Test void futureDateCannotBecomeTechnicalEvidence() {
        ObjectNode body = daily(60);
        ((ObjectNode)body.get("output2").get(0)).put("stck_bsop_date", "20261013");
        stub("inquire-daily-itemchartprice", body);
        assertThat(technical().get("status")).isEqualTo("INVALID_RESPONSE");
    }

    @Test void wrongIdentityAndImpossibleOhlcAreRejected() {
        ObjectNode wrong = daily(60);
        ((ObjectNode)wrong.get("output1")).put("stck_shrn_iscd", "000660");
        stub("inquire-daily-itemchartprice", wrong);
        assertThat(technical().get("status")).isEqualTo("INVALID_IDENTITY");
        service = new KisResearchEvidenceService(gateway, mapper, clock);
        ObjectNode impossible = daily(60);
        ((ObjectNode)impossible.get("output2").get(0)).put("stck_clpr", "150");
        stub("inquire-daily-itemchartprice", impossible);
        assertThat(technical().get("status")).isEqualTo("INVALID_RESPONSE");
    }

    @Test void providerErrorWithPlausibleBarsCannotProduceMetrics() {
        ObjectNode body = daily(60).put("rt_cd", "1");
        stub("inquire-daily-itemchartprice", body);
        assertThat(technical().get("status")).isEqualTo("PROVIDER_ERROR");
    }

    @Test void missingVolumeIsUnknownRatherThanZero() {
        ObjectNode body = daily(60);
        ((ObjectNode)body.get("output2").get(0)).remove("acml_vol");
        stub("inquire-daily-itemchartprice", body);
        assertThat(technical().get("status")).isEqualTo("INVALID_RESPONSE");
    }

    @Test void valuationUsesProviderAnnualFieldsAndOmitsAbsentRatios() {
        stub("inquire-price", mapper.createObjectNode().put("rt_cd", "0")
            .set("output", mapper.createObjectNode().put("stck_prpr", "70000").put("stck_mxpr", "91000")
                .put("stck_llam", "49000").put("w52_hgpr", "98000").put("w52_lwpr", "42000").put("per", "")));
        Map<String,Object> facts = service.fetchEvidence(List.of("005930")).candidates().getFirst().valuation();
        assertThat(facts.get("high52").toString()).isEqualTo("98000");
        assertThat(facts.get("low52").toString()).isEqualTo("42000");
        assertThat(facts).doesNotContainKeys("per", "pbr");
    }

    @Test void financialPeriodAndUnitsRemainExplicitAndFuturePeriodsAreRejected() {
        ObjectNode body = mapper.createObjectNode().put("rt_cd", "0");
        body.putArray("output").addObject().put("stac_yymm", "202606").put("roe_val", "12.34")
            .put("eps", "1000").put("bps", "5000").put("lblt_rate", "50").put("grs", "");
        stub("financial-ratio", body);
        var financial = service.fetchEvidence(List.of("005930")).candidates().getFirst().financial();
        @SuppressWarnings("unchecked") Map<String,Object> quarterly = (Map<String,Object>)financial.get("quarterly");
        assertThat(quarterly.get("period")).isEqualTo("202606");
        assertThat(quarterly.get("periodType")).isEqualTo("QUARTERLY");
        assertThat(quarterly.get("providerUnit")).isEqualTo("UNCONFIRMED");
        assertThat(quarterly).containsKey("roe").doesNotContainKey("revenueGrowth");
        service = new KisResearchEvidenceService(gateway, mapper, clock);
        ((ObjectNode)body.get("output").get(0)).put("stac_yymm", "202612");
        stub("financial-ratio", body);
        financial = service.fetchEvidence(List.of("005930")).candidates().getFirst().financial();
        assertThat(financial.get("status")).isEqualTo("INVALID_RESPONSE");
    }

    @Test void intradayEstimateNeverClaimsConfirmedFlowOrAnInventedObservationDate() {
        ObjectNode body = mapper.createObjectNode().put("rt_cd", "0");
        body.putArray("output2").addObject().put("bsop_hour_gb", "1")
            .put("frgn_fake_ntby_qty", "100").put("orgn_fake_ntby_qty", "-20").put("sum_fake_ntby_qty", "80");
        stub("investor-trend-estimate", body);
        var facts = service.fetchEvidence(List.of("005930")).candidates().getFirst().investorEstimate();
        assertThat(facts.get("status")).isEqualTo("ESTIMATE_UNDATED");
        assertThat(facts.get("foreignEstimatedNetBuyShares")).isEqualTo(100L);
        assertThat(facts.get("institutionEstimatedNetBuyShares")).isEqualTo(-20L);
        assertThat(facts).doesNotContainKeys("observationDate", "confirmedNetBuy", "doubleBuy");
    }

    @Test void sourceCachePreservesOriginalFetchTimestamp() {
        when(gateway.fetch(endsWith("inquire-daily-itemchartprice"), anyString(), anyMap(), anyLong()))
            .thenReturn(new KisSourceGateway.Snapshot("AVAILABLE", now.minusSeconds(30), daily(60)));
        assertThat(technical().get("fetchedAt")).isEqualTo(now.minusSeconds(30).toString());
        assertThat(technical().get("fetchedAt")).isEqualTo(now.minusSeconds(30).toString());
        verify(gateway, times(1)).fetch(endsWith("inquire-daily-itemchartprice"), anyString(), anyMap(), anyLong());
    }

    @Test void invalidAndRepeatedCodesAreExcludedAndFiveCodeWorkLimitIsPreserved() {
        var result = service.fetchEvidence(List.of("bad", "005930", "005930", "000660", "035420", "035720", "005380", "051910"));
        assertThat(result.candidates()).extracting(KisResearchEvidenceService.CandidateEvidence::stockCode)
            .containsExactly("005930", "000660", "035420", "035720", "005380");
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
    }
}
