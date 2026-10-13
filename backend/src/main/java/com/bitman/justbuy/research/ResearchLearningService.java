package com.bitman.justbuy.research;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
/** Pure paired comparison for review; synthetic exception tests are never market evidence. */
public final class ResearchLearningService {
    public enum Origin { ACTUAL_CAPTURE, SYNTHETIC_EXCEPTION, MOCK_FIXTURE }
    public enum Status { INSUFFICIENT_EVIDENCE, REVIEW_CANDIDATE, REJECTED }
    /**
     * The caller supplies the verified KRX session window and captured outcome.
     * The local minimum calendar span rejects impossible horizons; it does not verify holidays,
     * missing sessions, or the authenticity of ACTUAL_CAPTURE. A review remains manual research.
     */
    public record Observation(String instrument,LocalDate date,LocalDate outcomeEndDate,int horizon,String policyId,String evaluationPolicyVersion,String costPolicyVersion,String inputVersion,BigDecimal grossReturn,BigDecimal netReturn,BigDecimal benchmarkReturn,boolean costsVerified,String measurementState,Origin origin,List<String> reasonCodes) {
        public Observation { reasonCodes=reasonCodes==null?List.of():List.copyOf(reasonCodes); }
    }
    public record Policy(String baselinePolicyId,String challengerPolicyId,int horizon,String evaluationPolicyVersion,String costPolicyVersion,String inputVersion,Map<LocalDate,Set<String>> expectedUniverse,int minIndependentDates,BigDecimal minimumMeanDelta) {
        public Policy {
            for(String identifier:List.of(baselinePolicyId,challengerPolicyId,evaluationPolicyVersion,costPolicyVersion,inputVersion)) if(identifier.isBlank()) throw new IllegalArgumentException("Policy identifiers required");
            if(baselinePolicyId.equals(challengerPolicyId) || !Set.of(1,3,5,20).contains(horizon) || minIndependentDates<1 || minimumMeanDelta==null || minimumMeanDelta.signum()<0) throw new IllegalArgumentException("Invalid comparison policy");
            Objects.requireNonNull(expectedUniverse);
            var copy=new TreeMap<LocalDate,Set<String>>();
            expectedUniverse.forEach((date,instruments) -> {
                Objects.requireNonNull(date); Objects.requireNonNull(instruments);
                if(instruments.isEmpty() || instruments.stream().anyMatch(x->x==null || x.isBlank())) throw new IllegalArgumentException("Expected universe must name instruments");
                copy.put(date,Collections.unmodifiableSet(new TreeSet<>(instruments)));
            });
            expectedUniverse=Collections.unmodifiableMap(copy);
        }
        /** Thirty dates is a conservative study default, not statistical proof of profitability. */
        public Policy(String baseline,String challenger,int horizon,String eval,String cost,String input,Map<LocalDate,Set<String>> expected) { this(baseline,challenger,horizon,eval,cost,input,expected,30,BigDecimal.ZERO); }
    }
    public record DailyComparison(LocalDate date,int pairs,BigDecimal baselineNet,BigDecimal challengerNet,BigDecimal delta,LocalDate outcomeEndDate) {}
    public record Review(Status status,int suppliedObservations,int expectedPairs,int eligiblePairs,int completeDates,int independentDates,BigDecimal baselineMeanNet,BigDecimal challengerMeanNet,BigDecimal meanPairedDelta,Map<String,Integer> reasonCohorts,List<DailyComparison> dailyComparisons,boolean productionActivationAllowed) {
        public Review {
            reasonCohorts=Collections.unmodifiableMap(new TreeMap<>(reasonCohorts));
            dailyComparisons=List.copyOf(dailyComparisons);
            if(productionActivationAllowed) throw new IllegalArgumentException("Research reviews cannot activate production");
        }
    }
    private record Key(LocalDate date,String instrument) {}
    private static final MathContext MATH=MathContext.DECIMAL128;

