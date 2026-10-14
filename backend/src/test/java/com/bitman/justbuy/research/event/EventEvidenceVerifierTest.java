package com.bitman.justbuy.research.event;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventEvidenceVerifierTest {
    private static final Instant CUTOFF = Instant.parse("2026-10-14T07:00:00Z");
    private static final String URL = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261014000123";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void preservesServerFactsAndScenarioClassificationWhenAiQuotesThemExactly() {
        // Accepting a model source or reclassifying an estimate as observed breaks this test.
        try (var verifier = verifier(response("005930", validClaims()), "deepseek")) {
            var result = verifier.verify(input(List.of("CASH_INPUTS_UNKNOWN", "HISTORICAL_ISSUER_MARGIN_PROXY")));
            assertThat(result.status()).isEqualTo("AI_CHECKED");
            assertThat(result.claims()).hasSize(2);
            assertThat(result.claims().get(1).kind()).isEqualTo("SCENARIO");
            assertThat(result.claims().get(1).sourceUrl()).isEqualTo(URL);
            assertThat(result.risks()).contains("CASH_FLOW_UNKNOWN", "HISTORICAL_MARGIN_PROXY");
            assertThat(result.issues()).contains("FACT_FIDELITY_ONLY_NOT_PROFIT_VERIFICATION");
        }
    }

    @Test void maliciousClaimsCannotBeReportedAsAiCheckedEvidence() {
        // Wrong subject, invented amount, duplicate claim and scenario-as-fact are all untrusted.
        for (String claims : List.of(
            validClaims().replace("500000000", "500000001"),
            validClaims().replace("scenario:BASE:profit:lower", "scenario:INVENTED:profit:lower"),
            validClaims().replace("\"kind\":\"SCENARIO\"", "\"kind\":\"OBSERVED\""),
            validClaims().replace("\"value\":500000000", "\"value\":500000000,\"unit\":\"USD\""),
            "[{\"factId\":\"contract:amount\",\"value\":100000000000,\"kind\":\"OBSERVED\"},"
                + "{\"factId\":\"contract:amount\",\"value\":100000000000,\"kind\":\"OBSERVED\"}]")) {
            try (var verifier = verifier(response("005930", claims), "deepseek")) {
                var result = verifier.verify(input(List.of("CASH_INPUTS_UNKNOWN")));
                assertThat(result.status()).isEqualTo("RULE_ONLY");
                assertThat(result.claims()).isEmpty();
                assertThat(result.risks()).contains("CASH_FLOW_UNKNOWN");
            }
        }
        try (var verifier = verifier(response("000660", validClaims()), "deepseek")) {
            assertThat(verifier.verify(input(List.of())).status()).isEqualTo("RULE_ONLY");
        }
    }

    @Test void freeTextClaimsCannotSmuggleAFuturePriceForecastOrAConsensus() {
        for (String raw : List.of(
            response("005930", validClaims()).replace("\"riskFlags\":[\"MARGIN_ASSUMPTION\"]", "\"riskFlags\":[\"내일 목표가 999999원\"]"),
            response("005930", validClaims()).replace("\"verdict\":\"WATCH\"", "\"verdict\":\"CONSENSUS\""),
            response("005930", validClaims()).replace("\"invalidation\":\"RECOGNITION_OR_MARGIN_ASSUMPTION_FAILED\"", "\"invalidation\":\"실적 확정\""),
            response("005930", validClaims()).replace("\"subjectCode\":\"005930\"", "\"subjectCode\":\"000660\",\"subjectCode\":\"005930\""))) {
            try (var verifier = verifier(raw, "deepseek")) {
                assertThat(verifier.verify(input(List.of())).status()).isEqualTo("RULE_ONLY");
            }
        }
    }

    @Test void futureCrossSubjectAndUnofficialSourceFactsInvalidateTheInput() {
        for (var fact : List.of(
            new EventEvidenceVerifier.Fact("contract:amount", "005930", "OBSERVED", BigDecimal.ONE, "KRW", URL, CUTOFF.plusSeconds(1)),
            new EventEvidenceVerifier.Fact("contract:amount", "000660", "OBSERVED", BigDecimal.ONE, "KRW", URL, CUTOFF),
            new EventEvidenceVerifier.Fact("contract:amount", "005930", "OBSERVED", BigDecimal.ONE, "KRW", "https://dart.fss.or.kr.evil.example/original", CUTOFF))) {
            try (var verifier = verifier(response("005930", validClaims()), "deepseek")) {
                var result = verifier.verify(new EventEvidenceVerifier.Input("005930", CUTOFF, List.of(fact), List.of(), List.of()));
                assertThat(result.status()).isEqualTo("RULE_ONLY");
                assertThat(result.issues()).contains("INVALID_EVIDENCE_INPUT");
            }
        }
    }

    @Test void deterministicAdverseFactsRemainEvenWhenTheProviderSupportsTheInput() {
        try (var verifier = verifier(response("005930", validClaims()).replace("WATCH", "SUPPORTED_WITH_ASSUMPTIONS"), "deepseek")) {
            var result = verifier.verify(input(List.of("WITHDRAWAL", "AMENDMENT", "PREVIOUS_AMOUNT_UNKNOWN", "COUNTERPARTY_UNKNOWN", "CASH_INPUTS_UNKNOWN")));
            assertThat(result.verdict()).isEqualTo("REJECT");
            assertThat(result.risks()).contains("WITHDRAWAL", "AMENDMENT", "PREVIOUS_AMOUNT_UNKNOWN", "COUNTERPARTY_UNKNOWN", "CASH_FLOW_UNKNOWN");
        }
    }

    @Test void missingUnknownRefusingAndErrorProvidersKeepAnHonestRuleOnlyStatus() {
        try (var verifier = new EventEvidenceVerifier(null, MAPPER)) {
            assertThat(verifier.verify(input(List.of("CASH_INPUTS_UNKNOWN"))).status()).isEqualTo("RULE_ONLY");
        }
        try (var verifier = verifier(response("005930", validClaims()), "unknown-provider")) {
            assertThat(verifier.verify(input(List.of())).status()).isEqualTo("RULE_ONLY");
        }
        for (String raw : List.of("I cannot assist.", "", "{}", "[]", "```json\n" + response("005930", validClaims()) + "\n```")) {
            try (var verifier = verifier(raw, "deepseek")) {
                assertThat(verifier.verify(input(List.of())).status()).isEqualTo("RULE_ONLY");
            }
        }
        AiAgent failed = agent("deepseek", AgentResult.error("deepseek", "test", "Provider request failed", 1));
        try (var verifier = new EventEvidenceVerifier(failed, MAPPER)) {
            assertThat(verifier.verify(input(List.of())).status()).isEqualTo("RULE_ONLY");
        }
    }

    @Test void aSlowProviderIsBoundedAndCannotBeCalledAgainWhileItIsStillRunning() {
        AiAgent slow = new AiAgent() {
            public String name() { return "deepseek"; }
            public boolean isAvailable() { return true; }
            public AgentResult analyze(String system, String user) {
                long stop = System.nanoTime() + Duration.ofMillis(250).toNanos();
                while (System.nanoTime() < stop) Thread.onSpinWait();
                return AgentResult.skipped("deepseek", "slow fixture");
            }
        };
        try (var verifier = new EventEvidenceVerifier(slow, MAPPER, Duration.ofMillis(10))) {
            var first = verifier.verify(input(List.of()));
            var second = verifier.verify(input(List.of()));
            assertThat(first.status()).isEqualTo("RULE_ONLY");
            assertThat(first.issues()).contains("AI_TIMEOUT");
            assertThat(second.status()).isEqualTo("RULE_ONLY");
            assertThat(second.issues()).contains("AI_BUSY");
        }
    }

    @Test void runtimeCatalogUsesTheContractIdentityAndLabelsEveryProfitEstimateAsAScenario() {
        var contract = CashImpactCalculatorTest.contract();
        var observed = new CashImpactCalculator.Observed("005930", contract.receiptNo(), "ORIGINAL", contract.amountWon(), null,
            contract.startDate(), contract.endDate(), contract.firstSeenAt(), new BigDecimal("200000000000"), new BigDecimal("20000000000"),
            "2025 ANNUAL", URL, CUTOFF.minusSeconds(5));
        var range = new CashImpactCalculator.Range(new BigDecimal("500000000"), new BigDecimal("1000000000"));
        var scenario = new CashImpactCalculator.Scenario("BASE", "SCENARIO", "KRW", range, range, null);
        var calculation = new CashImpactCalculator.Result("PARTIAL", contract.amountWon(), range, range, null, List.of(scenario),
            List.of("CASH_INPUTS_UNKNOWN", "HISTORICAL_ISSUER_MARGIN_PROXY"), observed, null);
        try (var verifier = verifier(response("005930", validClaims()), "deepseek")) {
            var packet = verifier.input(contract, calculation, CUTOFF);
            assertThat(packet.facts()).anySatisfy(fact -> {
                assertThat(fact.id()).isEqualTo("contract:customerName");
                assertThat(fact.value()).isEqualTo("실명 고객");
                assertThat(fact.kind()).isEqualTo("OBSERVED");
            });
            var result = verifier.verify(contract, calculation, CUTOFF);
            assertThat(result.status()).isEqualTo("AI_CHECKED");
            assertThat(result.claims()).extracting(EventEvidenceVerifier.Claim::kind).containsExactly("OBSERVED", "SCENARIO");
        }
    }

    @Test void theRuleOnlyEntryPointPreservesAdverseFactsWithoutSpendingAPaidCall() {
        try (var verifier = verifier(response("005930", validClaims()), "deepseek")) {
            var result = verifier.ruleOnly(input(List.of("WITHDRAWAL", "CASH_INPUTS_UNKNOWN")), "AI_REFRESH_BUDGET_EXHAUSTED");
            assertThat(result.status()).isEqualTo("RULE_ONLY");
            assertThat(result.verdict()).isEqualTo("REJECT");
            assertThat(result.claims()).isEmpty();
            assertThat(result.risks()).contains("WITHDRAWAL", "CASH_FLOW_UNKNOWN");
            assertThat(result.issues()).contains("AI_REFRESH_BUDGET_EXHAUSTED");
        }
    }

    private static EventEvidenceVerifier verifier(String json, String provider) {
        return new EventEvidenceVerifier(agent(provider, new AgentResult(provider, json, "test", 5, 7, "success", null, 1)), MAPPER);
    }

    private static AiAgent agent(String provider, AgentResult output) {
        return new AiAgent() {
            public String name() { return provider; }
            public boolean isAvailable() { return true; }
            public AgentResult analyze(String system, String user) { return output; }
        };
    }

    private static String response(String subject, String claims) {
        return "{\"subjectCode\":\"" + subject + "\",\"verdict\":\"WATCH\",\"claims\":" + claims
            + ",\"riskFlags\":[\"MARGIN_ASSUMPTION\"],\"invalidation\":\"RECOGNITION_OR_MARGIN_ASSUMPTION_FAILED\"}";
    }

    private static String validClaims() {
        return "[{\"factId\":\"contract:amount\",\"value\":100000000000,\"kind\":\"OBSERVED\"},"
            + "{\"factId\":\"scenario:BASE:profit:lower\",\"value\":500000000,\"kind\":\"SCENARIO\"}]";
    }

    private static EventEvidenceVerifier.Input input(List<String> issues) {
        var facts = List.of(
            new EventEvidenceVerifier.Fact("contract:amount", "005930", "OBSERVED", new BigDecimal("100000000000"), "KRW", URL, CUTOFF.minusSeconds(1)),
            new EventEvidenceVerifier.Fact("scenario:BASE:profit:lower", "005930", "SCENARIO", new BigDecimal("500000000"), "KRW", URL, CUTOFF));
        return new EventEvidenceVerifier.Input("005930", CUTOFF, facts, List.of(), issues);
    }
}
