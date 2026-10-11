package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KeywordConditionSearchServiceTest {
    @Test void verifiedResearchConditionsFilterActualQuoteCandidates() {
        var research=mock(KisResearchEvidenceService.class);
        service.configureResearchEvidence(research);
        snapshot(quote("005930","삼성전자","KOSPI","60000","4","20000000000"),
            quote("000660","SK하이닉스","KOSPI","40000","3","12000000000"));
        when(research.fetchEvidence(anyList())).thenReturn(new KisResearchEvidenceService.EvidenceBatch("PARTIAL",time,List.of(
            researchCandidate("005930",new BigDecimal("12"),true),researchCandidate("000660",new BigDecimal("25"),true))));
        var result=service.search("PER 15배 이하 이평선 정배열");
        assertThat(result.matches()).extracting(m -> m.code()).containsExactly("005930");
        assertThat(result.matches().getFirst().evidence()).anyMatch(e -> e.source().equals("KIS OpenAPI") && e.observedValue().equals("12"));
    }

    @Test void disabledOrMissingResearchEvidenceIsUnavailableAndNeverAFalseEmptySearch() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","4","20000000000"));
        assertThat(service.search("PER 15배 이하").outcome()).isEqualTo("UNAVAILABLE");
        var research=mock(KisResearchEvidenceService.class);service.configureResearchEvidence(research);
        when(research.fetchEvidence(anyList())).thenReturn(new KisResearchEvidenceService.EvidenceBatch("DISABLED",time,List.of()));
        var result=service.search("20일 돌파");
        assertThat(result.outcome()).isEqualTo("UNAVAILABLE");assertThat(result.matches()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("KIS") && w.contains("미확인"));
    }

    @Test void researchSubsetIsBoundedAndItsCoverageIsExplicit() {
        var research=mock(KisResearchEvidenceService.class);service.configureResearchEvidence(research);
        var quotes=new KeywordSearchDataService.Quote[6];for(int i=0;i<6;i++)quotes[i]=quote(String.format("%06d",i+1),"실측종목"+i,"KOSPI","10000","1","10000000000");
        snapshot(quotes);
        when(research.fetchEvidence(anyList())).thenAnswer(call -> new KisResearchEvidenceService.EvidenceBatch("PARTIAL",time,
            ((List<String>)call.getArgument(0)).stream().map(code -> researchCandidate(code,new BigDecimal("10"),true)).toList()));
        var result=service.search("PER 15 이하");
        assertThat(result.matches()).hasSize(5);assertThat(result.scope().truncated()).isTrue();
        assertThat(result.scope().label()).contains("5종목");
        assertThat(result.warnings()).anyMatch(w -> w.contains("5종목"));
    }

    @Test void verifiedResearchNonMatchIsNormalEmptyAndNeverUsesEstimatedFlowAsAPoint() {
        var research=mock(KisResearchEvidenceService.class);service.configureResearchEvidence(research);
        snapshot(quote("005930","삼성전자","KOSPI","60000","4","20000000000"));
        when(research.fetchEvidence(anyList())).thenReturn(new KisResearchEvidenceService.EvidenceBatch("PARTIAL",time,
            List.of(researchCandidate("005930",new BigDecimal("25"),false))));
        assertThat(service.search("PER 15 이하").outcome()).isEqualTo("EMPTY");
        assertThat(service.search("이평선 정배열").matches()).isEmpty();
    }

    KisResearchEvidenceService.CandidateEvidence researchCandidate(String code,BigDecimal per,boolean aligned) {
        String date=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul"))).toString();
        Map<String,Object> technical=Map.of("status","AVAILABLE","source","KIS OpenAPI","fetchedAt",time.toString(),"observedDate",date,
            "coverageStart",LocalDate.parse(date).minusDays(80).toString(),"coverageEnd",date,"maAligned",aligned,"volumeRatio20",new BigDecimal("2"));
        Map<String,Object> valuation=Map.of("status","AVAILABLE","source","KIS OpenAPI","fetchedAt",time.toString(),"per",per);
        return new KisResearchEvidenceService.CandidateEvidence(code,technical,valuation,Map.of("status","UNAVAILABLE"),
            Map.of("status","ESTIMATE_UNDATED"),Map.of("status","UNAVAILABLE"),Map.of("status","UNAVAILABLE"));
    }
    final KeywordSearchDataService data = mock(KeywordSearchDataService.class);
    final KeywordSearchQueryParser parser = new KeywordSearchQueryParser();
    final KeywordConditionSearchService service = new KeywordConditionSearchService(parser, data);
    final Instant time = Instant.now().minusSeconds(20);
    KeywordSearchDataService.Quote quote(String code,String name,String market,String price,String rate,String value) {
        return new KeywordSearchDataService.Quote(code,name,market,new BigDecimal(price),new BigDecimal(rate),
            new BigDecimal("1000000"),value == null ? null : new BigDecimal(value),"Naver Finance",time,time);
    }
    void snapshot(KeywordSearchDataService.Quote... quotes) {
        when(data.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("COMPLETE",List.of(quotes),time,"코스피/코스닥 시가총액 상위 각 200개 (최대 400개)",List.of()));
    }
    @Test void intersectsAllNumericAndMarketConditionsBeforeRanking() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","4","20000000000"),
            quote("000660","SK하이닉스","KOSPI","40000","3","12000000000"),
            quote("035420","NAVER","KOSPI","40000","1","30000000000"),
            quote("247540","에코프로비엠","KOSDAQ","40000","3","15000000000"));
        var result=service.search("코스피 현재가 5만원 이하 상승률 3% 이상 거래대금 100억 이상");
        assertThat(result.matches()).extracting(m -> m.code()).containsExactly("000660");
        assertThat(result.matches().getFirst().evidence()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.scope().examinedCount()).isEqualTo(4);
        assertThat(result.scope().matchedCount()).isEqualTo(1);
    }
    @Test void validEmptySearchIsEmptyRatherThanCollectionFailure() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","4","20000000000"));
        assertThat(service.search("현재가 1만원 이하").outcome()).isEqualTo("EMPTY");
    }
    @Test void unavailableSourceNeverCreatesDemonstrationRows() {
        when(data.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("UNAVAILABLE",List.of(),time,"현재 시세",List.of("시세 조회 실패")));
        var result=service.search("거래대금 상위");
        assertThat(result.outcome()).isEqualTo("UNAVAILABLE");
        assertThat(result.matches()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }
    @Test void pagesWithOnlyInvalidSourceRowsAreUnavailableRatherThanNormalEmpty() {
        when(data.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("PARTIAL",List.of(),time,
            "검증된 시장 페이지",List.of("종목 식별이 확인되지 않은 행을 제외했습니다.")));
        assertThat(service.search("거래대금 상위").outcome()).isEqualTo("UNAVAILABLE");
    }
    @Test void directLookupPreservesOldObservationsAndWarnsInsteadOfImplyingFreshTrading() {
        var q=new KeywordSearchDataService.Quote("005930","삼성전자","KOSPI",new BigDecimal("60000"),
            BigDecimal.ONE,null,null,"NAVER_FINANCE",time,time.minusSeconds(8*86400));
        when(data.lookup("005930")).thenReturn(q);
        var result=service.search("005930");
        assertThat(result.matches().getFirst().observedAt()).isEqualTo(q.observedAt());
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.warnings()).anyMatch(w -> w.contains("7일"));
    }
    @Test void unsupportedConceptsAreNotSilentlyIgnoredOrPassedToAnAi() {
        var result=service.search("공시 호재 반도체 관련주");
        assertThat(result.outcome()).isEqualTo("UNSUPPORTED");
        assertThat(result.unsupportedTerms()).isNotEmpty();
        assertThat(result.matches()).isEmpty();
        verifyNoInteractions(data);
    }
    @Test void absentTradingValueIsUnknownInsteadOfEstimatedFromPriceAndVolume() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","4",null));
        var result=service.search("거래대금 100억 이상");
        assertThat(result.matches()).isEmpty();
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.warnings()).anyMatch(w -> w.contains("미확인"));
    }
    @Test void sortsAllMatchesBeforeApplyingTopLimit() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","1","10000000000"),
            quote("000660","SK하이닉스","KOSPI","40000","3","12000000000"),
            quote("035420","NAVER","KOSPI","40000","2","30000000000"));
        var result=service.search("거래대금 상위 2");
        assertThat(result.matches()).extracting(m -> m.code()).containsExactly("035420","000660");
        assertThat(result.scope().matchedCount()).isEqualTo(3);
        assertThat(result.scope().truncated()).isTrue();
    }
    @Test void requestedRankNeverIncludesRowsWithoutTheRankingMeasurement() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","1",null),
            quote("000660","SK하이닉스","KOSPI","40000","3","12000000000"));
        var result=service.search("거래대금 상위 2");
        assertThat(result.matches()).extracting(m -> m.code()).containsExactly("000660");
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.sourceStatus()).isEqualTo("PARTIAL");
    }
    @Test void unavailableRankMeasurementsCannotProduceACodeOrderedTopList() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","1",null));
        var result=service.search("거래대금 상위");
        assertThat(result.matches()).isEmpty();
        assertThat(result.outcome()).isEqualTo("UNAVAILABLE");
    }
    @Test void exactCodePriceLookupDoesNotRequireAnUnrequestedTurnoverMeasurement() {
        when(data.lookup("005930")).thenReturn(quote("005930","삼성전자","KOSPI","60000","1",null));
        assertThat(service.search("005930").matches()).extracting(m -> m.code()).containsExactly("005930");
    }
    @Test void noFlowMeasurementsIsUnavailableButVerifiedZeroNetBuyIsNormalEmpty() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","4","20000000000"));
        when(data.flows(anyList(),eq(3))).thenReturn(Map.of());
        var unavailable=service.search("최근 3거래일 외국인 기관 순매수");
        assertThat(unavailable.outcome()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.sourceStatus()).isEqualTo("UNAVAILABLE");
        String today=LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        String from=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(2).toString();
        when(data.flows(anyList(),eq(3))).thenReturn(Map.of("005930",
            new KeywordSearchDataService.Flow("005930",0,0,3,from,today,time)));
        assertThat(service.search("최근 3거래일 외국인 기관 순매수").outcome()).isEqualTo("EMPTY");
    }
    @Test void preservesOriginalQuoteAndObservationTimestamps() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","4","20000000000"));
        var match=service.search("삼성전자").matches().getFirst();
        assertThat(match.quoteCheckedAt()).isEqualTo(time);
        assertThat(match.observedAt()).isEqualTo(time);
        assertThat(match.quoteSource()).isEqualTo("Naver Finance");
    }
    @Test void directStockCodeUsesVerifiedLookupInsteadOfASeedOrAnInventedName() {
        var q=quote("005930","삼성전자","KOSPI","60000","4","20000000000");
        when(data.lookup("005930")).thenReturn(q);
        assertThat(service.search("005930 현재가 10만원 이하").matches()).extracting(m -> m.code()).containsExactly("005930");
        verify(data,never()).snapshot();
    }
    @Test void partialCollectionIsExplicitEvenWhenSomeStocksMatch() {
        var q=quote("005930","삼성전자","KOSPI","60000","4","20000000000");
        when(data.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("PARTIAL",List.of(q),time,"일부 페이지",List.of("코스닥 페이지 누락")));
        var result=service.search("상승 종목");
        assertThat(result.matches()).hasSize(1);
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.warnings()).contains("코스닥 페이지 누락");
    }
    @Test void rejectsInvalidIdentityOrQuoteProvenance() {
        snapshot(quote("ABCDEF","가짜","KOSPI","10000","5","30000000000"),
            quote("005930","삼성전자","KOSPI","0","5","30000000000"),
            new KeywordSearchDataService.Quote("000660","SK하이닉스","KOSPI",new BigDecimal("10000"),BigDecimal.ONE,
                BigDecimal.ONE,BigDecimal.ONE,"",time,time));
        assertThat(service.search("거래대금 상위").matches()).isEmpty();
    }
    @Test void flowConstraintsUseTheRequestedObservationPeriodAndVerifyAllRequiredValues() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","4","20000000000"),quote("000660","SK하이닉스","KOSPI","40000","3","12000000000"));
        String today=LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        String from=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(2).toString();
        when(data.flows(anyList(),eq(3))).thenReturn(Map.of(
            "005930",new KeywordSearchDataService.Flow("005930",100,200,3,from,today,time),
            "000660",new KeywordSearchDataService.Flow("000660",100,-50,3,from,today,time)));
        var result=service.search("최근 3거래일 외국인 기관 순매수");
        assertThat(result.matches()).extracting(m -> m.code()).containsExactly("005930");
        assertThat(result.matches().getFirst().evidence()).anyMatch(e -> e.observedValue().equals("200") && e.source().equals("KIS OpenAPI"));
    }
    @Test void todayFlowCannotReusePreviousBusinessDayOrAnIncompletePeriod() {
        snapshot(quote("005930","삼성전자","KOSPI","60000","4","20000000000"));
        String yesterday=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString();
        when(data.flows(anyList(),eq(1))).thenReturn(Map.of("005930",new KeywordSearchDataService.Flow("005930",100,200,1,yesterday,yesterday,time)));
        var result=service.search("오늘 쌍끌이 순매수");
        assertThat(result.matches()).isEmpty();
        assertThat(result.outcome()).isEqualTo("UNAVAILABLE");
    }
}
