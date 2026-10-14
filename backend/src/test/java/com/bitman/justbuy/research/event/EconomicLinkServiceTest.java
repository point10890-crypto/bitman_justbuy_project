package com.bitman.justbuy.research.event;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class EconomicLinkServiceTest {
    private static final Instant KNOWN=Instant.parse("2026-10-14T03:00:00Z");
    private static final Instant EARLIER=KNOWN.minusSeconds(86400);
    private final EconomicLinkService service=new EconomicLinkService();

    @Test void propagatesOfficialCustomerEventOnlyThroughPreviouslyKnownExactLegalName() {
        var links=service.relationships(List.of(contract("주식회사 고객기업",EARLIER,"ORIGINAL")),KNOWN.plusSeconds(20));
        var event=filing("(주) 고객기업","영업(잠정)실적(공정공시)",KNOWN.plusSeconds(10));
        var result=service.propagate(links,List.of(event),KNOWN.plusSeconds(20));
        assertThat(links).hasSize(1);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().link().supplierCode()).isEqualTo("123456");
        assertThat(result.getFirst().event()).isEqualTo(event);
        assertThat(result.getFirst().status()).isEqualTo("TRANSFER_UNQUANTIFIED");
        assertThat(result.getFirst().issues()).containsExactly("CUSTOMER_EVENT_ALLOCATION_UNKNOWN");
    }

    @Test void newlyDiscoveredRelationshipCannotPropagateEarlierOrSimultaneousCustomerEvent() {
        var links=service.relationships(List.of(contract("고객기업",KNOWN,"ORIGINAL")),KNOWN.plusSeconds(30));
        assertThat(service.propagate(links,List.of(filing("고객기업","단일판매ㆍ공급계약체결",KNOWN.minusSeconds(1)),filing("고객기업","신규시설투자등",KNOWN)),KNOWN.plusSeconds(30))).isEmpty();
    }

    @Test void futureRelationAndFutureCustomerEventNeverLeakBeforeCutoff() {
        assertThat(service.relationships(List.of(contract("고객기업",KNOWN.plusSeconds(1),"ORIGINAL")),KNOWN)).isEmpty();
        var links=service.relationships(List.of(contract("고객기업",KNOWN.minusSeconds(1),"ORIGINAL")),KNOWN);
        assertThat(service.propagate(links,List.of(filing("고객기업","신규시설투자등",KNOWN.plusSeconds(1))),KNOWN)).isEmpty();
    }

    @Test void anonymousCustomerAndWithdrawnContractCannotEstablishActiveLink() {
        var contracts=List.of(contract(null,KNOWN,"ORIGINAL"),contract("영업비밀로 미공개",KNOWN,"ORIGINAL"),
            contract("국내 주요 고객사",KNOWN,"ORIGINAL"),contract("고객기업",KNOWN,"WITHDRAWN"));
        assertThat(service.relationships(contracts,KNOWN.plusSeconds(1))).isEmpty();
    }

    @Test void similarBrandSubstringDoesNotMatchAnotherLegalEntity() {
        var links=service.relationships(List.of(contract("삼성전자 주식회사",EARLIER,"ORIGINAL")),KNOWN.plusSeconds(20));
        assertThat(service.propagate(links,List.of(filing("삼성전자서비스","영업(잠정)실적(공정공시)",KNOWN.plusSeconds(10)),
            filing("삼성","단일판매ㆍ공급계약체결",KNOWN.plusSeconds(10))),KNOWN.plusSeconds(20))).isEmpty();
    }

    @Test void unrelatedOfficialFilingDoesNotImplyCustomerDemandEvent() {
        var links=service.relationships(List.of(contract("고객기업",EARLIER,"ORIGINAL")),KNOWN.plusSeconds(20));
        assertThat(service.propagate(links,List.of(filing("고객기업","주주총회소집결의",KNOWN.plusSeconds(10)),
            filing("고객기업","임원ㆍ주요주주특정증권등소유상황보고서",KNOWN.plusSeconds(10))),KNOWN.plusSeconds(20))).isEmpty();
    }

    @Test void forgedSourceUrlAndMissingTimeDoNotCreatePropagation() {
        var link=new EconomicLinkService.Link("고객기업","123456","공급기업","반도체장비","20261014000001","https://evil.example/a",EARLIER);
        assertThat(service.propagate(List.of(link),List.of(filing("고객기업","신규시설투자등",KNOWN.plusSeconds(10))),KNOWN.plusSeconds(20))).isEmpty();
        var links=service.relationships(List.of(contract("고객기업",EARLIER,"ORIGINAL")),KNOWN.plusSeconds(20));
        var bad=new DartSupplyContractSource.Filing("654321","고객기업","20261014000002","신규시설투자등",LocalDate.of(2026,10,14),null,"https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261014000002");
        assertThat(service.propagate(links,List.of(bad),KNOWN.plusSeconds(20))).isEmpty();
    }

    @Test void repeatedEvidenceAndEventsProduceOneObservationWithOldestKnownTime() {
        var early=contract("고객기업",EARLIER,"ORIGINAL");var later=contract("고객기업",EARLIER.plusSeconds(1),"ORIGINAL");
        var links=service.relationships(List.of(later,early,early),KNOWN.plusSeconds(20));
        assertThat(links).hasSize(1);
        assertThat(links.getFirst().knownAt()).isEqualTo(EARLIER);
        var event=filing("고객기업","신규시설투자등",KNOWN.plusSeconds(10));
        assertThat(service.propagate(links,List.of(event,event),KNOWN.plusSeconds(20))).hasSize(1);
    }

    @Test void lateDiscoveryCannotBackdateRelationshipToOlderOfficialCustomerFilingDate() {
        var links=service.relationships(List.of(contract("고객기업",EARLIER,"ORIGINAL")),KNOWN.plusSeconds(20));
        var oldEvent=new DartSupplyContractSource.Filing("654321","고객기업","20261010000002","신규시설투자등",LocalDate.of(2026,10,10),KNOWN,
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261010000002");
        assertThat(service.propagate(links,List.of(oldEvent),KNOWN.plusSeconds(20))).isEmpty();
    }

    @Test void dateOnlyCustomerFilingCannotProveOrderOnSameDayAsRelationshipDiscovery() {
        var links=service.relationships(List.of(contract("고객기업",KNOWN.minusSeconds(3600),"ORIGINAL")),KNOWN.plusSeconds(20));
        assertThat(service.propagate(links,List.of(filing("고객기업","신규시설투자등",KNOWN.plusSeconds(10))),KNOWN.plusSeconds(20))).isEmpty();
    }

    @Test void knownExpiredContractCannotSupportLaterCustomerEvent() {
        var expired=contract("고객기업",EARLIER,"ORIGINAL",LocalDate.of(2026,10,1),LocalDate.of(2026,10,13),"20261013000001");
        var links=service.relationships(List.of(expired),KNOWN.plusSeconds(20));
        assertThat(service.propagate(links,List.of(filing("고객기업","신규시설투자등",KNOWN.plusSeconds(10))),KNOWN.plusSeconds(20))).isEmpty();
    }

    @Test void newerWithdrawalSuppressesOlderSameExposureAndReportsAmbiguity() {
        var original=contract("고객기업",EARLIER,"ORIGINAL");
        var withdrawn=contract("(주)고객기업",KNOWN,"WITHDRAWN",LocalDate.of(2026,10,1),LocalDate.of(2027,10,14),"20261014000003");
        assertThat(service.relationships(List.of(original,withdrawn),KNOWN.plusSeconds(20))).isEmpty();
        assertThat(service.relationshipIssues(List.of(original,withdrawn),KNOWN.plusSeconds(20))).contains("AMBIGUOUS_CURRENT_EXPOSURE");
    }

    @Test void newerAmendmentSuppressesOldExposureUntilLaterOriginalReestablishesIt() {
        var original=contract("고객기업",EARLIER,"ORIGINAL");
        var amended=contract("고객기업",KNOWN,"AMENDED",LocalDate.of(2026,10,1),null,"20261014000003");
        assertThat(service.relationships(List.of(original,amended),KNOWN.plusSeconds(20))).isEmpty();
        var renewed=contract("고객기업",KNOWN.plusSeconds(86400),"ORIGINAL",LocalDate.of(2026,10,15),LocalDate.of(2027,10,14),"20261015000004");
        renewed=new DartSupplyContractSource.Contract(renewed.supplierCode(),renewed.supplierName(),renewed.customerName(),renewed.product(),renewed.amountWon(),null,null,renewed.startDate(),renewed.endDate(),LocalDate.of(2026,10,15),renewed.receiptNo(),renewed.sourceUrl(),renewed.firstSeenAt(),renewed.amendmentStatus(),renewed.evidenceText(),renewed.documentHash());
        var links=service.relationships(List.of(original,amended,renewed),KNOWN.plusSeconds(86401));
        assertThat(links).hasSize(1);
        assertThat(links.getFirst().receiptNo()).isEqualTo("20261015000004");
    }

    @Test void unknownContractPeriodIsAnExplicitHistoricalWatchWithUnknownValidity() {
        var unknown=contract("고객기업",EARLIER,"ORIGINAL",null,null,"20261013000001");
        var links=service.relationships(List.of(unknown),KNOWN.plusSeconds(20));
        var result=service.propagate(links,List.of(filing("고객기업","신규시설투자등",KNOWN.plusSeconds(10))),KNOWN.plusSeconds(20));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().issues()).contains("CUSTOMER_EVENT_ALLOCATION_UNKNOWN","RELATIONSHIP_VALIDITY_UNKNOWN");
    }

    @Test void renamedProductAmendmentCannotLeaveEarlierCustomerExposureActive() {
        var original=contract("고객기업",EARLIER,"ORIGINAL");
        var base=contract("고객기업",KNOWN,"AMENDED",null,null,"20261014000003");
        var amendment=new DartSupplyContractSource.Contract(base.supplierCode(),base.supplierName(),base.customerName(),"개정 반도체장비",base.amountWon(),null,null,null,null,base.filingDate(),base.receiptNo(),base.sourceUrl(),base.firstSeenAt(),base.amendmentStatus(),base.evidenceText(),base.documentHash());
        assertThat(service.relationships(List.of(original,amendment),KNOWN.plusSeconds(20))).isEmpty();
        assertThat(service.relationshipIssues(List.of(original,amendment),KNOWN.plusSeconds(20))).contains("AMBIGUOUS_CURRENT_EXPOSURE");
    }

    @Test void unknownCustomerWithdrawalConservativelyBlocksEarlierIssuerExposure() {
        var original=contract("고객기업",EARLIER,"ORIGINAL");
        var withdrawal=contract(null,KNOWN,"WITHDRAWN",null,null,"20261014000003");
        assertThat(service.relationships(List.of(original,withdrawal),KNOWN.plusSeconds(20))).isEmpty();
        assertThat(service.relationshipIssues(List.of(original,withdrawal),KNOWN.plusSeconds(20))).contains("AMBIGUOUS_CURRENT_EXPOSURE");
    }

    @Test void lateDiscoveryOfOldOriginalDoesNotRecoverAfterLaterWithdrawal() {
        var withdrawal=contract("고객기업",KNOWN,"WITHDRAWN",null,null,"20261014000003");
        var oldOriginal=contract("고객기업",KNOWN.plusSeconds(86400),"ORIGINAL");
        assertThat(service.relationships(List.of(withdrawal,oldOriginal),KNOWN.plusSeconds(86401))).isEmpty();
        assertThat(service.relationshipIssues(List.of(withdrawal,oldOriginal),KNOWN.plusSeconds(86401))).contains("AMBIGUOUS_CURRENT_EXPOSURE");
    }

    private static DartSupplyContractSource.Contract contract(String customer,Instant seen,String amendment) {
        return contract(customer,seen,amendment,LocalDate.of(2026,10,1),LocalDate.of(2027,10,14),"20261014000001");
    }
    private static DartSupplyContractSource.Contract contract(String customer,Instant seen,String amendment,LocalDate start,LocalDate end,String receipt) {
        return new DartSupplyContractSource.Contract("123456","공급기업",customer,"반도체장비",new BigDecimal("1000000"),null,null,
            start,end,LocalDate.of(2026,10,12),receipt,
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+receipt,seen,amendment,"계약 근거","a".repeat(64));
    }
    private static DartSupplyContractSource.Filing filing(String name,String title,Instant seen) {
        return new DartSupplyContractSource.Filing("654321",name,"20261014000002",title,LocalDate.of(2026,10,14),seen,
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261014000002");
    }
}
