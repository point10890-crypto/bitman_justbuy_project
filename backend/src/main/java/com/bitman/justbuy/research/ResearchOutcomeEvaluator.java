package com.bitman.justbuy.research;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure offline evaluation. The caller supplies the authoritative, ordered KRX regular-session calendar.
 * Prices are research references, never actual fills. Gross/net/excess always describe the fixed-horizon
 * reference change; optional risk path bounds describe a separate price scenario, without costs or fills.
 * Published-reference observation requires an exact publication-time quote; old captures cannot establish
 * post-publication movement. The next-session-open simulation deliberately does not use the captured quote.
 * The initial next-session policy supports publication at or after the declared KRX regular close
 * (15:30 Asia/Seoul); earlier publication is outside this after-close research policy, not shifted forward.
 */
public final class ResearchOutcomeEvaluator {
    public enum Measurement { NEXT_SESSION_OPEN_SIMULATION, PUBLISHED_REFERENCE_OBSERVATION }
    public enum State { COMPLETE, PENDING, UNMEASURABLE, UNFILLABLE, AMBIGUOUS_PATH }
    public enum Adjustment { RAW, ADJUSTED, TOTAL_RETURN }
    public enum Provenance { REAL, MOCK }
    public enum PathState { NOT_REQUESTED, HORIZON_CLOSE, STOP, TARGET, GAP_STOP, GAP_TARGET, AMBIGUOUS_PATH }
    public record Signal(String signalId, String instrumentId, Instant publishedAt, Instant referenceObservedAt,
                         BigDecimal referencePrice, String venue, String session, Provenance provenance, Adjustment adjustmentBasis) {}
    public record SessionBar(String instrumentId, LocalDate date, Instant availableAt, BigDecimal open,
                             BigDecimal high, BigDecimal low, BigDecimal close, boolean entryEligible,
                             boolean unresolvedCorporateAction, String venue, String session,
                             Provenance provenance, Adjustment adjustmentBasis) {}
    public record Benchmark(LocalDate entryDate, LocalDate exitDate, String venue, String session,
                            Provenance provenance, Adjustment adjustmentBasis, BigDecimal grossReturnPct, Instant availableAt) {}
    public record Policy(String version, int horizonSessions, Measurement measurement, BigDecimal roundTripCostPct,
                         BigDecimal stopPrice, BigDecimal targetPrice) {}
    public record Outcome(String signalId, String policyVersion, int horizonSessions, Measurement measurement,
                          State state, PathState pathState, LocalDate entryDate, LocalDate exitDate,
                          BigDecimal referencePrice, BigDecimal exitPrice, BigDecimal grossReturnPct,
                          BigDecimal netReturnPct, BigDecimal excessReturnPct, BigDecimal lowerReturnPct,
                          BigDecimal upperReturnPct, List<String> reasons) {
        public Outcome { reasons = List.copyOf(reasons); }
    }

