package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class DetectedStockQualityServiceTest {
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-12T06:00:00Z"),ZoneOffset.UTC);
    private final DetectedStockQualityService service=new DetectedStockQualityService(clock);

    @Test void highAiScoreAndLegacyFinancialScoreCannotSubstituteForActualEvidence() {
        var row=service.evaluate("FLOW_LEADER",response(pick("2026-10-12T05:55:00Z"),null,2,2)).stocks().getFirst();
        assertThat(row.quoteVerified()).isTrue();
        assertThat(row.financialEvidenceAvailable()).isFalse();
        assertThat(row.evidenceState()).isEqualTo("LIMITED_EVIDENCE");
        assertThat(row.issues()).contains("FINANCIAL_EVIDENCE_MISSING", "CONSENSUS_UNAVAILABLE");
    }

    @Test void historicalQuotesAreExplicitlyStaleAndFutureQuotesAreInvalid() {
        var stale=service.evaluate("FLOW_LEADER",response(pick("2026-10-11T05:55:00Z"),null,2,2)).stocks().getFirst();
        assertThat(stale.evidenceState()).isEqualTo("STALE"); assertThat(stale.quoteVerified()).isTrue(); assertThat(stale.quoteFresh()).isFalse();
        var future=service.evaluate("FLOW_LEADER",response(pick("2026-10-12T06:05:00Z"),null,2,2)).stocks().getFirst();
        assertThat(future.evidenceState()).isEqualTo("REVIEW_REQUIRED"); assertThat(future.quoteVerified()).isFalse();
    }

    @Test void oneAnalystMentionAndUnmatchedFinancialStockCannotEarnTraceableLabel() {
        var financial=Map.<String,Object>of("dartFinancial",Map.of("stocks",List.of(Map.of("stockCode","000660","status","AVAILABLE","facts",Map.of("currency","KRW","receiptNo","20260814000001","period","HALF_YEAR_YTD","revenue",10)))));
        var consensus=new ConsensusResult(List.of(new ConsensusResult.ConsensusStock("삼성전자","005930","주목",99,1,Map.of("chatgpt",new ConsensusResult.AgentVote("주목",.99,null,null)),.99,null,null,null)),"neutral",99,List.of(),2);
        var row=service.evaluate("FLOW_LEADER",response(pick("2026-10-12T05:55:00Z"),financial,2,1,consensus)).stocks().getFirst();
        assertThat(row.analystCount()).isEqualTo(1); assertThat(row.financialEvidenceAvailable()).isFalse();
        assertThat(row.evidenceState()).isEqualTo("LIMITED_EVIDENCE"); assertThat(row.issues()).contains("PARTIAL_AGENT_FAILURE", "SINGLE_ANALYST_SUPPORT");
    }

    @Test void validDatedFinancialFactsAreDifferentFromAKeywordScore() {
        var financial=Map.<String,Object>of("dartFinancial",Map.of("stocks",List.of(Map.of("stockCode","005930","status","COMPLETE","facts",Map.of("currency","KRW","receiptNo","20260814000001","period","2026 사업연도 반기 당분기(3개월)","revenue",10)))));
        var row=service.evaluate("REVERSAL_EDGE",response(pick("2026-10-12T05:55:00Z"),financial,2,2)).stocks().getFirst();
        assertThat(row.financialEvidenceAvailable()).isTrue(); assertThat(row.issues()).doesNotContain("FINANCIAL_EVIDENCE_MISSING");
        assertThat(service.evaluate("FLOW_LEADER",null).stocks()).isEmpty();
    }

    @Test void opposingVotesAreParticipationRatherThanAgreementAndClosedSessionIsStale() {
        var votes=Map.of("chatgpt",new ConsensusResult.AgentVote("매수",.9,null,null),"grok",new ConsensusResult.AgentVote("매도",.9,null,null));
        var consensus=new ConsensusResult(List.of(new ConsensusResult.ConsensusStock("삼성전자","005930","관망",99,2,votes,.9,null,null,null)),"neutral",99,List.of(),2);
        var row=service.evaluate("FLOW_LEADER",response(pick("2026-10-12T05:55:00Z"),null,2,2,consensus)).stocks().getFirst();
        assertThat(row.issues()).contains("AGENT_ACTION_DISAGREEMENT");
        var closed=new DetectedStockQualityService(Clock.fixed(Instant.parse("2026-10-12T06:31:00Z"),ZoneOffset.UTC));
        var late=closed.evaluate("BREAKOUT",response(pick("2026-10-12T06:29:00Z"),null,2,2)).stocks().getFirst();
        assertThat(late.quoteFresh()).isFalse(); assertThat(late.evidenceState()).isEqualTo("STALE");
    }

    @Test void displayedTargetAndStopExposeAsymmetricRiskWithoutTreatingThemAsObservedPrices() {
        var lowReward=new StockPick("DB손해보험","005830","194,400","197,600","186,000","주목","근거",20,"부채비율","MARKET_DATA","NAVER_FINANCE","2026-10-12T05:55:00Z");
        var row=service.evaluate("REVERSAL_EDGE",response(lowReward,null,2,2)).stocks().getFirst();
        assertThat(row.issues()).contains("REWARD_RISK_BELOW_ONE","MODEL_PRICE_LEVELS_UNVALIDATED");
        var inverted=new StockPick("HMM","011200","21,900","20,000","23,000","주목","근거",0,"","MARKET_DATA","NAVER_FINANCE","2026-10-12T05:55:00Z");
        assertThat(service.evaluate("REVERSAL_EDGE",response(inverted,null,2,2)).stocks().getFirst().issues()).contains("INVALID_TARGET_STOP_DIRECTION");
    }

    private static StockPick pick(String at) {
        return new StockPick("삼성전자","005930","70,000","77,000","65,000","주목","근거",99,"배당 호재","KIS_MARKET_DATA","KIS",at);
    }
    private static AnalysisResponse response(StockPick pick, Map<String,Object> facts,int used,int success) {return response(pick,facts,used,success,null);}
    private static AnalysisResponse response(StockPick pick, Map<String,Object> facts,int used,int success,ConsensusResult consensus) {
        return new AnalysisResponse("FLOW_LEADER","private question",List.of(),null,"private explanation",List.of(pick),consensus,"2026-10-12T05:55:00Z",true,new AnalysisResponse.Metadata(100,used,success,null,facts));
    }
}
