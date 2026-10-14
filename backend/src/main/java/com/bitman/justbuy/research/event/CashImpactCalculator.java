package com.bitman.justbuy.research.event;

import com.bitman.justbuy.service.DartFinancialEvidenceService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure interval arithmetic. A scenario is never an observed quarter profit or contract cash receipt. */
public class CashImpactCalculator {
    private static final MathContext MATH = new MathContext(34, RoundingMode.HALF_EVEN);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    public record Range(BigDecimal lower, BigDecimal upper) {}
    public record Observed(String supplierCode, String receiptNo, String amendmentStatus, BigDecimal contractAmountWon,
        BigDecimal previousAmountWon, LocalDate startDate, LocalDate endDate, Instant firstSeenAt,
        BigDecimal historicalRevenueWon, BigDecimal historicalOperatingProfitWon, String financialPeriod,
        String financialSourceUrl, Instant financialKnownAt) {}
    public record Assumptions(Range supplierAllocation, Range periodRecognition, Range operatingMargin,
        Range cashTaxRate, Range cashConversion, Range workingCapitalWon, Range capexWon, LocalDate forecastStart,
        LocalDate forecastEnd, BigDecimal capacityRevenueWon, String marginBasis) {}
    public record Input(Observed observed, Assumptions assumptions, Instant cutoff) {}
    public record Scenario(String id, String classification, String unit, Range revenueWon, Range operatingProfitWon, Range cashFlowWon) {}
    public record Result(String status, BigDecimal incrementalContractAmountWon, Range recognizedRevenueWon,
        Range operatingProfitWon, Range cashFlowWon, List<Scenario> scenarios, List<String> issues,
        Observed observed, Assumptions assumptions) {}
    public Result calculate(DartSupplyContractSource.Contract contract, DartFinancialEvidenceService.StockEvidence finance, Instant cutoff) {
        if (contract == null || cutoff == null) return calculate((Input) null);
        var facts = finance == null ? null : finance.facts();
        LocalDate date = cutoff.atZone(KOREA).toLocalDate();
        boolean usableFinance = finance != null && contract.supplierCode() != null && contract.supplierCode().equals(finance.stockCode())
            && Set.of("COMPLETE", "AVAILABLE", "PARTIAL", "PARTIAL_COVERAGE").contains(finance.status() == null ? "" : finance.status())
            && finance.capturedAt() != null && !finance.capturedAt().isAfter(cutoff) && facts != null && "KRW".equals(facts.currency())
            && facts.businessYear() <= date.getYear() && (facts.filingDate() == null || !facts.filingDate().isAfter(date))
            && money(facts.revenue()) && facts.revenue().signum() > 0 && money(facts.operatingProfit())
            && facts.period() != null && !facts.period().isBlank() && EventEvidenceVerifier.officialSource(facts.sourceUrl());
        Observed observed = new Observed(contract.supplierCode(), contract.receiptNo(), contract.amendmentStatus(), contract.amountWon(),
            contract.previousAmountWon(), contract.startDate(), contract.endDate(), contract.firstSeenAt(),
            usableFinance ? facts.revenue() : null, usableFinance ? facts.operatingProfit() : null,
            usableFinance ? facts.period() : null, usableFinance ? facts.sourceUrl() : null, usableFinance ? finance.capturedAt() : null);
        LocalDate forecastStart = LocalDate.of(date.getYear(), ((date.getMonthValue() - 1) / 3) * 3 + 1, 1);
        LocalDate forecastEnd = forecastStart.plusMonths(3).minusDays(1);
        Range recognition = null;
        if (contract.startDate() != null && contract.endDate() != null && !contract.endDate().isBefore(contract.startDate())) {
            LocalDate start = contract.startDate().isAfter(forecastStart) ? contract.startDate() : forecastStart;
            LocalDate end = contract.endDate().isBefore(forecastEnd) ? contract.endDate() : forecastEnd;
            long overlap = end.isBefore(start) ? 0 : ChronoUnit.DAYS.between(start, end) + 1;
            long total = ChronoUnit.DAYS.between(contract.startDate(), contract.endDate()) + 1;
            recognition = point(BigDecimal.valueOf(overlap).divide(BigDecimal.valueOf(total), 18, RoundingMode.HALF_EVEN));
        }
        Range margin = null;
        if (usableFinance) {
            BigDecimal full = facts.operatingProfit().divide(facts.revenue(), 18, RoundingMode.HALF_EVEN);
            BigDecimal half = full.multiply(new BigDecimal("0.5"));
            // Multiplying an odd-ending 18-place ratio by one half creates 19 places.
            // Quantize generated bounds outward to the supported precision; keep input validation strict.
            margin = new Range(full.min(half).setScale(18,RoundingMode.FLOOR), full.max(half).setScale(18,RoundingMode.CEILING));
        }
        Assumptions assumptions = new Assumptions(point(BigDecimal.ONE), recognition, margin, null, null, null, null,
            forecastStart, forecastEnd, null, "HISTORICAL_ISSUER_MARGIN_PROXY");
        Result calculated;
        if (!EventEvidenceVerifier.officialSource(contract.sourceUrl()) || contract.filingDate() != null && contract.filingDate().isAfter(date))
            calculated = result("UNKNOWN", null, null, null, null, new ArrayList<>(), observed, assumptions, "CONTRACT_EVIDENCE_UNKNOWN_OR_FUTURE");
        else calculated = calculate(new Input(observed, assumptions, cutoff));
        var issues = new ArrayList<>(calculated.issues());
        if (!usableFinance) issues.add("FINANCIAL_EVIDENCE_UNKNOWN");
        if (usableFinance && facts.filingDate() == null) issues.add("FINANCIAL_FILING_DATE_UNKNOWN_CAPTURE_TIME_USED");
        issues.add("STRAIGHT_LINE_TIME_SHARE_ASSUMPTION"); issues.add("ISSUER_CONTRACT_SHARE_ASSUMPTION");
        return new Result(calculated.status(), calculated.incrementalContractAmountWon(), calculated.recognizedRevenueWon(),
            calculated.operatingProfitWon(), calculated.cashFlowWon(), calculated.scenarios(), List.copyOf(issues), observed, assumptions);
    }
    public Result calculate(Input input) {
        var issues = new ArrayList<String>();
        Observed observed = input == null ? null : input.observed();
        Assumptions assumptions = input == null ? null : input.assumptions();
        if (input == null || observed == null || input.cutoff() == null)
            return result("UNKNOWN", null, null, null, null, issues, observed, assumptions, "OBSERVED_INPUT_UNKNOWN");
        if (observed.supplierCode() == null || !observed.supplierCode().matches("[0-9]{6}")
            || observed.firstSeenAt() == null || observed.firstSeenAt().isAfter(input.cutoff()))
            return result("UNKNOWN", null, null, null, null, issues, observed, assumptions, "CONTRACT_EVIDENCE_UNKNOWN_OR_FUTURE");
        if ("WITHDRAWN".equals(observed.amendmentStatus()))
            return result("INVALIDATED", null, null, null, null, issues, observed, assumptions, "WITHDRAWAL");
        if (!Set.of("ORIGINAL", "AMENDED").contains(observed.amendmentStatus() == null ? "" : observed.amendmentStatus()))
            return result("UNKNOWN", null, null, null, null, issues, observed, assumptions, "AMENDMENT_STATUS_UNKNOWN");
        BigDecimal amount = observed.contractAmountWon();
        if (!money(amount) || amount.signum() < 0)
            return result("UNKNOWN", null, null, null, null, issues, observed, assumptions, "CONTRACT_AMOUNT_UNKNOWN_OR_INVALID");
        if (amount.signum() == 0 && "ORIGINAL".equals(observed.amendmentStatus()))
            return result("UNKNOWN", null, null, null, null, issues, observed, assumptions, "ZERO_CONTRACT_AMOUNT");
        BigDecimal delta = amount;
        if ("AMENDED".equals(observed.amendmentStatus())) {
            issues.add("AMENDMENT");
            if (!money(observed.previousAmountWon()) || observed.previousAmountWon().signum() < 0)
                return result("UNKNOWN", null, null, null, null, issues, observed, assumptions, "PREVIOUS_AMOUNT_UNKNOWN");
            delta = amount.subtract(observed.previousAmountWon());
        }
        if (delta.signum() < 0) issues.add("NEGATIVE_CONTRACT_DELTA");
        if (delta.signum() == 0) issues.add("NO_INCREMENTAL_CONTRACT_AMOUNT");
        if (observed.startDate() == null || observed.endDate() == null
            || observed.endDate().isBefore(observed.startDate()))
            return result("UNKNOWN", delta, null, null, null, issues, observed, assumptions, "CONTRACT_PERIOD_UNKNOWN");
        if (assumptions == null || !fraction(assumptions.supplierAllocation()) || !fraction(assumptions.periodRecognition())
            || !valid(assumptions.operatingMargin()) || assumptions.operatingMargin().lower().compareTo(BigDecimal.ONE.negate()) < 0
            || assumptions.operatingMargin().upper().compareTo(BigDecimal.ONE) > 0 || assumptions.marginBasis() == null
            || assumptions.marginBasis().isBlank())
            return result("UNKNOWN", delta, null, null, null, issues, observed, assumptions, "INVALID_ASSUMPTIONS");
        if (assumptions.forecastStart() == null || assumptions.forecastEnd() == null
            || assumptions.forecastEnd().isBefore(assumptions.forecastStart())
            || assumptions.forecastStart().isAfter(observed.endDate()) || assumptions.forecastEnd().isBefore(observed.startDate()))
            return result("INVALIDATED", delta, null, null, null, issues, observed, assumptions, "PERIOD_INVALID");
        if (observed.financialKnownAt() != null && observed.financialKnownAt().isAfter(input.cutoff()))
            return result("UNKNOWN", delta, null, null, null, issues, observed, assumptions, "FUTURE_FINANCIAL_EVIDENCE");
        if ("HISTORICAL_ISSUER_MARGIN_PROXY".equals(assumptions.marginBasis())) {
            issues.add("HISTORICAL_ISSUER_MARGIN_PROXY");
            if (!money(observed.historicalRevenueWon()) || observed.historicalRevenueWon().signum() <= 0
                || !money(observed.historicalOperatingProfitWon()) || observed.financialKnownAt() == null
                || observed.financialPeriod() == null || observed.financialPeriod().isBlank()
                || !EventEvidenceVerifier.officialSource(observed.financialSourceUrl()))
                return result("UNKNOWN", delta, null, null, null, issues, observed, assumptions, "FINANCIAL_EVIDENCE_UNKNOWN");
        }
        issues.add("SUPPLIER_ALLOCATION_ASSUMPTION");
        issues.add("PERIOD_RECOGNITION_ASSUMPTION");
        Range revenue = multiply(multiply(point(delta), assumptions.supplierAllocation()), assumptions.periodRecognition());
        if (assumptions.capacityRevenueWon() != null && (!money(assumptions.capacityRevenueWon())
            || assumptions.capacityRevenueWon().signum() < 0 || revenue.upper().compareTo(assumptions.capacityRevenueWon()) > 0))
            return result("INVALIDATED", delta, revenue, null, null, issues, observed, assumptions, "CAPACITY_EXCEEDED");
        Range profit = multiply(revenue, assumptions.operatingMargin());
        Range cash = null;
        if (assumptions.cashTaxRate() == null || assumptions.cashConversion() == null
            || assumptions.workingCapitalWon() == null || assumptions.capexWon() == null) issues.add("CASH_INPUTS_UNKNOWN");
        else if (!fraction(assumptions.cashTaxRate()) || !fraction(assumptions.cashConversion())
            || !valid(assumptions.workingCapitalWon()) || !valid(assumptions.capexWon()) || assumptions.capexWon().lower().signum() < 0)
            issues.add("CASH_INPUTS_INVALID");
        else {
            Range afterTax = multiply(profit, new Range(BigDecimal.ONE.subtract(assumptions.cashTaxRate().upper()),
                BigDecimal.ONE.subtract(assumptions.cashTaxRate().lower())));
            cash = subtract(subtract(multiply(afterTax, assumptions.cashConversion()), assumptions.workingCapitalWon()), assumptions.capexWon());
        }
        var scenarios = List.of(
            new Scenario("CONSERVATIVE", "SCENARIO", "KRW", point(revenue.lower()), point(profit.lower()), cash == null ? null : point(cash.lower())),
            new Scenario("BASE", "SCENARIO", "KRW", revenue, profit, cash));
        return new Result(cash == null ? "PARTIAL" : "AVAILABLE", delta, revenue, profit, cash, scenarios, List.copyOf(issues), observed, assumptions);
    }