    private static final ZoneId KRX_ZONE = ZoneId.of("Asia/Seoul");
    private static final Set<Integer> HORIZONS = Set.of(1, 3, 5, 20);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public Outcome evaluate(Signal signal, List<LocalDate> orderedRegularSessions, List<SessionBar> bars,
                            Policy policy, Instant evaluatedAt, Benchmark benchmark) {
        if (policy == null || blank(policy.version()) || !HORIZONS.contains(policy.horizonSessions())
                || policy.measurement() == null || (policy.roundTripCostPct() != null && policy.roundTripCostPct().signum() < 0))
            return absent(signal, policy, State.UNMEASURABLE, null, null, "INVALID_POLICY");
        if (signal == null || blank(signal.signalId()) || blank(signal.instrumentId()) || signal.publishedAt() == null
                || signal.provenance() == null || signal.adjustmentBasis() == null
                || !"KRX".equals(signal.venue()) || !"REGULAR".equals(signal.session()))
            return absent(signal, policy, State.UNMEASURABLE, null, null, "INVALID_SIGNAL");
        if (evaluatedAt == null || signal.publishedAt().isAfter(evaluatedAt))
            return absent(signal, policy, State.UNMEASURABLE, null, null, "SIGNAL_NOT_AVAILABLE");
        LocalDate publishedDate = signal.publishedAt().atZone(KRX_ZONE).toLocalDate();
        if (policy.measurement() == Measurement.NEXT_SESSION_OPEN_SIMULATION && signal.publishedAt().isBefore(close(publishedDate)))
            return absent(signal, policy, State.UNMEASURABLE, null, null, "SIGNAL_BEFORE_REGULAR_CLOSE");
        if (!validCalendar(orderedRegularSessions))
            return absent(signal, policy, State.UNMEASURABLE, null, null, "INVALID_CALENDAR");

        int entryIndex = 0;
        while (entryIndex < orderedRegularSessions.size() && !orderedRegularSessions.get(entryIndex).isAfter(publishedDate)) entryIndex++;
        if (entryIndex == orderedRegularSessions.size())
            return absent(signal, policy, State.UNMEASURABLE, null, null, "CALENDAR_COVERAGE_MISSING");
        LocalDate entryDate = orderedRegularSessions.get(entryIndex);
        int exitIndex = entryIndex + policy.horizonSessions() - 1;
        if (exitIndex >= orderedRegularSessions.size())
            return absent(signal, policy, State.UNMEASURABLE, entryDate, null, "CALENDAR_COVERAGE_MISSING");
        LocalDate exitDate = orderedRegularSessions.get(exitIndex);

        if (policy.measurement() == Measurement.PUBLISHED_REFERENCE_OBSERVATION) {
            if (signal.referenceObservedAt() == null || !positive(signal.referencePrice()))
                return absent(signal, policy, State.UNMEASURABLE, entryDate, exitDate, "REFERENCE_MISSING");
            if (signal.referenceObservedAt().isBefore(signal.publishedAt()))
                return absent(signal, policy, State.UNMEASURABLE, entryDate, exitDate, "REFERENCE_PRECEDES_PUBLICATION");
            if (signal.referenceObservedAt().isAfter(signal.publishedAt()))
                return absent(signal, policy, State.UNMEASURABLE, entryDate, exitDate, "REFERENCE_AFTER_PUBLICATION");
            if (policy.stopPrice() != null || policy.targetPrice() != null)
                return absent(signal, policy, State.UNMEASURABLE, entryDate, exitDate, "RISK_PATH_REQUIRES_SIMULATION");
        }

        Map<LocalDate, List<SessionBar>> byDate = new HashMap<>();
        if (bars != null) for (SessionBar bar : bars) {
            if (bar != null && Objects.equals(bar.instrumentId(), signal.instrumentId()) && bar.date() != null)
                byDate.computeIfAbsent(bar.date(), ignored -> new ArrayList<>()).add(bar);
        }
        List<SessionBar> interval = new ArrayList<>();
        // Do not inspect future bars even if an input file includes them. Missing already-mature sessions
        // are data gaps, while a current/future session without a completed bar remains pending.
        for (int index = entryIndex; index <= exitIndex; index++) {
            LocalDate date = orderedRegularSessions.get(index);
            if (close(date).isAfter(evaluatedAt))
                return absent(signal, policy, State.PENDING, entryDate, exitDate, "HORIZON_NOT_MATURE");
            List<SessionBar> observations = byDate.get(date);
            if (observations == null || observations.isEmpty())
                return absent(signal, policy, State.UNMEASURABLE, entryDate, exitDate, "MISSING_SESSION_BAR");
            if (observations.size() != 1)
                return absent(signal, policy, State.UNMEASURABLE, entryDate, exitDate, "DUPLICATE_SESSION_BAR");
            SessionBar bar = observations.getFirst();
            String invalid = validateBar(signal, bar, evaluatedAt,
                    index == entryIndex && policy.measurement() == Measurement.NEXT_SESSION_OPEN_SIMULATION);
            if (invalid != null) return absent(signal, policy,
                    "ENTRY_NOT_ELIGIBLE".equals(invalid) ? State.UNFILLABLE : State.UNMEASURABLE, entryDate, exitDate, invalid);
            interval.add(bar);
        }
        BigDecimal reference = policy.measurement() == Measurement.NEXT_SESSION_OPEN_SIMULATION
                ? interval.getFirst().open() : signal.referencePrice();
        if (!validRisk(policy, reference))
            return absent(signal, policy, State.UNMEASURABLE, entryDate, exitDate, "INVALID_RISK_LEVELS");
        BigDecimal exit = interval.getLast().close();
        BigDecimal gross = pct(reference, exit);
        BigDecimal net = policy.roundTripCostPct() == null ? null : gross.subtract(policy.roundTripCostPct());
        List<String> reasons = new ArrayList<>();
        if (net == null) reasons.add("COSTS_UNKNOWN");
        BigDecimal excess = null;
        if (benchmark == null) reasons.add("BENCHMARK_MISSING");
        else if (policy.measurement() != Measurement.NEXT_SESSION_OPEN_SIMULATION
                || !alignedBenchmark(signal, benchmark, entryDate, exitDate, evaluatedAt)) reasons.add("BENCHMARK_NOT_ALIGNED");
        else if (net != null) excess = net.subtract(benchmark.grossReturnPct());

        RiskPath path = riskPath(interval, policy, reference, gross);
        if (path.state() == PathState.AMBIGUOUS_PATH) reasons.add("AMBIGUOUS_PATH");
        if (policy.stopPrice() != null || policy.targetPrice() != null) reasons.add("RISK_BOUNDS_ARE_SEPARATE_PRICE_SCENARIOS");
        return new Outcome(signal.signalId(), policy.version(), policy.horizonSessions(), policy.measurement(),
                State.COMPLETE, path.state(), entryDate, exitDate, reference, exit, gross, net, excess,
                path.lower(), path.upper(), reasons);
    }

