package com.bitman.justbuy.research.event;

import org.junit.jupiter.api.Test;
import com.bitman.justbuy.service.DartFinancialEvidenceService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CashImpactCalculatorTest {
    private static final Instant CUTOFF = Instant.parse("2026-10-14T07:00:00Z");
    private final CashImpactCalculator calculator = new CashImpactCalculator();

    @Test void calculatesAllocationRecognitionAndMarginAsAnExplicitForecastInterval() {
        // A wrong multiplication or treating contract total as quarterly flow breaks this test.
        var result = calculator.calculate(input("ORIGINAL", "100000000000", null, assumptions(null, null, null, null)));
        assertThat(result.incrementalContractAmountWon()).isEqualByComparingTo("100000000000");
        assertRange(result.recognizedRevenueWon(), "10000000000", "30000000000");
        assertRange(result.operatingProfitWon(), "500000000", "3000000000");
        assertThat(result.cashFlowWon()).isNull();
        assertThat(result.issues()).contains("CASH_INPUTS_UNKNOWN", "HISTORICAL_ISSUER_MARGIN_PROXY");
        assertThat(result.scenarios()).extracting(CashImpactCalculator.Scenario::classification).containsOnly("SCENARIO");
        assertThat(result.observed().contractAmountWon()).isEqualByComparingTo("100000000000");
        assertThat(result.assumptions().marginBasis()).isEqualTo("HISTORICAL_ISSUER_MARGIN_PROXY");
    }

    @Test void computesCashOnlyWithExplicitTaxConversionWorkingCapitalAndCapex() {
        // Cash cannot be copied from operating profit or use zero for a missing cost.
        var explicit = assumptions(range("0.20", "0.30"), range("0.80", "0.90"),
            range("100000000", "200000000"), range("50000000", "100000000"));
        var result = calculator.calculate(input("ORIGINAL", "100000000000", null, explicit));
        assertRange(result.cashFlowWon(), "-20000000", "2010000000");
        assertThat(result.issues()).doesNotContain("CASH_INPUTS_UNKNOWN");
    }

    @Test void anAmendmentWithoutThePreviousAmountCannotBecomeANewPositiveOrder() {
        var result = calculator.calculate(input("AMENDED", "100000000000", null, assumptions(null, null, null, null)));
        assertThat(result.incrementalContractAmountWon()).isNull();
        assertThat(result.operatingProfitWon()).isNull();
        assertThat(result.scenarios()).isEmpty();
        assertThat(result.issues()).contains("PREVIOUS_AMOUNT_UNKNOWN", "AMENDMENT");
    }

    @Test void anAmendmentKeepsNegativeDeltaAndOrdersTheIntervalCorrectly() {
        // Taking abs(delta) or multiplying only matching range endpoints gives a false gain.
        var result = calculator.calculate(input("AMENDED", "60000000000", "100000000000", assumptions(null, null, null, null)));
        assertThat(result.incrementalContractAmountWon()).isEqualByComparingTo("-40000000000");
        assertRange(result.recognizedRevenueWon(), "-12000000000", "-4000000000");
        assertRange(result.operatingProfitWon(), "-1200000000", "-200000000");
        assertThat(result.issues()).contains("NEGATIVE_CONTRACT_DELTA");
    }

    @Test void marginCrossingZeroUsesAllProductCorners() {
        var a = new CashImpactCalculator.Assumptions(range("0.5", "0.6"), range("0.2", "0.5"),
            range("-0.05", "0.10"), null, null, null, null, LocalDate.parse("2026-10-01"),
            LocalDate.parse("2026-12-31"), null, "EXPLICIT_MARGIN_ASSUMPTION");
        var result = calculator.calculate(input("AMENDED", "60000000000", "100000000000", a));
        assertRange(result.operatingProfitWon(), "-1200000000", "600000000");
    }

    @Test void withdrawnContractsProduceNoIncrementalScenario() {
        var result = calculator.calculate(input("WITHDRAWN", "100000000000", "100000000000", assumptions(null, null, null, null)));
        assertThat(result.operatingProfitWon()).isNull();
        assertThat(result.issues()).contains("WITHDRAWAL");
    }

    @Test void aForecastOutsideTheContractPeriodIsInvalidated() {
        var normal = assumptions(null, null, null, null);
        var a = new CashImpactCalculator.Assumptions(normal.supplierAllocation(), normal.periodRecognition(), normal.operatingMargin(),
            null, null, null, null, LocalDate.parse("2027-01-01"), LocalDate.parse("2027-03-31"), null, normal.marginBasis());
        var result = calculator.calculate(input("ORIGINAL", "100000000000", null, a));
        assertThat(result.status()).isEqualTo("INVALIDATED");
        assertThat(result.operatingProfitWon()).isNull();
        assertThat(result.issues()).contains("PERIOD_INVALID");
    }

    @Test void revenueOverAnExplicitCapacityInvalidatesTheProfitEstimate() {
        var normal = assumptions(null, null, null, null);
        var a = new CashImpactCalculator.Assumptions(normal.supplierAllocation(), normal.periodRecognition(), normal.operatingMargin(),
            null, null, null, null, normal.forecastStart(), normal.forecastEnd(), decimal("29000000000"), normal.marginBasis());
        var result = calculator.calculate(input("ORIGINAL", "100000000000", null, a));
        assertThat(result.status()).isEqualTo("INVALIDATED");
        assertThat(result.operatingProfitWon()).isNull();
        assertThat(result.issues()).contains("CAPACITY_EXCEEDED");
    }

    @Test void unknownAndFutureEvidenceNeverBecomeHistoricalFinancialInputs() {
        var base = input("ORIGINAL", "100000000000", null, assumptions(null, null, null, null));
        var o = base.observed();
        var future = new CashImpactCalculator.Observed(o.supplierCode(), o.receiptNo(), o.amendmentStatus(), o.contractAmountWon(),
            null, o.startDate(), o.endDate(), o.firstSeenAt(), o.historicalRevenueWon(), o.historicalOperatingProfitWon(),
            o.financialPeriod(), o.financialSourceUrl(), CUTOFF.plusSeconds(1));
        var result = calculator.calculate(new CashImpactCalculator.Input(future, base.assumptions(), CUTOFF));
        assertThat(result.operatingProfitWon()).isNull();
        assertThat(result.issues()).contains("FUTURE_FINANCIAL_EVIDENCE");
    }

    @Test void allocationAndRecognitionMustBeFractionsAndMoneyMustBeBounded() {
        var normal = assumptions(null, null, null, null);
        var a = new CashImpactCalculator.Assumptions(range("0.5", "1.1"), normal.periodRecognition(), normal.operatingMargin(),
            null, null, null, null, normal.forecastStart(), normal.forecastEnd(), null, normal.marginBasis());
        assertThat(calculator.calculate(input("ORIGINAL", "100000000000", null, a)).issues()).contains("INVALID_ASSUMPTIONS");
        assertThat(calculator.calculate(input("ORIGINAL", "-1", null, normal)).operatingProfitWon()).isNull();
        assertThat(calculator.calculate(input("ORIGINAL", "0", null, normal)).issues()).contains("ZERO_CONTRACT_AMOUNT");
    }

    @Test void runtimeOverloadLabelsHistoricalIssuerMarginAndUsesQuarterTimeShareAsAnAssumption() {
        var result = calculator.calculate(contract(), finance("005930", CUTOFF.minusSeconds(1), LocalDate.parse("2026-03-20")), CUTOFF);
        assertThat(result.recognizedRevenueWon()).isNotNull();
        assertThat(result.recognizedRevenueWon().upper()).isCloseTo(new BigDecimal("25205479452.05479452"), within(new BigDecimal("0.01")));
        assertThat(result.operatingProfitWon().upper()).isCloseTo(new BigDecimal("2520547945.20547945"), within(new BigDecimal("0.01")));
        assertThat(result.assumptions().marginBasis()).isEqualTo("HISTORICAL_ISSUER_MARGIN_PROXY");
        assertThat(result.cashFlowWon()).isNull();
        assertThat(result.issues()).contains("STRAIGHT_LINE_TIME_SHARE_ASSUMPTION", "ISSUER_CONTRACT_SHARE_ASSUMPTION");
    }

    @Test void runtimeOverloadDoesNotUseMissingCrossIssuerOrFutureFinancialReports() {
        for (var evidence : new DartFinancialEvidenceService.StockEvidence[] { null,
            finance("000660", CUTOFF.minusSeconds(1), LocalDate.parse("2026-03-20")),
            finance("005930", CUTOFF.plusSeconds(1), LocalDate.parse("2026-03-20")),
            finance("005930", CUTOFF.minusSeconds(1), LocalDate.parse("2026-10-15")) }) {
            var result = calculator.calculate(contract(), evidence, CUTOFF);
            assertThat(result.operatingProfitWon()).isNull();
            assertThat(result.cashFlowWon()).isNull();
            assertThat(result.issues()).contains("FINANCIAL_EVIDENCE_UNKNOWN");
        }
    }

    static DartSupplyContractSource.Contract contract() {
        return new DartSupplyContractSource.Contract("005930", "공급회사", "실명 고객", "공시된 제품", new BigDecimal("100000000000"), null,
            new BigDecimal("200000000000"), LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), LocalDate.parse("2026-10-14"),
            "20261014000123", "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261014000123", CUTOFF.minusSeconds(1), "ORIGINAL", "원문 근거", "hash");
    }

    private static DartFinancialEvidenceService.StockEvidence finance(String code, Instant captured, LocalDate filing) {
        var facts = new DartFinancialEvidenceService.FinancialFacts(2025, "11011", "CFS", "KRW", "20260320000123", filing, "2025 ANNUAL",
            new BigDecimal("200000000000"), new BigDecimal("20000000000"), null, null, null, null, null, null, new BigDecimal("10"),
            null, "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260320000123", List.of(), "2025 ANNUAL", "2025 ANNUAL", "HISTORICAL_REPORT");
        return new DartFinancialEvidenceService.StockEvidence(code, "12345678", "AVAILABLE", captured, facts, List.of());
    }

    private static CashImpactCalculator.Input input(String amendment, String amount, String previous, CashImpactCalculator.Assumptions assumptions) {
        var observed = new CashImpactCalculator.Observed("005930", "20261014000123", amendment, decimal(amount), decimal(previous),
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), CUTOFF.minusSeconds(1), decimal("200000000000"),
            decimal("20000000000"), "2025 ANNUAL", "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260320000123", CUTOFF.minusSeconds(10));
        return new CashImpactCalculator.Input(observed, assumptions, CUTOFF);
    }

    private static CashImpactCalculator.Assumptions assumptions(CashImpactCalculator.Range tax, CashImpactCalculator.Range conversion,
        CashImpactCalculator.Range workingCapital, CashImpactCalculator.Range capex) {
        return new CashImpactCalculator.Assumptions(range("0.5", "0.6"), range("0.2", "0.5"), range("0.05", "0.10"),
            tax, conversion, workingCapital, capex, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"), null,
            "HISTORICAL_ISSUER_MARGIN_PROXY");
    }

    private static CashImpactCalculator.Range range(String low, String high) {
        return new CashImpactCalculator.Range(decimal(low), decimal(high));
    }
    private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
    private static void assertRange(CashImpactCalculator.Range actual, String lower, String upper) {
        assertThat(actual).isNotNull();
        assertThat(actual.lower()).isEqualByComparingTo(lower);
        assertThat(actual.upper()).isEqualByComparingTo(upper);
    }
}
