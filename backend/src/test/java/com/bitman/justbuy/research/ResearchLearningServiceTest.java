package com.bitman.justbuy.research;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.bitman.justbuy.research.ResearchLearningService.*;

/** Constructed exception/comparison fixtures; these tests are not actual market performance. */
class ResearchLearningServiceTest {
    private final ResearchLearningService service = new ResearchLearningService();
    private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
    private Observation observation(String instrument, int offset, String policy, String net) {
        return new Observation(instrument, DAY.plusDays(offset), DAY.plusDays(offset), 1, policy, "eval-v1", "cost-v1", "input-v1",
                new BigDecimal(net).add(new BigDecimal("0.001")), new BigDecimal(net), BigDecimal.ZERO, true, "MEASURED", Origin.ACTUAL_CAPTURE, List.of());
    }
    private Policy policy(Map<LocalDate,Set<String>> expected, int min) {
        return new Policy("base", "new", 1, "eval-v1", "cost-v1", "input-v1", expected, min, BigDecimal.ZERO);
    }
    private Map<LocalDate,Set<String>> universe(int dates, String... instruments) {
        var result = new TreeMap<LocalDate,Set<String>>();
        for (int i=0;i<dates;i++) result.put(DAY.plusDays(i), Set.of(instruments));
        return result;
    }

    @Test void manuallyDerivedDailyMeansUseEqualDateWeightAndNeverActivateProduction() {
        // Day 1: mean base=.015, new=.03 => delta=.015; Day 2: -.02 vs -.01 => .01.
        var observations = List.of(observation("A",0,"base","0.01"), observation("A",0,"new","0.04"),
                observation("B",0,"base","0.02"), observation("B",0,"new","0.02"),
                observation("A",1,"base","-0.02"), observation("A",1,"new","-0.01"));
        var expected = new TreeMap<LocalDate,Set<String>>(); expected.put(DAY,Set.of("A","B")); expected.put(DAY.plusDays(1),Set.of("A"));
        Review review = service.compare(observations, policy(expected,2));
        assertEquals(Status.REVIEW_CANDIDATE, review.status());
        assertEquals(0, new BigDecimal("0.0125").compareTo(review.meanPairedDelta()));
        assertEquals(0, new BigDecimal("-0.0025").compareTo(review.baselineMeanNet()));
        assertEquals(0, new BigDecimal("0.01").compareTo(review.challengerMeanNet()));
        assertEquals(3, review.eligiblePairs()); assertEquals(2,review.independentDates());
        assertFalse(review.productionActivationAllowed());
        var reverse = new ArrayList<>(observations); Collections.reverse(reverse);
        assertEquals(review, service.compare(reverse,policy(expected,2)));
    }

    @Test void oneDateWithHundredSignalsIsOneIndependentDate() {
        var observations = new ArrayList<Observation>(); var instruments = new HashSet<String>();
        for (int i=0;i<100;i++) { String code="I"+i; instruments.add(code); observations.add(observation(code,0,"base","0")); observations.add(observation(code,0,"new","0.1")); }
        var review=service.compare(observations, policy(Map.of(DAY,instruments),30));
        assertEquals(Status.INSUFFICIENT_EVIDENCE,review.status()); assertEquals(1,review.independentDates()); assertEquals(100,review.eligiblePairs());
    }

    @Test void omittedLosingPairAndMissingDenominatorBlockCandidate() {
        var rows=List.of(observation("A",0,"base","0"),observation("A",0,"new","0.1"));
        var review=service.compare(rows,policy(universe(1,"A","LOSER"),1));
        assertEquals(Status.INSUFFICIENT_EVIDENCE,review.status()); assertEquals(2,review.expectedPairs());
        assertEquals(1,review.reasonCohorts().get("MISSING_PAIR")); assertEquals(0,review.completeDates());
        assertEquals(Status.INSUFFICIENT_EVIDENCE,service.compare(rows,policy(Map.of(),1)).status());
    }

    @Test void duplicatePolicyAndUnrelatedRowsCannotPromote() {
        var rows=new ArrayList<>(List.of(observation("A",0,"base","0"),observation("A",0,"new","0.1")));
        rows.add(observation("A",0,"new","0.1"));
        assertEquals(Status.INSUFFICIENT_EVIDENCE,service.compare(rows,policy(universe(1,"A"),1)).status());
        rows.removeLast(); rows.add(observation("B",0,"third-policy","0.9"));
        var review=service.compare(rows,policy(universe(1,"A"),1));
        assertEquals(Status.INSUFFICIENT_EVIDENCE,review.status()); assertTrue(review.reasonCohorts().containsKey("UNEXPECTED_POLICY"));
    }

