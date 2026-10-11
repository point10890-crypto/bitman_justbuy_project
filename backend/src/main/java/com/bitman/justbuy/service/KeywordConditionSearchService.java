package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.KeywordSearchResponse;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import com.bitman.justbuy.service.KeywordSearchQueryParser.Filter;
import com.bitman.justbuy.service.KeywordSearchQueryParser.Spec;
import com.bitman.justbuy.service.KeywordSearchDataService.Quote;
import com.bitman.justbuy.service.KeywordSearchDataService.Flow;
import com.bitman.justbuy.service.KeywordSearchDataService.Snapshot;
import com.bitman.justbuy.dto.KeywordSearchResponse.*;

@Service
public class KeywordConditionSearchService {
    private final KeywordSearchQueryParser parser;
    private final KeywordSearchDataService data;
    private final Semaphore slots = new Semaphore(4);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public KeywordConditionSearchService(KeywordSearchQueryParser parser, KeywordSearchDataService data) {
        this.parser = parser;
        this.data = data;
    }
    public KeywordSearchResponse search(String query) {
        Spec spec = parser.parse(query);
        if (!spec.unsupportedTerms().isEmpty()) return response(query,"UNSUPPORTED",spec,
            new Scope("지원 조건을 확인한 후 조회합니다",0,0,0,false),null,"NOT_QUERIED",
            List.of("미지원 조건은 생략하지 않습니다. 가격·등락률·거래량·거래대금·순매수 조건으로 조회해 주세요."),List.of());
        if (!slots.tryAcquire()) return response(query,"UNAVAILABLE",spec,new Scope("조회 대기",0,0,0,false),
            null,"UNAVAILABLE",List.of("다른 검색이 진행 중입니다. 잠시 후 다시 조회해 주세요."),List.of());
        try { return select(query,spec); }
        catch (RuntimeException failure) {
            return response(query,"UNAVAILABLE",spec,new Scope("실데이터 조회 실패",0,0,0,false),null,
                "UNAVAILABLE",List.of("실제 시세를 확인하지 못했습니다. 가상 종목을 대신 표시하지 않습니다."),List.of());
        } finally { slots.release(); }
    }

