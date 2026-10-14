package com.bitman.justbuy.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;

/** Initial screening settings, not empirically validated return forecasts. Amounts are KRW. */
public final class ConditionLiquidityPolicy {
    private ConditionLiquidityPolicy() {}
    private static final BigDecimal EOK = BigDecimal.valueOf(100_000_000);
    public record Rule(long minimumEok, long average20Eok, long preferredEok, long strongestEok) {}
    public record Result(boolean eligible, int bonus, String reason, String observedAt) {
        public Result(boolean eligible, int bonus, String reason) { this(eligible, bonus, reason, null); }
    }
    public static final Map<String, Rule> RULES = Map.of(
        "BREAKOUT", new Rule(100, 0, 300, 0),
        "REVERSAL_EDGE", new Rule(0, 50, 100, 0),
        "FLOW_LEADER", new Rule(500, 200, 1000, 0),
        "CATALYST_BURST", new Rule(100, 0, 300, 0),
        "JONGGA_V2", new Rule(100, 0, 300, 500));

    public static Result evaluate(String mode, BigDecimal today, BigDecimal average20, LocalTime time) {
        Rule rule = RULES.get(mode);
        if (rule == null) return new Result(false, 0, "지원하지 않는 거래대금 모드");
        if (today == null || today.signum() < 0) return new Result(false, 0, "실제 거래대금 미확인");
        long minimum = rule.minimumEok();
        // Actual accumulated turnover only: no straight-line end-of-day projections.
        if (time != null && !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(11, 0))) {
            boolean early = time.isBefore(LocalTime.of(10, 0));
            if ("BREAKOUT".equals(mode) || "CATALYST_BURST".equals(mode)) minimum = early ? 30 : 50;
            if ("FLOW_LEADER".equals(mode)) minimum = early ? 150 : 250;
        }
        if (rule.average20Eok() > 0 && (average20 == null || average20.compareTo(eok(rule.average20Eok())) < 0))
            return new Result(false, 0, "직전 20거래일 평균 " + rule.average20Eok() + "억 기준 미달 또는 미확인");
        if (today.compareTo(eok(minimum)) < 0) return new Result(false, 0, "누적 거래대금 " + minimum + "억 미달");
        int bonus = today.compareTo(eok(rule.preferredEok())) >= 0 ? 5 : 0;
        if (rule.strongestEok() > 0 && today.compareTo(eok(rule.strongestEok())) >= 0) bonus = 10;
        String evidence = "누적 " + today.divide(EOK).stripTrailingZeros().toPlainString() + "억 / 최소 " + minimum + "억";
        if (rule.average20Eok() > 0) evidence += " · 직전 20거래일 평균 "
            + average20.divide(EOK).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "억";
        return new Result(true, bonus, evidence + " · 거래대금 정렬 가점 " + bonus);
    }
    private static BigDecimal eok(long value) { return EOK.multiply(BigDecimal.valueOf(value)); }
}