    private static Result result(String status, BigDecimal delta, Range revenue, Range profit, Range cash, List<String> issues,
        Observed observed, Assumptions assumptions, String issue) {
        issues.add(issue);
        return new Result(status, delta, revenue, profit, cash, List.of(), List.copyOf(issues), observed, assumptions);
    }
    private static boolean money(BigDecimal value) {
        if (value == null) return false;
        BigDecimal canonical = value.stripTrailingZeros();
        return canonical.precision() <= 30 && Math.abs((long) canonical.scale()) <= 18;
    }
    private static boolean valid(Range range) {
        return range != null && money(range.lower()) && money(range.upper()) && range.lower().compareTo(range.upper()) <= 0;
    }
    private static boolean fraction(Range range) {
        return valid(range) && range.lower().signum() >= 0 && range.upper().compareTo(BigDecimal.ONE) <= 0;
    }
    private static Range point(BigDecimal value) { return new Range(value, value); }
    private static Range multiply(Range left, Range right) {
        var values = List.of(left.lower().multiply(right.lower(), MATH), left.lower().multiply(right.upper(), MATH),
            left.upper().multiply(right.lower(), MATH), left.upper().multiply(right.upper(), MATH));
        return new Range(values.stream().min(BigDecimal::compareTo).orElseThrow(), values.stream().max(BigDecimal::compareTo).orElseThrow());
    }
    private static Range subtract(Range left, Range right) {
        return new Range(left.lower().subtract(right.upper()), left.upper().subtract(right.lower()));
    }
}
