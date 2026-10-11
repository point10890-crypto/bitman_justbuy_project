import com.bitman.justbuy.ai.*;
import com.bitman.justbuy.ai.agent.*;
import com.bitman.justbuy.condition.run.*;
import com.bitman.justbuy.controller.AnalysisController;
import com.bitman.justbuy.controller.SubscriptionAccessGuard;
import com.bitman.justbuy.config.AppConfig;
import com.bitman.justbuy.dto.*;
import com.bitman.justbuy.service.*;
import com.bitman.justbuy.util.StructuredAnalysisParser.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Offline audit observations against current classes; all providers/tools are mocked. */
public class PipelineAuditProbe {
    public static void main(String[] args) throws Exception {
        StructuredStock a = new StructuredStock("fixture A", "005930", "매수", .9, 10000L, 12000L, 9000L, null, List.of("fixture"));
        StructuredStock b = new StructuredStock("fixture B", "000660", "매수", .9, 10000L, 12000L, 9000L, null, List.of("fixture"));
        ConsensusEngine engine = new ConsensusEngine();
        var single = engine.calculateConsensus(Map.of("chatgpt", new StructuredAnalysis(List.of(a), "neutral", List.of()), "deepseek", new StructuredAnalysis(List.of(b), "neutral", List.of())), Map.of(), "GREEN").stocks().stream().filter(s -> s.code().equals("005930")).findFirst().orElseThrow();
        var repeated = engine.calculateConsensus(Map.of("chatgpt", new StructuredAnalysis(List.of(a, a), "neutral", List.of()), "deepseek", new StructuredAnalysis(List.of(b), "neutral", List.of())), Map.of(), "GREEN").stocks().stream().filter(s -> s.code().equals("005930")).findFirst().orElseThrow();
        check(single.consensusScore() == 50 && repeated.consensusScore() == 100 && repeated.agentVotes().size() == 1, "Duplicate vote observation changed");
        System.out.printf("OBSERVED duplicateConsensus score=%d->%d mentionCount=%d uniqueVoters=%d agents=%d%n", single.consensusScore(), repeated.consensusScore(), repeated.mentionCount(), repeated.agentVotes().size(), 2);

        MarketDataService market = mock(MarketDataService.class);
        when(market.fetchFormattedMarketData()).thenReturn("Verified input candidate: 삼성전자(005930), current price 10,000 KRW.");
        when(market.fetchStockPrices(anyList())).thenReturn(Map.of());
        KisApiService kis = mock(KisApiService.class);
        DartApiService dart = mock(DartApiService.class);
        FinancialScorer financial = mock(FinancialScorer.class);
        when(financial.score(anyString())).thenReturn(FinancialScore.empty());
        TrackRecordService track = mock(TrackRecordService.class);
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForEntity(anyString(), eq(String.class))).thenReturn(ResponseEntity.ok("{\"gate\":\"GREEN\"}"));
        GrokAgent grok = mock(GrokAgent.class);
        when(grok.name()).thenReturn("grok");
        when(grok.isAvailable()).thenReturn(false);
        GeminiAgent gemini = mock(GeminiAgent.class);
        when(gemini.name()).thenReturn("gemini");
        when(gemini.isAvailable()).thenReturn(true);
        String hallucinated = "📌 테스트가상 (999999) — 현재가 10,000원\n투자판단: 매수\n목표가: 12,000원\n손절가: 9,000원\n근거: This fixture ticker is outside all verified input candidates.\n";
        AiAgent gpt = fixtureAgent("chatgpt", hallucinated);
        AiAgent deep = fixtureAgent("deepseek", hallucinated);
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(List.of(gpt, deep, grok, gemini), market, engine, dart, financial, kis, track, rest, new ObjectMapper());
        AnalysisResponse result = orchestrator.runAnalysis("fixture audit request", "CATALYST_BURST");
        StockPick fabricated = result.stockPicks().stream().filter(p -> p.code().equals("999999")).findFirst().orElseThrow();
        check(result.isFresh() && fabricated.currentPrice().equals("10,000"), "Ungrounded pick observation changed");
        verify(gemini, never()).analyze(anyString(), anyString());
        System.out.printf("OBSERVED ungroundedPick code=%s currentPrice=%s verifiedQuote=NONE inputCandidate=005930 fresh=%s agents=%d/%d%n", fabricated.code(), fabricated.currentPrice(), result.isFresh(), result.metadata().agentsSucceeded(), result.metadata().agentsUsed());
        System.out.println("OBSERVED Grok unavailable + Gemini available: Gemini calls=0");

        var failed = new AnalysisResponse("BREAKOUT", "fixture", List.of(), null, "All agents failed", List.of(), null, Instant.now().toString(), true, new AnalysisResponse.Metadata(0, 2, 0));
        ConditionRunService runs = new ConditionRunService();
        var run = runs.create(ConditionRunTrigger.LIVE_ANALYSIS, "BREAKOUT", "fixture");
        runs.markComplete(run.runId(), failed);
        check(runs.find(run.runId()).orElseThrow().status() == ConditionRunStatus.COMPLETE, "Zero success status observation changed");
        System.out.println("OBSERVED agentsSucceeded=0/2,picks=0 -> ConditionRun COMPLETE");

        AsyncJobManager jobs = new AsyncJobManager();
        String jobId = jobs.createJob();
        jobs.markComplete(jobId, result);
        SubscriptionAccessGuard guard = mock(SubscriptionAccessGuard.class);
        AnalysisController controller = new AnalysisController(mock(ConditionSearchPipeline.class), jobs, guard, runs, mock(TradingResearchViewService.class), mock(LiveAnalysisQuotaService.class));
        UUID otherMember = UUID.randomUUID();
        var polled = controller.getJobStatus(otherMember, jobId);
        verify(guard).requirePro(otherMember);
        check(polled.getStatusCode().value() == 200 && polled.getBody() == result, "Job ownership observation changed");
        System.out.println("OBSERVED unrelated PRO principal + known jobId -> HTTP200 analysis result; owner check=MISSING");

        var healthCache = new AppConfig().cacheManager().getCache("health");
        var caffeineCache = (com.github.benmanes.caffeine.cache.Cache<?, ?>) healthCache.getNativeCache();
        long healthTtlSeconds = caffeineCache.policy().expireAfterWrite().orElseThrow().getExpiresAfter(TimeUnit.SECONDS);
        check(healthTtlSeconds == 14400, "Health cache TTL observation changed");
        System.out.println("OBSERVED server health TTL seconds=" + healthTtlSeconds + " (4 hours)");

        Path blocked = Files.createTempFile("bitman-audit-storage-", ".fixture");
        try {
            var storage = new AnalysisService(orchestrator, mock(ConditionSearchFormulaCatalog.class), mock(KeywordConditionAnalysisService.class), new ObjectMapper(), blocked.toString());
            AnalysisResponse saved = storage.runLiveAnalysis("fixture storage failure", "CATALYST_BURST");
            check(saved.metadata().agentsSucceeded() == 2 && storage.getCachedLive("fixture storage failure", "CATALYST_BURST") != null && !Files.exists(blocked.resolve("CATALYST_BURST.json")), "Storage failure observation changed");
            System.out.println("OBSERVED storage IOException -> successful response + live cache, persisted result=MISSING");
        } finally { Files.deleteIfExists(blocked); }
    }
    static AiAgent fixtureAgent(String name, String content) {
        return new AiAgent() {
            public String name() { return name; }
            public boolean isAvailable() { return true; }
            public AgentResult analyze(String system, String user) { return new AgentResult(name, content, "fixture-only", 0, 0, "success", null, 0); }
        };
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