    private static String validateBar(Signal signal, SessionBar bar, Instant evaluatedAt, boolean requiresEntry) {
        if (!Objects.equals(signal.venue(), bar.venue()) || !Objects.equals(signal.session(), bar.session())
                || signal.provenance() != bar.provenance() || signal.adjustmentBasis() != bar.adjustmentBasis())
            return "BAR_BASIS_MISMATCH";
        if (bar.availableAt() == null || bar.availableAt().isAfter(evaluatedAt)) return "BAR_NOT_AVAILABLE";
        if (bar.availableAt().isBefore(close(bar.date()))) return "INVALID_BAR_AVAILABILITY";
        if (bar.unresolvedCorporateAction()) return "UNRESOLVED_CORPORATE_ACTION";
        if (requiresEntry && !bar.entryEligible()) return "ENTRY_NOT_ELIGIBLE";
        if (!positive(bar.open()) || !positive(bar.high()) || !positive(bar.low()) || !positive(bar.close())
                || bar.high().compareTo(bar.low()) < 0 || bar.high().compareTo(bar.open()) < 0
                || bar.high().compareTo(bar.close()) < 0 || bar.low().compareTo(bar.open()) > 0
                || bar.low().compareTo(bar.close()) > 0) return "INVALID_OHLC";
        return null;
    }

    private static boolean validCalendar(List<LocalDate> calendar) {
        if (calendar == null || calendar.isEmpty()) return false;
        LocalDate previous = null;
        for (LocalDate date : calendar) {
            if (date == null || (previous != null && !date.isAfter(previous))
                    || date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
            previous = date;
        }
        return true;
    }

    private static boolean validRisk(Policy policy, BigDecimal reference) {
        return (policy.stopPrice() == null || (positive(policy.stopPrice()) && policy.stopPrice().compareTo(reference) < 0))
                && (policy.targetPrice() == null || (positive(policy.targetPrice()) && policy.targetPrice().compareTo(reference) > 0));
    }

    private static boolean alignedBenchmark(Signal signal, Benchmark benchmark, LocalDate entry, LocalDate exit, Instant asOf) {
        return entry.equals(benchmark.entryDate()) && exit.equals(benchmark.exitDate())
                && Objects.equals(signal.venue(), benchmark.venue()) && Objects.equals(signal.session(), benchmark.session())
                && signal.provenance() == benchmark.provenance() && signal.adjustmentBasis() == benchmark.adjustmentBasis()
                && benchmark.grossReturnPct() != null && benchmark.availableAt() != null
                && !benchmark.availableAt().isBefore(close(exit)) && !benchmark.availableAt().isAfter(asOf);
    }

    private record RiskPath(PathState state, BigDecimal lower, BigDecimal upper) {}
    private static RiskPath riskPath(List<SessionBar> interval, Policy policy, BigDecimal reference, BigDecimal gross) {
        BigDecimal stop = policy.stopPrice(), target = policy.targetPrice();
        if (stop == null && target == null) return new RiskPath(PathState.NOT_REQUESTED, null, null);
        for (SessionBar bar : interval) {
            if (stop != null && bar.open().compareTo(stop) <= 0) {
                BigDecimal scenario = pct(reference, bar.open());
                return new RiskPath(PathState.GAP_STOP, scenario, scenario);
            }
            if (target != null && bar.open().compareTo(target) >= 0) {
                BigDecimal scenario = pct(reference, bar.open());
                return new RiskPath(PathState.GAP_TARGET, scenario, scenario);
            }
            boolean stopReached = stop != null && bar.low().compareTo(stop) <= 0;
            boolean targetReached = target != null && bar.high().compareTo(target) >= 0;
            if (stopReached && targetReached) return new RiskPath(PathState.AMBIGUOUS_PATH, pct(reference, stop), pct(reference, target));
            if (stopReached) {
                BigDecimal scenario = pct(reference, stop);
                return new RiskPath(PathState.STOP, scenario, scenario);
            }
            if (targetReached) {
                BigDecimal scenario = pct(reference, target);
                return new RiskPath(PathState.TARGET, scenario, scenario);
            }
        }
        return new RiskPath(PathState.HORIZON_CLOSE, gross, gross);
    }

    private static Outcome absent(Signal signal, Policy policy, State state, LocalDate entry, LocalDate exit, String reason) {
        return new Outcome(signal == null ? null : signal.signalId(), policy == null ? null : policy.version(),
                policy == null ? 0 : policy.horizonSessions(), policy == null ? null : policy.measurement(), state,
                PathState.NOT_REQUESTED, entry, exit, null, null, null, null, null, null, null, List.of(reason));
    }
    private static Instant close(LocalDate date) { return date.atTime(15, 30).atZone(KRX_ZONE).toInstant(); }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static BigDecimal pct(BigDecimal reference, BigDecimal end) {
        return end.subtract(reference).multiply(HUNDRED).divide(reference, 12, RoundingMode.HALF_EVEN);
    }
}
