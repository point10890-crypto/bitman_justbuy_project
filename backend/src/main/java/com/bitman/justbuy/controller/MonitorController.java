package com.bitman.justbuy.controller;

import com.bitman.justbuy.ai.agent.AiAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/**
 * 모니터링 API — 서비스 헬스체크 + 상태 대시보드
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    private final List<AiAgent> agents;

    // 분석 실행 로그 (인메모리, 최근 50건)
    private static final List<Map<String, Object>> analysisLogs = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_LOGS = 50;

    public MonitorController(List<AiAgent> agents) {
        this.agents = agents != null ? agents : List.of();
        log.info("[Monitor] initialized with {} agents", this.agents.size());
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

    /** GET /api/monitor/ping — 단순 연결 테스트 */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("status", "ok", "timestamp", Instant.now().toString());
    }

    /** GET /api/monitor/health — 종합 헬스체크 */
    @GetMapping("/health")
    public Map<String, Object> fullHealthCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());

        try {
            result.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        } catch (Exception e) {
            result.put("uptime", 0);
        }

        // 1. AI 에이전트 상태
        try {
            Map<String, Object> agentStatus = new LinkedHashMap<>();
            int available = 0;
            for (AiAgent agent : agents) {
                try {
                    boolean isAvail = agent.isAvailable();
                    agentStatus.put(agent.name(), Map.of("available", isAvail));
                    if (isAvail) available++;
                } catch (Exception e) {
                    agentStatus.put("unknown", Map.of("available", false));
                }
            }
            result.put("agents", agentStatus);
            result.put("agentsAvailable", available + "/" + agents.size());
        } catch (Exception e) {
            result.put("agents", Map.of());
            result.put("agentsAvailable", "0/0");
            log.warn("[Monitor] agent check failed: {}", e.getMessage());
        }

        // 2. 외부 서비스 프로브 (순차, 안전)
        try {
            List<Map<String, Object>> services = new ArrayList<>();
            services.add(probeUrl("Naver Finance", "https://m.stock.naver.com/api/index/KOSPI/basic"));
            services.add(probeUrl("Cloudflare Frontend", "https://bitman-justbuy.pages.dev"));
            result.put("services", services);
        } catch (Exception e) {
            result.put("services", List.of());
            log.warn("[Monitor] service probe failed: {}", e.getMessage());
        }

        // 3. 메모리 사용량
        try {
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long max = rt.maxMemory() / (1024 * 1024);
            long pct = max > 0 ? Math.round(((double) used / max) * 100) : 0;
            Map<String, Object> mem = new LinkedHashMap<>();
            mem.put("used", used + "MB");
            mem.put("max", max + "MB");
            mem.put("usagePercent", pct);
            result.put("memory", mem);
        } catch (Exception e) {
            result.put("memory", Map.of("used", "?", "max", "?", "usagePercent", 0));
        }

        // 4. 최근 분석 로그
        try {
            List<Map<String, Object>> snapshot = new ArrayList<>(analysisLogs);
            result.put("recentAnalyses", snapshot.size() > 10 ? snapshot.subList(0, 10) : snapshot);
        } catch (Exception e) {
            result.put("recentAnalyses", List.of());
        }

        // 5. 통계
        try {
            List<Map<String, Object>> snapshot = new ArrayList<>(analysisLogs);
            long total = snapshot.size();
            long successCount = snapshot.stream().filter(l -> Boolean.TRUE.equals(l.get("success"))).count();
            double avgMs = snapshot.stream()
                .filter(l -> Boolean.TRUE.equals(l.get("success")) && l.get("durationMs") instanceof Number)
                .mapToLong(l -> ((Number) l.get("durationMs")).longValue())
                .average().orElse(0);

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("totalAnalyses", total);
            stats.put("successRate", total > 0 ? Math.round((double) successCount / total * 100) : 100);
            stats.put("avgDurationMs", Math.round(avgMs));
            result.put("stats", stats);
        } catch (Exception e) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("totalAnalyses", 0);
            stats.put("successRate", 100);
            stats.put("avgDurationMs", 0);
            result.put("stats", stats);
        }

        // 6. 전체 상태 판정
        try {
            int avail = 0;
            for (AiAgent a : agents) {
                try { if (a.isAvailable()) avail++; } catch (Exception ignored) {}
            }
            result.put("status", avail >= 3 ? "healthy" : avail >= 1 ? "degraded" : "down");
        } catch (Exception e) {
            result.put("status", "unknown");
        }

        return result;
    }

    /** GET /api/monitor/logs — 분석 실행 로그 */
    @GetMapping("/logs")
    public List<Map<String, Object>> getLogs() {
        return new ArrayList<>(analysisLogs);
    }

    /** 외부 서비스 프로브 (HttpURLConnection 사용, 의존성 없음) */
    private Map<String, Object> probeUrl(String name, String url) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            long latency = System.currentTimeMillis() - start;
            conn.disconnect();

            result.put("status", (code >= 200 && code < 400) ? "healthy" : "degraded");
            result.put("latencyMs", latency);
        } catch (Exception e) {
            result.put("status", "down");
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("error", e.getMessage() != null ? e.getMessage() : "unknown");
        }

        return result;
    }
}
