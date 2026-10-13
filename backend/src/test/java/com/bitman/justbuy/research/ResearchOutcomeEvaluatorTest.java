package com.bitman.justbuy.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.bitman.justbuy.research.ResearchOutcomeEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic boundary fixtures, never evidence of actual security profitability. */
class ResearchOutcomeEvaluatorTest {
    private final ResearchOutcomeEvaluator evaluator = new ResearchOutcomeEvaluator();
    private final LocalDate publicationDate = LocalDate.of(2026, 10, 9);
    private final Instant publishedAt = publicationDate.atTime(16, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
    private final List<LocalDate> calendar = calendar();
    private final Instant evaluatedAt = Instant.parse("2026-12-01T00:00:00Z");

    @ParameterizedTest
    @CsvSource({"1,2026-10-12,1", "3,2026-10-14,3", "5,2026-10-16,5", "20,2026-11-06,20"})
    void countsEntrySessionAsOneAndSkipsNonCalendarDays(int horizon, String exit, String pct) {
        Outcome out = evaluate(bars(), policy(horizon));
        assertEquals(State.COMPLETE, out.state());
        assertEquals(LocalDate.of(2026, 10, 12), out.entryDate());
        assertEquals(LocalDate.parse(exit), out.exitDate());
        decimal(pct, out.grossReturnPct());
        assertEquals(Measurement.NEXT_SESSION_OPEN_SIMULATION, out.measurement());
    }

    @Test void explicitHolidayCalendarDeterminesNextEntry() {
        var holidayCalendar = new ArrayList<>(calendar); holidayCalendar.remove(0);
        var out = evaluator.evaluate(signal(), holidayCalendar, bars(), policy(1), evaluatedAt, null);
        assertEquals(LocalDate.of(2026, 10, 13), out.entryDate());
        decimal("2", out.grossReturnPct());
    }
    @Test void doesNotShiftMissingEntryToLaterAvailableBar() {
        var missing = new ArrayList<>(bars()); missing.remove(0);
        Outcome out = evaluate(missing, policy(3));
        reason(State.UNMEASURABLE, "MISSING_SESSION_BAR", out);
        assertEquals(calendar.getFirst(), out.entryDate());
        assertNull(out.grossReturnPct());
    }
    @Test void haltedEntryIsUnfillableAndNeverMovesToResumption() {
        var input = new ArrayList<>(bars()); input.set(0, replace(input.getFirst(), false, false, "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW));
        reason(State.UNFILLABLE, "ENTRY_NOT_ELIGIBLE", evaluate(input, policy(5)));
    }
    @Test void futureMaturityIsPendingEvenWhenFutureBarWasSupplied() {
        var out = evaluator.evaluate(signal(), calendar, bars(), policy(5), close(calendar.get(2)), null);
        reason(State.PENDING, "HORIZON_NOT_MATURE", out);
        assertNull(out.grossReturnPct());
    }
    @Test void matureOutcomeMissingMiddleBarIsNotForwardFilled() {
        var input = new ArrayList<>(bars()); input.remove(1);
        reason(State.UNMEASURABLE, "MISSING_SESSION_BAR", evaluate(input, policy(3)));
    }
    @Test void missingCalendarHorizonIsExplicitlyUnknown() {
        var out = evaluator.evaluate(signal(), calendar.subList(0, 3), bars(), policy(5), evaluatedAt, null);
        reason(State.UNMEASURABLE, "CALENDAR_COVERAGE_MISSING", out);
    }
    @Test void calendarCannotBeSilentlySortedOrDeduplicated() {
        var unsorted = new ArrayList<>(calendar); Collections.swap(unsorted, 0, 1);
        reason(State.UNMEASURABLE, "INVALID_CALENDAR", evaluator.evaluate(signal(), unsorted, bars(), policy(1), evaluatedAt, null));
        var duplicate = new ArrayList<>(calendar); duplicate.add(1, calendar.getFirst());
        reason(State.UNMEASURABLE, "INVALID_CALENDAR", evaluator.evaluate(signal(), duplicate, bars(), policy(1), evaluatedAt, null));
    }
    @Test void unresolvedCorporateActionIsNotCalledPlainMissing() {
        var input = new ArrayList<>(bars()); input.set(1, replace(input.get(1), true, true, "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW));
        reason(State.UNMEASURABLE, "UNRESOLVED_CORPORATE_ACTION", evaluate(input, policy(3)));
    }
    @ParameterizedTest @CsvSource({"0,105,95,101", "100,99,95,101", "100,105,102,101", "100,105,-1,101"})
    void rejectsNonPositiveAndInconsistentOhlc(String o, String h, String l, String c) {
        var input = new ArrayList<>(bars()); input.set(0, bar(calendar.getFirst(), o, h, l, c));
        reason(State.UNMEASURABLE, "INVALID_OHLC", evaluate(input, policy(1)));
    }
    @Test void recordedAvailabilityAfterEvaluationCannotBecomeHistoricalFact() {
        var input = new ArrayList<>(bars()); var b = input.getFirst();
        input.set(0, new SessionBar(b.instrumentId(), b.date(), evaluatedAt.plusSeconds(1), b.open(), b.high(), b.low(), b.close(), true, false, b.venue(), b.session(), b.provenance(), b.adjustmentBasis()));
        reason(State.UNMEASURABLE, "BAR_NOT_AVAILABLE", evaluate(input, policy(1)));
    }
    @Test void completedOhlcCannotClaimAvailabilityBeforeSessionClose() {
        var input = new ArrayList<>(bars()); var b = input.getFirst();
        input.set(0, new SessionBar(b.instrumentId(), b.date(), close(b.date()).minusSeconds(1), b.open(), b.high(), b.low(), b.close(), true, false, b.venue(), b.session(), b.provenance(), b.adjustmentBasis()));
        reason(State.UNMEASURABLE, "INVALID_BAR_AVAILABILITY", evaluate(input, policy(1)));
    }
    @Test void duplicateSessionEvidenceIsNotArbitrarilySelected() {
        var input = new ArrayList<>(bars()); input.add(input.getFirst());
        reason(State.UNMEASURABLE, "DUPLICATE_SESSION_BAR", evaluate(input, policy(1)));
    }
    @ParameterizedTest @CsvSource({"NXT,REGULAR,REAL,RAW", "KRX,AFTER_HOURS,REAL,RAW", "KRX,REGULAR,MOCK,RAW", "KRX,REGULAR,REAL,ADJUSTED"})
    void rejectsMixedVenueSessionProvenanceOrAdjustment(String venue, String session, Provenance provenance, Adjustment adjustment) {
        var input = new ArrayList<>(bars()); input.set(0, replace(input.getFirst(), true, false, venue, session, provenance, adjustment));
        reason(State.UNMEASURABLE, "BAR_BASIS_MISMATCH", evaluate(input, policy(1)));
    }
    @Test void grossIsAvailableButUnknownCostsDoNotProduceNetOrExcess() {
        Outcome out = evaluate(bars(), policy(1));
        decimal("1", out.grossReturnPct());
        assertNull(out.netReturnPct()); assertNull(out.excessReturnPct());
        assertTrue(out.reasons().contains("COSTS_UNKNOWN"));
    }
    @Test void knownCostsAndAlignedBenchmarkProduceExactDecimalNetAndExcess() {
        Policy p = new Policy("v1", 1, Measurement.NEXT_SESSION_OPEN_SIMULATION, bd("0.25"), null, null);
        var out = evaluator.evaluate(signal(), calendar, bars(), p, evaluatedAt,
                new Benchmark(calendar.getFirst(), calendar.getFirst(), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW, bd("0.5"), close(calendar.getFirst())));
        decimal("0.75", out.netReturnPct()); decimal("0.25", out.excessReturnPct());
    }
    @Test void mismatchedBenchmarkDatesCannotProduceExcess() {
        var out = evaluator.evaluate(signal(), calendar, bars(), new Policy("v1", 1, Measurement.NEXT_SESSION_OPEN_SIMULATION, bd("0"), null, null), evaluatedAt,
                new Benchmark(calendar.get(1), calendar.get(1), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW, bd("0.5"), close(calendar.get(1))));
        assertNull(out.excessReturnPct()); assertTrue(out.reasons().contains("BENCHMARK_NOT_ALIGNED"));
    }
    @Test void mismatchedOrFutureBenchmarkCannotProduceExcess() {
        var p = new Policy("v1", 1, Measurement.NEXT_SESSION_OPEN_SIMULATION, bd("0"), null, null);
        for (var b : List.of(new Benchmark(calendar.getFirst(), calendar.getFirst(), "NXT", "REGULAR", Provenance.REAL, Adjustment.RAW, bd("0.5"), close(calendar.getFirst())),
                new Benchmark(calendar.getFirst(), calendar.getFirst(), "KRX", "REGULAR", Provenance.REAL, Adjustment.TOTAL_RETURN, bd("0.5"), close(calendar.getFirst())),
                new Benchmark(calendar.getFirst(), calendar.getFirst(), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW, bd("0.5"), evaluatedAt.plusSeconds(1)))) {
            var out = evaluator.evaluate(signal(), calendar, bars(), p, evaluatedAt, b);
            assertEquals(State.COMPLETE, out.state()); decimal("1", out.netReturnPct());
            assertNull(out.excessReturnPct()); assertTrue(out.reasons().contains("BENCHMARK_NOT_ALIGNED"));
        }
    }
    @Test void oldCaptureDoesNotBecomePostPublicationObservedReturn() {
        var out = evaluate(bars(), new Policy("observed", 1, Measurement.PUBLISHED_REFERENCE_OBSERVATION, null, null, null));
        reason(State.UNMEASURABLE, "REFERENCE_PRECEDES_PUBLICATION", out);
        assertNull(out.grossReturnPct());
    }
    @Test void simulationIgnoresStaleSignalCaptureAndAnchorsToNextOpen() {
        decimal("1", evaluate(bars(), policy(1)).grossReturnPct());
    }
    @Test void validPublishedReferenceRemainsExplicitObservedMeasurement() {
        var s = new Signal("s", "000001", publishedAt, publishedAt, bd("100"), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW);
        var out = evaluator.evaluate(s, calendar, bars(), new Policy("observed", 1, Measurement.PUBLISHED_REFERENCE_OBSERVATION, null, null, null), evaluatedAt, null);
        assertEquals(State.COMPLETE, out.state()); decimal("1", out.grossReturnPct());
        assertEquals(Measurement.PUBLISHED_REFERENCE_OBSERVATION, out.measurement());
    }
    @Test void bothLevelsInOneBarPreserveBoundsWithoutInventingPath() {
        var input = new ArrayList<>(bars()); input.set(0, bar(calendar.getFirst(), "100", "115", "85", "101"));
        var out = evaluate(input, riskPolicy(1));
        assertEquals(State.COMPLETE, out.state()); assertEquals(PathState.AMBIGUOUS_PATH, out.pathState());
        decimal("-10", out.lowerReturnPct()); decimal("10", out.upperReturnPct());
        decimal("1", out.grossReturnPct()); assertNull(out.netReturnPct()); decimal("101", out.exitPrice());
    }
    @Test void stopGapUsesOpeningReferenceInsteadOfGuaranteeingStopPrice() {
        var input = new ArrayList<>(bars()); input.set(1, bar(calendar.get(1), "80", "85", "75", "82"));
        var out = evaluate(input, riskPolicy(3));
        assertEquals(PathState.GAP_STOP, out.pathState()); decimal("-20", out.lowerReturnPct()); decimal("-20", out.upperReturnPct());
        decimal("3", out.grossReturnPct()); assertEquals(calendar.get(2), out.exitDate());
    }
    @Test void targetGapUsesOpeningReferenceWithExplicitSimulation() {
        var input = new ArrayList<>(bars()); input.set(1, bar(calendar.get(1), "120", "125", "115", "122"));
        var out = evaluate(input, riskPolicy(3));
        assertEquals(PathState.GAP_TARGET, out.pathState()); decimal("20", out.lowerReturnPct()); decimal("20", out.upperReturnPct());
        decimal("3", out.grossReturnPct());
    }
    @Test void firstEntryOpenMustBeInsideRequestedRiskBracket() {
        var out = evaluate(bars(), new Policy("v1", 1, Measurement.NEXT_SESSION_OPEN_SIMULATION, null, bd("110"), bd("120")));
        reason(State.UNMEASURABLE, "INVALID_RISK_LEVELS", out);
    }
    @Test void referenceUnavailableOrFuturePublicationCannotBeMeasured() {
        var s = new Signal("s", "000001", evaluatedAt.plusSeconds(1), publishedAt, bd("100"), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW);
        reason(State.UNMEASURABLE, "SIGNAL_NOT_AVAILABLE", evaluator.evaluate(s, calendar, bars(), policy(1), evaluatedAt, null));
    }
    @Test void unsupportedHorizonCannotBeSilentlyInterpreted() {
        reason(State.UNMEASURABLE, "INVALID_POLICY", evaluate(bars(), policy(2)));
    }
    @Test void observedReferenceCannotCompareAgainstNextSessionOpenBenchmark() {
        var s = new Signal("s", "000001", publishedAt, publishedAt, bd("100"), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW);
        var out = evaluator.evaluate(s, calendar, bars(), new Policy("obs", 1, Measurement.PUBLISHED_REFERENCE_OBSERVATION, bd("0"), null, null), evaluatedAt,
                new Benchmark(calendar.getFirst(), calendar.getFirst(), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW, bd("0.5"), close(calendar.getFirst())));
        decimal("1", out.grossReturnPct()); assertNull(out.excessReturnPct());
        assertTrue(out.reasons().contains("BENCHMARK_NOT_ALIGNED"));
    }
    @Test void futureReferenceCannotRetroactivelyAnchorToPublication() {
        var s = new Signal("s", "000001", publishedAt, publishedAt.plusSeconds(1), bd("100"), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW);
        reason(State.UNMEASURABLE, "REFERENCE_AFTER_PUBLICATION", evaluator.evaluate(s, calendar, bars(), new Policy("obs", 1, Measurement.PUBLISHED_REFERENCE_OBSERVATION, null, null, null), evaluatedAt, null));
    }
    @Test void absentBarDuringEntryDayIsPendingBeforeRegularClose() {
        var asOf = calendar.getFirst().atTime(14, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        reason(State.PENDING, "HORIZON_NOT_MATURE", evaluator.evaluate(signal(), calendar, List.of(), policy(1), asOf, null));
    }
    @Test void missingAlreadyMatureEntryIsNotHiddenByFutureHorizon() {
        reason(State.UNMEASURABLE, "MISSING_SESSION_BAR", evaluator.evaluate(signal(), calendar, List.of(), policy(5), close(calendar.getFirst()), null));
    }
    @Test void explicitUnfillableEntryDoesNotRequireInventedOhlc() {
        var input = new ArrayList<>(bars());
        input.set(0, new SessionBar("000001", calendar.getFirst(), close(calendar.getFirst()), null, null, null, null, false, false, "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW));
        reason(State.UNFILLABLE, "ENTRY_NOT_ELIGIBLE", evaluate(input, policy(1)));
    }
    @ParameterizedTest @CsvSource({"08:00:00", "15:29:59"})
    void nextSessionPolicyRejectsPublicationBeforeKnownRegularClose(String time) {
        var earlyPublication = calendar.getFirst().atTime(LocalTime.parse(time)).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        var s = new Signal("early", "000001", earlyPublication, earlyPublication, bd("100"), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW);
        var out = evaluator.evaluate(s, calendar, bars(), policy(1), evaluatedAt, null);
        reason(State.UNMEASURABLE, "SIGNAL_BEFORE_REGULAR_CLOSE", out);
        assertNull(out.grossReturnPct()); assertNull(out.entryDate());
    }
    @Test void exactlyRegularClosePublicationUsesFollowingSession() {
        var atClose = close(calendar.getFirst());
        var s = new Signal("close", "000001", atClose, atClose, bd("100"), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW);
        var out = evaluator.evaluate(s, calendar, bars(), policy(1), evaluatedAt, null);
        assertEquals(State.COMPLETE, out.state()); assertEquals(calendar.get(1), out.entryDate());
        decimal("2", out.grossReturnPct());
    }

    private Outcome evaluate(List<SessionBar> bars, Policy policy) { return evaluator.evaluate(signal(), calendar, bars, policy, evaluatedAt, null); }
    private Policy policy(int horizon) { return new Policy("v1", horizon, Measurement.NEXT_SESSION_OPEN_SIMULATION, null, null, null); }
    private Policy riskPolicy(int horizon) { return new Policy("risk-v1", horizon, Measurement.NEXT_SESSION_OPEN_SIMULATION, null, bd("90"), bd("110")); }
    private Signal signal() { return new Signal("s", "000001", publishedAt, publishedAt.minusSeconds(3600), bd("50"), "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW); }
    private List<SessionBar> bars() {
        var result = new ArrayList<SessionBar>();
        for (int i = 0; i < calendar.size(); i++) result.add(bar(calendar.get(i), "100", Integer.toString(102 + i), "99", Integer.toString(101 + i)));
        return result;
    }
    private static SessionBar bar(LocalDate date, String open, String high, String low, String close) {
        return new SessionBar("000001", date, close(date), bd(open), bd(high), bd(low), bd(close), true, false, "KRX", "REGULAR", Provenance.REAL, Adjustment.RAW);
    }
    private static SessionBar replace(SessionBar b, boolean eligible, boolean action, String venue, String session, Provenance provenance, Adjustment adjustment) {
        return new SessionBar(b.instrumentId(), b.date(), b.availableAt(), b.open(), b.high(), b.low(), b.close(), eligible, action, venue, session, provenance, adjustment);
    }
    private List<LocalDate> calendar() {
        var result = new ArrayList<LocalDate>();
        for (LocalDate d = publicationDate.plusDays(3); result.size() < 20; d = d.plusDays(1))
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) result.add(d);
        return List.copyOf(result);
    }
    private static Instant close(LocalDate date) { return date.atTime(15, 30).atZone(ZoneId.of("Asia/Seoul")).toInstant(); }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static void decimal(String expected, BigDecimal actual) { assertNotNull(actual); assertEquals(0, bd(expected).compareTo(actual), () -> expected + " != " + actual); }
    private static void reason(State state, String reason, Outcome actual) { assertEquals(state, actual.state()); assertTrue(actual.reasons().contains(reason), actual.reasons().toString()); }
}
