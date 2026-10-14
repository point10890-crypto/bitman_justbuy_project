package com.bitman.justbuy.research.event;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class KindBuybackSourceTest {
    private static final Instant KNOWN=Instant.parse("2026-10-13T11:07:00Z");
    private String html(boolean execution,String amountUnit,String qty,String amount) {
        return "<table><tr><td>"+(execution?"체결일자":"호가를 행할 일자")+"</td><td>2026-10-"+(execution?"13":"14")+"</td></tr><tr><td>번호</td><td>구분</td><td>종목</td><td>종목코드</td><td>신고서구분</td><td>신청수량</td>"+(execution?"<td>체결수량</td><td>체결금액("+amountUnit+")</td>":"")+"</tr><tr><td>1</td><td>매수</td><td>회사</td><td>A000660</td><td>직접</td><td>50,000</td>"+(execution?"<td>"+qty+"</td><td>"+amount+"</td>":"")+"</tr></table>";
    }
    @Test void observedZeroExecutionAndThousandWonUnitArePreserved() {
        var rows=KindBuybackSource.parseDocument(html(true,"천원","0","0"),"KOSPI",true,"https://kind.krx.co.kr/external/zero.htm",KNOWN);
        assertEquals(1,rows.size());assertEquals(BigDecimal.ZERO,rows.getFirst().executed());assertEquals(BigDecimal.ZERO,rows.getFirst().amountWon());
        var filled=KindBuybackSource.parseDocument(html(true,"천원","40","123"),"KOSPI",true,"https://kind.krx.co.kr/external/filled.htm",KNOWN).getFirst();
        assertEquals(new BigDecimal("123000"),filled.amountWon());
        var kosdaq=KindBuybackSource.parseDocument(html(true,"원","40","123"),"KOSDAQ",true,"https://kind.krx.co.kr/external/kosdaq.htm",KNOWN).getFirst();
        assertEquals(new BigDecimal("123"),kosdaq.amountWon());
    }
    @Test void expectedDemandUsesOnlyPastExecutionsAndIsNeverObservedExecution() {
        var past=KindBuybackSource.parseDocument(html(true,"천원","40","123"),"KOSPI",true,"https://kind.krx.co.kr/external/past.htm",KNOWN);
        var application=KindBuybackSource.parseDocument(html(false,"","",""),"KOSPI",false,"https://kind.krx.co.kr/external/plan.htm",KNOWN);
        var future=KindBuybackSource.parseDocument(html(true,"천원","50,000","123"),"KOSPI",true,"https://kind.krx.co.kr/external/future.htm",KNOWN.plusSeconds(86400));
        var all=new ArrayList<KindBuybackSource.ReportRow>();all.addAll(past);all.addAll(application);all.addAll(future);
        var result=KindBuybackSource.summarize(all,KNOWN.plusSeconds(30),List.of());
        assertEquals(1,result.rows().size());var row=result.rows().getFirst();
        assertEquals("0.0800",row.get("fulfilmentRatePct"));assertEquals("40",row.get("expectedQuantity"));
        assertEquals("2026-10-13",row.get("executionDate"));assertEquals("2026-10-14",row.get("applicationDate"));
        assertEquals("OBSERVED_PLAN_WITH_HISTORICAL_SCENARIO",row.get("status"));
    }
    @Test void malformedQuantitiesAndSellingRowsCannotBecomeBuyDemand() {
        assertTrue(KindBuybackSource.parseDocument(html(true,"천원","-","123"),"KOSPI",true,"https://kind.krx.co.kr/external/a.htm",KNOWN).isEmpty());
        assertTrue(KindBuybackSource.parseDocument(html(false,"","","").replace("매수","매도"),"KOSPI",false,"https://kind.krx.co.kr/external/a.htm",KNOWN).isEmpty());
        assertThrows(IllegalArgumentException.class,()->KindBuybackSource.parseDocument(html(false,"","",""),"KOSPI",false,"https://evil.example/a",KNOWN));
    }
    @Test void realKosdaqExecutionHasNoApplicationColumnAndUsesWonUnit() throws Exception {
        String original=new String(getClass().getResourceAsStream("/research/event/kind-kosdaq-execution-20261013.html").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        var rows=KindBuybackSource.parseDocument(original,"KOSDAQ",true,"https://kind.krx.co.kr/external/2026/10/13/000725/20261013001620/70800.htm",KNOWN);
        assertFalse(rows.isEmpty());var item=rows.stream().filter(r->r.code().equals("014100")&&r.category().equals("직접")).findFirst().orElseThrow();
        assertNull(item.applied());assertEquals(new BigDecimal("9871"),item.executed());assertEquals(new BigDecimal("16812619"),item.amountWon());
    }
    @Test void executionWithoutApplicationColumnJoinsExactDateApplicationInsteadOfInventingDenominator() {
        var before=new KindBuybackSource.ReportRow("KOSDAQ","000660","회사","직접",LocalDate.of(2026,10,13),false,new BigDecimal("50000"),null,null,"https://kind.krx.co.kr/external/past-plan.htm",KNOWN.minusSeconds(86400));
        var execution=new KindBuybackSource.ReportRow("KOSDAQ","000660","회사","직접",LocalDate.of(2026,10,13),true,null,new BigDecimal("40"),new BigDecimal("123"),"https://kind.krx.co.kr/external/past-exec.htm",KNOWN);
        var next=new KindBuybackSource.ReportRow("KOSDAQ","000660","회사","직접",LocalDate.of(2026,10,14),false,new BigDecimal("50000"),null,null,"https://kind.krx.co.kr/external/next.htm",KNOWN);
        var result=KindBuybackSource.summarize(List.of(before,execution,next),KNOWN.plusSeconds(1),List.of());assertEquals("40",result.rows().getFirst().get("expectedQuantity"));
    }
}