    @Test void missingCostSyntheticDataAndMixedVersionsAreIneligible() {
        Observation base=observation("A",0,"base","0"); Observation good=observation("A",0,"new","0.1");
        for (Observation bad : List.of(
                new Observation("A",DAY,DAY,1,"new","eval-v1","cost-v1","input-v1",BigDecimal.ONE,null,BigDecimal.ZERO,false,"MEASURED",Origin.ACTUAL_CAPTURE,List.of("COST_UNKNOWN")),
                new Observation("A",DAY,DAY,1,"new","eval-v2","cost-v1","input-v1",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,true,"MEASURED",Origin.ACTUAL_CAPTURE,List.of()),
                new Observation("A",DAY,DAY,5,"new","eval-v1","cost-v1","input-v1",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,true,"MEASURED",Origin.ACTUAL_CAPTURE,List.of()),
                new Observation("A",DAY,DAY,1,"new","eval-v1","cost-v1","input-v1",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,true,"MEASURED",Origin.SYNTHETIC_EXCEPTION,List.of()))) {
            assertEquals(Status.INSUFFICIENT_EVIDENCE,service.compare(List.of(base,bad),policy(universe(1,"A"),1)).status());
        }
        assertEquals(Status.REVIEW_CANDIDATE,service.compare(List.of(base,good),policy(universe(1,"A"),1)).status());
    }

    @Test void lowerResultRejectsAndFailuresRemainVisibleWithoutZeroImputation() {
        var review=service.compare(List.of(observation("A",0,"base","0.1"),observation("A",0,"new","-0.1")),policy(universe(1,"A"),1));
        assertEquals(Status.REJECTED,review.status());
        Observation pending=new Observation("A",DAY,DAY,1,"new","eval-v1","cost-v1","input-v1",null,null,null,false,"UNMEASURABLE",Origin.ACTUAL_CAPTURE,List.of("MISSING_PRICE"));
        review=service.compare(List.of(observation("A",0,"base","0"),pending),policy(universe(1,"A"),1));
        assertNull(review.meanPairedDelta()); assertTrue(review.reasonCohorts().containsKey("MISSING_PRICE"));
    }

    @Test void overlappingOutcomeDatesDoNotBecomeIndependentEvidence() {
        var rows=new ArrayList<Observation>();
        for(int i=0;i<3;i++) for(String p:List.of("base","new")) rows.add(new Observation("A",DAY.plusDays(i),DAY.plusDays(i+3),1,p,"eval-v1","cost-v1","input-v1",BigDecimal.ONE,new BigDecimal(p.equals("base")?"0":"0.1"),null,true,"MEASURED",Origin.ACTUAL_CAPTURE,List.of()));
        var review=service.compare(rows,policy(universe(3,"A"),3));
        assertEquals(3,review.completeDates()); assertEquals(1,review.independentDates()); assertEquals(Status.INSUFFICIENT_EVIDENCE,review.status());
    }

    @Test void emptyActualCaptureCannotClaimProfitAndDefaultNeedsThirtyDates() {
        var review=service.compare(List.of(),new Policy("base","new",1,"eval-v1","cost-v1","input-v1",Map.of()));
        assertEquals(Status.INSUFFICIENT_EVIDENCE,review.status()); assertEquals(0,review.eligiblePairs()); assertNull(review.meanPairedDelta());
    }

    @Test void twentySessionOutcomeCannotEndOnCaptureDateToBypassOverlapChecks() {
        var rows=new ArrayList<Observation>();
        for(int i=0;i<30;i++) for(String id:List.of("base","new")) {
            LocalDate date=DAY.plusDays(i);
            rows.add(new Observation("A",date,date,20,id,"eval-v1","cost-v1","input-v1",
                    BigDecimal.ONE,id.equals("base")?BigDecimal.ZERO:BigDecimal.ONE,null,true,"MEASURED",Origin.ACTUAL_CAPTURE,List.of()));
        }
        var policy=new Policy("base","new",20,"eval-v1","cost-v1","input-v1",universe(30,"A"));
        var review=service.compare(rows,policy);
        assertEquals(Status.INSUFFICIENT_EVIDENCE,review.status());
        assertEquals(0,review.eligiblePairs()); assertEquals(0,review.independentDates());
        assertEquals(60,review.reasonCohorts().get("INVALID_OUTCOME_WINDOW"));
        assertNull(review.meanPairedDelta());
    }
}
