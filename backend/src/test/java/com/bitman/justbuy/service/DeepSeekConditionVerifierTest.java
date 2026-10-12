package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.EvidencePacket;
import com.bitman.justbuy.dto.condition.EvidenceVerdict;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.ai.agent.DeepSeekAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

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
            + "\"summary\":\"쌍끌이 수급 동반\",\"claims\":[{\"factId\":\"196170:/metrics/foreignNet\",\"value\":900000}],\"evidence\":[\"외국인 순매수\"],"
            + "\"riskFlags\":[\"변동성\"],\"invalidation\":\"수급 이탈\",\"displayStatus\":\"포착\"}]}";

        Map<String, EvidenceVerdict> verdicts = verifier.parse(content, List.of(packet("196170")));

        assertThat(verdicts).containsKey("196170");
        EvidenceVerdict verdict = verdicts.get("196170");
        assertThat(verdict.aiScore()).isEqualTo(82);
        assertThat(verdict.verdict()).isEqualTo("VALID");
        assertThat(verdict.rejected()).isFalse();
        assertThat(verdict.evidence()).anyMatch(item -> item.contains("외국인 순매수") && item.contains("900000"));
        assertThat(verdict.evidence()).noneMatch(item -> item.equals("외국인 순매수"));
        assertThat(verdict.displayStatus()).isEqualTo("포착");
    }

    @Test
    void invalidScoreCannotEnterVerdicts() {
        String content = "[{\"stockCode\":\"042660\",\"aiScore\":150,\"verdict\":\"REJECT\"}]";

        Map<String, EvidenceVerdict> verdicts = verifier.parse(content, List.of(packet("042660")));

        assertThat(verdicts).isEmpty();
    }

    @Test void rejectsMissingEvidenceRiskInvalidationAndUnknownVerdict() {
        String valid = "{\"stockCode\":\"196170\",\"aiScore\":82,\"verdict\":\"VALID\",\"summary\":\"근거 요약\",\"claims\":[{\"factId\":\"196170:/metrics/foreignNet\",\"value\":900000}],\"riskFlags\":[\"변동성\"],\"invalidation\":\"수급 이탈\"}";
        assertThat(verifier.parse("{\"stockCode\":\"196170\",\"aiScore\":99,\"verdict\":\"VALID\"}", List.of(packet("196170")))).isEmpty();
        assertThat(verifier.parse(valid.replace("VALID", "UNKNOWN"), List.of(packet("196170")))).isEmpty();
        assertThat(verifier.parse(valid.replace("[\"변동성\"]", "[]"), List.of(packet("196170")))).isEmpty();
        assertThat(verifier.parse(valid.replace("수급 이탈", ""), List.of(packet("196170")))).isEmpty();
        assertThat(verifier.parse(valid.replace("196170", "999999"), List.of(packet("196170")))).isEmpty();
        assertThat(verifier.parse("[" + valid.replace("VALID", "REJECT") + "," + valid + "]", List.of(packet("196170")))).isEmpty();
    }

    @Test void catalogContainsOriginalFactsAndPreservesProvenance() throws Exception {
        var json = new ObjectMapper().readTree(verifier.buildUserMessage(List.of(richPacket("196170"))));
        var facts = json.path("candidates").get(0).path("facts");
        assertThat(facts.toString()).contains("196170:/currentPrice", "196170:/metrics/kiwoomEvidence/investorDaily/foreignNetBuy");
        assertThat(facts.toString()).contains("KIWOOM_REST_MOCK", "MOCK", "2026-06-17", "shares");
        assertThat(facts.toString()).doesNotContain("999999");
    }

    @Test void originalClaimsGenerateEvidenceWithoutTrustingAiEvidence() throws Exception {
        var response = response("196170", List.of(Map.of("factId", "196170:/currentPrice", "value", "330,000")), "확인된 가격을 기준으로 관찰");
        response.put("evidence", List.of("허위 목표가 999999원"));
        var verdict = parseResponse(response, packet("196170")).get("196170");
        assertThat(verdict).isNotNull();
        assertThat(verdict.evidence()).anyMatch(text -> text.contains("현재가") && text.contains("330000") && text.contains("원"));
        assertThat(verdict.evidence().toString()).doesNotContain("/metrics", "/currentPrice", "source=", "unit=");
        assertThat(verdict.evidence().toString()).doesNotContain("999999");
    }

    @Test void legacyTextEvidenceCannotBecomeVerifiedJudgment() throws Exception {
        var response = response("196170", List.of(), "근거 요약");
        response.remove("claims"); response.put("evidence", List.of("외국인 순매수"));
        assertThat(parseResponse(response, packet("196170"))).isEmpty();
    }

    @Test void rejectsUnknownCrossStockWrongValueAndRepeatedClaims() throws Exception {
        for (var claims : List.of(
            List.of(Map.of("factId", "196170:/metrics/unknown", "value", 900000)),
            List.of(Map.of("factId", "005930:/metrics/foreignNet", "value", 900000)),
            List.of(Map.of("factId", "196170:/metrics/foreignNet", "value", 900001)),
            List.of(Map.of("factId", "196170:/metrics/foreignNet", "value", 900000), Map.of("factId", "196170:/metrics/foreignNet", "value", 900000)))) {
            assertThat(parseResponse(response("196170", claims, "근거 요약"), packet("196170"))).isEmpty();
        }
    }

    @Test void rejectsClaimProvenanceOverridesAndUnitConversions() throws Exception {
        for (var claim : List.of(
            Map.of("factId", "196170:/currentPrice", "value", "33만원"),
            Map.of("factId", "196170:/currentPrice", "value", "330,00"),
            Map.of("factId", "196170:/metrics/kiwoomEvidence/investorDaily/foreignNetBuy", "value", 1200, "observedDate", "2026-06-18"),
            Map.of("factId", "196170:/metrics/kiwoomEvidence/investorDaily/foreignNetBuy", "value", 1200, "environment", "REAL"),
            Map.of("factId", "196170:/metrics/kiwoomEvidence/investorDaily/foreignNetBuy", "value", 1200, "unit", "KRW"))) {
            assertThat(parseResponse(response("196170", List.of(claim), "근거 요약"), richPacket("196170"))).isEmpty();
        }
    }

    @Test void signedDecimalsAreComparedNumericallyWithoutLosingDirection() throws Exception {
        var packet = new EvidencePacket("LEADERS", "FLOW_LEADER", "2026-06-18T10:20:00+09:00",
            "196170", "알테오젠", "330,000", "-1.5%", 88, List.of("수급 관찰"), Map.of("foreignNet", new java.math.BigDecimal("-1200.50")));
        assertThat(parseResponse(response("196170", List.of(Map.of("factId", "196170:/metrics/foreignNet", "value", "−1,200.500")), "수급 감소 관찰"), packet)).containsKey("196170");
        assertThat(parseResponse(response("196170", List.of(Map.of("factId", "196170:/metrics/foreignNet", "value", 1200.5)), "수급 감소 관찰"), packet)).isEmpty();
    }

    @Test void numericNarrativeAndUnconfirmedMetricsFallBackToRuleScore() throws Exception {
        for (String narrative : List.of("목표가 999999원", "순매수 1억", "미래 2026-06-19 수급", "수급 −123.4주", "가격 ３３만원", "수급 일억 원", "수급 삼천주", "거래량 두 배", "수량 세 주", "변동성 열 퍼센트", "보유 한 달", "거래량 두 배로 증가", "수량 세 주를 매수")) {
            assertThat(parseResponse(response("196170", List.of(Map.of("factId", "196170:/currentPrice", "value", 330000)), narrative), packet("196170"))).isEmpty();
        }
        assertThat(parseResponse(response("196170", List.of(Map.of("factId", "196170:/currentPrice", "value", 330000)), "불확실한 주가 흐름"), packet("196170"))).containsKey("196170");
        var pendingClaim = Map.of("factId", "196170:/metrics/kiwoomEvidence/technical/ma20", "value", 999999);
        assertThat(parseResponse(response("196170", List.of(pendingClaim), "차트 확인"), richPacket("196170"))).isEmpty();
    }

    @Test void evidenceUsesHumanLabelsButKeepsOriginalSourceAndObservationContext() throws Exception {
        var verdict = parseResponse(response("196170", List.of(Map.of("factId", "196170:/metrics/kiwoomEvidence/investorDaily/foreignNetBuy", "value", 1200)), "수급 관찰"), richPacket("196170")).get("196170");
        assertThat(verdict).isNotNull();
        assertThat(verdict.evidence().get(0)).contains("외국인 순매수", "1200", "출처", "키움 REST", "MOCK", "환경", "관측일", "2026-06-17", "단위", "주");
        assertThat(verdict.evidence().get(0)).doesNotContain("/metrics/", "foreignNetBuy", "source=", "observedDate=", "shares");
        var odd = new EvidencePacket("LEADERS", "FLOW_LEADER", "2026-06-18T10:20:00+09:00", "196170", "알테오젠", "330,000", "+6.0", 88, List.of("관찰"), Map.of("internalPrivateMetric",7));
        var unknown = parseResponse(response("196170", List.of(Map.of("factId", "196170:/metrics/internalPrivateMetric", "value", 7)), "관찰"), odd).get("196170");
        assertThat(unknown.evidence().get(0)).contains("입력 지표", "7").doesNotContain("internalPrivateMetric");
    }

    @Test void allNarrativeFieldsMustRemainQualitative() throws Exception {
        for (String field : List.of("invalidation", "displayStatus", "riskFlags")) {
            var response = response("196170", List.of(Map.of("factId", "196170:/currentPrice", "value", 330000)), "관찰");
            response.put(field, "riskFlags".equals(field) ? List.of("수급 77억") : "수급 77억");
            assertThat(parseResponse(response, packet("196170"))).isEmpty();
        }
    }

    @Test void duplicateInputCandidatesAndMalformedClaimTypesCannotBeVerified() throws Exception {
        var response = response("196170", List.of(Map.of("factId", "196170:/currentPrice", "value", 330000)), "관찰");
        assertThat(verifier.parse(new ObjectMapper().writeValueAsString(response), List.of(packet("196170"), richPacket("196170")))).isEmpty();
        for (Object wrongValue : List.of(true, List.of(330000), Map.of("value",330000))) {
            assertThat(parseResponse(response("196170", List.of(Map.of("factId", "196170:/currentPrice", "value", wrongValue)), "관찰"), packet("196170"))).isEmpty();
        }
    }

    @Test void aBadCandidateDoesNotPoisonAnotherCandidatesVerifiedFacts() throws Exception {
        var good = response("196170", List.of(Map.of("factId", "196170:/currentPrice", "value", 330000)), "관찰");
        var bad = response("005930", List.of(Map.of("factId", "196170:/currentPrice", "value", 330000)), "관찰");
        assertThat(verifier.parse(new ObjectMapper().writeValueAsString(List.of(good,bad)),List.of(packet("196170"),packet("005930"))))
            .containsOnlyKeys("196170");
    }

    @Test void duplicateJsonKeysCannotSilentlyReplaceClaims() throws Exception {
        var json = new ObjectMapper().writeValueAsString(response("196170", List.of(Map.of("factId", "196170:/currentPrice", "value", 330000)), "관찰"));
        assertThat(verifier.parse(json.replace("\"value\":330000", "\"value\":999999,\"value\":330000"),List.of(packet("196170")))).isEmpty();
    }

    @Test void journalCapturesInputsBeforeAvailabilityAndOnlySafeDecisionFields() throws Exception {
        var journal = mock(AnalysisEvidenceJournalService.class);
        verifier.configureEvidenceJournal(journal);
        assertThat(verifier.verify(List.of(packet("196170")))).isEmpty();
        verify(journal).capture(eq("CONDITION_VERIFY"),eq("FLOW_LEADER"),eq(List.of("196170")),anyMap(),eq(List.of()));

        var agent = mock(DeepSeekAgent.class);
        when(agent.isAvailable()).thenReturn(true);
        var content = new ObjectMapper().writeValueAsString(response("196170", List.of(Map.of("factId", "196170:/currentPrice", "value", 330000)), "관찰"));
        when(agent.analyze(anyString(),anyString())).thenReturn(new AgentResult("deepseek",content,"test",0,0,"success",null,1));
        var active = new DeepSeekConditionVerifier(agent,new ObjectMapper());
        active.configureEvidenceJournal(journal);
        assertThat(active.verify(List.of(packet("196170")))).containsKey("196170");
        verify(journal).capture(eq("CONDITION_VERIFY"),eq("FLOW_LEADER"),eq(List.of("196170")),anyMap(),
            eq(List.of(Map.of("stockCode","196170","aiScore",82,"verdict","VALID"))));
    }

    @Test void journalFailureCannotStopRuleFallback() {
        var journal = mock(AnalysisEvidenceJournalService.class);
        when(journal.capture(anyString(),anyString(),anyList(),anyMap(),anyList())).thenThrow(new IllegalStateException("hidden payload"));
        verifier.configureEvidenceJournal(journal);
        assertThat(verifier.verify(List.of(packet("196170")))).isEmpty();
    }

    private Map<String, EvidenceVerdict> parseResponse(Map<String, Object> response, EvidencePacket packet) throws Exception {
        return verifier.parse(new ObjectMapper().writeValueAsString(Map.of("signals", List.of(response))), List.of(packet));
    }

    private static Map<String, Object> response(String code, List<?> claims, String summary) {
        var result = new java.util.LinkedHashMap<String,Object>();
        result.put("stockCode", code); result.put("aiScore", 82); result.put("verdict", "VALID");
        result.put("summary", summary); result.put("claims", claims); result.put("riskFlags", List.of("변동성"));
        result.put("evidence", List.of("원문 근거"));
        result.put("invalidation", "수급 이탈"); result.put("displayStatus", "관찰");
        return result;
    }

    private static EvidencePacket richPacket(String code) {
        return new EvidencePacket("LEADERS", "FLOW_LEADER", "2026-06-18T10:20:00+09:00", code, "알테오젠", "330,000", "+6.0", 88, List.of("쌍끌이"),
            Map.of("kiwoomEvidence", Map.of("investorDaily", Map.of("status", "AVAILABLE", "source", "KIWOOM_REST_MOCK", "environment", "MOCK", "observedDate", "2026-06-17", "unit", "shares", "foreignNetBuy", 1200),
                "technical", Map.of("status", "PENDING", "ma20", 999999))));
    }

    private static EvidencePacket packet(String code) {
        return new EvidencePacket("LEADERS", "FLOW_LEADER", "2026-06-18T10:20:00+09:00",
            code, "알테오젠", "330,000", "+6.0", 88, List.of("쌍끌이"), Map.of("foreignNet", 900_000));
    }
}
