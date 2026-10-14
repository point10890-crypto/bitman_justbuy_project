package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KeywordFlowReadinessTest {
    static class MutableClock extends Clock {
        Instant value=Instant.now();
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        @Override public Instant instant(){return value;}
    }
    final KeywordSearchDataService data=mock(KeywordSearchDataService.class);
    final KeywordConditionSearchService service=new KeywordConditionSearchService(new KeywordSearchQueryParser(),data);
    final Instant now=Instant.now().minusSeconds(2);
    final String code="005930";
    final LocalDate end=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")));
    KeywordFlowReadinessTest() {
        var quote=new KeywordSearchDataService.Quote(code,"삼성전자","KOSPI",new BigDecimal("10000"),BigDecimal.ONE,
            new BigDecimal("1000"),new BigDecimal("10000000"),"NAVER_FINANCE",now,now);
        when(data.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("COMPLETE",List.of(quote),now,"고정 후보",List.of()));
        when(data.kiwoomReadiness()).thenReturn(Map.of("enabled",true,"source","KIWOOM_REST_MOCK","environment","MOCK","candidateLimit",20));
    }
    Map<String,Object> batch(Map<String,Object> facts) {
        return Map.of("status","PARTIAL","source","KIWOOM_REST_MOCK","environment","MOCK","fetchedAt",now.toString(),
            "pendingCodes",1,"candidates",Map.of(code,facts));
    }
    Map<String,Object> pendingInvestor() {return Map.of("status","PENDING","source","KIWOOM_REST_MOCK","environment","MOCK");}
    Map<String,Object> investor(int days) {
        var rows=new ArrayList<Map<String,Object>>();LocalDate cursor=end;
        for(int i=0;i<days;i++) {rows.add(Map.of("observedDate",cursor.toString(),"foreignNetBuyShares",100,"institutionNetBuyShares",50));
            cursor=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(cursor);}
        return Map.of("status","PARTIAL_COVERAGE","source","KIWOOM_REST_MOCK","environment","MOCK","fetchedAt",now.toString(),
            "requestedStockCode",code,"quantityUnit","shares","flowBasis","DAILY_NET_BUY_PREVIOUS_TRADING_DAYS","observations",rows);
    }
    @Test void unrelatedValuationPendingCannotClaimRequiredFlowIsStillCollecting() {
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(batch(Map.of("valuation",pendingInvestor(),
            "investorDaily",Map.of("status","PROVIDER_UNAVAILABLE"))));
        var result=service.search("최근 3거래일 외국인 순매수");
        assertThat(result.outcome()).isEqualTo("UNAVAILABLE");assertThat(result.matches()).isEmpty();
    }
    @Test void flowQueryDoesNotQueueUnrequestedValuationAndTechnicalFacts() {
        service.search("최근 3거래일 외국인 순매수",UUID.randomUUID());
        verify(data).kiwoomEvidence(List.of(code),List.of("quote","investorDaily"));
        verify(data,never()).kiwoomEvidence(anyList());
    }
    @Test void researchFiltersRequestTheirRequiredPartsWithoutFetchingOtherResearch() {
        service.search("최근 3거래일 외국인 순매수 PER 15 이하",UUID.randomUUID());
        verify(data).kiwoomEvidence(List.of(code),List.of("quote","investorDaily","valuation"));
        clearInvocations(data);service.search("이평선 정배열",UUID.randomUUID());
        verify(data).kiwoomEvidence(List.of(code),List.of("quote","technical"));
    }
    @Test void oneCompletedDayCannotWaitForThreeDaysBecauseOnlyQuoteIsPending() {
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(batch(Map.of("quote",pendingInvestor(),"investorDaily",investor(1))));
        var result=service.search("최근 3거래일 외국인 순매수");
        assertThat(result.outcome()).isEqualTo("UNAVAILABLE");assertThat(result.matches()).isEmpty();
    }
    @Test void requiredPendingBecomesConfirmedUsingOnlyTheSameCandidatesAndCachedDailyFacts() {
        UUID owner=UUID.randomUUID();
        var pending=batch(Map.of("investorDaily",pendingInvestor()));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(pending);
        when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(pending);
        var first=service.search("최근 3거래일 외국인 순매수",owner);
        assertThat(first.flowCoverage()).isEqualTo(new com.bitman.justbuy.dto.KeywordSearchResponse.FlowCoverage(1,0,1,0));
        assertThat(first.progress()).isNotNull();assertThat(first.matches()).isEmpty();
        assertThat(first.progress().retryAfterMs()).isEqualTo(10000);
        when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(batch(Map.of("investorDaily",investor(3))));
        clearInvocations(data);
        var ready=service.progress(owner,first.progress().id());
        assertThat(ready.matches()).hasSize(1);
        assertThat(ready.matches().getFirst().currentPrice()).isEqualByComparingTo("10000");
        assertThat(ready.fetchedAt()).isEqualTo(first.fetchedAt());assertThat(ready.scope().label()).isEqualTo(first.scope().label());
        assertThat(ready.flowCoverage()).isEqualTo(new com.bitman.justbuy.dto.KeywordSearchResponse.FlowCoverage(1,1,0,0));
        assertThat(ready.progress()).isNull();
        verify(data).cachedInvestorEvidence(List.of(code),end);verifyNoMoreInteractions(data);
    }
    @Test void primaryFlowAlreadyVerifiedDoesNotCountSecondaryPendingOrCreateProgress() {
        when(data.flows(anyList(),eq(3))).thenReturn(Map.of(code,new KeywordSearchDataService.Flow(code,100,50,3,end.minusDays(5).toString(),end.toString(),now)));
        var pending=batch(Map.of("investorDaily",pendingInvestor()));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(pending);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(pending);
        var result=service.search("최근 3거래일 외국인 순매수",UUID.randomUUID());
        assertThat(result.flowCoverage()).isEqualTo(new com.bitman.justbuy.dto.KeywordSearchResponse.FlowCoverage(1,1,0,0));
        assertThat(result.progress()).isNull();assertThat(result.matches()).hasSize(1);
    }
    @Test void initialSelectionRechecksCacheAfterTheForegroundKisStage() {
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(batch(Map.of("investorDaily",pendingInvestor())));
        when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(batch(Map.of("investorDaily",investor(3))));
        var result=service.search("최근 3거래일 외국인 순매수",UUID.randomUUID());
        assertThat(result.matches()).hasSize(1);assertThat(result.progress()).isNull();
        assertThat(result.flowCoverage()).isEqualTo(new com.bitman.justbuy.dto.KeywordSearchResponse.FlowCoverage(1,1,0,0));
    }
    @Test void completedInsufficientCoverageStopsPollingRatherThanInventingMissingDays() {
        UUID owner=UUID.randomUUID();var pending=batch(Map.of("investorDaily",pendingInvestor()));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(pending);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(pending);
        var first=service.search("최근 3거래일 외국인 순매수",owner);
        when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(batch(Map.of("investorDaily",investor(1))));
        var terminal=service.progress(owner,first.progress().id());
        assertThat(terminal.outcome()).isEqualTo("UNAVAILABLE");assertThat(terminal.matches()).isEmpty();assertThat(terminal.progress()).isNull();
        assertThat(terminal.flowCoverage()).isEqualTo(new com.bitman.justbuy.dto.KeywordSearchResponse.FlowCoverage(1,0,0,1));
    }
    @Test void failedOrExpiredQueueDemandStopsPollingWithoutFreshProviderWork() {
        UUID owner=UUID.randomUUID();var pending=batch(Map.of("investorDaily",pendingInvestor()));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(pending);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(pending);
        var first=service.search("최근 3거래일 외국인 순매수",owner);
        when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(batch(Map.of("investorDaily",Map.of("status","UNAVAILABLE"))));
        clearInvocations(data);var terminal=service.progress(owner,first.progress().id());
        assertThat(terminal.progress()).isNull();assertThat(terminal.outcome()).isEqualTo("UNAVAILABLE");
        verify(data).cachedInvestorEvidence(List.of(code),end);verifyNoMoreInteractions(data);
    }
    @Test void progressIsPrivateToTheAuthenticatedOwnerAndOpaqueIdsCannotBeGuessed() {
        UUID owner=UUID.randomUUID();var pending=batch(Map.of("investorDaily",pendingInvestor()));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(pending);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(pending);
        var first=service.search("최근 3거래일 외국인 순매수",owner);clearInvocations(data);
        assertThat(UUID.fromString(first.progress().id())).isNotNull();
        for(String id:List.of(first.progress().id(),"../PRIVATE-PROVIDER",UUID.randomUUID().toString()))
            assertThatThrownBy(()->service.progress(UUID.randomUUID(),id)).isInstanceOfSatisfying(com.bitman.justbuy.controller.ApiException.class,
                error->assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        verifyNoInteractions(data);
    }
    @Test void pendingSearchExpiresAfterTwoMinutesAndDoesNotReadProvidersOnExpiry() {
        var clock=new MutableClock();clock.value=Instant.now();
        var timed=new KeywordConditionSearchService(new KeywordSearchQueryParser(),data,clock);UUID owner=UUID.randomUUID();
        var pending=batch(Map.of("investorDaily",pendingInvestor()));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(pending);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(pending);
        var first=timed.search("최근 3거래일 외국인 순매수",owner);clock.value=first.progress().expiresAt();clearInvocations(data);
        assertThatThrownBy(()->timed.progress(owner,first.progress().id())).isInstanceOfSatisfying(com.bitman.justbuy.controller.ApiException.class,
            error->assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.GONE));
        verifyNoInteractions(data);
    }
    @Test void aNewCompletedSessionCannotReplaceTheFixedOriginalPeriodDuringProgress() {
        var clock=new MutableClock();clock.value=LocalDate.of(2026,10,14).atTime(23,59,30).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        var timed=new KeywordConditionSearchService(new KeywordSearchQueryParser(),data,clock);UUID owner=UUID.randomUUID();
        LocalDate fixed=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(LocalDate.of(2026,10,14));
        var pending=batch(Map.of("investorDaily",pendingInvestor()));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(pending);when(data.cachedInvestorEvidence(anyList(),eq(fixed))).thenReturn(pending);
        var first=timed.search("최근 3거래일 외국인 순매수",owner);clock.value=clock.value.plusSeconds(60);clearInvocations(data);
        assertThatThrownBy(()->timed.progress(owner,first.progress().id())).isInstanceOfSatisfying(com.bitman.justbuy.controller.ApiException.class,
            error->assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.GONE));
        verifyNoInteractions(data);
    }
    @Test void boundedPendingStoreEvictsOldestDemandAndNeverExpandsBeyondTwentyCandidates() {
        UUID owner=UUID.randomUUID();var pending=batch(Map.of("investorDaily",pendingInvestor()));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(pending);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(pending);
        var oldest=service.search("최근 3거래일 외국인 순매수",owner);
        com.bitman.justbuy.dto.KeywordSearchResponse newest=null;
        for(int i=0;i<256;i++)newest=service.search("최근 3거래일 외국인 순매수",owner);
        assertThatThrownBy(()->service.progress(owner,oldest.progress().id())).isInstanceOfSatisfying(com.bitman.justbuy.controller.ApiException.class,
            error->assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThat(service.progress(owner,newest.progress().id()).flowCoverage().requestedCount()).isEqualTo(1);
    }
    @Test void initialAndNewlyConfirmedKiwoomFactsSurviveCacheEvictionWithinTheFixedRun() {
        String other="000660";UUID owner=UUID.randomUUID();
        var original=data.snapshot().quotes().getFirst();
        var second=new KeywordSearchDataService.Quote(other,"SK하이닉스","KOSPI",original.price(),original.changePct(),original.volume(),original.tradingValueKrw(),original.source(),now,now);
        when(data.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("COMPLETE",List.of(original,second),now,"고정 후보",List.of()));
        var initial=Map.<String,Object>of("candidates",Map.of(code,Map.of("investorDaily",investor(3)),other,Map.of("investorDaily",pendingInvestor())));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(initial);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(initial);
        var first=service.search("최근 3거래일 외국인 순매수",owner);
        assertThat(first.matches()).extracting(match->match.code()).containsExactly(code);assertThat(first.progress()).isNotNull();
        var otherFact=new LinkedHashMap<>(investor(3));otherFact.put("requestedStockCode",other);
        when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(Map.of("candidates",Map.of(code,Map.of("investorDaily",Map.of("status","UNAVAILABLE")),other,Map.of("investorDaily",otherFact))));
        var complete=service.progress(owner,first.progress().id());
        assertThat(complete.matches()).extracting(match->match.code()).containsExactly(other,code);
        assertThat(complete.flowCoverage()).isEqualTo(new com.bitman.justbuy.dto.KeywordSearchResponse.FlowCoverage(2,2,0,0));
        assertThat(complete.progress()).isNull();
    }
    @Test void factsConfirmedByAnEarlierProgressPollAreAlsoPreserved() {
        String other="000660";UUID owner=UUID.randomUUID();var original=data.snapshot().quotes().getFirst();
        var second=new KeywordSearchDataService.Quote(other,"SK하이닉스","KOSPI",original.price(),original.changePct(),original.volume(),original.tradingValueKrw(),original.source(),now,now);
        when(data.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("COMPLETE",List.of(original,second),now,"고정 후보",List.of()));
        var initial=Map.<String,Object>of("candidates",Map.of(code,Map.of("investorDaily",pendingInvestor()),other,Map.of("investorDaily",pendingInvestor())));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(initial);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(initial);
        var first=service.search("최근 3거래일 외국인 순매수",owner);
        when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(Map.of("candidates",Map.of(code,Map.of("investorDaily",investor(3)),other,Map.of("investorDaily",pendingInvestor()))));
        var middle=service.progress(owner,first.progress().id());assertThat(middle.matches()).hasSize(1);assertThat(middle.progress()).isNotNull();
        var otherFact=new LinkedHashMap<>(investor(3));otherFact.put("requestedStockCode",other);
        when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(Map.of("candidates",Map.of(code,Map.of("investorDaily",Map.of("status","UNAVAILABLE")),other,Map.of("investorDaily",otherFact))));
        assertThat(service.progress(owner,first.progress().id()).flowCoverage()).isEqualTo(new com.bitman.justbuy.dto.KeywordSearchResponse.FlowCoverage(2,2,0,0));
    }
    @Test void fabricatedPendingSourceCannotProduceAFlowProgressHandle() {
        var untrusted=batch(Map.of("investorDaily",Map.of("status","PENDING","source","UNKNOWN","environment","REAL")));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(untrusted);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(untrusted);
        var first=service.search("최근 3거래일 외국인 순매수",UUID.randomUUID());
        assertThat(first.progress()).isNull();assertThat(first.flowCoverage().pendingCount()).isZero();assertThat(first.outcome()).isEqualTo("UNAVAILABLE");
    }
    @Test void aValidOriginalKiwoomObservationIsNotLostIfItsCacheWasEvictedDuringTheKisStage() {
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(batch(Map.of("investorDaily",investor(3))));
        when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(batch(Map.of("investorDaily",Map.of("status","UNAVAILABLE"))));
        var result=service.search("최근 3거래일 외국인 순매수",UUID.randomUUID());
        assertThat(result.matches()).hasSize(1);assertThat(result.flowCoverage()).isEqualTo(new com.bitman.justbuy.dto.KeywordSearchResponse.FlowCoverage(1,1,0,0));
    }
    @Test void initialFreshValuationRemainsValidAtItsFixedCutoffWhileFlowArrivesTwentySecondsLater() {
        Instant initial=Instant.now();var clock=new MutableClock();clock.value=initial;
        var timed=new KeywordConditionSearchService(new KeywordSearchQueryParser(),data,clock);UUID owner=UUID.randomUUID();String other="000660";
        var original=data.snapshot().quotes().getFirst();
        var second=new KeywordSearchDataService.Quote(other,"SK하이닉스","KOSPI",original.price(),original.changePct(),original.volume(),original.tradingValueKrw(),original.source(),now,now);
        when(data.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("COMPLETE",List.of(original,second),now,"고정 후보",List.of()));
        var valuation=Map.<String,Object>of("status","AVAILABLE","source","KIWOOM_REST_MOCK","environment","MOCK",
            "requestedStockCode",code,"fetchedAt",initial.minusSeconds(50).toString(),"per",new BigDecimal("12"));
        var secondValuation=new LinkedHashMap<>(valuation);secondValuation.put("requestedStockCode",other);
        var firstFacts=Map.<String,Object>of("candidates",Map.of(code,Map.of("valuation",valuation,"investorDaily",investor(3)),
            other,Map.of("valuation",secondValuation,"investorDaily",pendingInvestor())));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(firstFacts);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(firstFacts);
        try(var time=mockStatic(Instant.class,CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(initial);
            var first=timed.search("PER 15 이하 최근 3거래일 외국인 순매수",owner);
            assertThat(first.matches()).extracting(match->match.code()).containsExactly(code);assertThat(first.progress()).isNotNull();
            var laterFlow=new LinkedHashMap<>(investor(3));laterFlow.put("requestedStockCode",other);laterFlow.put("fetchedAt",initial.plusSeconds(20).toString());
            when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(Map.of("candidates",Map.of(other,Map.of("investorDaily",laterFlow))));
            clock.value=initial.plusSeconds(20);time.when(Instant::now).thenReturn(clock.value);
            var ready=timed.progress(owner,first.progress().id());
            assertThat(ready.matches()).extracting(match->match.code()).containsExactly(other,code);
            assertThat(ready.matches()).allSatisfy(match->assertThat(match.evidence()).anyMatch(evidence->evidence.condition().contains("PER")&&evidence.fetchedAt().equals(initial.minusSeconds(50))));
            assertThat(ready.progress()).isNull();assertThat(ready.flowCoverage().availableCount()).isEqualTo(2);
        }
    }
    @Test void initiallyStaleOrFutureResearchCannotBecomeValidOnlyBecauseProgressTimeAdvanced() {
        Instant initial=Instant.now();
        for(long offset:new long[]{-61,5}) {
            var clock=new MutableClock();clock.value=initial;UUID owner=UUID.randomUUID();
            var timed=new KeywordConditionSearchService(new KeywordSearchQueryParser(),data,clock);
            var valuation=Map.<String,Object>of("status","AVAILABLE","source","KIWOOM_REST_MOCK","environment","MOCK",
                "requestedStockCode",code,"fetchedAt",initial.plusSeconds(offset).toString(),"per",new BigDecimal("12"));
            var firstFacts=batch(Map.of("valuation",valuation,"investorDaily",pendingInvestor()));
            when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(firstFacts);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(firstFacts);
            try(var time=mockStatic(Instant.class,CALLS_REAL_METHODS)) {
                time.when(Instant::now).thenReturn(initial);
                var first=timed.search("PER 15 이하 최근 3거래일 외국인 순매수",owner);
                assertThat(first.matches()).isEmpty();assertThat(first.progress()).isNotNull();
                var laterFlow=new LinkedHashMap<>(investor(3));laterFlow.put("fetchedAt",initial.plusSeconds(20).toString());
                when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(batch(Map.of("investorDaily",laterFlow)));
                clock.value=initial.plusSeconds(20);time.when(Instant::now).thenReturn(clock.value);
                var ready=timed.progress(owner,first.progress().id());
                assertThat(ready.matches()).isEmpty();assertThat(ready.progress()).isNull();assertThat(ready.flowCoverage().availableCount()).isEqualTo(1);
            }
        }
    }
    @Test void completedFlowKeepsInitialCrossChecksAsHistoricalWarningsWithoutClaimingJobsStillRun() {
        UUID owner=UUID.randomUUID();var pending=batch(Map.of("investorDaily",pendingInvestor()));
        when(data.kisQuoteProgress(anyList())).thenReturn(Map.of("pendingCodes",1));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(pending);when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(pending);
        var first=service.search("최근 3거래일 외국인 순매수",owner);assertThat(first.progress()).isNotNull();
        when(data.cachedInvestorEvidence(anyList(),eq(end))).thenReturn(batch(Map.of("investorDaily",investor(3))));
        clearInvocations(data);var ready=service.progress(owner,first.progress().id());
        assertThat(ready.flowCoverage()).isEqualTo(new com.bitman.justbuy.dto.KeywordSearchResponse.FlowCoverage(1,1,0,0));
        assertThat(ready.progress()).isNull();assertThat(ready.matches()).hasSize(1);
        assertThat(ready.warnings()).noneMatch(warning->warning.contains("조회가 진행 중")||warning.contains("순차 확인하고 있습니다")||warning.contains("대기 중인 값을"));
        assertThat(first.warnings()).anyMatch(warning->warning.startsWith("최초 검색에서 KIS"));
        assertThat(first.warnings()).anyMatch(warning->warning.startsWith("최초 검색에서 키움 REST"));
        assertThat(ready.warnings()).anyMatch(warning->warning.startsWith("최초 검색에서 KIS"));
        assertThat(ready.warnings()).anyMatch(warning->warning.startsWith("최초 검색에서 키움 REST"));
        assertThat(ready.providers()).isEqualTo(first.providers());assertThat(ready.fetchedAt()).isEqualTo(first.fetchedAt());
        verify(data).cachedInvestorEvidence(List.of(code),end);verifyNoMoreInteractions(data);
    }
    @Test void nonFlowSearchRetainsItsOriginalCurrentCrossCheckWarningContract() {
        when(data.kisQuoteProgress(anyList())).thenReturn(Map.of("pendingCodes",1));
        when(data.kiwoomEvidence(anyList(),anyList())).thenReturn(batch(Map.of("quote",pendingInvestor())));
        var result=service.search("삼성전자",UUID.randomUUID());
        assertThat(result.warnings()).anyMatch(warning->warning.contains("KIS 주시세 추가 교차 조회가 진행 중"));
        assertThat(result.warnings()).anyMatch(warning->warning.contains("키움 REST 추가 근거를 순차 확인하고 있습니다"));
        assertThat(result.flowCoverage()).isNull();assertThat(result.progress()).isNull();assertThat(result.matches()).hasSize(1);
    }
}
