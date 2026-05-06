package com.bitman.justbuy.controller;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.dto.AdminResetPasswordRequest;
import com.bitman.justbuy.dto.AdminUpdateUserRequest;
import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.service.AnalysisService;
import com.bitman.justbuy.service.DeepSeekEndpointService;
import com.bitman.justbuy.service.KisApiService;
import com.bitman.justbuy.service.PrecomputeScheduler;
import com.bitman.justbuy.service.RuntimeAiConfigService;
import com.bitman.justbuy.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SubscriptionService subscriptionService;
    private final AnalysisService analysisService;
    private final List<AiAgent> agents;
    private final KisApiService kisApiService;
    private final RuntimeAiConfigService runtimeAiConfigService;
    private final DeepSeekEndpointService deepSeekEndpointService;
    private final Map<String, RefreshJob> refreshJobs = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private PrecomputeScheduler precomputeScheduler;

    private enum RefreshStatus { PENDING, RUNNING, COMPLETE, ERROR }

    private record RefreshJob(
        RefreshStatus status,
        Instant createdAt,
        Instant completedAt,
        List<Map<String, Object>> results,
        String error
    ) {}

    public AdminController(SubscriptionService subscriptionService,
                           AnalysisService analysisService,
                           List<AiAgent> agents,
                           KisApiService kisApiService,
                           RuntimeAiConfigService runtimeAiConfigService,
                           DeepSeekEndpointService deepSeekEndpointService) {
        this.subscriptionService = subscriptionService;
        this.analysisService = analysisService;
        this.agents = agents;
        this.kisApiService = kisApiService;
        this.runtimeAiConfigService = runtimeAiConfigService;
        this.deepSeekEndpointService = deepSeekEndpointService;
    }

    @GetMapping("/subscriptions/pending")
    public ResponseEntity<List<UserDto>> pendingSubscriptions() {
        return ResponseEntity.ok(subscriptionService.getPendingSubscriptions());
    }

    @GetMapping("/subscriptions/stats")
    public ResponseEntity<Map<String, Object>> subscriptionStats() {
        return ResponseEntity.ok(subscriptionService.getSubscriptionStats());
    }

    @PostMapping("/subscriptions/{userId}/approve")
    public ResponseEntity<UserDto> approve(@PathVariable UUID userId) {
        return ResponseEntity.ok(subscriptionService.approveSubscription(userId));
    }

    @PostMapping("/subscriptions/{userId}/reject")
    public ResponseEntity<UserDto> reject(@PathVariable UUID userId) {
        return ResponseEntity.ok(subscriptionService.rejectSubscription(userId));
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> allUsers() {
        return ResponseEntity.ok(subscriptionService.getAllUsers());
    }

    @PostMapping("/subscriptions/{userId}/revoke")
    public ResponseEntity<UserDto> revoke(@PathVariable UUID userId) {
        return ResponseEntity.ok(subscriptionService.revokeSubscription(userId));
    }

    // --- 회원관리 ---

    @PutMapping("/users/{userId}")
    public ResponseEntity<UserDto> updateUser(@PathVariable UUID userId,
                                               @Valid @RequestBody AdminUpdateUserRequest request) {
        return ResponseEntity.ok(subscriptionService.adminUpdateUser(
                userId, request.name(), request.email(), request.subscription()));
    }

    @PutMapping("/users/{userId}/password")
    public ResponseEntity<Map<String, String>> resetPassword(@PathVariable UUID userId,
                                                              @Valid @RequestBody AdminResetPasswordRequest request) {
        subscriptionService.adminResetPassword(userId, request.newPassword());
        return ResponseEntity.ok(Map.of("message", "비밀번호가 초기화되었습니다."));
    }

    @GetMapping("/users/search")
    public ResponseEntity<List<UserDto>> searchUsers(@RequestParam String q) {
        return ResponseEntity.ok(subscriptionService.searchUsers(q));
    }

    // --- 시스템 관리 ---

    @GetMapping("/system/status")
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        long startMs = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();

        // 서버 상태
        result.put("status", "ok");
        result.put("timestamp", Instant.now().toString());

        // AI 엔진 상태 — 프론트엔드가 engines: [{name, online}] 배열을 기대
        try {
            List<Map<String, Object>> engines = new java.util.ArrayList<>();
            for (AiAgent agent : agents) {
                engines.add(Map.of("name", agent.name(), "online", agent.isAvailable()));
            }
            result.put("engines", engines);
            result.put("totalAgents", agents.size());
            result.put("availableAgents", agents.stream().filter(AiAgent::isAvailable).count());
        } catch (Exception e) {
            result.put("engines", List.of());
            result.put("totalAgents", 0);
            result.put("availableAgents", 0);
        }

        // 캐시 상태 — 프론트엔드가 cache: [{mode, status, lastUpdated, elapsed}] 배열을 기대
        try {
            Map<String, Map<String, Object>> rawCache = analysisService.getCacheStatus();
            List<Map<String, Object>> cacheList = new java.util.ArrayList<>();
            for (var entry : rawCache.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("mode", entry.getKey());
                Map<String, Object> v = entry.getValue();
                boolean exists = Boolean.TRUE.equals(v.get("exists"));
                boolean valid = Boolean.TRUE.equals(v.get("valid"));
                item.put("status", !exists ? "missing" : valid ? "valid" : "expired");
                item.put("lastUpdated", v.getOrDefault("updatedAt", null));
                long elapsedMin = v.get("elapsedMinutes") instanceof Number n ? n.longValue() : -1;
                item.put("elapsed", elapsedMin >= 0 ? elapsedMin + "분 전" : null);
                cacheList.add(item);
            }
            result.put("cache", cacheList);
        } catch (Exception e) {
            result.put("cache", List.of());
        }

        // KIS API 상태
        try {
            boolean kisAvailable = kisApiService != null && kisApiService.isAvailable();
            result.put("kisAvailable", kisAvailable);
        } catch (Exception e) {
            result.put("kisAvailable", false);
        }

        // 스케줄러 상태
        result.put("schedulerEnabled", precomputeScheduler != null);

        // 응답 시간
        result.put("responseTime", System.currentTimeMillis() - startMs);

        return ResponseEntity.ok(result);
    }

    // --- AI provider runtime config ---

    public record DeepSeekKeyRequest(String apiKey, String baseUrl, String model) {}
    public record DeepSeekTestRequest(String message) {}

    @GetMapping("/ai/deepseek")
    public ResponseEntity<Map<String, Object>> getDeepSeekConfig() {
        return ResponseEntity.ok(deepSeekEndpointService.status());
    }

    @PutMapping("/ai/deepseek")
    public ResponseEntity<Map<String, Object>> saveDeepSeekConfig(@RequestBody DeepSeekKeyRequest request) {
        runtimeAiConfigService.saveDeepSeekConfig(request.apiKey(), request.baseUrl(), request.model());
        return ResponseEntity.ok(deepSeekEndpointService.status());
    }

    @DeleteMapping("/ai/deepseek")
    public ResponseEntity<Map<String, Object>> deleteDeepSeekRuntimeConfig() {
        runtimeAiConfigService.deleteRuntimeDeepSeekConfig();
        return ResponseEntity.ok(deepSeekEndpointService.status());
    }

    @PostMapping("/ai/deepseek/test")
    public ResponseEntity<Map<String, Object>> testDeepSeek(@RequestBody(required = false) DeepSeekTestRequest request) {
        String message = request == null ? "ping" : request.message();
        return ResponseEntity.ok(deepSeekEndpointService.test(message));
    }

    @PostMapping("/system/refresh-all")
    public ResponseEntity<Map<String, Object>> refreshAll() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (precomputeScheduler == null) {
            result.put("status", "error");
            result.put("message", "스케줄러가 비활성화되어 있습니다. bitman.scheduler.enabled=true 설정이 필요합니다.");
            return ResponseEntity.badRequest().body(result);
        }

        String jobId = UUID.randomUUID().toString().substring(0, 8);
        Instant startedAt = Instant.now();
        refreshJobs.put(jobId, new RefreshJob(RefreshStatus.PENDING, startedAt, null, List.of(), null));

        Thread.startVirtualThread(() -> {
            refreshJobs.put(jobId, new RefreshJob(RefreshStatus.RUNNING, startedAt, null, List.of(), null));
            try {
                Map<String, String> rawResults = precomputeScheduler.refreshAll();
                List<Map<String, Object>> normalized = rawResults.entrySet().stream()
                    .map(entry -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("mode", entry.getKey());
                        item.put("status", entry.getValue());
                        item.put("success", "success".equalsIgnoreCase(entry.getValue()));
                        return item;
                    })
                    .toList();
                refreshJobs.put(jobId, new RefreshJob(RefreshStatus.COMPLETE, startedAt, Instant.now(), normalized, null));
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                refreshJobs.put(jobId, new RefreshJob(RefreshStatus.ERROR, startedAt, Instant.now(), List.of(), message));
            }
        });

        result.put("status", "accepted");
        result.put("jobId", jobId);
        result.put("startedAt", startedAt.toString());
        result.put("message", "refresh-all started");

        return ResponseEntity.ok(result);
    }

    @GetMapping("/system/refresh-all/{jobId}")
    public ResponseEntity<Map<String, Object>> refreshAllStatus(@PathVariable String jobId) {
        RefreshJob job = refreshJobs.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                "status", "error",
                "error", "refresh job not found"
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", job.status().name().toLowerCase());
        body.put("jobId", jobId);
        body.put("startedAt", job.createdAt().toString());
        if (job.completedAt() != null) body.put("completedAt", job.completedAt().toString());
        if (job.error() != null) body.put("error", job.error());
        body.put("results", job.results());
        return ResponseEntity.ok(body);
    }
}
