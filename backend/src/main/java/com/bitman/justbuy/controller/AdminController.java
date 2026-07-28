package com.bitman.justbuy.controller;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.dto.AdminResetPasswordRequest;
import com.bitman.justbuy.dto.AdminUpdateUserRequest;
import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.service.AnalysisService;
import com.bitman.justbuy.service.BackupService;
import com.bitman.justbuy.service.ConditionSearchPipeline;
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

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SubscriptionService subscriptionService;
    private final AnalysisService analysisService;
    private final ConditionSearchPipeline conditionSearchPipeline;
    private final List<AiAgent> agents;
    private final KisApiService kisApiService;
    private final RuntimeAiConfigService runtimeAiConfigService;
    private final DeepSeekEndpointService deepSeekEndpointService;
    private final ConditionRunService conditionRunService;

    @Autowired(required = false)
    private PrecomputeScheduler precomputeScheduler;

    @Autowired(required = false)
    private BackupService backupService;

    public AdminController(SubscriptionService subscriptionService,
                           AnalysisService analysisService,
                           ConditionSearchPipeline conditionSearchPipeline,
                           List<AiAgent> agents,
                           KisApiService kisApiService,
                           RuntimeAiConfigService runtimeAiConfigService,
                           DeepSeekEndpointService deepSeekEndpointService,
                           ConditionRunService conditionRunService) {
        this.subscriptionService = subscriptionService;
        this.analysisService = analysisService;
        this.conditionSearchPipeline = conditionSearchPipeline;
        this.agents = agents;
        this.kisApiService = kisApiService;
        this.runtimeAiConfigService = runtimeAiConfigService;
        this.deepSeekEndpointService = deepSeekEndpointService;
        this.conditionRunService = conditionRunService;
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

    /** 가입만 하고 한 번도 구독한 적 없는 회원(NO티어) — 신규 구독 유도 대상. */
    @GetMapping("/subscriptions/never-subscribed")
    public ResponseEntity<List<UserDto>> neverSubscribed() {
        return ResponseEntity.ok(subscriptionService.getNeverSubscribedUsers());
    }

    /** 구독했다가 만료된 회원 — 재구독 유도 대상. */
    @GetMapping("/subscriptions/expired")
    public ResponseEntity<List<UserDto>> expiredMembers() {
        return ResponseEntity.ok(subscriptionService.getExpiredUsers());
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> allUsers() {
        return ResponseEntity.ok(subscriptionService.getAllUsers());
    }

    @PostMapping("/subscriptions/{userId}/revoke")
    public ResponseEntity<UserDto> revoke(@PathVariable UUID userId) {
        return ResponseEntity.ok(subscriptionService.revokeSubscription(userId));
    }

    @PostMapping("/subscriptions/expire-now")
    public ResponseEntity<Map<String, Object>> expireNow() {
        return ResponseEntity.ok(subscriptionService.expireSubscriptionsNow());
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

        // 메인 조건검색식 파이프라인
        try {
            result.put("pipeline", conditionSearchPipeline.describe());
        } catch (Exception e) {
            result.put("pipeline", Map.of("status", "error", "message", e.getMessage()));
        }

        // 스케줄러 상태
        result.put("schedulerEnabled", precomputeScheduler != null);
        result.put("conditionRuns", conditionRunService.recent(10));

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

    @GetMapping("/condition-runs")
    public ResponseEntity<Map<String, Object>> conditionRuns(
            @RequestParam(defaultValue = "50") int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return ResponseEntity.ok(Map.of(
            "limit", boundedLimit,
            "runs", conditionRunService.recent(boundedLimit)
        ));
    }

    @GetMapping("/condition-runs/{runId}/events")
    public ResponseEntity<Map<String, Object>> conditionRunEvents(@PathVariable UUID runId) {
        return conditionRunService.find(runId)
            .<ResponseEntity<Map<String, Object>>>map(run -> ResponseEntity.ok(Map.of(
                "run", run,
                "events", conditionRunService.events(runId)
            )))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 즉시 H2 DB 백업 트리거 — 미니PC 이전·코드 배포 등 위험 작업 직전 안전망용.
     * H2 native BACKUP TO 'file.zip' SQL 사용 — 라이브 락에서도 atomic snapshot 획득 가능.
     */
    @PostMapping("/system/backup")
    public ResponseEntity<Map<String, Object>> triggerBackup() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (backupService == null) {
            result.put("status", "error");
            result.put("message", "BackupService not available (bitman.backup.enabled=false?)");
            return ResponseEntity.badRequest().body(result);
        }

        long start = System.currentTimeMillis();
        result.put("startedAt", Instant.now().toString());

        try {
            java.nio.file.Path zip = backupService.runBackup();
            long size = java.nio.file.Files.size(zip);
            result.put("status", "ok");
            result.put("file", zip.toString());
            result.put("sizeBytes", size);
            result.put("sizeKB", size / 1024);
            result.put("durationMs", System.currentTimeMillis() - start);
            result.put("completedAt", Instant.now().toString());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
            result.put("durationMs", System.currentTimeMillis() - start);
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/system/refresh-all")
    public ResponseEntity<Map<String, Object>> refreshAll() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (precomputeScheduler == null) {
            result.put("status", "error");
            result.put("message", "스케줄러가 비활성화되어 있습니다. bitman.scheduler.enabled=true 설정이 필요합니다.");
            return ResponseEntity.badRequest().body(result);
        }

        result.put("status", "ok");
        result.put("startedAt", Instant.now().toString());
        Map<String, String> refreshResults = precomputeScheduler.refreshAll();
        result.put("results", refreshResults);
        result.put("completedAt", Instant.now().toString());

        return ResponseEntity.ok(result);
    }
}
