package com.bitman.justbuy.research;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.bitman.justbuy.research.ResearchConditionEngine.*;

class ResearchConditionEngineTest {
    private final ResearchConditionEngine engine = new ResearchConditionEngine();
    private static final Instant CUT = Instant.parse("2026-10-12T07:00:00Z");
    private static final Duration AGE = Duration.ofDays(1);
    private static BigDecimal number(String value) { return new BigDecimal(value); }
    private static Fact fact(String id, Metric metric, String value) {
        Unit unit = switch(metric) { case PER, REWARD_RISK -> Unit.MULTIPLE; case CHANGE_PCT -> Unit.PERCENT;
            case FOREIGN_NET_BUY, INST_NET_BUY -> Unit.SHARES; default -> Unit.KRW; };
        return new Fact(id, metric, number(value), unit, "KIS", Environment.REAL, CUT, CUT, FactStatus.VALID);
    }
    private static Candidate candidate(String id, String per, String change) {
        List<Fact> facts = new ArrayList<>(List.of(fact(id, Metric.CURRENT_PRICE, "10000")));
        if (per != null) facts.add(fact(id, Metric.PER, per));
        if (change != null) facts.add(fact(id, Metric.CHANGE_PCT, change));
        return new Candidate(id, facts);
    }
    private static Compare cheap() { return new Compare(Metric.PER, Operator.LTE, number("20"), Unit.MULTIPLE); }
    private static Request request(Expression where, SelectionOrder order, int limit) {
        return new Request(where, Metric.CHANGE_PCT, Direction.DESC, order, limit);
    }
    private Result evaluate(Expression expression, Candidate... candidates) {
        return engine.evaluate(new Snapshot(CUT, AGE, List.of(candidates), candidates.length, true),
            request(expression, SelectionOrder.FILTER_THEN_RANK, 20));
    }
    private Candidate with(String id, Fact replacement) {
        return new Candidate(id, List.of(fact(id, Metric.CURRENT_PRICE, "10000"), fact(id, Metric.CHANGE_PCT, "2"), replacement));
    }

