package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DartOriginalDocumentParserTest {
    @Test
    void onlyUniqueExplicitLabelValuePairsBecomeContractFacts() throws Exception {
        var facts = DartOriginalDocumentParser.parse(zip("report.xml", "<DOCUMENT><TABLE>"
            + "<TR><TD>계약금액(원)</TD><TD>1,234,000</TD></TR>"
            + "<TR><TD>계약기간</TD><TD>2026-10-11 ~ 2027-10-11</TD></TR></TABLE></DOCUMENT>"), "SUPPLY_CONTRACT");
        assertThat(facts.facts()).containsEntry("contractAmountWon", "1,234,000")
            .containsEntry("contractPeriod", "2026-10-11 ~ 2027-10-11");
    }

    @Test
    void shareholderPercentKeepsPercentageUnitsInsteadOfBecomingAMoneyValue() throws Exception {
        var facts = DartOriginalDocumentParser.parse(zip("report.xml", "<DOCUMENT><TABLE>"
            + "<TR><TD>보유비율(%)</TD><TD>5.21</TD></TR></TABLE></DOCUMENT>"), "SHAREHOLDER_CHANGE");
        assertThat(facts.facts()).containsEntry("ownershipPct", "5.21");
        assertThat(facts.facts()).doesNotContainKey("contractAmountWon");
    }

    @Test
    void duplicateOrUnlabelledAmountsStayUnknown() throws Exception {
        var facts = DartOriginalDocumentParser.parse(zip("report.xml", "<DOCUMENT><TABLE>"
            + "<TR><TD>계약금액(원)</TD><TD>1,000</TD></TR>"
            + "<TR><TD>계약금액(원)</TD><TD>2,000</TD></TR></TABLE></DOCUMENT>"), "SUPPLY_CONTRACT");
        assertThat(facts.facts()).doesNotContainKey("contractAmountWon");
        assertThat(facts.unknownFields()).contains("contractAmountWon");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10%", "1원0", "10원원"})
    void malformedOrPercentageUnitsCannotBecomeWonAmounts(String raw) throws Exception {
        var result = DartOriginalDocumentParser.parse(zip("report.xml", "<DOCUMENT><TABLE><TR>"
            + "<TD>계약금액(원)</TD><TD>" + raw + "</TD></TR></TABLE></DOCUMENT>"), "SUPPLY_CONTRACT");
        assertThat(result.facts()).doesNotContainKey("contractAmountWon");
        assertThat(result.unknownFields()).contains("contractAmountWon");
    }

    @Test
    void wonUnitsCannotBecomeShareholderPercentages() throws Exception {
        var result = DartOriginalDocumentParser.parse(zip("report.xml", "<DOCUMENT><TABLE><TR>"
            + "<TD>보유비율(%)</TD><TD>5원</TD></TR></TABLE></DOCUMENT>"), "SHAREHOLDER_CHANGE");
        assertThat(result.facts()).doesNotContainKey("ownershipPct");
        assertThat(result.unknownFields()).contains("ownershipPct");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../report.xml", "/report.xml", "C:/report.xml", "folder/../../report.xml", "folder\\report.xml"})
    void unsafeArchiveNamesAreRejectedWithoutExtraction(String name) throws Exception {
        byte[] zip = zip(name, "<DOCUMENT/>");
        assertThatThrownBy(() -> DartOriginalDocumentParser.parse(zip, "SUPPLY_CONTRACT"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doctypesAndExternalEntitiesAreRejected() throws Exception {
        byte[] zip = zip("report.xml", "<!DOCTYPE DOCUMENT [<!ENTITY external SYSTEM 'file:///fixture-secret'>]>"
            + "<DOCUMENT>&external;</DOCUMENT>");
        assertThatThrownBy(() -> DartOriginalDocumentParser.parse(zip, "SUPPLY_CONTRACT"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inflatedEntryAndTooManyEntriesAreRejected() throws Exception {
        byte[] oversized = zip("report.xml", "<DOCUMENT>" + "x".repeat(1_048_576) + "</DOCUMENT>");
        assertThatThrownBy(() -> DartOriginalDocumentParser.parse(oversized, "SUPPLY_CONTRACT"))
            .isInstanceOf(IllegalArgumentException.class);
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (int i = 0; i < 9; i++) {
                zip.putNextEntry(new ZipEntry("report" + i + ".xml"));
                zip.write("<DOCUMENT/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        assertThatThrownBy(() -> DartOriginalDocumentParser.parse(output.toByteArray(), "SUPPLY_CONTRACT"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedOrNonZipPayloadCannotClaimVerifiedUnknownFreeFacts() {
        assertThatThrownBy(() -> DartOriginalDocumentParser.parse("bad".getBytes(StandardCharsets.UTF_8), "SUPPLY_CONTRACT"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static byte[] zip(String name, String xml) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(xml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return output.toByteArray();
    }
}
