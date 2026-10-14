package com.bitman.justbuy.research.event;

import com.bitman.justbuy.config.DartProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DartSupplyContractSourceTest {
    private static final Instant NOW=Instant.parse("2026-10-14T03:00:00Z");
    @Test void missingKeyDoesNotRequestAndEmptyIsNotError() {
        var missing=source(null,(endpoint,query,limit,timeout)->{throw new AssertionError("request with missing key");});
        assertThat(missing.fetchRecent().status()).isEqualTo("MISSING_KEY");
        var empty=source("test-key",(e,q,l,t)->json("{\"status\":\"013\"}"));
        assertThat(empty.fetchRecent().status()).isEqualTo("EMPTY");
    }

    @Test void derivesSupplierFromVerifiedListAndPreservesFirstSeenOnCachedOriginal() {
        var originals=new AtomicInteger();
        var source=source("test-key",(endpoint,query,limit,timeout)->{
            if(endpoint.equals("list.json")) return page(1,List.of(row("20261014000123","005930","삼성전자","단일판매ㆍ공급계약체결","")));
            originals.incrementAndGet();
            return SupplyContractDocumentParserTest.zip(SupplyContractDocumentParserTest.document(
                SupplyContractDocumentParserTest.amount("1,000")+"<TR><TD>3. 계약상대방</TD><TD>고객 주식회사</TD></TR>"
                +"<TR><TD>1. 판매ㆍ공급계약 내용</TD><TD>장비</TD></TR>"
                +"<TR><TD>계약기간</TD><TD>2026-10-14 ~ 2027-10-14</TD></TR>"));
        });
        var first=source.fetchRecent();var second=source.fetchRecent();
        assertThat(first.status()).isEqualTo("COMPLETE");
        assertThat(first.contracts()).hasSize(1);
        var contract=first.contracts().getFirst();
        assertThat(contract.supplierCode()).isEqualTo("005930");
        assertThat(contract.supplierName()).isEqualTo("삼성전자");
        assertThat(contract.customerName()).isEqualTo("고객 주식회사");
        assertThat(contract.filingDate()).isEqualTo(LocalDate.of(2026,10,14));
        assertThat(contract.firstSeenAt()).isEqualTo(NOW);
        assertThat(second.contracts().getFirst()).isEqualTo(contract);
        assertThat(originals.get()).isEqualTo(1);
        assertThat(contract.sourceUrl()).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261014000123");
    }

    @Test void listAndOriginalCoverageIsBoundedAndReported() {
        var pages=new ArrayList<String>();var originals=new AtomicInteger();
        var source=source("test-key",(endpoint,query,limit,timeout)->{
            if(endpoint.equals("list.json")) {
                pages.add(query.get("page_no"));
                assertThat(query.get("page_count")).isEqualTo("100");
                var rows=new ArrayList<Map<String,String>>();
                for(int i=0;i<100;i++) rows.add(row(String.format("20261014%06d",Integer.parseInt(query.get("page_no"))*100+i),"005930","삼성전자","단일판매ㆍ공급계약체결",""));
                return page(5,rows);
            }
            originals.incrementAndGet(); return SupplyContractDocumentParserTest.zip(SupplyContractDocumentParserTest.document(SupplyContractDocumentParserTest.amount("1,000")));
        });
        var result=source.fetchRecent();
        assertThat(pages).containsExactly("1","2","3");
        assertThat(originals.get()).isEqualTo(12);
        assertThat(result.contracts()).hasSize(12);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.issues()).contains("DISCLOSURE_PAGE_LIMIT","ORIGINAL_DOCUMENT_LIMIT");
        assertThat(source.recentFilings()).hasSize(300);
    }

    @Test void invalidIdentityAndConflictingReceiptAreNotConvertedIntoSupplier() {
        var source=source("test-key",(endpoint,query,limit,timeout)->{
            if(!endpoint.equals("list.json")) throw new AssertionError("invalid originals must not be fetched");
            return page(1,List.of(row("20261014000123","005930","삼성전자","단일판매ㆍ공급계약체결",""),
                row("20261014000123","000660","SK하이닉스","단일판매ㆍ공급계약체결",""),
                row("20261014000124","","비상장","단일판매ㆍ공급계약체결","")));
        });
        assertThat(source.fetchRecent().contracts()).isEmpty();
        assertThat(source.fetchRecent().issues()).contains("CONFLICTING_DISCLOSURE_METADATA","INVALID_DISCLOSURE_IDENTITY_OR_DATE");
    }

    @Test void rateLimitAndTransportFailureDoNotExposeKeyOrExceptionText() {
        var limited=source("super-secret-test",(e,q,l,t)->json("{\"status\":\"020\",\"message\":\"key=super-secret-test\"}"));
        assertThat(limited.fetchRecent().status()).isEqualTo("RATE_LIMITED");
        assertThat(limited.fetchRecent().issues()).containsExactly("PROVIDER_RATE_LIMIT");
        var failed=source("super-secret-test",(e,q,l,t)->{throw new IllegalStateException("https://opendart.fss.or.kr/?crtfc_key=super-secret-test");});
        assertThat(failed.fetchRecent().issues()).containsExactly("TRANSPORT_OR_PARSE_ERROR");
    }

    @Test void amendmentAndWithdrawalAreNotLabelledOriginal() {
        var source=source("test-key",(e,q,l,t)->e.equals("list.json")?page(1,List.of(
            row("20261014000123","005930","삼성전자","[기재정정]단일판매ㆍ공급계약체결","정"),
            row("20261014000124","005930","삼성전자","단일판매ㆍ공급계약해지","철")))
            :SupplyContractDocumentParserTest.zip(SupplyContractDocumentParserTest.document(SupplyContractDocumentParserTest.amount("0"))));
        var result=source.fetchRecent();
        assertThat(result.contracts()).extracting(DartSupplyContractSource.Contract::amendmentStatus).containsExactly("AMENDED","WITHDRAWN");
        assertThat(result.contracts()).allSatisfy(c->assertThat(c.previousAmountWon()).isNull());
        assertThat(result.issues()).contains("20261014000123:AMENDMENT_PRIOR_AMOUNT_UNKNOWN","20261014000124:WITHDRAWN_CONTRACT");
    }

    @Test void successfulPartialOriginalIsCachedWithItsUnknownsAndInferredAmendment() {
        var originals=new AtomicInteger();
        var source=source("test-key",(e,q,l,t)->{
            if(e.equals("list.json")) return page(1,List.of(row("20261014000123","005930","삼성전자","단일판매ㆍ공급계약체결","")));
            originals.incrementAndGet();return SupplyContractDocumentParserTest.zip(SupplyContractDocumentParserTest.document(
                SupplyContractDocumentParserTest.amount("1000")+"<TR><TD>변경계약을 최초 공시합니다.</TD></TR>"));
        });
        var first=source.fetchRecent();var second=source.fetchRecent();
        assertThat(first.status()).isEqualTo("PARTIAL");
        assertThat(second.contracts()).isEqualTo(first.contracts());
        assertThat(second.issues()).contains("20261014000123:AMENDMENT_PRIOR_AMOUNT_UNKNOWN","20261014000123:CUSTOMER_UNKNOWN");
        assertThat(originals.get()).isEqualTo(1);
    }

    @Test void xmlDocumentProviderRateLimitStopsRemainingOriginalRequests() {
        var documents=new AtomicInteger();
        var source=source("test-key",(e,q,l,t)->{
            if(e.equals("list.json")) return page(1,List.of(row("20261014000123","005930","삼성전자","단일판매ㆍ공급계약체결",""),row("20261014000124","005930","삼성전자","단일판매ㆍ공급계약체결","")));
            documents.incrementAndGet();return json("<?xml version=\"1.0\"?><result><status>020</status><message>request limit</message></result>");
        });
        var result=source.fetchRecent();
        assertThat(result.status()).isEqualTo("RATE_LIMITED");
        assertThat(result.issues()).contains("PROVIDER_RATE_LIMIT");
        assertThat(documents.get()).isEqualTo(1);
    }

    @Test void elapsedBudgetPreventsStartingAnotherNetworkRequest() {
        var requests=new AtomicInteger();var time=new java.util.concurrent.atomic.AtomicLong();
        var source=new DartSupplyContractSource(new DartProperties("test-key"),new ObjectMapper(),(e,q,l,t)->{
            requests.incrementAndGet();time.set(26_000_000_000L);
            return page(2,List.of(row("20261014000123","005930","삼성전자","단일판매ㆍ공급계약체결","")));
        },Clock.fixed(NOW,ZoneOffset.UTC),time::get);
        var result=source.fetchRecent();
        assertThat(requests.get()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.issues()).contains("BUDGET_EXHAUSTED");
        assertThat(result.contracts()).isEmpty();
    }

    @Test void subsidiaryDisclosureDoesNotInventParentIssuerAsSupplier() {
        var source=source("test-key",(e,q,l,t)->{
            if(!e.equals("list.json"))throw new AssertionError("unverified subsidiary must not become listed parent supplier");
            return page(1,List.of(row("20261014000123","005930","삼성전자","단일판매ㆍ공급계약체결(자회사의 주요경영사항)","")));
        });
        var result=source.fetchRecent();
        assertThat(result.contracts()).isEmpty();
        assertThat(result.issues()).contains("SUBSIDIARY_SUPPLIER_IDENTITY_UNVERIFIED");
    }

    @Test void validatedOfficialFilingCatalogReusesListCallsAndPreservesFirstSeen() {
        var requests=new AtomicInteger();
        var currentTime=new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var movingClock=new Clock() {
            @Override public ZoneId getZone(){return ZoneOffset.UTC;}
            @Override public Clock withZone(ZoneId zone){return this;}
            @Override public Instant instant(){return currentTime.get();}
        };
        var source=new DartSupplyContractSource(new DartProperties("test-key"),new ObjectMapper(),(e,q,l,t)->{
            requests.incrementAndGet();
            assertThat(e).isEqualTo("list.json");
            return page(1,List.of(row("20261014000123","005930","삼성전자","영업(잠정)실적(공정공시)",""),
                row("20261014000124","000660","SK하이닉스","신규시설투자등",""),
                row("20261014000125","","비상장","영업실적","")));
        },movingClock,System::nanoTime);
        assertThat(source.fetchRecent().contracts()).isEmpty();
        var first=source.recentFilings();
        assertThat(first).hasSize(2);
        assertThat(first.getFirst().title()).isEqualTo("영업(잠정)실적(공정공시)");
        assertThat(first.getFirst().firstSeenAt()).isEqualTo(NOW);
        assertThat(first.getFirst().sourceUrl()).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261014000123");
        assertThat(requests.get()).isEqualTo(1);
        currentTime.set(NOW.plusSeconds(86400));
        source.fetchRecent();
        assertThat(source.recentFilings()).isEqualTo(first);
        assertThatThrownBy(()->first.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static DartSupplyContractSource source(String key,DartSupplyContractSource.Fetcher fetcher) {
        return new DartSupplyContractSource(new DartProperties(key),new ObjectMapper(),fetcher,Clock.fixed(NOW,ZoneOffset.UTC),System::nanoTime);
    }
    private static byte[] json(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static byte[] page(int total,List<Map<String,String>> rows){try{return new ObjectMapper().writeValueAsBytes(Map.of("status","000","total_page",total,"list",rows));}catch(Exception e){throw new IllegalStateException(e);}}
    private static Map<String,String> row(String receipt,String code,String name,String title,String remarks){return Map.of("rcept_no",receipt,"rcept_dt","20261014","stock_code",code,"corp_code","00126380","corp_name",name,"report_nm",title,"rm",remarks);}
}