    @Test void missingInsideNotRemainsUnknown() {
        Result result = evaluate(new Not(cheap()), candidate("A", null, "2"));
        assertEquals(Truth.UNKNOWN, result.decisions().getFirst().condition());
        assertEquals(1, result.coverage().unknown());
        assertEquals(List.of(), result.matched());
    }
    @Test void validConditionProducesRankedCandidate() {
        Result result = evaluate(cheap(), candidate("A", "10", "2"));
        assertEquals(List.of("A"), result.matched().stream().map(RankedCandidate::id).toList());
        assertEquals(RankingScope.COMPLETE, result.rankingScope());
    }
    @Test void providerConflictDoesNotChoosePassingValue() {
        Candidate a = candidate("A", "10", "2");
        List<Fact> values = new ArrayList<>(a.facts());
        values.add(new Fact("A", Metric.PER, number("50"), Unit.MULTIPLE, "KIWOOM", Environment.REAL, CUT, CUT, FactStatus.VALID));
        Result result = evaluate(cheap(), new Candidate("A", values));
        assertEquals(Truth.UNKNOWN, result.decisions().getFirst().condition());
        assertTrue(result.decisions().getFirst().reasons().contains("PER:CONFLICT"));
    }
    @Test void sameNumberInDifferentEnvironmentsIsNotBlended() {
        List<Fact> facts = new ArrayList<>(candidate("A", "10", "2").facts());
        facts.add(new Fact("A", Metric.PER, number("10"), Unit.MULTIPLE, "KIWOOM", Environment.MOCK, CUT, CUT, FactStatus.VALID));
        assertEquals(Truth.UNKNOWN, evaluate(cheap(), new Candidate("A", facts)).decisions().getFirst().condition());
    }
    @Test void mockProvenanceAloneCanBeValid() {
        Fact mock = new Fact("A", Metric.PER, number("10"), Unit.MULTIPLE, "KIWOOM", Environment.MOCK, CUT, CUT, FactStatus.VALID);
        assertEquals(Truth.TRUE, evaluate(cheap(), with("A", mock)).decisions().getFirst().condition());
    }
    @Test void futureAvailabilityIsUnknown() {
        Fact future = new Fact("A", Metric.PER, number("10"), Unit.MULTIPLE, "KIS", Environment.REAL, CUT, CUT.plusSeconds(1), FactStatus.VALID);
        assertTrue(evaluate(cheap(), with("A", future)).decisions().getFirst().reasons().contains("PER:FUTURE"));
    }
    @Test void futureObservationIsUnknown() {
        Fact future = new Fact("A", Metric.PER, number("10"), Unit.MULTIPLE, "KIS", Environment.REAL, CUT.plusSeconds(1), CUT, FactStatus.VALID);
        assertEquals(Truth.UNKNOWN, evaluate(cheap(), with("A", future)).decisions().getFirst().condition());
    }
    @Test void staleObservationIsUnknown() {
        Fact stale = new Fact("A", Metric.PER, number("10"), Unit.MULTIPLE, "KIS", Environment.REAL, CUT.minus(AGE).minusSeconds(1), CUT, FactStatus.VALID);
        assertTrue(evaluate(cheap(), with("A", stale)).decisions().getFirst().reasons().contains("PER:STALE"));
    }
    @Test void incorrectFactUnitIsUnknown() {
        Fact wrong = new Fact("A", Metric.PER, number("10"), Unit.KRW, "KIS", Environment.REAL, CUT, CUT, FactStatus.VALID);
        assertTrue(evaluate(cheap(), with("A", wrong)).decisions().getFirst().reasons().contains("PER:UNIT_MISMATCH"));
    }
    @Test void factCannotBorrowAnotherInstrumentIdentity() {
        assertEquals(Truth.UNKNOWN, evaluate(cheap(), with("A", fact("B", Metric.PER, "10"))).decisions().getFirst().condition());
    }
    @Test void trueOrBranchCannotOverrideMissingPriceEligibility() {
        Candidate a = new Candidate("A", List.of(fact("A", Metric.PER, "10"), fact("A", Metric.CHANGE_PCT, "2")));
        Result result = evaluate(new Any(List.of(cheap(), new Compare(Metric.TRADING_VALUE, Operator.GT, number("100"), Unit.KRW))), a);
        assertEquals(Truth.TRUE, result.decisions().getFirst().condition());
        assertEquals(Truth.UNKNOWN, result.decisions().getFirst().eligibility());
        assertEquals(0, result.matched().size());
    }
    @Test void negativeOrZeroPerCannotPassCheapValuation() {
        Result result = evaluate(cheap(), candidate("A", "-3", "2"), candidate("B", "0", "1"));
        assertEquals(2, result.coverage().falseCount());
        assertEquals(List.of(), result.matched());
    }
    @Test void searchOrderHasDifferentMeaning() {
        Snapshot snapshot = new Snapshot(CUT, AGE, List.of(candidate("A", "50", "10"), candidate("B", "10", "5")), 2, true);
        assertEquals(List.of("B"), engine.evaluate(snapshot, request(cheap(), SelectionOrder.FILTER_THEN_RANK, 1)).matched().stream().map(RankedCandidate::id).toList());
        assertEquals(List.of(), engine.evaluate(snapshot, request(cheap(), SelectionOrder.RANK_THEN_FILTER, 1)).matched());
    }
    @Test void missingRankCannotClaimFullRanking() {
        Result result = evaluate(cheap(), candidate("A", "10", "2"), candidate("B", "10", null));
        assertEquals(1, result.coverage().unranked());
        assertEquals(RankingScope.KNOWN_SUBSET, result.rankingScope());
        assertEquals(List.of("A"), result.matched().stream().map(RankedCandidate::id).toList());
    }
    @Test void partialUniverseCountsUninvestigatedWithoutInventedCandidates() {
        Result result = engine.evaluate(new Snapshot(CUT, AGE, List.of(candidate("A", "10", "2")), 100, false), request(cheap(), SelectionOrder.FILTER_THEN_RANK, 20));
        assertEquals(99, result.coverage().notInvestigated());
        assertEquals(RankingScope.KNOWN_SUBSET, result.rankingScope());
    }
    @Test void unknownUniverseHasUnknownDenominator() {
        Result result = engine.evaluate(new Snapshot(CUT, AGE, List.of(candidate("A", "10", "2")), null, false), request(cheap(), SelectionOrder.FILTER_THEN_RANK, 20));
        assertNull(result.coverage().notInvestigated());
        assertEquals(1, result.coverage().investigated());
    }
    @Test void tiedValuesUseIdentityInEitherDirectionAndInputOrder() {
        List<Candidate> candidates = List.of(candidate("B", "10", "2.00"), candidate("A", "10", "2"));
        Result result = engine.evaluate(new Snapshot(CUT, AGE, candidates, 2, true), request(cheap(), SelectionOrder.FILTER_THEN_RANK, 20));
        assertEquals(List.of("A", "B"), result.matched().stream().map(RankedCandidate::id).toList());
        List<Candidate> reversed = new ArrayList<>(candidates); Collections.reverse(reversed);
        assertEquals(result, engine.evaluate(new Snapshot(CUT, AGE, reversed, 2, true), request(cheap(), SelectionOrder.FILTER_THEN_RANK, 20)));
    }
    @Test void duplicateCandidatesMergeEvidenceAndCountUniqueIdentity() {
        Result result = evaluate(cheap(), candidate("A", "10", "2"), candidate("A", "10", "2"));
        assertEquals(1, result.coverage().investigated());
        assertEquals(1, result.matched().size());
        assertEquals(RankingScope.KNOWN_SUBSET, result.rankingScope());
    }
    @Test void validatesExpressionAndInputBoundsBeforeEvaluation() {
        Snapshot snapshot = new Snapshot(CUT, AGE, List.of(candidate("A", "10", "2")), 1, true);
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(snapshot, request(new Compare(Metric.PER, Operator.LTE, number("20"), Unit.KRW), SelectionOrder.FILTER_THEN_RANK, 20)));
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(snapshot, request(new All(List.of()), SelectionOrder.FILTER_THEN_RANK, 20)));
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(snapshot, request(new Not(null), SelectionOrder.FILTER_THEN_RANK, 20)));
        Expression deep = cheap(); for(int i=0;i<40;i++) deep = new Not(deep);
        Expression overdeep = deep;
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(snapshot, request(overdeep, SelectionOrder.FILTER_THEN_RANK, 20)));
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(snapshot, request(new All(Collections.nCopies(300, cheap())), SelectionOrder.FILTER_THEN_RANK, 20)));
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(new Snapshot(CUT, AGE, Collections.nCopies(10001, candidate("A", "10", "2")), null, false), request(cheap(), SelectionOrder.FILTER_THEN_RANK, 20)));
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(snapshot, request(cheap(), SelectionOrder.FILTER_THEN_RANK, 0)));
    }
    @Test void rejectsImpossibleUniverseAndFreshness() {
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(new Snapshot(CUT, AGE, List.of(candidate("A", "10", "2")), 0, true), request(cheap(), SelectionOrder.FILTER_THEN_RANK, 20)));
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(new Snapshot(CUT, Duration.ofSeconds(-1), List.of(), 0, true), request(cheap(), SelectionOrder.FILTER_THEN_RANK, 20)));
    }
    @Test void negationCannotMakeNegativePerCheapValuationEligible() {
        Compare expensive = new Compare(Metric.PER, Operator.GT, number("20"), Unit.MULTIPLE);
        Result result = evaluate(new Not(expensive), candidate("A", "-3", "2"));
        assertEquals(Truth.FALSE, result.decisions().getFirst().eligibility());
        assertEquals(List.of(), result.matched());
    }
    @Test void rankFirstKeepsNonpositivePerUntilAfterTopSlotSelection() {
        Snapshot snapshot = new Snapshot(CUT, AGE, List.of(candidate("A", "-3", "10"), candidate("B", "10", "5")), 2, true);
        Result result = engine.evaluate(snapshot, request(cheap(), SelectionOrder.RANK_THEN_FILTER, 1));
        assertEquals(List.of(), result.matched());
        assertEquals(RankingScope.COMPLETE, result.rankingScope());
        assertEquals(Truth.FALSE, result.decisions().getFirst().eligibility());
        assertEquals(List.of("B"), engine.evaluate(snapshot, request(cheap(), SelectionOrder.FILTER_THEN_RANK, 1))
            .matched().stream().map(RankedCandidate::id).toList());
    }
    @Test void unknownConditionOnIneligibleIdentityDoesNotClaimCompleteEvidence() {
        Candidate a = new Candidate("A", List.of(fact("A", Metric.CURRENT_PRICE, "0"), fact("A", Metric.CHANGE_PCT, "2")));
        Result result = evaluate(cheap(), a);
        assertEquals(RankingScope.KNOWN_SUBSET, result.rankingScope());
    }
    @Test void threeValuedBranchesPreserveFalseAndTrueDominance() {
        Compare knownFalse = new Compare(Metric.CHANGE_PCT, Operator.GT, number("5"), Unit.PERCENT);
        Compare knownTrue = new Compare(Metric.CHANGE_PCT, Operator.LT, number("5"), Unit.PERCENT);
        assertEquals(Truth.FALSE, evaluate(new All(List.of(knownFalse, cheap())), candidate("A", null, "2")).decisions().getFirst().condition());
        assertEquals(Truth.TRUE, evaluate(new Any(List.of(knownTrue, cheap())), candidate("A", null, "2")).decisions().getFirst().condition());
        assertEquals(Truth.UNKNOWN, evaluate(new All(List.of(knownTrue, cheap())), candidate("A", null, "2")).decisions().getFirst().condition());
        assertEquals(Truth.UNKNOWN, evaluate(new Any(List.of(knownFalse, cheap())), candidate("A", null, "2")).decisions().getFirst().condition());
    }
    @Test void cutoffAndAgeBoundaryAreInclusive() {
        Fact boundary = new Fact("A", Metric.PER, number("10"), Unit.MULTIPLE, "KIS", Environment.REAL, CUT.minus(AGE), CUT, FactStatus.VALID);
        assertEquals(Truth.TRUE, evaluate(cheap(), with("A", boundary)).decisions().getFirst().condition());
    }
    @Test void missingRankInRankFirstCanHideEarlierRejectedCandidate() {
        Snapshot snapshot = new Snapshot(CUT, AGE, List.of(candidate("A", "50", null), candidate("B", "10", "2")), 2, true);
        Result result = engine.evaluate(snapshot, request(cheap(), SelectionOrder.RANK_THEN_FILTER, 1));
        assertEquals(1, result.coverage().unranked());
        assertEquals(RankingScope.KNOWN_SUBSET, result.rankingScope());
    }
    @Test void ascendingRankingStillBreaksTiesByIdentity() {
        Request request = new Request(cheap(), Metric.CHANGE_PCT, Direction.ASC, SelectionOrder.FILTER_THEN_RANK, 20);
        Result result = engine.evaluate(new Snapshot(CUT, AGE, List.of(candidate("B", "10", "2"), candidate("A", "10", "2"), candidate("C", "10", "1")), 3, true), request);
        assertEquals(List.of("C", "A", "B"), result.matched().stream().map(RankedCandidate::id).toList());
    }
    @Test void malformedFactMetadataIsUnknownNotZero() {
        Fact fact = new Fact("A", Metric.PER, null, Unit.MULTIPLE, null, null, null, null, FactStatus.UNKNOWN);
        Result result = evaluate(cheap(), with("A", fact));
        assertEquals(Truth.UNKNOWN, result.decisions().getFirst().condition());
        assertEquals(0, result.matched().size());
    }
}
