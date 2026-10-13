package com.bitman.justbuy.research;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Offline point-in-time condition contract. Price-distance ratios are not realized returns. */
public final class ResearchConditionEngine {
    public enum Metric { CURRENT_PRICE, ENTRY_PRICE, STOP_PRICE, TARGET_PRICE, PER, CHANGE_PCT, TRADING_VALUE, FOREIGN_NET_BUY, INST_NET_BUY, REWARD_RISK }
    public enum Unit { KRW, MULTIPLE, PERCENT, SHARES }
    public enum Environment { REAL, MOCK }
    public enum FactStatus { VALID, UNKNOWN, CONFLICT }
    public enum Operator { LT, LTE, EQ, GTE, GT }
    public enum Truth { TRUE, FALSE, UNKNOWN }
    public enum Direction { ASC, DESC }
    public enum SelectionOrder { FILTER_THEN_RANK, RANK_THEN_FILTER }
    public enum RankingScope { COMPLETE, TOP_K_PROVEN, KNOWN_SUBSET }
    public record Fact(String instrumentId, Metric metric, BigDecimal value, Unit unit, String provider,
                       Environment environment, Instant observedAt, Instant availableAt, FactStatus status) {}
    public record Candidate(String id, List<Fact> facts) {
        public Candidate { facts = List.copyOf(facts); }
    }
    public record Snapshot(Instant cutoff, Duration maxAge, List<Candidate> candidates,
                           Integer universeTotal, boolean complete) {
        public Snapshot { candidates = List.copyOf(candidates); }
    }
    public sealed interface Expression permits Compare, All, Any, Not {}
    public record Compare(Metric metric, Operator op, BigDecimal value, Unit unit) implements Expression {}
    public record All(List<Expression> children) implements Expression {
        public All { children = List.copyOf(children); }
    }
    public record Any(List<Expression> children) implements Expression {
        public Any { children = List.copyOf(children); }
    }
    public record Not(Expression child) implements Expression {}
    public record Request(Expression where, Metric rank, Direction direction, SelectionOrder order, int limit) {}
    public record Decision(String id, Truth condition, Truth eligibility, BigDecimal rankValue, List<String> reasons) {
        public Decision { reasons = List.copyOf(reasons); }
    }
    public record RankedCandidate(String id, BigDecimal rankValue) {}
    public record Coverage(int investigated, int completed, int matched, int falseCount, int unknown,
                           int unranked, Integer universeTotal, Integer notInvestigated) {}
    public record Result(List<RankedCandidate> matched, List<Decision> decisions, Coverage coverage, RankingScope rankingScope) {
        public Result { matched = List.copyOf(matched); decisions = List.copyOf(decisions); }
    }
    private static final int MAX_CANDIDATES = 10_000;
    private static final int MAX_FACTS = 100_000;
    private static final int MAX_FACTS_PER_CANDIDATE = 1_024;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 256;
    private record Value(BigDecimal number, String reason) {}

