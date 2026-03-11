package com.bitman.justbuy.controller;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.service.PrecomputeScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 모니터링 API — 서비스 헬스체크 + 상태 대시보드
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    private final List<AiAgent> agents;
    private final RestTemplate restTemplate = new RestTemplate();

    // 분석 실행 로그 (인메모리, 최근 50건)
    private static final List<Map<String, Object>> analysisLogs = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_LOGS = 50;

    @Autowired(required = false)
    private PrecomputeScheduler scheduler;

    public MonitorController(List<AiAgent> agents) {
        this.agents = agents;
    }

    /** 분석 결과 기록 (PrecomputeScheduler에서 호출) */
    public static void recordAnalysis(String mode, boolean success, long durationMs,
                                       int agentsUsed, int agentsSucceeded, int stocksFound, String error) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("mode", mode);
        entry.put("timestamp", Instant.now().toString());
        entry.put("success", success);
        entry.put("durationMs", durationMs);
        entry.put("agentsUsed", agentsUsed);
        entry.put("agentsSucceeded", agentsSucceeded);
        entry.put("stocksFound", stocksFound);
        if (error != null) entry.put("error", error);

        analysisLogs.add(0, entry);
        while (analysisLogs.size() > MAX_LOGS) {
            analysisLogs.remove(analysisLogs.size() - 1);
        }
    }

    /** GET /api/monitor/health — 종합 헬스체크 */
    @GetMapping("/health")
    public Map<String, Object> fullHealthCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);

        // 1. AI 에이전트 상태
        Map<String, Object> agentStatus = new LinkedHashMap<>();
        int available = 0;
        for (AiAgent agent : agents) {
            boolean isAvail = agent.isAvailable();
            agentStatus.put(agent.name(), Map.of("available", isAvail));
            if (isAvail) available++;
        }
        result.put("agents", agentStatus);
        result.put("agentsAvailable", available + "/" + agents.size());

        // 2. 외부 서비스 프로브 (병렬)
        result.put("services", probeExternalServices());

        // 3. 메모리 사용량
        Runtime rt = Runtime.getRuntime();
        result.put("memory", Map.of(
            "used", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024) + "MB",
            "max", rt.maxMemory() / (1024 * 1024) + "MB",
            "usagePercent", Math.round(((double)(rt.totalMemory() - rt.freeMemory()) / rt.maxMemory()) * 100)
        ));

        // 4. 최근 분석 로그
        result.put("recentAnalyses", analysisLogs.stream().limit(10).toList());

        // 5. 통계
        long successCount = analysisLogs.stream().filter(l -> Boolean.TRUE.equals(l.get("success"))).count();
        result.put("stats", Map.of(
            "totalAnalyses", analysisLogs.size(),
            "successRate", analysisLogs.isEmpty() ? 100 : Math.round((double) successCount / analysisLogs.size() * 100),
            "avgDurationMs", analysisLogs.stream()
                .filter(l -> Boolean.TRUE.equals(l.get("success")))
                .mapToLong(l -> ((Number) l.get("durationMs")).longValue())
                .average().orElse(0)
        ));

        // 6. 전체 상태 판정
        boolean allAgentsUp = available >= 3;
        result.put("status", allAgentsUp ? "healthy" : available >= 1 ? "degraded" : "down");

        return result;
    }

    /** GET /api/monitor/logs — 분석 실행 로그 */
    @GetMapping("/logs")
    public List<Map<String, Object>> getLogs() {
        return analysisLogs;
    }

    /** 외부 서비스 프로브 (Naver Finance, Cloudflare) */
    private List<Map<String, Object>> probeExternalServices() {
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<Map<String, Object>>> futures = new ArrayList<>();

        // Naver Finance
        futures.add(exec.submit(() -> probeUrl("Naver Finance", "https://m.stock.naver.com/api/index/KOSPI/basic")));
        // Cloudflare Frontend
        futures.add(exec.submit(() -> probeUrl("Cloudflare Frontend", "https://bitman-justbuy.pages.dev")));

        List<Map<String, Object>> results = new ArrayList<>();
        for (Future<Map<String, Object>> f : futures) {
            try {
                results.add(f.get(15, TimeUnit.SECONDS));
            } catch (Exception e) {
                results.add(Map.of("name", "unknown", "status", "timeout"));
            }
        }
        exec.shutdown();
        return results;
    }

    private Map<String, Object> probeUrl(String name, String url) {
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> res = restTemplate.getForEntity(url, String.class);
            long latency = System.currentTimeMillis() - start;
            return Map.of(
                "name", name,
                "status", res.getStatusCode().is2xxSuccessful() ? "healthy" : "degraded",
                "latencyMs", latency
            );
        } catch (Exception e) {
            return Map.of(
                "name", name,
                "status", "down",
                "latencyMs", System.currentTimeMillis() - start,
                "error", e.getMessage() != null ? e.getMessage() : "unknown"
            );
        }
    }
}
