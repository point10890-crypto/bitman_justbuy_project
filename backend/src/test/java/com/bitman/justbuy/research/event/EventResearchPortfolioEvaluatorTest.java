package com.bitman.justbuy.research.event;

import com.bitman.justbuy.research.ResearchLearningService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic arithmetic fixtures are not actual market performance evidence. */
class EventResearchPortfolioEvaluatorTest {
    private final EventResearchPortfolioEvaluator evaluator = new EventResearchPortfolioEvaluator();
    private static final EventResearchPortfolioEvaluator.Selection BASE = selection("SWING_BASELINE", "005930", "0.7", "000660", "0.3");
    private static final EventResearchPortfolioEvaluator.Selection NEW = selection("EVENT_RESEARCH", "005930", "0.2", "018700", "0.8");
    private static final List<EventResearchPortfolioEvaluator.StockReturn> ACTUAL_SHAPED_FIXTURES = List.of(
        measured("005930", "10"), measured("000660", "-5"), measured("018700", "20"));

    @Test void differentSelectedStocksAndWeightsYieldDifferentPortfolioReturns() {
        // Comparing only common Samsung, or copying its return to both policies, loses the selection effect.
        var result = evaluator.compare(BASE, NEW, ACTUAL_SHAPED_FIXTURES, 5, decimal("0.2"), true);
        assertThat(result.state()).isEqualTo("COMPLETE");
        assertThat(result.baselineGross()).isEqualByComparingTo("5.5");
        assertThat(result.challengerGross()).isEqualByComparingTo("18");
        assertThat(result.baselineNet()).isEqualByComparingTo("5.3");
        assertThat(result.challengerNet()).isEqualByComparingTo("17.8");
        assertThat(result.netDelta()).isEqualByComparingTo("12.5");
        assertThat(result.reasons()).contains("FROZEN_PORTFOLIO_UNION", "REFERENCE_SIMULATION_NOT_REALIZED_PNL");
    }

    @Test void havingOnlyTheCommonStockCannotMeasureEitherWholePortfolio() {
        var result = evaluator.compare(BASE, NEW, List.of(measured("005930", "10")), 5, decimal("0.2"), true);
        assertThat(result.state()).isEqualTo("UNMEASURABLE");
        assertThat(result.baselineGross()).isNull();
        assertThat(result.challengerGross()).isNull();
        assertThat(result.netDelta()).isNull();
        assertThat(result.reasons()).contains("MISSING_RETURN:000660", "MISSING_RETURN:018700");
    }

    @Test void identicalFrozenSelectionsHaveZeroDeltaDespiteAnExtraOutsideStock() {
        var same = selection("EVENT_RESEARCH", "005930", "0.7", "000660", "0.3");
        var result = evaluator.compare(BASE, same, ACTUAL_SHAPED_FIXTURES, 1, decimal("0.2"), true);
        assertThat(result.baselineGross()).isEqualByComparingTo("5.5");
        assertThat(result.challengerGross()).isEqualByComparingTo("5.5");
        assertThat(result.netDelta()).isEqualByComparingTo("0");
    }

    @Test void zeroWeightStocksNeedNoReturnAndCashRemainderIsExplicitlyAssumedToEarnZero() {
        var base = selection("SWING_BASELINE", "005930", "0.5", "000660", "0");
        var challenger = selection("EVENT_RESEARCH", "018700", "1");
        var result = evaluator.compare(base, challenger, List.of(measured("005930", "10"), measured("018700", "20")), 3, decimal("0.2"), true);
        assertThat(result.baselineGross()).isEqualByComparingTo("5");
        assertThat(result.baselineNet()).isEqualByComparingTo("4.9");
        assertThat(result.challengerNet()).isEqualByComparingTo("19.8");
        assertThat(result.netDelta()).isEqualByComparingTo("14.9");
        assertThat(result.reasons()).contains("CASH_REMAINDER_ZERO_RETURN_ASSUMPTION", "COST_CHARGED_ON_TRADED_WEIGHTS_ONLY");
    }

    @Test void aCashOnlyBaselineIsComparedToTheMeasuredChallengerWithoutChargingCashFees() {
        var base = new EventResearchPortfolioEvaluator.Selection("SWING_BASELINE", Map.of());
        var challenger = selection("EVENT_RESEARCH", "005930", "0.5");
        var result = evaluator.compare(base, challenger, List.of(measured("005930", "10")), 20, decimal("0.2"), true);
        assertThat(result.baselineGross()).isEqualByComparingTo("0");
        assertThat(result.baselineNet()).isEqualByComparingTo("0");
        assertThat(result.challengerNet()).isEqualByComparingTo("4.9");
        assertThat(result.netDelta()).isEqualByComparingTo("4.9");
    }