    private KeywordSearchResponse select(String query, Spec spec) {
        Snapshot snapshot;
        if (spec.stockCode()!=null && !spec.stockCode().isBlank()) {
            Quote q=data.lookup(spec.stockCode());
            snapshot=new Snapshot(q==null ? "UNAVAILABLE":"COMPLETE",q==null ? List.of():List.of(q),
                q==null ? null:q.fetchedAt(),"종목코드 직접 조회",q==null ? List.of("해당 코드의 실제 시세를 확인하지 못했습니다."):List.of());
        } else snapshot=data.snapshot();
        if (snapshot==null || !Set.of("COMPLETE","PARTIAL").contains(snapshot.status())) return response(query,"UNAVAILABLE",spec,
            new Scope(snapshot==null ? "실데이터 조회":snapshot.scope(),0,0,0,false),snapshot==null ? null:snapshot.fetchedAt(),
            "UNAVAILABLE",snapshot==null ? List.of("시세 조회 실패"):snapshot.warnings(),List.of());
        var warnings=new LinkedHashSet<String>(snapshot.warnings()==null ? List.of():snapshot.warnings());
        List<Quote> raw=snapshot.quotes()==null ? List.of():snapshot.quotes();
        var distinct=new LinkedHashMap<String,Quote>();
        for (Quote q:raw) {
            if (!validQuote(q)) { warnings.add("코드·가격·출처·조회 시각을 검증하지 못한 시세는 제외했습니다."); continue; }
            if (spec.stockCode()!=null && !spec.stockCode().isBlank() && !spec.stockCode().equals(q.code())) continue;
            if (q.observedAt()==null) warnings.add("거래 시각 미확인 시세가 있습니다. 조회 시각을 거래 시각으로 대신 표시하지 않습니다.");
            else if (Duration.between(q.observedAt(),q.fetchedAt()).compareTo(Duration.ofDays(7))>0)
                warnings.add("7일 이상 지난 관측 시각의 종목이 포함되어 있습니다.");
            distinct.putIfAbsent(q.code(),q);
        }
        var eligible=new ArrayList<Quote>();
        int missingRank=0;
        for (Quote q:distinct.values()) {
            if (spec.market()!=null && !spec.market().isBlank() && !spec.market().equalsIgnoreCase(q.market())) continue;
            if (spec.nameContains()!=null && !spec.nameContains().isBlank()
                && !normalize(q.name()).contains(normalize(spec.nameContains()))) continue;
            if (spec.todayOnly() && (q.observedAt()==null || !q.observedAt().atZone(KST).toLocalDate().equals(LocalDate.now(KST)))) {
                warnings.add("오늘 조건은 오늘의 실제 관측만 사용합니다. 이전 거래일 시세는 제외했습니다."); continue;
            }
            boolean matches=true;
            for (Filter f:spec.filters()) {
                if (isFlow(f.field())) continue;
                BigDecimal v=metric(q,f.field());
                if (v==null) { warnings.add(f.label()+"에 필요한 실측값 미확인 종목은 제외했습니다."); matches=false; }
                else if (!compare(v,f)) matches=false;
            }
            if (matches && spec.rankingExplicit() && metric(q,spec.sortBy())==null) {
                missingRank++;
                warnings.add("요청한 순위 기준의 실측값 미확인 종목은 순위에서 제외했습니다.");
                matches=false;
            }
            if (matches) eligible.add(q);
        }
        eligible.sort(order(spec));
        boolean needsFlow=spec.filters().stream().anyMatch(f -> isFlow(f.field()));
        boolean flowLimited=needsFlow && eligible.size()>20;
        Map<String,Flow> flows=Map.of();
        if (needsFlow && !eligible.isEmpty()) {
            if (flowLimited) warnings.add("수급은 시세 조건을 통과한 정렬 상위 20종목까지만 확인합니다. 전체 시장 수급 검색이 아닙니다.");
            var codes=eligible.stream().limit(20).map(Quote::code).toList();
            try { var observed=data.flows(codes,spec.flowDays()); if(observed!=null) flows=observed; }
            catch (RuntimeException failure) { warnings.add("KIS 수급 조회 미확인: 순매수 값을 추정하지 않았습니다."); }
        }
        var matched=new ArrayList<Match>();
        int verifiedFlows=0;
        for (Quote q:eligible) {
            Flow flow=flows.get(q.code());
            if (needsFlow && !validFlow(flow,q.code(),spec)) {
                warnings.add("요청한 수급 기간·실제 관측일·순매수 값 미확인 종목은 제외했습니다."); continue;
            }
            if (needsFlow) verifiedFlows++;
            if (needsFlow && spec.filters().stream().filter(f -> isFlow(f.field())).anyMatch(f ->
                !compare(BigDecimal.valueOf("FOREIGN_NET".equals(f.field()) ? flow.foreignNet():flow.institutionNet()),f))) continue;
            List<Evidence> evidence=new ArrayList<>();
            for (Filter f:spec.filters()) {
                if (isFlow(f.field())) evidence.add(new Evidence(f.label(),Long.toString("FOREIGN_NET".equals(f.field()) ? flow.foreignNet():flow.institutionNet()),
                    "주","KIS OpenAPI",flow.fromDate()+" ~ "+flow.toDate()));
                else evidence.add(new Evidence(f.label(),metric(q,f.field()).stripTrailingZeros().toPlainString(),unit(f.field()),q.source(),
                    q.observedAt()==null ? null:q.observedAt().toString()));
            }
            if (evidence.isEmpty()) evidence.add(new Evidence("실제 종목 시세 확인",q.price().toPlainString(),"원",q.source(),
                q.observedAt()==null ? null:q.observedAt().toString()));
            matched.add(new Match(q.code(),q.name(),q.market(),q.price(),q.changePct(),q.volume(),q.tradingValueKrw(),
                q.source(),q.fetchedAt(),q.observedAt(),List.copyOf(evidence)));
        }
        int count=matched.size();
        int limit=Math.max(1,Math.min(20,spec.limit()));
        var picks=List.copyOf(matched.subList(0,Math.min(limit,count)));
        boolean partial="PARTIAL".equals(snapshot.status()) || !warnings.isEmpty();
        String outcome=partial ? "PARTIAL":count==0 ? "EMPTY":"COMPLETE";
        if (!raw.isEmpty() && distinct.isEmpty()) outcome="UNAVAILABLE";
        if (raw.isEmpty() && !warnings.isEmpty()) outcome="UNAVAILABLE";
        if ((eligible.isEmpty() && missingRank>0) || (needsFlow && !eligible.isEmpty() && verifiedFlows==0)) outcome="UNAVAILABLE";
        String scope=snapshot.scope()+(needsFlow ? " · 최근 "+spec.flowDays()+"개 실제 거래일 수급, 최대 20종목":"");
        String sourceStatus="UNAVAILABLE".equals(outcome) ? "UNAVAILABLE":partial ? "PARTIAL":"COMPLETE";
        return response(query,outcome,spec,new Scope(scope,distinct.size(),eligible.size(),count,flowLimited || count>limit),
            snapshot.fetchedAt(),sourceStatus,List.copyOf(warnings),picks);
    }

