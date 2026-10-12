package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KiwoomConditionEnricherTest {
    private static final Instant NOW=Instant.parse("2026-10-12T01:00:00Z");
    private final KiwoomReadOnlyService adapter=mock(KiwoomReadOnlyService.class);
    private final KiwoomConditionEnricher enricher=new KiwoomConditionEnricher(adapter,Clock.fixed(NOW,ZoneOffset.UTC));
    private final Map<String,String> names=Map.of("005930","삼성전자");
    private Map<String,Object> fact(String part) {
        var fact=new LinkedHashMap<String,Object>();fact.put("status","AVAILABLE");fact.put("source","KIWOOM_REST_MOCK");
        fact.put("environment","MOCK");fact.put("requestedStockCode","005930");fact.put("fetchedAt",NOW.toString());
        if("quote".equals(part)) {fact.put("stockCode","005930");fact.put("stockName","삼성전자");fact.put("currentPrice",72000);}
        if("valuation".equals(part)) {fact.put("per",12);fact.put("pbr",1);fact.put("reportingPeriodStatus","UNKNOWN");}
        if("technical".equals(part)) {fact.put("observedDate","2026-10-08");fact.put("fromDate","2026-07-10");fact.put("bars",60);fact.put("ma20",71000);fact.put("ma60",70000);}
        if("investorDaily".equals(part)) {fact.put("observedDate","2026-10-08");fact.put("days",5);fact.put("foreignNetBuy5dShares",100);fact.put("institutionNetBuy5dShares",200);}
        return fact;
    }
    private void ready(Map<String,Object> groups) {
        when(adapter.isEnabled()).thenReturn(true);when(adapter.readiness()).thenReturn(Map.of("environment","MOCK"));
        when(adapter.fetchEvidence(anyList(),anyMap())).thenReturn(Map.of("source","KIWOOM_REST_MOCK","environment","MOCK","candidates",Map.of("005930",groups)));
    }
    @Test void bothProvidersCanBeUsedAndAllGroupsRequestedForTwentyDeterministicCandidates() {
        ready(Map.of("quote",fact("quote"),"valuation",fact("valuation"),"technical",fact("technical"),"investorDaily",fact("investorDaily")));
        var candidates=new LinkedHashMap<String,String>();candidates.put("invalid","가상");candidates.put("005930","삼성전자");
        for(int i=0;i<21;i++) candidates.put(String.format("%06d",100000+i),"검증후보"+i);
        var result=enricher.enrich(candidates);
        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<String>> codes=org.mockito.ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<Map<String,List<String>>> parts=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(adapter).fetchEvidence(codes.capture(),parts.capture());
        assertThat(codes.getValue()).hasSize(20).contains("005930","100018").doesNotContain("invalid","100019","100020");
        assertThat(parts.getValue().values()).allSatisfy(group->assertThat(group).containsExactly("quote","valuation","technical","investorDaily"));
        assertThat(result.coverage()).containsEntry("source","KIWOOM_REST_MOCK").containsEntry("environment","MOCK").containsEntry("status","PARTIAL");
        assertThat(result.facts()).hasSize(20).doesNotContainKey("invalid").doesNotContainKey("100019");
        assertThat(result.coverage()).containsEntry("candidateLimit",20).containsEntry("requestedCodes",20);
    }
    @Test void completeActualMockMarketFactsRetainIndependentSourceAndPeriods() {
        ready(Map.of("quote",fact("quote"),"valuation",fact("valuation"),"technical",fact("technical"),"investorDaily",fact("investorDaily")));
        var result=enricher.enrich(names);
        assertThat(result.coverage()).containsEntry("status","AVAILABLE").containsEntry("availableFacts",4).containsEntry("usableFacts",4);
        assertThat(result.evidence("005930")).anyMatch(line->line.contains("2026-10-08")&&line.contains("KIWOOM_REST_MOCK"))
            .anyMatch(line->line.contains("재무 기준 기간 미제공"));
        assertThat(result.prompt()).contains("가격을 평균하거나 수급을 합산하지 않는다","MOCK","72000");
    }
    @Test void wrongReturnedCodeCannotBeHiddenByCorrectRequestedCode() {
        var quote=fact("quote");quote.put("stockCode","000660");
        ready(Map.of("quote",quote));
        var result=enricher.enrich(names);
        var group=(Map<?,?>)result.forCode("005930").get("quote");
        assertThat(group.get("status")).isEqualTo("INVALID_DATA");assertThat(group.get("currentPrice")).isNull();
    }
    @Test void wrongNameSourceEnvironmentAndStaleOrFutureQuotesNeverBecomeEvidence() {
        for(var change:List.of(Map.entry("stockName","다른 회사"),Map.entry("source","KIWOOM_REST"),Map.entry("environment","REAL"),
            Map.entry("fetchedAt",NOW.plusSeconds(20).toString()),Map.entry("fetchedAt",NOW.minusSeconds(61).toString()))) {
            var quote=fact("quote");quote.put(change.getKey(),change.getValue());ready(Map.of("quote",quote));
            var result=enricher.enrich(names);
            assertThat(((Map<?,?>)result.forCode("005930").get("quote")).get("currentPrice")).isNull();
            assertThat(result.coverage()).containsEntry("status","UNAVAILABLE");
        }
    }
    @Test void partialPeriodsRemainPartialWithoutClaimingFiveDayOrSixtyBarCoverage() {
        var chart=fact("technical");chart.put("status","PARTIAL_COVERAGE");chart.put("bars",20);chart.remove("ma60");
        var flow=fact("investorDaily");flow.put("status","PARTIAL_COVERAGE");flow.put("days",2);flow.remove("foreignNetBuy5dShares");flow.remove("institutionNetBuy5dShares");
        ready(Map.of("technical",chart,"investorDaily",flow));var result=enricher.enrich(names);
        assertThat(result.coverage()).containsEntry("status","PARTIAL").containsEntry("availableFacts",0).containsEntry("usableFacts",2);
        assertThat(result.evidence("005930")).anyMatch(line->line.contains("20개")&&line.contains("부분 기간"))
            .anyMatch(line->line.contains("2일")&&line.contains("5일 수급 기간 부족"));
    }
    @Test void priorSessionOrUndatedChartAndFlowAreNotConfirmed() {
        for(String part:List.of("technical","investorDaily")) {
            var group=fact(part);group.put("observedDate","2026-10-07");ready(Map.of(part,group));
            assertThat(((Map<?,?>)enricher.enrich(names).forCode("005930").get(part)).get("status")).isEqualTo("STALE_DATA");
            group.remove("observedDate");ready(Map.of(part,group));
            assertThat(((Map<?,?>)enricher.enrich(names).forCode("005930").get(part)).get("status")).isEqualTo("STALE_DATA");
        }
    }
    @Test void attachingIndependentFactsDoesNotChangePrimaryPriceFlowOrScores() {
        ready(Map.of("quote",fact("quote"),"investorDaily",fact("investorDaily")));var result=enricher.enrich(names);
        ConditionSignalDto original=signal();var observed=result.attach(original);
        assertThat(observed.currentPrice()).isEqualTo("71,000");assertThat(observed.ruleScore()).isEqualTo(75);
        assertThat(observed.evidence()).contains("KIS 원자료 외국인 300주").anyMatch(line->line.contains("100주 / 기관 200주"));
        assertThat(observed.capturePrice()).isEqualTo(original.capturePrice());assertThat(observed.finalScore()).isEqualTo(original.finalScore());
    }
    @Test void providerFailureAndDisabledAdapterPreservePrimaryResults() {
        when(adapter.isEnabled()).thenReturn(false);assertThat(enricher.enrich(names).attach(signal()).currentPrice()).isEqualTo("71,000");
        verify(adapter,never()).fetchEvidence(anyList(),anyMap());
        when(adapter.isEnabled()).thenReturn(true);when(adapter.readiness()).thenReturn(Map.of("environment","MOCK"));
        when(adapter.fetchEvidence(anyList(),anyMap())).thenThrow(new IllegalStateException("private-provider-response"));
        var result=enricher.enrich(names);assertThat(result.facts()).isEmpty();assertThat(result.coverage()).containsEntry("status","UNAVAILABLE");
        assertThat(result.prompt()).doesNotContain("private-provider-response");
    }
    @Test void backgroundQueueMetadataPreservesPendingWithoutPromotingWaitingValuesToEvidence() {
        ready(Map.of());
        when(adapter.fetchEvidence(anyList(),anyMap())).thenReturn(Map.of("source","KIWOOM_REST_MOCK","environment","MOCK",
            "queueDepth",17,"queueStatus","COLLECTING","backgroundRefreshEnabled",true,
            "candidates",Map.of("005930",Map.of("quote",fact("quote"),"technical",Map.of("status","PENDING","ma20",999999),
                "valuation",Map.of("status","PENDING","per",999),"investorDaily",Map.of("status","PENDING","foreignNetBuy5dShares",99999)))));
        var result=enricher.enrich(names);
        assertThat(result.coverage()).containsEntry("pendingCodes",1).containsEntry("pendingFacts",3).containsEntry("queueDepth",17)
            .containsEntry("queueStatus","COLLECTING").containsEntry("backgroundRefreshEnabled",true).containsEntry("enrichedCodes",1).containsEntry("status","PARTIAL");
        assertThat(result.prompt()).contains("PENDING","배경 수집 대기").doesNotContain("999999","99999");
        assertThat(result.evidence("005930")).anyMatch(line->line.contains("수집 대기"));
    }
    @Test void invalidOrWrongProviderQueueMetadataIsNeverForwarded() {
        ready(Map.of());when(adapter.fetchEvidence(anyList(),anyMap())).thenReturn(Map.of("source","KIWOOM_REST","environment","REAL",
            "queueDepth",99999,"queueStatus","PRIVATE_PROVIDER_TEXT","backgroundRefreshEnabled",true,"candidates",Map.of()));
        var result=enricher.enrich(names);
        assertThat(result.coverage()).doesNotContainKey("queueDepth").doesNotContainKey("queueStatus").doesNotContainKey("backgroundRefreshEnabled");
        assertThat(result.coverage()).containsEntry("pendingCodes",0).containsEntry("pendingFacts",0);
    }
    private ConditionSignalDto signal() {return new ConditionSignalDto("short_term","BREAKOUT",1,"삼성전자","005930","70,000","71,000","-","-","1.4%","포착",75,0,75,"KIS 원자료",List.of("KIS 원자료 외국인 300주"),List.of(),"",NOW.toString());}
}
