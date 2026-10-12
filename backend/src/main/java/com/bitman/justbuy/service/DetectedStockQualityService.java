package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Evidence completeness is separate from investment returns and AI confidence. No supplier calls. */
@Service
public class DetectedStockQualityService {
    private final Clock clock;
    @Autowired public DetectedStockQualityService(){this(Clock.systemUTC());}
    DetectedStockQualityService(Clock clock){this.clock=clock;}
    public record StockQuality(String stockCode,String stockName,String evidenceState,boolean quoteVerified,
        boolean quoteFresh,String quoteSource,String quoteCheckedAt,int analystCount,boolean financialEvidenceAvailable,List<String> issues){}
    public record ModeQuality(String mode,String updatedAt,List<StockQuality> stocks,Map<String,Long> counts,List<String> limitations){}

    public ModeQuality evaluate(String mode,AnalysisResponse response){
        var rows=new ArrayList<StockQuality>();
        Set<String> seen=new HashSet<>();
        if(response!=null&&response.stockPicks()!=null)for(StockPick pick:response.stockPicks()){
            if(pick==null)continue;
            var issues=new LinkedHashSet<String>();
            boolean verified=AnalysisOutcome.isVerifiedPick(pick,clock.instant(),Long.MAX_VALUE);
            boolean fresh=AnalysisOutcome.isVerifiedPick(pick,clock.instant(),175)&&analysisFresh(response);
            if(!verified)issues.add("QUOTE_PROVENANCE_INVALID");else if(!fresh)issues.add("STALE_QUOTE_OR_ANALYSIS");
            if(!seen.add(pick.code()))issues.add("DUPLICATE_STOCK_CODE");
            int analystCount=0;
            if(response.consensus()==null||response.consensus().stocks()==null)issues.add("CONSENSUS_UNAVAILABLE");
            else{
                var item=response.consensus().stocks().stream().filter(Objects::nonNull).filter(s->Objects.equals(pick.code(),s.code())).findFirst();
                if(item.isPresent()&&item.get().agentVotes()!=null){
                    var votes=item.get().agentVotes().values().stream().filter(Objects::nonNull).filter(v->v.action()!=null&&!v.action().isBlank()).toList();
                    analystCount=votes.size();
                    if(votes.stream().map(v->v.action().trim().toLowerCase(Locale.ROOT)).distinct().count()>1)issues.add("AGENT_ACTION_DISAGREEMENT");
                }
                if(analystCount<2)issues.add("SINGLE_ANALYST_SUPPORT");
            }
            if(response.metadata()!=null&&response.metadata().agentsSucceeded()<response.metadata().agentsUsed())issues.add("PARTIAL_AGENT_FAILURE");
            boolean financial=hasFinancialFacts(response,pick.code());
            if(!financial)issues.add("FINANCIAL_EVIDENCE_MISSING");
            issues.add("TECHNICAL_AND_FLOW_COVERAGE_REQUIRES_REVIEW");
            Long price=price(pick.currentPrice()),target=price(pick.targetPrice()),stop=price(pick.stopLoss());
            if(target!=null||stop!=null)issues.add("MODEL_PRICE_LEVELS_UNVALIDATED");
            if(price!=null&&target!=null&&stop!=null){
                if(target<=price||stop>=price)issues.add("INVALID_TARGET_STOP_DIRECTION");
                else if(target-price<price-stop)issues.add("REWARD_RISK_BELOW_ONE");
            }
            String state=!verified||issues.contains("DUPLICATE_STOCK_CODE")?"REVIEW_REQUIRED":!fresh?"STALE":"LIMITED_EVIDENCE";
            rows.add(new StockQuality(pick.code(),pick.name(),state,verified,fresh,pick.quoteSource(),pick.quoteCheckedAt(),analystCount,financial,List.copyOf(issues)));
        }
        var counts=new LinkedHashMap<String,Long>();
        for(String state:List.of("LIMITED_EVIDENCE","STALE","REVIEW_REQUIRED"))counts.put(state,rows.stream().filter(r->state.equals(r.evidenceState())).count());
        return new ModeQuality(mode,response==null?null:response.updatedAt(),List.copyOf(rows),Collections.unmodifiableMap(counts),List.of(
            "근거 품질 평가이며 수익률·성공 확률·매수 추천이 아닙니다.",
            "같은 입력을 사용하는 AI의 일치는 독립 데이터 소스의 교차 검증과 다릅니다.",
            "구조화 기술·수급 근거와 당시 원자료가 없는 과거 픽은 완전 검증으로 승격하지 않습니다."));
    }

    private static Long price(String text){
        if(text==null||!text.trim().matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)\\s*원?"))return null;
        try{long value=Long.parseLong(text.replace(",","").replace("원","").trim());return value>0?value:null;}
        catch(RuntimeException ignored){return null;}
    }

    private boolean analysisFresh(AnalysisResponse response){
        try{
            Instant updated=Instant.parse(response.updatedAt());
            var nowKst=clock.instant().atZone(ZoneId.of("Asia/Seoul"));
            if(nowKst.toLocalTime().isAfter(LocalTime.of(15,30))&&updated.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().equals(nowKst.toLocalDate()))return false;
            Duration age=Duration.between(updated,clock.instant());return !age.isNegative()&&age.compareTo(Duration.ofMinutes(175))<=0;
        }
        catch(RuntimeException ignored){return false;}
    }
    private boolean hasFinancialFacts(AnalysisResponse response,String code){
        if(response.metadata()==null||response.metadata().sourceExpansion()==null)return false;
        Object data=response.metadata().sourceExpansion().get("dartFinancial");
        if(!(data instanceof Map<?,?> financial)||!(financial.get("stocks") instanceof List<?> stocks))return false;
        for(Object value:stocks)if(value instanceof Map<?,?> stock&&Objects.equals(code,stock.get("stockCode"))&&Set.of("COMPLETE","PARTIAL").contains(Objects.toString(stock.get("status"),""))&&stock.get("facts") instanceof Map<?,?> facts){
            Object receipt=facts.get("receiptNo"),period=facts.get("period");
            if(!"KRW".equals(facts.get("currency"))||!(receipt instanceof String r)||!r.matches("[0-9]{14}")||!(period instanceof String p)||p.isBlank())continue;
            // A title, status or financialScore without an actual account does not count.
            if(List.of("revenue","operatingProfit","netIncome","operatingCashFlow","equity","debt").stream().anyMatch(k->facts.get(k) instanceof Number))return true;
        }
        return false;
    }
}
