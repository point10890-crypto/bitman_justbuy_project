package com.bitman.justbuy.research.event;

import com.bitman.justbuy.research.ResearchLearningService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure long-only, unlevered, fixed-horizon reference comparison in percentage units.
 * The caller must durably freeze both selections and their union before future outcomes exist,
 * and must supply returns from the same verified KRX session window and adjustment basis.
 * This code checks coverage and weights; it cannot authenticate a caller's realSource flag or
 * independently verify a calendar. Reference price simulations are never actual fills or profit proof.
 */
public final class EventResearchPortfolioEvaluator {
    public static final String INSTRUMENT = "FROZEN_PORTFOLIO_UNION";
    public static final String EVALUATION_POLICY_VERSION = "FROZEN_PORTFOLIO_NEXT_SESSION_OPEN_V1";
    public static final String COST_POLICY_VERSION = "VERIFIED_WEIGHTED_ROUND_TRIP_COST_V1";
    private static final Set<Integer> HORIZONS = Set.of(1, 3, 5, 20);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    public record Selection(String policyId, Map<String, BigDecimal> weights) {
        // Snapshot identity is immutable even if the caller later changes a ranking/map.
        public Selection { if (weights != null) weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights)); }
    }
    public record StockReturn(String stockCode, String state, BigDecimal grossReturnPct, boolean realSource) {}
    public record Comparison(String state, int horizonSessions, BigDecimal baselineGross, BigDecimal challengerGross,
        BigDecimal baselineNet, BigDecimal challengerNet, BigDecimal netDelta, List<String> reasons) {
        public Comparison { reasons = reasons == null ? List.of() : List.copyOf(reasons); }
    }
    public Comparison compare(Selection baseline, Selection challenger, List<StockReturn> returns, int horizonSessions,
        BigDecimal roundTripCostPct, boolean costsVerified) {
        var reasons = new LinkedHashSet<String>();
        if (!HORIZONS.contains(horizonSessions)) reasons.add("INVALID_HORIZON");
        if (!validSelection(baseline) || !validSelection(challenger)) reasons.add("INVALID_SELECTION");
        if (baseline != null && challenger != null && baseline.policyId() != null && baseline.policyId().equals(challenger.policyId())) reasons.add("SAME_POLICY_ID");
        if (roundTripCostPct != null && (!decimal(roundTripCostPct) || roundTripCostPct.signum() < 0)) reasons.add("INVALID_COST");
        if (!reasons.isEmpty()) return absent("UNMEASURABLE", horizonSessions, reasons);

        reasons.add("FROZEN_PORTFOLIO_UNION"); reasons.add("REFERENCE_SIMULATION_NOT_REALIZED_PNL");
        reasons.add("NO_AUTOMATIC_PROMOTION");
        BigDecimal baselineTraded = sumWeights(baseline), challengerTraded = sumWeights(challenger);
        if (baselineTraded.compareTo(BigDecimal.ONE) < 0 || challengerTraded.compareTo(BigDecimal.ONE) < 0)
            reasons.add("CASH_REMAINDER_ZERO_RETURN_ASSUMPTION");
        var union = new TreeSet<String>();
        addPositive(union, baseline); addPositive(union, challenger);
        var byCode = new TreeMap<String, List<StockReturn>>();
        boolean invalid = false, pending = false;
        if (returns != null) for (StockReturn row : returns) {
            if (row == null || row.stockCode() == null || !row.stockCode().matches("[0-9]{6}")) {
                reasons.add("MALFORMED_RETURN"); invalid = true;
            } else if (union.contains(row.stockCode())) byCode.computeIfAbsent(row.stockCode(), ignored -> new ArrayList<>()).add(row);
        }
        // Every traded instrument from either policy is required, not just the shared names.
        for (String code : union) {
            List<StockReturn> rows = byCode.get(code);
            if (rows == null || rows.isEmpty()) { reasons.add("MISSING_RETURN:" + code); invalid = true; continue; }
            if (rows.size() != 1) { reasons.add("DUPLICATE_RETURN:" + code); invalid = true; continue; }
            StockReturn row = rows.getFirst();
            if (!row.realSource()) { reasons.add("NON_REAL_RETURN:" + code); invalid = true; }
            if ("PENDING".equals(row.state())) { reasons.add("RETURN_PENDING:" + code); pending = true; }
            else if (!"COMPLETE".equals(row.state())) { reasons.add("RETURN_NOT_COMPLETE:" + code); invalid = true; }
            else if (!decimal(row.grossReturnPct()) || row.grossReturnPct().compareTo(HUNDRED.negate()) < 0) {
                reasons.add("RETURN_VALUE_UNKNOWN_OR_INVALID:" + code); invalid = true;
            }
        }
        if (invalid) return absent("UNMEASURABLE", horizonSessions, reasons);
        if (pending) return absent("PENDING", horizonSessions, reasons);
        BigDecimal baseGross = weighted(baseline, byCode), newGross = weighted(challenger, byCode);
        if (roundTripCostPct == null || !costsVerified) {
            reasons.add("COSTS_UNKNOWN_OR_UNVERIFIED");
            return new Comparison("GROSS_ONLY", horizonSessions, baseGross, newGross, null, null, null, List.copyOf(reasons));
        }
        reasons.add("COST_CHARGED_ON_TRADED_WEIGHTS_ONLY");
        BigDecimal baseNet = baseGross.subtract(roundTripCostPct.multiply(baselineTraded));
        BigDecimal newNet = newGross.subtract(roundTripCostPct.multiply(challengerTraded));
        return new Comparison("COMPLETE", horizonSessions, baseGross, newGross, baseNet, newNet, newNet.subtract(baseNet), List.copyOf(reasons));
    }
    public static List<ResearchLearningService.Observation> learningObservations(Comparison comparison,
        LocalDate decisionDate, LocalDate outcomeEndDate, Selection baseline, Selection challenger, String inputVersion) {
        if (comparison == null || !validSelection(baseline) || !validSelection(challenger)
            || baseline.policyId().equals(challenger.policyId()) || decisionDate == null || inputVersion == null
            || inputVersion.isBlank() || inputVersion.length() > 128 || !HORIZONS.contains(comparison.horizonSessions())) return List.of();
        var reasons = new LinkedHashSet<>(comparison.reasons());
        reasons.add("RETURNS_AS_FRACTIONS_FOR_LEARNING"); reasons.add("NO_AUTOMATIC_PROMOTION");
        boolean window = outcomeEndDate != null && outcomeEndDate.isAfter(decisionDate)
            && ChronoUnit.DAYS.between(decisionDate, outcomeEndDate) >= comparison.horizonSessions() - 1L;
        if (!window) reasons.add("INVALID_OUTCOME_WINDOW");
        boolean costs = "COMPLETE".equals(comparison.state()) && comparison.baselineGross() != null && comparison.challengerGross() != null
            && comparison.baselineNet() != null && comparison.challengerNet() != null && comparison.netDelta() != null
            && comparison.baselineNet().compareTo(comparison.baselineGross()) <= 0 && comparison.challengerNet().compareTo(comparison.challengerGross()) <= 0
            && comparison.challengerNet().subtract(comparison.baselineNet()).compareTo(comparison.netDelta()) == 0;
        String measurement = !window ? "UNMEASURABLE" : costs ? "MEASURED" : comparison.state();
        if ("COMPLETE".equals(measurement)) { measurement = "UNMEASURABLE"; reasons.add("INVALID_COMPARISON"); }
        boolean real = reasons.stream().noneMatch(reason -> reason.startsWith("NON_REAL_RETURN:"));
        var origin = real ? ResearchLearningService.Origin.ACTUAL_CAPTURE : ResearchLearningService.Origin.MOCK_FIXTURE;
        return List.of(
            observation(baseline.policyId(), comparison.baselineGross(), costs ? comparison.baselineNet() : null,
                comparison.horizonSessions(), decisionDate, outcomeEndDate, inputVersion, costs && window, measurement, origin, reasons),
            observation(challenger.policyId(), comparison.challengerGross(), costs ? comparison.challengerNet() : null,
                comparison.horizonSessions(), decisionDate, outcomeEndDate, inputVersion, costs && window, measurement, origin, reasons));
    }

    private static ResearchLearningService.Observation observation(String policy, BigDecimal gross, BigDecimal net, int horizon,
        LocalDate date, LocalDate end, String inputVersion, boolean costs, String state, ResearchLearningService.Origin origin, Set<String> reasons) {
        return new ResearchLearningService.Observation(INSTRUMENT, date, end, horizon, policy, EVALUATION_POLICY_VERSION,
            COST_POLICY_VERSION, inputVersion, gross == null ? null : gross.divide(HUNDRED), net == null ? null : net.divide(HUNDRED),
            null, costs, state, origin, List.copyOf(reasons));
    }
    private static Comparison absent(String state, int horizon, Set<String> reasons) {
        return new Comparison(state, horizon, null, null, null, null, null, List.copyOf(reasons));
    }
    private static boolean validSelection(Selection selection) {
        if (selection == null || selection.policyId() == null || selection.policyId().isBlank() || selection.policyId().length() > 128
            || selection.weights() == null || selection.weights().size() > 128) return false;
        BigDecimal sum = BigDecimal.ZERO;
        for (var weight : selection.weights().entrySet()) {
            if (weight.getKey() == null || !weight.getKey().matches("[0-9]{6}") || !decimal(weight.getValue())
                || weight.getValue().signum() < 0 || weight.getValue().compareTo(BigDecimal.ONE) > 0) return false;
            sum = sum.add(weight.getValue());
        }
        return sum.compareTo(BigDecimal.ONE) <= 0;
    }
    private static boolean decimal(BigDecimal value) {
        if (value == null) return false;
        BigDecimal canonical = value.stripTrailingZeros();
        return canonical.precision() <= 30 && Math.abs((long)canonical.scale()) <= 18;
    }
    private static void addPositive(Set<String> union, Selection selection) {
        selection.weights().forEach((code, weight) -> { if (weight.signum() > 0) union.add(code); });
    }
    private static BigDecimal sumWeights(Selection selection) {
        return selection.weights().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static BigDecimal weighted(Selection selection, Map<String, List<StockReturn>> returns) {
        BigDecimal result = BigDecimal.ZERO;
        for (var weight : selection.weights().entrySet()) if (weight.getValue().signum() > 0)
            result = result.add(weight.getValue().multiply(returns.get(weight.getKey()).getFirst().grossReturnPct()));
        return result;
    }
}
