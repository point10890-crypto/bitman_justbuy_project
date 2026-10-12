package com.bitman.justbuy.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StructuredAnalysisParserInspectionTest {
    private static final String VALID = "{\"name\":\"삼성전자\",\"code\":\"005930\",\"confidence\":0.8,\"reasons\":[\"실제 자료 검증\"]}";
    private static String block(String json) { return "```json:analysis\n" + json + "\n```"; }
    @Test void validStructuredResponseReportsOnlyCounts() {
        var result=StructuredAnalysisParser.inspect(block("{\"stocks\":["+VALID+"]}"));
        assertThat(result.status()).isEqualTo("STRUCTURED_VALID");
        assertThat(result.declaredStocks()).isEqualTo(1);assertThat(result.acceptedStocks()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("삼성전자","005930","실제 자료 검증");
    }
    @Test void explicitZeroRecommendationsAreDistinctFromMalformedStocks() {
        var result=StructuredAnalysisParser.inspect(block("{\"stocks\":[]}"));
        assertThat(result.status()).isEqualTo("DECLARED_EMPTY");
        assertThat(result.declaredStocks()).isZero();assertThat(result.acceptedStocks()).isZero();
        assertThat(StructuredAnalysisParser.parse(block("{\"stocks\":[]}"))).isNull();
    }
    @Test void wrongConfidenceAndEmptyReasonsRemainRejected() {
        String badConfidence=VALID.replace("0.8","\"0.8\"");
        String badReasons=VALID.replace("[\"실제 자료 검증\"]","[]");
        var result=StructuredAnalysisParser.inspect(block("{\"stocks\":["+badConfidence+","+badReasons+"]}"));
        assertThat(result.status()).isEqualTo("SCHEMA_INVALID");
        assertThat(result.declaredStocks()).isEqualTo(2);assertThat(result.acceptedStocks()).isZero();
        assertThat(StructuredAnalysisParser.parse(block("{\"stocks\":["+badConfidence+","+badReasons+"]}"))).isNull();
    }
    @Test void partialValidationUsesExistingStrictStockRules() {
        String badScenario=VALID.substring(0,VALID.length()-1)+",\"scenario\":{\"bull\":{\"probability\":-1}}}";
        String response=block("{\"stocks\":["+VALID+","+badScenario+"]}");
        var result=StructuredAnalysisParser.inspect(response);
        assertThat(result.status()).isEqualTo("PARTIAL_INVALID");
        assertThat(result.declaredStocks()).isEqualTo(2);assertThat(result.acceptedStocks()).isEqualTo(1);
        assertThat(StructuredAnalysisParser.parse(response).stocks()).hasSize(1);
    }
    @Test void absentBlockInvalidJsonAndWrongRootHaveDifferentStatuses() {
        assertThat(StructuredAnalysisParser.inspect(null).status()).isEqualTo("NO_STRUCTURED_BLOCK");
        assertThat(StructuredAnalysisParser.inspect("일반 텍스트 추천").status()).isEqualTo("NO_STRUCTURED_BLOCK");
        assertThat(StructuredAnalysisParser.inspect(block("{broken")).status()).isEqualTo("JSON_INVALID");
        assertThat(StructuredAnalysisParser.inspect(block("{\"stocks\":{}}")).status()).isEqualTo("SCHEMA_INVALID");
        assertThat(StructuredAnalysisParser.inspect(block("null")).status()).isEqualTo("SCHEMA_INVALID");
    }
    @Test void genericJsonFenceKeepsTheOriginalParserContract() {
        String content="```json\n{\"stocks\":["+VALID+"]}\n```";
        assertThat(StructuredAnalysisParser.inspect(content).status()).isEqualTo("STRUCTURED_VALID");
        assertThat(StructuredAnalysisParser.parse(content).stocks()).hasSize(1);
    }
    @Test void inspectionPreservesOriginalFencePriorityAndFallback() {
        String generic="```json\n{\"stocks\":["+VALID+"]}\n```";
        assertThat(StructuredAnalysisParser.inspect(block("{broken")+generic).status()).isEqualTo("STRUCTURED_VALID");
        assertThat(StructuredAnalysisParser.inspect(block("{\"stocks\":[]}")+generic).status()).isEqualTo("DECLARED_EMPTY");
        assertThat(StructuredAnalysisParser.parse(block("{\"stocks\":[]}")+generic)).isNull();
    }
}
