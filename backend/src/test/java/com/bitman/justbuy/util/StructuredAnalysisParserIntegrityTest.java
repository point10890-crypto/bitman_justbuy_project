package com.bitman.justbuy.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StructuredAnalysisParserIntegrityTest {
    String content(String fields) {
        return "```json:analysis\n{\"stocks\":[{\"name\":\"삼성전자\",\"code\":\"005930\",\"confidence\":0.8,\"reasons\":[\"입력 근거\"]" + fields + "}]}\n```";
    }
    @Test void invalidCodesConfidenceAndReasonsCannotEnterConsensus() {
        assertThat(StructuredAnalysisParser.parse(content("").replace("005930", "ABCDEF"))).isNull();
        assertThat(StructuredAnalysisParser.parse(content("").replace("0.8", "\"NaN\""))).isNull();
        assertThat(StructuredAnalysisParser.parse(content("").replace("0.8", "1.2"))).isNull();
        assertThat(StructuredAnalysisParser.parse(content("").replace("[\"입력 근거\"]", "[]"))).isNull();
    }
    @Test void invalidScenarioRejectsRowAndPositiveDistributionNormalizes() {
        String zero = ",\"scenario\":{\"bull\":{\"probability\":0},\"base\":{\"probability\":0},\"bear\":{\"probability\":0}}";
        assertThat(StructuredAnalysisParser.parse(content(zero))).isNull();
        assertThat(StructuredAnalysisParser.parse(content(zero.replaceFirst("probability\\\":0", "probability\":-1")))).isNull();
        var parsed = StructuredAnalysisParser.parse(content(zero.replace("probability\":0", "probability\":0.2")));
        assertThat(parsed.stocks().getFirst().scenario().bull().probability()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(0.0001));
    }
}
