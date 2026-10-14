package com.bitman.justbuy.research.event;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class SupplyContractDocumentParserTest {
    @Test void actualOpenDartXmlNamedUtf8HtmlUsesPayloadEncodingDespiteEucKrMeta() throws Exception {
        byte[] bytes;
        try(var input=getClass().getResourceAsStream("/research/event/dart-opendart-20261014800068-original.zip")) {bytes=input.readAllBytes();}
        var parsed=SupplyContractDocumentParser.parse(bytes,"ORIGINAL");
        assertThat(parsed.customerName()).isEqualTo("롯데건설주식회사");
        assertThat(parsed.product()).isEqualTo("안양 평촌 터미널 부지 복합개발사업 중 기계설비공사");
        assertThat(parsed.amountWon()).isEqualByComparingTo("14378000000");
        assertThat(parsed.recentSalesWon()).isEqualByComparingTo("164457886988");
        assertThat(parsed.startDate()).isEqualTo(LocalDate.of(2026,10,13));
        assertThat(parsed.endDate()).isEqualTo(LocalDate.of(2029,11,30));
        assertThat(parsed.previousAmountWon()).isNull();
        assertThat(parsed.amendmentStatus()).isEqualTo("ORIGINAL");
        assertThat(parsed.issues()).isEmpty();
        assertThat(parsed.evidenceText()).contains("계약상대", "롯데건설주식회사", "14,378,000,000");
    }

    @Test void malformedXmlDoesNotTriggerGenericHtmlFallbackAndHtmlDtdRemainsRejected() {
        String malformed="<DOCUMENT><TABLE><TR><TD>계약금액(원)</TD><TD>1000</TD></TABLE></DOCUMENT>";
        assertThatThrownBy(()->SupplyContractDocumentParser.parse(zip(malformed),"ORIGINAL"))
            .isInstanceOf(IllegalArgumentException.class);
        String hostile="<html><!DOCTYPE DOCUMENT [<!ENTITY xxe SYSTEM 'file:///secret'>]><body><table><tr><td>&xxe;</td></tr></table></body></html>";
        assertThatThrownBy(()->SupplyContractDocumentParser.parse(zip(hostile),"ORIGINAL"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void capturedPublicOriginalRecognizesCumulativeChangedContractDespiteOriginalTitle() throws Exception {
        byte[] bytes;
        try(var input=getClass().getResourceAsStream("/research/event/dart-20261012800721-original.zip")) { bytes=input.readAllBytes(); }
        var parsed=SupplyContractDocumentParser.parse(bytes,"ORIGINAL");
        assertThat(parsed.customerName()).isEqualTo("한국도로공사");
        assertThat(parsed.product()).isEqualTo("고속국도 제400호선 양평~이천간 건설공사(제4공구)");
        assertThat(parsed.amountWon()).isEqualByComparingTo("33005832385");
        assertThat(parsed.recentSalesWon()).isEqualByComparingTo("1149191208443");
        assertThat(parsed.startDate()).isEqualTo(LocalDate.of(2020,6,16));
        assertThat(parsed.endDate()).isEqualTo(LocalDate.of(2027,12,31));
        assertThat(parsed.amendmentStatus()).isEqualTo("AMENDED");
        assertThat(parsed.previousAmountWon()).isNull();
        assertThat(parsed.issues()).contains("AMENDMENT_PRIOR_AMOUNT_UNKNOWN","CUMULATIVE_CHANGED_CONTRACT");
        assertThat(parsed.evidenceText()).contains("변경계약", "부가가치세 포함");
    }

    @Test void capturedDateOnlyCorrectionIsAnAmendmentWithoutInventedPriorMoney() throws Exception {
        byte[] bytes;
        try(var input=getClass().getResourceAsStream("/research/event/dart-20261012800726-original.zip")) { bytes=input.readAllBytes(); }
        var parsed=SupplyContractDocumentParser.parse(bytes,"ORIGINAL");
        assertThat(parsed.customerName()).isEqualTo("에스케이에코플랜트(주)");
        assertThat(parsed.product()).isEqualTo("용인 Cluster 1기 구축공사");
        assertThat(parsed.amountWon()).isEqualByComparingTo("25116000000");
        assertThat(parsed.recentSalesWon()).isEqualByComparingTo("401776583229");
        assertThat(parsed.endDate()).isEqualTo(LocalDate.of(2026,12,31));
        assertThat(parsed.amendmentStatus()).isEqualTo("AMENDED");
        assertThat(parsed.previousAmountWon()).isNull();
        assertThat(parsed.issues()).contains("AMENDMENT_PRIOR_AMOUNT_UNKNOWN");
        assertThat(parsed.evidenceText()).contains("정정전", "2026-12-31");
    }

    @Test void capturedNamedOriginalKeepsUndeterminedEndDateUnknown() throws Exception {
        byte[] bytes;
        try(var input=getClass().getResourceAsStream("/research/event/dart-20261012800583-original.zip")) { bytes=input.readAllBytes(); }
        var parsed=SupplyContractDocumentParser.parse(bytes,"ORIGINAL");
        assertThat(parsed.customerName()).isEqualTo("솔라닉스육호 주식회사");
        assertThat(parsed.product()).isEqualTo("사업개발용역계약");
        assertThat(parsed.amountWon()).isEqualByComparingTo("43120000000");
        assertThat(parsed.recentSalesWon()).isEqualByComparingTo("385640769900");
        assertThat(parsed.startDate()).isEqualTo(LocalDate.of(2026,10,12));
        assertThat(parsed.endDate()).isNull();
        assertThat(parsed.amendmentStatus()).isEqualTo("ORIGINAL");
        assertThat(parsed.previousAmountWon()).isNull();
        assertThat(parsed.issues()).contains("CONTRACT_PERIOD_UNKNOWN");
    }
    @Test void readsNamedCounterpartyAndNestedPeriodFromUppercaseDartTable() {
        var parsed = SupplyContractDocumentParser.parse(zip(document("""
            <TR><TD>1. 판매ㆍ공급계약 내용</TD><TD>OLED 제조장비</TD></TR>
            <TR><TD ROWSPAN="3">2. 계약내역</TD><TD>계약금액(원)</TD><TD>24,500,000,000</TD></TR>
            <TR><TD>최근매출액(원)</TD><TD>100,000,000,000</TD></TR>
            <TR><TD>매출액대비(%)</TD><TD>24.5</TD></TR>
            <TR><TD>3. 계약상대방</TD><TD>Samsung Display Co., Ltd.</TD></TR>
            <TR><TD ROWSPAN="2">5. 계약기간</TD><TD>시작일</TD><TD>2026-10-13</TD></TR>
            <TR><TD>종료일</TD><TD>2027-10-12</TD></TR>
            """)), "ORIGINAL");
        assertThat(parsed.customerName()).isEqualTo("Samsung Display Co., Ltd.");
        assertThat(parsed.product()).isEqualTo("OLED 제조장비");
        assertThat(parsed.amountWon()).isEqualByComparingTo("24500000000");
        assertThat(parsed.recentSalesWon()).isEqualByComparingTo("100000000000");
        assertThat(parsed.previousAmountWon()).isNull();
        assertThat(parsed.startDate()).isEqualTo(LocalDate.of(2026,10,13));
        assertThat(parsed.endDate()).isEqualTo(LocalDate.of(2027,10,12));
        assertThat(parsed.documentHash()).matches("[0-9a-f]{64}");
        assertThat(parsed.evidenceText()).contains("계약금액(원)", "Samsung Display");
    }

    @Test void conflictingMainAmountsStayUnknownInsteadOfChoosingFirst() {
        var parsed = SupplyContractDocumentParser.parse(zip(document(amount("1,000")+amount("2,000"))), "ORIGINAL");
        assertThat(parsed.amountWon()).isNull();
        assertThat(parsed.issues()).contains("AMBIGUOUS_AMOUNT");
    }

    @Test void matchingRepeatedAmountsAreOneFact() {
        var parsed = SupplyContractDocumentParser.parse(zip(document(amount("1,000")+amount("1,000"))), "ORIGINAL");
        assertThat(parsed.amountWon()).isEqualByComparingTo("1000");
    }

    @Test void correctionColumnsEstablishPreviousAndCurrentSeparately() {
        String correction="<TABLE><TR><TH>정정항목</TH><TH>정정전</TH><TH>정정후</TH></TR>"
            +"<TR><TD>계약금액(원)</TD><TD>2,000,000</TD><TD>1,500,000</TD></TR></TABLE>";
        var parsed=SupplyContractDocumentParser.parse(zip("<DOCUMENT>"+correction+document(amount("1,500,000"))+"</DOCUMENT>"),"AMENDED");
        assertThat(parsed.previousAmountWon()).isEqualByComparingTo("2000000");
        assertThat(parsed.amountWon()).isEqualByComparingTo("1500000");
        assertThat(parsed.amendmentStatus()).isEqualTo("AMENDED");
    }

    @Test void correctionWithoutPriorDoesNotInventZero() {
        var parsed=SupplyContractDocumentParser.parse(zip(document(amount("1,000"))),"AMENDED");
        assertThat(parsed.previousAmountWon()).isNull();
        assertThat(parsed.issues()).contains("AMENDMENT_PRIOR_AMOUNT_UNKNOWN");
    }

    @Test void correctionContradictingCurrentBodyIsAmbiguous() {
        String correction="<TABLE><TR><TH>정정항목</TH><TH>정정전</TH><TH>정정후</TH></TR>"
            +"<TR><TD>계약금액(원)</TD><TD>2,000</TD><TD>1,500</TD></TR></TABLE>";
        var parsed=SupplyContractDocumentParser.parse(zip("<DOCUMENT>"+correction+document(amount("1,700"))+"</DOCUMENT>"),"AMENDED");
        assertThat(parsed.amountWon()).isNull();
        assertThat(parsed.issues()).contains("AMBIGUOUS_AMOUNT");
    }

    @Test void withdrawnContractIsExplicitAndUnknownCounterpartyStaysNull() {
        var parsed=SupplyContractDocumentParser.parse(zip(document(amount("0")+"<TR><TD>3. 계약상대방</TD><TD>영업비밀로 미공개</TD></TR>")),"WITHDRAWN");
        assertThat(parsed.amountWon()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(parsed.customerName()).isNull();
        assertThat(parsed.amendmentStatus()).isEqualTo("WITHDRAWN");
        assertThat(parsed.issues()).contains("WITHDRAWN_CONTRACT", "CUSTOMER_UNKNOWN");
    }

    @Test void actualCancellationNoteOverridesOriginalMetadataButFutureConditionDoesNot() {
        var withdrawn=SupplyContractDocumentParser.parse(zip(document(amount("1000")+"<TR><TD>계약상대방 요청으로 본 계약이 해지되었습니다.</TD></TR>")),"ORIGINAL");
        assertThat(withdrawn.amendmentStatus()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.issues()).contains("WITHDRAWN_CONTRACT");
        var futureCondition=SupplyContractDocumentParser.parse(zip(document(amount("1000")+"<TR><TD>계약조건에 따라 계약이 해지될 수 있습니다.</TD></TR>")),"ORIGINAL");
        assertThat(futureCondition.amendmentStatus()).isEqualTo("ORIGINAL");
    }

    @Test void moneyRequiresExplicitWonUnitAndValidGrouping() {
        var missing=SupplyContractDocumentParser.parse(zip(document("<TR><TD>계약금액</TD><TD>100</TD></TR>")),"ORIGINAL");
        var malformed=SupplyContractDocumentParser.parse(zip(document(amount("10,00"))),"ORIGINAL");
        var negative=SupplyContractDocumentParser.parse(zip(document(amount("-100"))),"ORIGINAL");
        assertThat(missing.amountWon()).isNull();
        assertThat(malformed.amountWon()).isNull();
        assertThat(negative.amountWon()).isNull();
        var million=SupplyContractDocumentParser.parse(zip(document("<TR><TD>계약금액(백만원)</TD><TD>100.5</TD></TR>")),"ORIGINAL");
        assertThat(million.amountWon()).isEqualByComparingTo("100500000");
    }

    @Test void foreignCurrencyLabelCannotBecomeWonFromContradictoryCellSuffix() {
        var parsed=SupplyContractDocumentParser.parse(zip(document("<TR><TD>계약금액(USD)</TD><TD>100 원</TD></TR>")),"ORIGINAL");
        assertThat(parsed.amountWon()).isNull();
        assertThat(parsed.issues()).contains("INVALID_AMOUNT");
    }

    @Test void magnitudeOnlyUnitDoesNotEstablishKoreanWon() {
        var parsed=SupplyContractDocumentParser.parse(zip(document("<TR><TD>계약금액(백만)</TD><TD>100</TD></TR>")),"ORIGINAL");
        assertThat(parsed.amountWon()).isNull();
        assertThat(parsed.issues()).contains("INVALID_AMOUNT");
    }

    @Test void malformedCurrentBodyCannotBeReplacedByCorrectionTableAmount() {
        String correction="<TABLE><TR><TH>정정항목</TH><TH>정정전</TH><TH>정정후</TH></TR>"
            +"<TR><TD>계약금액(원)</TD><TD>2,000</TD><TD>1,500</TD></TR></TABLE>";
        var parsed=SupplyContractDocumentParser.parse(zip("<DOCUMENT>"+correction+document(amount("불명"))+"</DOCUMENT>"),"AMENDED");
        assertThat(parsed.amountWon()).isNull();
        assertThat(parsed.issues()).contains("INVALID_AMOUNT","AMBIGUOUS_AMOUNT");
    }

    @Test void invalidDatesAndReversedPeriodsStayUnknown() {
        var parsed=SupplyContractDocumentParser.parse(zip(document("<TR><TD>계약기간</TD><TD>2026-02-30 ~ 2026-03-01</TD></TR>")),"ORIGINAL");
        assertThat(parsed.startDate()).isNull();
        var reversed=SupplyContractDocumentParser.parse(zip(document("<TR><TD>계약기간</TD><TD>2026.10.14 ~ 2026.10.13</TD></TR>")),"ORIGINAL");
        assertThat(reversed.startDate()).isNull();
        assertThat(reversed.endDate()).isNull();
        assertThat(reversed.issues()).contains("INVALID_CONTRACT_PERIOD");
    }

    @Test void rejectsExternalEntitiesAndExpandedArchiveBomb() {
        assertThatThrownBy(()->SupplyContractDocumentParser.parse(zip("<!DOCTYPE DOCUMENT [<!ENTITY xxe SYSTEM 'file:///secret'>]><DOCUMENT>&xxe;</DOCUMENT>"),"ORIGINAL"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SupplyContractDocumentParser.parse(zip("<DOCUMENT>"+"x".repeat(2_100_000)+"</DOCUMENT>"),"ORIGINAL"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsUnsafeEntryAndMultipleConflictingXmlDocuments() throws Exception {
        assertThatThrownBy(()->SupplyContractDocumentParser.parse(zipEntry("../evil.xml",document(amount("1,000"))),"ORIGINAL"))
            .isInstanceOf(IllegalArgumentException.class);
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)) {
            write(zip,"a.xml",document(amount("1,000")));
            write(zip,"b.xml",document(amount("2,000")));
        }
        assertThat(SupplyContractDocumentParser.parse(output.toByteArray(),"ORIGINAL").amountWon()).isNull();
    }

    static String amount(String value) { return "<TR><TD>계약금액(원)</TD><TD>"+value+"</TD></TR>"; }
    static String document(String rows) { return "<DOCUMENT><TABLE><TBODY>"+rows+"</TBODY></TABLE></DOCUMENT>"; }
    static byte[] zip(String xml) { return zipEntry("original.xml",xml); }
    static byte[] zipEntry(String name,String xml) {
        try {
            var output=new ByteArrayOutputStream();
            try(var zip=new ZipOutputStream(output)) { write(zip,name,xml); }
            return output.toByteArray();
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    static void write(ZipOutputStream zip,String name,String xml) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(xml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
