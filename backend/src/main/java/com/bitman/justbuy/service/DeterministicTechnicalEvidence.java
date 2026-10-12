package com.bitman.justbuy.service;

import com.bitman.justbuy.util.KoreanMarketCalendar;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Derived evidence only: callers supply identity-checked, completed, adjusted daily prices. */
final class DeterministicTechnicalEvidence {
    record Bar(LocalDate date, BigDecimal high, BigDecimal low, BigDecimal close) {}
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal FOURTEEN = BigDecimal.valueOf(14);
    private static final int ANNUALIZATION_DAYS = 252;

    private DeterministicTechnicalEvidence() {}

    /** No forward filling: each metric needs its entire latest consecutive trading-day window. */
    static Map<String,Object> calculate(List<Bar> input, LocalDate completedDate) {
        if (input == null || input.isEmpty() || input.size() > 100 || completedDate == null) return Map.of();
        LocalDate previous = null;
        for (Bar bar : input) {
            if (bar == null || bar.date() == null || !KoreanMarketCalendar.isTradingDay(bar.date())
                || previous != null && !bar.date().isAfter(previous) || !validPrice(bar.high())
                || !validPrice(bar.low()) || !validPrice(bar.close()) || bar.low().compareTo(bar.high()) > 0
                || bar.close().compareTo(bar.low()) < 0 || bar.close().compareTo(bar.high()) > 0) return Map.of();
            previous = bar.date();
        }
        if (!input.getLast().date().equals(completedDate)) return Map.of();
        int start = input.size() - 1;
        while (start > 0 && input.get(start-1).date().equals(KoreanMarketCalendar.previousTradingDay(input.get(start).date()))) start--;
        List<Bar> bars = input.subList(start, input.size());
        if (bars.size() < 15) return Map.of();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("derivedMetricsBasis", "COMPLETED_ADJUSTED_DAILY_OHLC");
        out.put("derivedPercentUnit", "percent");
        out.put("derivedConsecutiveBars", bars.size());
        addReturn(out, bars, 20);
        addReturn(out, bars, 60);
        addAtr(out, bars);
        addVolatility(out, bars);
        return Map.copyOf(out);
    }

    private static boolean validPrice(BigDecimal price) { return price != null && price.signum() > 0; }
    private static BigDecimal rounded(BigDecimal value) { return value.setScale(6, RoundingMode.HALF_UP); }

    private static void addReturn(Map<String,Object> out, List<Bar> bars, int days) {
        if (bars.size() < days + 1) return;
        Bar base = bars.get(bars.size() - days - 1), latest = bars.getLast();
        BigDecimal result = latest.close().subtract(base.close()).divide(base.close(), PRECISION).multiply(HUNDRED);
        String key = "return" + days + "d";
        out.put(key + "Pct", rounded(result));
        out.put(key + "FromDate", base.date().toString());
        out.put(key + "ToDate", latest.date().toString());
    }

    /** Wilder update and gap-aware TR: https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/atr */
    private static void addAtr(Map<String,Object> out, List<Bar> bars) {
        // The preceding close is observed for every one of the initial 14 true ranges.
        BigDecimal atr = BigDecimal.ZERO;
        for (int i = 1; i <= 14; i++) atr = atr.add(trueRange(bars.get(i), bars.get(i-1).close()));
        atr = atr.divide(FOURTEEN, PRECISION);
        for (int i = 15; i < bars.size(); i++)
            atr = atr.multiply(BigDecimal.valueOf(13)).add(trueRange(bars.get(i), bars.get(i-1).close())).divide(FOURTEEN, PRECISION);
        out.put("atr14", rounded(atr));
        out.put("atr14Pct", rounded(atr.divide(bars.getLast().close(), PRECISION).multiply(HUNDRED)));
        out.put("atrReferencePrice", bars.getLast().close());
        out.put("atrReferencePriceBasis", "LATEST_COMPLETED_ADJUSTED_CLOSE");
        out.put("atrMethod", "WILDER_14_SEEDED_WITH_MEAN_OF_14_OBSERVED_TRUE_RANGES");
        out.put("atrPriceUnit", "KRW");
        out.put("atrHistoryFromDate", bars.getFirst().date().toString());
        out.put("atrHistoryToDate", bars.getLast().date().toString());
        out.put("atrTrueRangeCount", bars.size()-1);
    }
    private static BigDecimal trueRange(Bar bar, BigDecimal previousClose) {
        return bar.high().subtract(bar.low()).max(bar.high().subtract(previousClose).abs()).max(bar.low().subtract(previousClose).abs());
    }

    /** Sample variance of 20 close-to-close log returns, annualized with an explicit 252-day assumption.
     * Formula: https://www.fidelity.com/webcontent/ap002390-mlo-content/20.04/pdf/strategyEvaluatorHowTo.pdf (HVOL appendix).
     */
    private static void addVolatility(Map<String,Object> out, List<Bar> bars) {
        if (bars.size() < 21) return;
        double mean = 0, sumSquaredDeviation = 0;
        int start = bars.size()-21;
        for (int i = 1; i <= 20; i++) {
            double current = bars.get(start+i).close().doubleValue(), prior = bars.get(start+i-1).close().doubleValue();
            if (!(current > 0) || !(prior > 0) || !Double.isFinite(current) || !Double.isFinite(prior)) return;
            double change = StrictMath.log(current) - StrictMath.log(prior);
            double delta = change - mean;
            mean += delta / i;
            sumSquaredDeviation += delta * (change - mean);
        }
        double percent = StrictMath.sqrt(Math.max(0, sumSquaredDeviation)/19 * ANNUALIZATION_DAYS) * 100;
        if (!Double.isFinite(percent)) return;
        out.put("hv20AnnualizedPct", rounded(BigDecimal.valueOf(percent)));
        out.put("hv20Estimator", "SAMPLE_STANDARD_DEVIATION_OF_20_LOG_RETURNS");
        out.put("hv20AnnualizationDays", ANNUALIZATION_DAYS);
        out.put("hv20ReturnCount", 20);
        out.put("hv20FromDate", bars.get(start).date().toString());
        out.put("hv20ToDate", bars.getLast().date().toString());
    }
}