    @Test void unknownOrUnverifiedFeesProduceGrossOnlyRatherThanInventedNetZeroCosts() {
        for (var fee : new BigDecimal[] { null, decimal("0.2"), BigDecimal.ZERO }) {
            var result = evaluator.compare(BASE, NEW, ACTUAL_SHAPED_FIXTURES, 5, fee, false);
            assertThat(result.state()).isEqualTo("GROSS_ONLY");
            assertThat(result.baselineGross()).isEqualByComparingTo("5.5");
            assertThat(result.challengerGross()).isEqualByComparingTo("18");
            assertThat(result.baselineNet()).isNull();
            assertThat(result.challengerNet()).isNull();
            assertThat(result.netDelta()).isNull();
            assertThat(result.reasons()).contains("COSTS_UNKNOWN_OR_UNVERIFIED");
        }
        assertThat(evaluator.compare(BASE, NEW, ACTUAL_SHAPED_FIXTURES, 5, null, true).state()).isEqualTo("GROSS_ONLY");
    }

    @Test void malformedWeightsAndNegativeCostsAreNeverMeasured() {
        for (var invalid : List.of(
            selection("SWING_BASELINE", "005930", "-0.1"), selection("SWING_BASELINE", "005930", "1.1"),
            selection("SWING_BASELINE", "005930", "0.7", "000660", "0.4"), selection("SWING_BASELINE", "invented", "1"))) {
            var result = evaluator.compare(invalid, NEW, ACTUAL_SHAPED_FIXTURES, 5, decimal("0.2"), true);
            assertThat(result.state()).isEqualTo("UNMEASURABLE");
            assertThat(result.baselineGross()).isNull();
            assertThat(result.reasons()).contains("INVALID_SELECTION");
        }
        assertThat(evaluator.compare(BASE, NEW, ACTUAL_SHAPED_FIXTURES, 2, decimal("0.2"), true).reasons()).contains("INVALID_HORIZON");
        assertThat(evaluator.compare(BASE, NEW, ACTUAL_SHAPED_FIXTURES, 5, decimal("-0.1"), false).reasons()).contains("INVALID_COST");
        assertThat(evaluator.compare(BASE, BASE, ACTUAL_SHAPED_FIXTURES, 5, decimal("0.2"), true).reasons()).contains("SAME_POLICY_ID");
    }

    @Test void duplicateConflictingMissingAndMockReturnsCannotBeImputedAsZero() {
        for (var bad : List.of(
            new EventResearchPortfolioEvaluator.StockReturn("018700", "COMPLETE", null, true),
            new EventResearchPortfolioEvaluator.StockReturn("018700", "UNMEASURABLE", decimal("20"), true),
            new EventResearchPortfolioEvaluator.StockReturn("018700", "COMPLETE", decimal("20"), false))) {
            var rows = List.of(measured("005930", "10"), measured("000660", "-5"), bad);
            var result = evaluator.compare(BASE, NEW, rows, 5, decimal("0.2"), true);
            assertThat(result.state()).isEqualTo("UNMEASURABLE");
            assertThat(result.baselineGross()).isNull();
            assertThat(result.challengerGross()).isNull();
        }
        for (var duplicate : List.of(measured("018700", "20"), measured("018700", "21"))) {
            var rows = new ArrayList<>(ACTUAL_SHAPED_FIXTURES); rows.add(duplicate);
            assertThat(evaluator.compare(BASE, NEW, rows, 5, decimal("0.2"), true).reasons()).contains("DUPLICATE_RETURN:018700");
        }
    }

    @Test void aPendingFutureOutcomeLeavesBothPortfolioReturnsPending() {
        var rows = List.of(measured("005930", "10"), measured("000660", "-5"),
            new EventResearchPortfolioEvaluator.StockReturn("018700", "PENDING", decimal("999"), true));
        var result = evaluator.compare(BASE, NEW, rows, 5, decimal("0.2"), true);
        assertThat(result.state()).isEqualTo("PENDING");
        assertThat(result.baselineGross()).isNull();
        assertThat(result.challengerGross()).isNull();
        assertThat(result.reasons()).contains("RETURN_PENDING:018700");
    }

    @Test void callerMutationCannotChangeTheFrozenSelection() {
        var weights = new LinkedHashMap<String, BigDecimal>(); weights.put("005930", decimal("0.7")); weights.put("000660", decimal("0.3"));
        var frozen = new EventResearchPortfolioEvaluator.Selection("SWING_BASELINE", weights);
        weights.put("018700", decimal("1")); weights.remove("000660");
        var result = evaluator.compare(frozen, NEW, ACTUAL_SHAPED_FIXTURES, 5, decimal("0.2"), true);
        assertThat(result.baselineGross()).isEqualByComparingTo("5.5");
        assertThat(frozen.weights()).containsOnlyKeys("005930", "000660");
    }