    /** Counts describe unique investigated identities before selection limit, not the whole market. */
    public Result evaluate(Snapshot snapshot, Request request) {
        validate(snapshot, request);
        Map<String, List<Fact>> unique = new TreeMap<>();
        for (Candidate candidate : snapshot.candidates()) {
            unique.computeIfAbsent(candidate.id(), ignored -> new ArrayList<>()).addAll(candidate.facts());
        }
        if (snapshot.universeTotal() != null && snapshot.universeTotal() < unique.size()) {
            throw new IllegalArgumentException("Universe total below investigated identities");
        }
        List<Decision> decisions = new ArrayList<>();
        Map<String, Truth> priceEligibility = new TreeMap<>();
        int completed = 0, matched = 0, falseCount = 0, unknown = 0, unranked = 0;
        for (var entry : unique.entrySet()) {
            Map<Metric, Value> values = new EnumMap<>(Metric.class);
            TreeSet<String> reasons = new TreeSet<>();
            Value price = resolve(entry.getKey(), entry.getValue(), Metric.CURRENT_PRICE, snapshot);
            values.put(Metric.CURRENT_PRICE, price);
            note(Metric.CURRENT_PRICE, price, reasons);
            Truth eligibility = price.number() == null ? Truth.UNKNOWN
                : price.number().signum() > 0 ? Truth.TRUE : Truth.FALSE;
            priceEligibility.put(entry.getKey(), eligibility);
            if (eligibility == Truth.FALSE) reasons.add("CURRENT_PRICE:NON_POSITIVE");
            Truth condition = expression(request.where(), entry.getKey(), entry.getValue(), snapshot, values, reasons);
            if (usesMetric(request.where(), Metric.PER)) {
                Value per = values.get(Metric.PER);
                if (per != null && per.number() != null && per.number().signum() <= 0) eligibility = Truth.FALSE;
            }
            Truth effective = and(condition, eligibility);
            Value rank = value(request.rank(), entry.getKey(), entry.getValue(), snapshot, values, reasons);
            if (condition != Truth.UNKNOWN && eligibility != Truth.UNKNOWN) completed++;
            switch (effective) {
                case TRUE -> matched++;
                case FALSE -> falseCount++;
                case UNKNOWN -> unknown++;
            }
            boolean needsRank = request.order() == SelectionOrder.FILTER_THEN_RANK
                ? effective != Truth.FALSE : priceEligibility.get(entry.getKey()) != Truth.FALSE;
            BigDecimal rankValue = rank.number();
            if (request.rank() == Metric.PER && rankValue != null && rankValue.signum() <= 0) {
                rankValue = null;
                reasons.add("PER:NON_POSITIVE");
            }
            if (needsRank && rankValue == null) unranked++;
            decisions.add(new Decision(entry.getKey(), condition, eligibility, rankValue, new ArrayList<>(reasons)));
        }
        Comparator<Decision> ranking = Comparator.comparing(Decision::rankValue);
        if (request.direction() == Direction.DESC) ranking = ranking.reversed();
        ranking = ranking.thenComparing(Decision::id);
        List<Decision> eligibleRanked = decisions.stream()
            // Rank-first must reserve slots before applying valuation gates from WHERE.
            .filter(d -> (request.order() == SelectionOrder.RANK_THEN_FILTER
                ? priceEligibility.get(d.id()) : d.eligibility()) == Truth.TRUE && d.rankValue() != null)
            .filter(d -> request.order() == SelectionOrder.RANK_THEN_FILTER || d.condition() == Truth.TRUE)
            .sorted(ranking).toList();
        List<RankedCandidate> selection = eligibleRanked.stream().limit(request.limit())
            .filter(d -> d.condition() == Truth.TRUE && d.eligibility() == Truth.TRUE)
            .map(d -> new RankedCandidate(d.id(), d.rankValue())).toList();
        boolean fullUniverse = snapshot.complete() && snapshot.universeTotal() != null
            && snapshot.universeTotal() == unique.size();
        RankingScope scope = fullUniverse && completed == unique.size() && unknown == 0 && unranked == 0
            ? RankingScope.COMPLETE : RankingScope.KNOWN_SUBSET;
        Integer notInvestigated = snapshot.universeTotal() == null ? null : snapshot.universeTotal() - unique.size();
        return new Result(selection, decisions, new Coverage(unique.size(), completed, matched, falseCount,
            unknown, unranked, snapshot.universeTotal(), notInvestigated), scope);
    }

    private static Truth expression(Expression expression, String id, List<Fact> facts, Snapshot snapshot,
                                    Map<Metric, Value> values, TreeSet<String> reasons) {
        if (expression instanceof Compare compare) {
            Value value = value(compare.metric(), id, facts, snapshot, values, reasons);
            if (value.number() == null) return Truth.UNKNOWN;
            // A provider PER of zero/negative is not evidence of a cheap profitable denominator.
            if (compare.metric() == Metric.PER && value.number().signum() <= 0) {
                reasons.add("PER:NON_POSITIVE");
                return Truth.FALSE;
            }
            int comparison = value.number().compareTo(compare.value());
            boolean result = switch(compare.op()) {
                case LT -> comparison < 0;
                case LTE -> comparison <= 0;
                case EQ -> comparison == 0;
                case GTE -> comparison >= 0;
                case GT -> comparison > 0;
            };
            return result ? Truth.TRUE : Truth.FALSE;
        }
        if (expression instanceof Not not) {
            return switch(expression(not.child(), id, facts, snapshot, values, reasons)) {
                case TRUE -> Truth.FALSE;
                case FALSE -> Truth.TRUE;
                case UNKNOWN -> Truth.UNKNOWN;
            };
        }
        List<Expression> children = expression instanceof All all ? all.children() : ((Any) expression).children();
        Truth result = expression instanceof All ? Truth.TRUE : Truth.FALSE;
        for (Expression child : children) {
            Truth next = expression(child, id, facts, snapshot, values, reasons);
            result = expression instanceof All ? and(result, next) : or(result, next);
        }
        return result;
    }

    private static Truth and(Truth a, Truth b) {
        if (a == Truth.FALSE || b == Truth.FALSE) return Truth.FALSE;
        return a == Truth.UNKNOWN || b == Truth.UNKNOWN ? Truth.UNKNOWN : Truth.TRUE;
    }
    private static boolean usesMetric(Expression expression, Metric metric) {
        if (expression instanceof Compare compare) return compare.metric() == metric;
        if (expression instanceof Not not) return usesMetric(not.child(), metric);
        List<Expression> children = expression instanceof All all ? all.children() : ((Any)expression).children();
        return children.stream().anyMatch(child -> usesMetric(child, metric));
    }
    private static Truth or(Truth a, Truth b) {
        if (a == Truth.TRUE || b == Truth.TRUE) return Truth.TRUE;
        return a == Truth.UNKNOWN || b == Truth.UNKNOWN ? Truth.UNKNOWN : Truth.FALSE;
    }

    private static Value value(Metric metric, String id, List<Fact> facts, Snapshot snapshot,
                               Map<Metric, Value> values, TreeSet<String> reasons) {
        Value value = values.computeIfAbsent(metric, ignored -> resolve(id, facts, metric, snapshot));
        note(metric, value, reasons);
        return value;
    }
    private static void note(Metric metric, Value value, TreeSet<String> reasons) {
        if (value.reason() != null) reasons.add(metric.name() + ":" + value.reason());
    }