    public Review compare(List<Observation> observations,Policy policy) {
        Objects.requireNonNull(observations); Objects.requireNonNull(policy);
        var reasons=new TreeMap<String,Integer>();
        var byKey=new HashMap<Key,Map<String,List<Observation>>>();
        boolean incomplete=false;
        for(Observation row:observations) {
            if(row==null || row.date()==null || row.instrument()==null || row.instrument().isBlank()) { count(reasons,"MALFORMED_OBSERVATION"); incomplete=true; continue; }
            for(String reason:row.reasonCodes()) count(reasons,reason);
            if(!Objects.equals(row.policyId(),policy.baselinePolicyId()) && !Objects.equals(row.policyId(),policy.challengerPolicyId())) { count(reasons,"UNEXPECTED_POLICY"); incomplete=true; continue; }
            if(!policy.expectedUniverse().getOrDefault(row.date(),Set.of()).contains(row.instrument())) { count(reasons,"OUTSIDE_EXPECTED_UNIVERSE"); incomplete=true; continue; }
            byKey.computeIfAbsent(new Key(row.date(),row.instrument()),k->new HashMap<>()).computeIfAbsent(row.policyId(),k->new ArrayList<>()).add(row);
        }
        int expected=policy.expectedUniverse().values().stream().mapToInt(Set::size).sum();
        if(expected==0) { count(reasons,"MISSING_DENOMINATOR"); incomplete=true; }
        int eligible=0;
        var daily=new ArrayList<DailyComparison>();
        for(var entry:policy.expectedUniverse().entrySet()) {
            LocalDate date=entry.getKey(); BigDecimal baseSum=BigDecimal.ZERO,newSum=BigDecimal.ZERO;
            LocalDate end=date; int completePairs=0;
            for(String instrument:entry.getValue()) {
                var grouped=byKey.getOrDefault(new Key(date,instrument),Map.of());
                var baselines=grouped.getOrDefault(policy.baselinePolicyId(),List.of());
                var challengers=grouped.getOrDefault(policy.challengerPolicyId(),List.of());
                if(baselines.size()>1 || challengers.size()>1) { count(reasons,"DUPLICATE_PAIR"); incomplete=true; continue; }
                if(baselines.isEmpty() || challengers.isEmpty()) { count(reasons,"MISSING_PAIR"); incomplete=true; continue; }
                Observation base=baselines.getFirst(),challenger=challengers.getFirst();
                boolean baseValid=eligible(base,policy,reasons),newValid=eligible(challenger,policy,reasons);
                if(!Objects.equals(base.outcomeEndDate(),challenger.outcomeEndDate())) { count(reasons,"OUTCOME_WINDOW_MISMATCH"); baseValid=false; }
                if(base.benchmarkReturn()!=null && challenger.benchmarkReturn()!=null && base.benchmarkReturn().compareTo(challenger.benchmarkReturn())!=0) { count(reasons,"BENCHMARK_MISMATCH"); baseValid=false; }
                if(!baseValid || !newValid) { incomplete=true; continue; }
                eligible++; completePairs++;
                baseSum=baseSum.add(base.netReturn()); newSum=newSum.add(challenger.netReturn());
                if(base.outcomeEndDate().isAfter(end)) end=base.outcomeEndDate();
            }
            // Partial date groups retain pair counts but cannot contribute a biased daily mean.
            if(completePairs==entry.getValue().size()) {
                BigDecimal baseMean=divide(baseSum,completePairs),newMean=divide(newSum,completePairs);
                daily.add(new DailyComparison(date,completePairs,baseMean,newMean,newMean.subtract(baseMean),end));
            }
        }
        var independent=new ArrayList<DailyComparison>(); LocalDate priorEnd=null;
        for(DailyComparison day:daily) {
            if(priorEnd!=null && !day.date().isAfter(priorEnd)) { count(reasons,"OVERLAPPING_DATE_GROUP"); continue; }
            independent.add(day); priorEnd=day.outcomeEndDate();
        }
        BigDecimal baseMean=null,newMean=null,delta=null;
        if(!independent.isEmpty()) {
            baseMean=mean(independent.stream().map(DailyComparison::baselineNet).toList());
            newMean=mean(independent.stream().map(DailyComparison::challengerNet).toList());
            delta=mean(independent.stream().map(DailyComparison::delta).toList());
        }
        Status status;
        if(incomplete || independent.size()<policy.minIndependentDates()) { status=Status.INSUFFICIENT_EVIDENCE; if(independent.size()<policy.minIndependentDates()) count(reasons,"TOO_FEW_INDEPENDENT_DATES"); }
        else status=delta.compareTo(policy.minimumMeanDelta())>0?Status.REVIEW_CANDIDATE:Status.REJECTED;
        return new Review(status,observations.size(),expected,eligible,daily.size(),independent.size(),baseMean,newMean,delta,reasons,daily,false);
    }

    private static boolean eligible(Observation row,Policy policy,Map<String,Integer> reasons) {
        boolean valid=true;
        if(row.origin()!=Origin.ACTUAL_CAPTURE) { count(reasons,"NON_ACTUAL_ORIGIN"); valid=false; }
        if(!"MEASURED".equals(row.measurementState())) { count(reasons,"UNMEASURED_OUTCOME"); valid=false; }
        if(row.horizon()!=policy.horizon() || !Objects.equals(row.evaluationPolicyVersion(),policy.evaluationPolicyVersion()) || !Objects.equals(row.costPolicyVersion(),policy.costPolicyVersion()) || !Objects.equals(row.inputVersion(),policy.inputVersion())) { count(reasons,"POLICY_VERSION_MISMATCH"); valid=false; }
        if(!row.costsVerified() || row.grossReturn()==null || row.netReturn()==null) { count(reasons,"COST_OR_RETURN_UNKNOWN"); valid=false; }
        else if(row.netReturn().compareTo(row.grossReturn())>0) { count(reasons,"INVALID_NET_COST"); valid=false; }
        if(row.outcomeEndDate()==null || ChronoUnit.DAYS.between(row.date(),row.outcomeEndDate())<policy.horizon()-1L) { count(reasons,"INVALID_OUTCOME_WINDOW"); valid=false; }
        return valid;
    }
    private static void count(Map<String,Integer> reasons,String code) { reasons.merge(code,1,Integer::sum); }
    private static BigDecimal divide(BigDecimal value,int count) { return value.divide(BigDecimal.valueOf(count),MATH); }
    private static BigDecimal mean(List<BigDecimal> values) { return divide(values.stream().reduce(BigDecimal.ZERO,BigDecimal::add),values.size()); }
}
