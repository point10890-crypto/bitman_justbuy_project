package com.bitman.justbuy.service;

import com.bitman.justbuy.util.KoreanMarketCalendar;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DeterministicTechnicalEvidenceTest {
    private static final LocalDate COMPLETED = LocalDate.of(2026,10,8);
    private List<DeterministicTechnicalEvidence.Bar> bars(int count) {
        List<DeterministicTechnicalEvidence.Bar> result = new ArrayList<>();
        LocalDate date = COMPLETED;
        for(int i=0;i<count;i++) {
            result.add(new DeterministicTechnicalEvidence.Bar(date,new BigDecimal("102"),new BigDecimal("100"),new BigDecimal("101")));
            date=KoreanMarketCalendar.previousTradingDay(date);
        }
        Collections.reverse(result);return result;
    }
    private Map<String,Object> calculate(List<DeterministicTechnicalEvidence.Bar> bars) {
        return DeterministicTechnicalEvidence.calculate(bars,COMPLETED);
    }
    private BigDecimal value(Map<String,Object> facts,String key) {return (BigDecimal)facts.get(key);}
    private void replace(List<DeterministicTechnicalEvidence.Bar> bars,int index,String high,String low,String close) {
        bars.set(index,new DeterministicTechnicalEvidence.Bar(bars.get(index).date(),new BigDecimal(high),new BigDecimal(low),new BigDecimal(close)));
    }

    @Test void eachWindowRequiresItsFullNumberOfPriceChanges() {
        assertThat(calculate(bars(14))).isEmpty();
        assertThat(calculate(bars(15))).containsKeys("atr14","atr14Pct").doesNotContainKeys("return20dPct","hv20AnnualizedPct","return60dPct");
        assertThat(calculate(bars(20))).doesNotContainKeys("return20dPct","hv20AnnualizedPct","return60dPct");
        assertThat(calculate(bars(21))).containsKeys("return20dPct","hv20AnnualizedPct").doesNotContainKey("return60dPct");
        assertThat(calculate(bars(60))).doesNotContainKey("return60dPct");
        assertThat(calculate(bars(61))).containsKey("return60dPct");
    }

    @Test void twentyAndSixtyReturnsUseDifferentObservedBaselinesAndPreserveNegativeReturns() {
        var history=bars(61);
        for(int i=0;i<history.size();i++) {
            int close=100+i;replace(history,i,Integer.toString(close+1),Integer.toString(close-1),Integer.toString(close));
        }
        var facts=calculate(history);
        assertThat(value(facts,"return20dPct")).isEqualByComparingTo("14.285714");
        assertThat(value(facts,"return60dPct")).isEqualByComparingTo("60");
        assertThat(facts).containsEntry("return20dFromDate",history.get(40).date().toString())
            .containsEntry("return60dFromDate",history.getFirst().date().toString()).containsEntry("return60dToDate",COMPLETED.toString());
        var declining=bars(21);
        for(int i=0;i<declining.size();i++) {int close=100-i;replace(declining,i,Integer.toString(close+1),Integer.toString(close-1),Integer.toString(close));}
        assertThat(value(calculate(declining),"return20dPct")).isEqualByComparingTo("-20");
    }

    @Test void atrIncludesPriceGapsAndUsesWilderSmoothingAfterItsObservedSeed() {
        var history=bars(16);replace(history,15,"106","104","105");
        var facts=calculate(history);
        // Fourteen initial true ranges are 2; the next gap-aware TR is 5: (13*2+5)/14.
        assertThat(value(facts,"atr14")).isEqualByComparingTo("2.214286");
        assertThat(value(facts,"atr14Pct")).isEqualByComparingTo("2.108844");
        assertThat(facts).containsEntry("atrTrueRangeCount",15).containsEntry("atrReferencePrice",new BigDecimal("105"))
            .containsEntry("atrReferencePriceBasis","LATEST_COMPLETED_ADJUSTED_CLOSE")
            .containsEntry("atrHistoryFromDate",history.getFirst().date().toString());
    }

    @Test void historicalVolatilityUsesTwentyLogReturnsWithSampleVarianceAndExplicitAnnualization() {
        var history=bars(21);
        for(int i=0;i<history.size();i++) {int close=i%2==0?100:200;replace(history,i,Integer.toString(close+1),Integer.toString(close-1),Integer.toString(close));}
        var facts=calculate(history);
        // Ten +ln(2) and ten -ln(2) returns have mean 0 and sample variance 20*ln(2)^2/19.
        double expected=100*StrictMath.log(2)*StrictMath.sqrt(20.0*252/19);
        assertThat(value(facts,"hv20AnnualizedPct").doubleValue()).isCloseTo(expected,within(0.000001));
        assertThat(facts).containsEntry("hv20ReturnCount",20).containsEntry("hv20AnnualizationDays",252)
            .containsEntry("hv20FromDate",history.getFirst().date().toString()).containsEntry("hv20ToDate",COMPLETED.toString());
        assertThat(value(calculate(bars(21)),"hv20AnnualizedPct")).isZero();
    }

    @Test void aGapLimitsMetricsToTheActualRecentConsecutiveHistoryWithoutForwardFilling() {
        var recentGap=bars(61);recentGap.remove(50);assertThat(calculate(recentGap)).isEmpty();
        var olderGap=bars(62);olderGap.remove(1);var facts=calculate(olderGap);
        assertThat(facts).containsEntry("derivedConsecutiveBars",60).containsKeys("return20dPct","hv20AnnualizedPct")
            .doesNotContainKey("return60dPct");
    }

    @Test void staleOrDuplicateOrUnorderedHistoryCannotBecomeNewEvidence() {
        var history=bars(61);
        assertThat(DeterministicTechnicalEvidence.calculate(history,LocalDate.of(2026,10,12))).isEmpty();
        var duplicate=new ArrayList<>(history);duplicate.set(10,duplicate.get(9));assertThat(calculate(duplicate)).isEmpty();
        var unordered=new ArrayList<>(history);Collections.swap(unordered,5,6);assertThat(calculate(unordered)).isEmpty();
    }

    @Test void missingOrImpossiblePricesAreUnknownRatherThanZeroAndWorkIsBounded() {
        var history=bars(21);replace(history,20,"102","100","0");assertThat(calculate(history)).isEmpty();
        history=bars(21);replace(history,20,"102","100","103");assertThat(calculate(history)).isEmpty();
        history=bars(21);history.set(20,new DeterministicTechnicalEvidence.Bar(COMPLETED,null,new BigDecimal("100"),new BigDecimal("101")));
        assertThat(calculate(history)).isEmpty();assertThat(calculate(bars(101))).isEmpty();
    }
}