    private static Value resolve(String id, List<Fact> facts, Metric metric, Snapshot snapshot) {
        List<Fact> matching = facts.stream().filter(f -> f.metric() == metric).toList();
        if (matching.isEmpty()) return new Value(null, "MISSING");
        TreeSet<String> invalid = new TreeSet<>();
        for (Fact fact : matching) {
            if (!id.equals(fact.instrumentId())) invalid.add("IDENTITY_MISMATCH");
            if (fact.unit() != unit(metric)) invalid.add("UNIT_MISMATCH");
            if (fact.status() == FactStatus.CONFLICT) invalid.add("CONFLICT");
            else if (fact.status() != FactStatus.VALID || fact.value() == null) invalid.add("UNAVAILABLE");
            if (!validText(fact.provider()) || fact.environment() == null) invalid.add("PROVENANCE_MISSING");
            if (fact.observedAt() == null || fact.availableAt() == null) invalid.add("TIME_MISSING");
            else {
                if (fact.observedAt().isAfter(snapshot.cutoff()) || fact.availableAt().isAfter(snapshot.cutoff())) invalid.add("FUTURE");
                if (fact.observedAt().isBefore(snapshot.cutoff().minus(snapshot.maxAge()))) invalid.add("STALE");
                if (fact.availableAt().isBefore(fact.observedAt())) invalid.add("TIME_ORDER_INVALID");
            }
            if (fact.value() != null && !boundedNumber(fact.value())) invalid.add("VALUE_OUT_OF_BOUNDS");
            if (metric == Metric.REWARD_RISK && fact.value() != null && fact.value().signum() < 0) invalid.add("INVALID_DISTANCE_RATIO");
        }
        if (!invalid.isEmpty()) return new Value(null, String.join("|", invalid));
        Fact first = matching.getFirst();
        for (Fact other : matching) {
            // Equal numbers at different times or environments are not interchangeable observations.
            if (first.value().compareTo(other.value()) != 0 || first.environment() != other.environment()
                || !first.observedAt().equals(other.observedAt())) return new Value(null, "CONFLICT");
        }
        return new Value(first.value().stripTrailingZeros(), null);
    }

    private static Unit unit(Metric metric) {
        return switch(metric) {
            case PER, REWARD_RISK -> Unit.MULTIPLE;
            case CHANGE_PCT -> Unit.PERCENT;
            case FOREIGN_NET_BUY, INST_NET_BUY -> Unit.SHARES;
            default -> Unit.KRW;
        };
    }
    private static boolean validText(String text) { return text != null && !text.isBlank() && text.length() <= 128; }
    private static boolean boundedNumber(BigDecimal value) { return value.precision() <= 64 && Math.abs((long)value.scale()) <= 32; }

    private static void validate(Snapshot snapshot, Request request) {
        if (snapshot == null || request == null || snapshot.cutoff() == null || snapshot.maxAge() == null
            || snapshot.maxAge().isNegative() || snapshot.maxAge().compareTo(Duration.ofDays(36600)) > 0
            || snapshot.candidates().size() > MAX_CANDIDATES || snapshot.universeTotal() != null && snapshot.universeTotal() < 0) {
            throw new IllegalArgumentException("Invalid snapshot or input bounds");
        }
        if (request.rank() == null || request.direction() == null || request.order() == null
            || request.limit() < 1 || request.limit() > MAX_CANDIDATES) throw new IllegalArgumentException("Invalid ranking request");
        validateExpression(request.where(), 1, new int[]{0});
        long factCount = 0;
        for (Candidate candidate : snapshot.candidates()) {
            if (candidate == null || !validText(candidate.id()) || candidate.facts().size() > MAX_FACTS_PER_CANDIDATE)
                throw new IllegalArgumentException("Invalid candidate or fact bounds");
            factCount += candidate.facts().size();
            if (factCount > MAX_FACTS) throw new IllegalArgumentException("Total fact bound exceeded");
            for (Fact fact : candidate.facts()) {
                if (fact == null || fact.metric() == null) throw new IllegalArgumentException("Invalid metric fact");
            }
        }
    }
    private static void validateExpression(Expression expression, int depth, int[] count) {
        if (expression == null || depth > MAX_DEPTH || ++count[0] > MAX_NODES) throw new IllegalArgumentException("Malformed or excessive expression");
        if (expression instanceof Compare compare) {
            if (compare.metric() == null || compare.op() == null || compare.value() == null
                || compare.unit() != unit(compare.metric()) || !boundedNumber(compare.value())) throw new IllegalArgumentException("Invalid comparison");
        } else if (expression instanceof Not not) validateExpression(not.child(), depth + 1, count);
        else {
            List<Expression> children = expression instanceof All all ? all.children() : ((Any)expression).children();
            if (children.isEmpty() || children.size() > MAX_NODES) throw new IllegalArgumentException("Invalid expression children");
            for (Expression child : children) validateExpression(child, depth + 1, count);
        }
    }
}
