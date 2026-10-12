package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KeywordDualSourceSearchTest {
    final KeywordSearchDataService data=mock(KeywordSearchDataService.class);
    final KeywordConditionSearchService service=new KeywordConditionSearchService(new KeywordSearchQueryParser(),data);
    final Instant now=Instant.now().minusSeconds(2);
    final String code="005930";
    final LocalDate end=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")));
    KeywordSearchDataService.Quote quote(String c) {return new KeywordSearchDataService.Quote(c,"삼성전자","KOSPI",new BigDecimal("254000"),BigDecimal.ONE,new BigDecimal("10000"),new BigDecimal("2540000000"),"NAVER_FINANCE",now,now);}
    void primary(String... codes) {when(data.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("COMPLETE",Arrays.stream(codes).map(this::quote).toList(),now,"검증된 후보",List.of()));}
    void secondary(Map<String,Object> facts) {
        when(data.kiwoomReadiness()).thenReturn(Map.of("enabled",true,"source","KIWOOM_REST_MOCK","environment","MOCK","status","READY","candidateLimit",20));
        when(data.kiwoomEvidence(anyList())).thenReturn(Map.of("status","PARTIAL","source","KIWOOM_REST_MOCK","environment","MOCK","fetchedAt",now.toString(),"candidates",Map.of(code,facts)));
    }
    Map<String,Object> group(String part) {
        var v=new LinkedHashMap<String,Object>();v.put("status","AVAILABLE");v.put("source","KIWOOM_REST_MOCK");v.put("environment","MOCK");v.put("fetchedAt",now.toString());v.put("requestedStockCode",code);
        if(part.equals("technical")){v.put("observedDate",end.toString());v.put("coverageStart",end.minusDays(90).toString());v.put("coverageEnd",end.toString());v.put("maAligned",true);v.put("volumeRatio20",new BigDecimal("2"));}
        if(part.equals("valuation"))v.put("per",new BigDecimal("12"));
        if(part.equals("quote")){v.put("stockCode",code);v.put("stockName","삼성전자");v.put("market","UNKNOWN");v.put("currentPrice",new BigDecimal("253000"));v.put("volume",new BigDecimal("9000"));}
        return v;
    }
    Map<String,Object> investors(int days) {
        var v=group("investorDaily");v.put("quantityUnit","shares");v.put("flowBasis","DAILY_NET_BUY_PREVIOUS_TRADING_DAYS");
        var rows=new ArrayList<Map<String,Object>>();LocalDate date=end;
        for(int i=0;i<days;i++){rows.add(Map.of("observedDate",date.toString(),"foreignNetBuyShares",100L*(i+1),"institutionNetBuyShares",200L*(i+1)));date=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(date);}
        v.put("observations",rows);return v;
    }
    @Test void mockTechnicalAndValuationParticipateWhenKisUnavailableWithExactSourceProvenance() {
        primary(code);secondary(Map.of("technical",group("technical"),"valuation",group("valuation")));
        var result=service.search("PER 15 이하 이평선 정배열 거래량 20일 평균 대비 1.5배 이상");
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().evidence()).allSatisfy(e->{assertThat(e.source()).isEqualTo("KIWOOM_REST_MOCK");assertThat(e.environment()).isEqualTo("MOCK");assertThat(e.fetchedAt()).isEqualTo(now);});
        assertThat(result.providers()).anyMatch(p->p.source().equals("KIWOOM_REST_MOCK")&&p.environment().equals("MOCK"));
    }
    @Test void crossCheckedQuoteCannotOverwriteOrMixPrimaryQuoteFields() {
        primary(code);secondary(Map.of("quote",group("quote")));
        when(data.kisQuotes(anyList())).thenReturn(Map.of(code,new KeywordSearchDataService.Quote(code,"삼성전자","UNKNOWN",
            new BigDecimal("252000"),new BigDecimal("2"),new BigDecimal("8000"),new BigDecimal("2016000000"),"KIS OpenAPI",now,null)));
        var match=service.search("삼성전자").matches().getFirst();
        assertThat(match.currentPrice()).isEqualByComparingTo("254000");assertThat(match.volume()).isEqualByComparingTo("10000");
        assertThat(match.sourceObservations()).anyMatch(o->o.source().equals("KIS OpenAPI")&&o.currentPrice().compareTo(new BigDecimal("252000"))==0&&o.observedAt()==null);
        assertThat(match.sourceObservations()).anyMatch(o->o.source().equals("KIWOOM_REST_MOCK")&&o.currentPrice().compareTo(new BigDecimal("253000"))==0);
        assertThat(match.sourceObservations()).hasSize(3);
    }
    @Test void secondaryThreeDayFlowUsesExactlyThreeObservationsAndDoesNotAddKisValues() {
        primary(code);secondary(Map.of("investorDaily",investors(5)));
        var result=service.search("최근 3거래일 외국인 기관 순매수");
        assertThat(result.matches()).hasSize(1);assertThat(result.matches().getFirst().evidence()).anyMatch(e->e.observedValue().equals("600")&&e.source().equals("KIWOOM_REST_MOCK"));
        when(data.flows(anyList(),eq(3))).thenReturn(Map.of(code,new KeywordSearchDataService.Flow(code,10,20,3,end.minusDays(5).toString(),end.toString(),now)));
        var preserved=service.search("최근 3거래일 외국인 기관 순매수");
        assertThat(preserved.matches().getFirst().evidence()).anyMatch(e->e.observedValue().equals("10")&&e.source().equals("KIS OpenAPI"));
    }
    @Test void missingAndPreviousDayFlowCannotSatisfyLongerOrTodayConditions() {
        primary(code);secondary(Map.of("investorDaily",investors(2)));
        assertThat(service.search("최근 3거래일 외국인 기관 순매수").matches()).isEmpty();
        secondary(Map.of("investorDaily",investors(5)));
        assertThat(service.search("오늘 쌍끌이 순매수").matches()).isEmpty();
        assertThat(service.search("최근 6거래일 외국인 기관 순매수").matches()).isEmpty();
    }
    @Test void oneProviderFailurePreservesPrimaryAndMarksPartial() {
        primary(code);secondary(Map.of());when(data.kiwoomEvidence(anyList())).thenThrow(new IllegalStateException("provider failure"));
        var result=service.search("삼성전자");assertThat(result.matches()).hasSize(1);assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.providers()).anyMatch(p->p.source().equals("KIWOOM_REST_MOCK")&&p.status().equals("UNAVAILABLE"));
    }
    @Test void crossCheckExpandsToTwentyVerifiedCandidatesWithoutRequestingTheTwentyFirst() {
        primary(java.util.stream.IntStream.rangeClosed(1,25).mapToObj(i->String.format("%06d",i)).toArray(String[]::new));
        secondary(Map.of("quote",group("quote")));
        var result=service.search("거래량 상위 20");verify(data).kiwoomEvidence(argThat(codes->codes.size()==20));
        verify(data).kisQuotes(argThat(codes->codes.size()==20));assertThat(result.scope().label()).contains("20종목");
    }
    @Test void wrongCodeAndStaleResearchCannotSatisfyACondition() {
        primary(code);var wrong=group("valuation");wrong.put("requestedStockCode","000660");secondary(Map.of("valuation",wrong));
        var rejected=service.search("PER 15 이하");assertThat(rejected.matches()).isEmpty();
        assertThat(rejected.providers()).anyMatch(p->p.source().equals("KIWOOM_REST_MOCK")&&p.status().equals("UNAVAILABLE"));
        var stale=group("valuation");stale.put("fetchedAt",now.minusSeconds(120).toString());secondary(Map.of("valuation",stale));
        var staleResult=service.search("PER 15 이하");assertThat(staleResult.matches()).isEmpty();
        assertThat(staleResult.providers()).anyMatch(p->p.source().equals("KIWOOM_REST_MOCK")&&p.status().equals("UNAVAILABLE"));
    }
    @Test void knownMarketMismatchCannotBeCrossCheckedAsTheSamePrimaryMarket() {
        primary(code);var cross=group("quote");cross.put("market","KOSDAQ");secondary(Map.of("quote",cross));
        assertThat(service.search("삼성전자").matches().getFirst().sourceObservations()).hasSize(1);
    }
    @Test void fabricatedKisCrossSourceCannotBeDisplayedAsKisParticipation() {
        primary(code);secondary(Map.of("quote",group("quote")));
        when(data.kisQuotes(anyList())).thenReturn(Map.of(code,new KeywordSearchDataService.Quote(code,"다른종목","UNKNOWN",
            new BigDecimal("252000"),BigDecimal.ONE,BigDecimal.TEN,BigDecimal.TEN,"KIS OpenAPI",now,null)));
        var result=service.search("삼성전자");assertThat(result.matches().getFirst().sourceObservations()).hasSize(2);
        assertThat(result.providers()).anyMatch(p->p.source().equals("KIS OpenAPI")&&p.status().equals("UNAVAILABLE"));
    }
    @Test void unknownSourceCannotBePublishedAsAnActualMarketObservation() {
        var original=quote(code);
        when(data.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("COMPLETE",List.of(new KeywordSearchDataService.Quote(
            code,original.name(),original.market(),original.price(),original.changePct(),original.volume(),original.tradingValueKrw(),"UNKNOWN",now,now)),now,"검증 범위",List.of()));
        var result=service.search("삼성전자");assertThat(result.matches()).isEmpty();assertThat(result.outcome()).isEqualTo("UNAVAILABLE");
    }
    @Test void completedDailyFlowPreservesActualCachedCaptureWithinItsTenMinuteTtl() {
        primary(code);var cached=investors(5);Instant captured=now.minusSeconds(350);cached.put("fetchedAt",captured.toString());secondary(Map.of("investorDaily",cached));
        var result=service.search("최근 3거래일 외국인 기관 순매수");assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().evidence()).allSatisfy(e->assertThat(e.fetchedAt()).isEqualTo(captured));
    }
    @Test void kisQuoteWithoutProvidedNameUsesVerifiedPrimaryCodeWithoutInventingAName() {
        primary(code);secondary(Map.of("quote",group("quote")));
        when(data.kisQuotes(anyList())).thenReturn(Map.of(code,new KeywordSearchDataService.Quote(code,"","UNKNOWN",
            new BigDecimal("252500"),new BigDecimal("-3.63"),new BigDecimal("10096840"),new BigDecimal("2590501982500"),"KIS OpenAPI",now,null)));
        var result=service.search("삼성전자");var match=result.matches().getFirst();
        assertThat(match.name()).isEqualTo("삼성전자");assertThat(match.currentPrice()).isEqualByComparingTo("254000");
        assertThat(match.sourceObservations()).anyMatch(o->o.source().equals("KIS OpenAPI")&&o.currentPrice().compareTo(new BigDecimal("252500"))==0);
        assertThat(result.providers()).anyMatch(p->p.source().equals("KIS OpenAPI")&&p.status().equals("AVAILABLE")&&p.scope().contains("종목명 미제공"));
    }
    @Test void kiwoomObservationAllowsOnlyTheSameFiveSecondClockToleranceAsTheAdapter() {
        primary(code);var cross=group("quote");cross.put("observedAt",now.plusSeconds(3).toString());secondary(Map.of("quote",cross));
        var match=service.search("삼성전자").matches().getFirst();
        assertThat(match.sourceObservations()).anyMatch(o->o.source().equals("KIWOOM_REST_MOCK")&&o.observedAt().equals(now.plusSeconds(3)));
        cross.put("observedAt",now.plusSeconds(6).toString());secondary(Map.of("quote",cross));
        assertThat(service.search("삼성전자").matches().getFirst().sourceObservations()).hasSize(1);
    }
    @Test void pendingFactsAreNotFailedRequestsOrMadeUpConditionMatches() {
        primary(code);secondary(Map.of());
        when(data.kiwoomEvidence(anyList())).thenReturn(Map.of("status","PENDING","source","KIWOOM_REST_MOCK","environment","MOCK",
            "fetchedAt",now.toString(),"pendingCodes",1,"candidates",Map.of(code,Map.of("valuation",Map.of("status","PENDING")))));
        var result=service.search("PER 15 이하");assertThat(result.matches()).isEmpty();assertThat(result.outcome()).isEqualTo("PARTIAL");
        var provider=result.providers().stream().filter(p->p.source().equals("KIWOOM_REST_MOCK")).findFirst().orElseThrow();
        assertThat(provider.status()).isEqualTo("PENDING");assertThat(provider.requestedCount()).isEqualTo(1);
        assertThat(provider.availableCount()).isZero();assertThat(provider.pendingCount()).isEqualTo(1);
        assertThat(result.warnings()).anyMatch(w->w.contains("순차 확인"));
    }
    @Test void aVerifiedKiwoomResearchMatchBeyondTheKisFiveCandidateSubsetIsPreserved() {
        primary(java.util.stream.IntStream.rangeClosed(1,20).mapToObj(i->String.format("%06d",i)).toArray(String[]::new));
        secondary(Map.of());var value=group("valuation");value.put("requestedStockCode","000018");
        when(data.kiwoomEvidence(anyList())).thenReturn(Map.of("status","PARTIAL","source","KIWOOM_REST_MOCK","environment","MOCK",
            "fetchedAt",now.toString(),"candidates",Map.of("000018",Map.of("valuation",value))));
        var research=mock(KisResearchEvidenceService.class);service.configureResearchEvidence(research);
        var result=service.search("PER 15 이하");assertThat(result.matches()).extracting(m->m.code()).containsExactly("000018");
        verify(research).fetchEvidence(argThat(codes->codes.size()==5));
        assertThat(result.providers()).anyMatch(p->p.source().equals("KIWOOM_REST_MOCK")&&p.requestedCount()==20&&p.availableCount()==1);
    }
}
