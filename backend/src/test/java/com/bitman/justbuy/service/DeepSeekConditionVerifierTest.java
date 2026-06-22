package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.EvidencePacket;
import com.bitman.justbuy.dto.condition.EvidenceVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekConditionVerifierTest {

    private final DeepSeekConditionVerifier verifier =
        new DeepSeekConditionVerifier(null, new ObjectMapper());

    @Test
    void verifyFailsOpenWhenAgentUnavailable() {
        assertThat(verifier.isAvailable()).isFalse();
        assertThat(verifier.verify(List.of(packet("196170")))).isEmpty();
        assertThat(verifier.verify(List.of())).isEmpty();
        assertThat(verifier.verify(null)).isEmpty();
    }

    @Test
    void stripToJsonRemovesCodeFencesAndProse() {
        String raw = "여기 결과입니다:\n```json\n{\"signals\":[{\"stockCode\":\"196170\"}]}\n```\n참고하세요.";
        assertThat(DeepSeekConditionVerifier.stripToJson(raw))
            .isEqualTo("{\"signals\":[{\"stockCode\":\"196170\"}]}");
    }

    @Test
    void parseReadsSignalsArrayIntoVerdicts() {
        String content = "{\"signals\":[{\"stockCode\":\"196170\",\"aiScore\":82,\"verdict\":\"VALID\","
            + "\"summary\":\"쌍끌이 수급 동반\",\"evidence\":[\"외국인 순매수\"],"
            + "\"riskFlags\":[\"변동성\"],\"invalidation\":\"수급 이탈\",\"displayStatus\":\"포착\"}]}";

        Map<String, EvidenceVerdict> verdicts = verifier.parse(content, List.of());

        assertThat(verdicts).containsKey("196170");
        EvidenceVerdict verdict = verdicts.get("196170");
        assertThat(verdict.aiScore()).isEqualTo(82);
        assertThat(verdict.verdict()).isEqualTo("VALID");
        assertThat(verdict.rejected()).isFalse();
        assertThat(verdict.evidence()).contains("외국인 순매수");
        assertThat(verdict.displayStatus()).isEqualTo("포착");
    }

    @Test
    void parseClampsScoreAndToleratesBareArray() {
        String content = "[{\"stockCode\":\"042660\",\"aiScore\":150,\"verdict\":\"REJECT\"}]";

        Map<String, EvidenceVerdict> verdicts = verifier.parse(content, List.of());

        assertThat(verdicts.get("042660").aiScore()).isEqualTo(99);
        assertThat(verdicts.get("042660").rejected()).isTrue();
    }

    private static EvidencePacket packet(String code) {
        return new EvidencePacket("LEADERS", "FLOW_LEADER", "2026-06-18T10:20:00+09:00",
            code, "알테오젠", "330,000", "+6.0", 88, List.of("쌍끌이"), Map.of("foreignNet", 900_000));
    }
}