    private static boolean validQuote(Quote q) {
        if (q==null || q.code()==null || !q.code().matches("[0-9]{6}") || q.name()==null || q.name().isBlank()
            || q.source()==null || q.source().isBlank() || q.price()==null || q.price().signum()<=0
            || q.price().stripTrailingZeros().scale()>0 || q.fetchedAt()==null) return false;
        Instant now=Instant.now();
        return !q.fetchedAt().isAfter(now.plusSeconds(1)) && !q.fetchedAt().isBefore(now.minusSeconds(180))
            && (q.observedAt()==null || !q.observedAt().isAfter(now.plusSeconds(60)));
    }
    private static boolean validFlow(Flow f,String code,Spec spec) {
        if (f==null || !code.equals(f.code()) || f.days()!=spec.flowDays() || f.fetchedAt()==null
            || f.fetchedAt().isAfter(Instant.now().plusSeconds(1)) || f.fetchedAt().isBefore(Instant.now().minusSeconds(180))) return false;
        try {
            LocalDate from=LocalDate.parse(f.fromDate()),to=LocalDate.parse(f.toDate()),today=LocalDate.now(KST);
            return !from.isAfter(to) && java.time.temporal.ChronoUnit.DAYS.between(from,to)+1>=f.days()
                && !to.isAfter(today) && !to.isBefore(today.minusDays(7))
                && (!spec.todayOnly() || to.equals(today));
        } catch (RuntimeException e) { return false; }
    }
    private static boolean isFlow(String field) { return "FOREIGN_NET".equals(field) || "INSTITUTION_NET".equals(field); }
    private static BigDecimal metric(Quote q,String field) {
        return switch(field) { case "PRICE" -> q.price(); case "CHANGE_PCT" -> q.changePct();
            case "VOLUME" -> q.volume(); case "TRADING_VALUE" -> q.tradingValueKrw(); default -> null; };
    }
    private static boolean compare(BigDecimal v,Filter f) {
        int c=v.compareTo(f.threshold());
        return switch(f.operator()) { case "GTE" -> c>=0; case "LTE" -> c<=0; case "GT" -> c>0; case "LT" -> c<0; case "EQ" -> c==0; default -> false; };
    }
    private static Comparator<Quote> order(Spec spec) {
        Comparator<BigDecimal> numbers=spec.descending() ? Comparator.reverseOrder():Comparator.naturalOrder();
        return Comparator.comparing((Quote q)->metric(q,spec.sortBy()),Comparator.nullsLast(numbers)).thenComparing(Quote::code);
    }
    private static String unit(String field) { return switch(field) { case "CHANGE_PCT" -> "%"; case "VOLUME" -> "주"; default -> "원"; }; }
    private static String normalize(String text) { return text.replaceAll("\\s+","").toLowerCase(Locale.ROOT); }
    private static KeywordSearchResponse response(String query,String outcome,Spec spec,Scope scope,Instant at,String status,List<String> warnings,List<Match>matches) {
        return new KeywordSearchResponse(query,outcome,spec.appliedConditions(),spec.unsupportedTerms(),scope,at,status,
            warnings==null ? List.of():List.copyOf(warnings),matches);
    }
}