    @Test void learningRowsContainDistinctAggregateReturnsWithExplicitFractionUnits() {
        var comparison = evaluator.compare(BASE, NEW, ACTUAL_SHAPED_FIXTURES, 5, decimal("0.2"), true);
        var date = LocalDate.parse("2026-10-14"); var end = LocalDate.parse("2026-10-21");
        var observations = EventResearchPortfolioEvaluator.learningObservations(comparison, date, end, BASE, NEW, "input-v1");
        assertThat(observations).hasSize(2);
        assertThat(observations).extracting(ResearchLearningService.Observation::instrument).containsOnly(EventResearchPortfolioEvaluator.INSTRUMENT);
        assertThat(observations.get(0).grossReturn()).isEqualByComparingTo("0.055");
        assertThat(observations.get(0).netReturn()).isEqualByComparingTo("0.053");
        assertThat(observations.get(1).grossReturn()).isEqualByComparingTo("0.18");
        assertThat(observations.get(1).netReturn()).isEqualByComparingTo("0.178");
        assertThat(observations).allSatisfy(row -> {
            assertThat(row.origin()).isEqualTo(ResearchLearningService.Origin.ACTUAL_CAPTURE);
            assertThat(row.measurementState()).isEqualTo("MEASURED");
            assertThat(row.benchmarkReturn()).isNull();
            assertThat(row.reasonCodes()).contains("RETURNS_AS_FRACTIONS_FOR_LEARNING", "NO_AUTOMATIC_PROMOTION");
        });
    }

    @Test void grossOnlyAndInvalidOutcomeWindowsNeverBecomeEligibleLearningEvidence() {
        var date = LocalDate.parse("2026-10-14");
        var comparison = evaluator.compare(BASE, NEW, ACTUAL_SHAPED_FIXTURES, 5, null, false);
        var observations = EventResearchPortfolioEvaluator.learningObservations(comparison, date, date.plusDays(7), BASE, NEW, "input-v1");
        assertThat(observations).hasSize(2);
        assertThat(observations).allSatisfy(row -> { assertThat(row.netReturn()).isNull(); assertThat(row.costsVerified()).isFalse(); });
        var complete = evaluator.compare(BASE, NEW, ACTUAL_SHAPED_FIXTURES, 5, decimal("0.2"), true);
        var invalid = EventResearchPortfolioEvaluator.learningObservations(complete, date, date, BASE, NEW, "input-v1");
        assertThat(invalid).hasSize(2);
        assertThat(invalid).allSatisfy(row -> { assertThat(row.measurementState()).isEqualTo("UNMEASURABLE"); assertThat(row.reasonCodes()).contains("INVALID_OUTCOME_WINDOW"); });
    }

    @Test void twentyNineDatesCannotPromoteAndThirtyDatesRemainOnlyAManualReviewCandidate() {
        var comparison = evaluator.compare(BASE, NEW, ACTUAL_SHAPED_FIXTURES, 1, decimal("0.2"), true);
        var rows = new ArrayList<ResearchLearningService.Observation>(); var universe = new TreeMap<LocalDate, Set<String>>();
        var service = new ResearchLearningService();
        for (int i = 0; i < 30; i++) {
            var date = LocalDate.of(2026, 1, 1).plusDays(i * 3L); universe.put(date, Set.of(EventResearchPortfolioEvaluator.INSTRUMENT));
            rows.addAll(EventResearchPortfolioEvaluator.learningObservations(comparison, date, date.plusDays(1), BASE, NEW, "input-v1"));
            var policy = new ResearchLearningService.Policy(BASE.policyId(), NEW.policyId(), 1,
                EventResearchPortfolioEvaluator.EVALUATION_POLICY_VERSION, EventResearchPortfolioEvaluator.COST_POLICY_VERSION, "input-v1", universe);
            var review = service.compare(rows, policy);
            assertThat(review.independentDates()).isEqualTo(i + 1);
            assertThat(review.productionActivationAllowed()).isFalse();
            assertThat(review.status()).isEqualTo(i == 29 ? ResearchLearningService.Status.REVIEW_CANDIDATE : ResearchLearningService.Status.INSUFFICIENT_EVIDENCE);
            assertThat(review.meanPairedDelta()).isEqualByComparingTo("0.125");
        }
    }

    private static EventResearchPortfolioEvaluator.Selection selection(String policy, String... pairs) {
        var weights = new LinkedHashMap<String, BigDecimal>();
        for (int i = 0; i < pairs.length; i += 2) weights.put(pairs[i], decimal(pairs[i + 1]));
        return new EventResearchPortfolioEvaluator.Selection(policy, weights);
    }
    private static EventResearchPortfolioEvaluator.StockReturn measured(String code, String value) {
        return new EventResearchPortfolioEvaluator.StockReturn(code, "COMPLETE", decimal(value), true);
    }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
