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
import com.bitman.justbuy.service.KiwoomReadOnlyService;
import com.bitman.justbuy.service.PrecomputeScheduler;
import com.bitman.justbuy.service.RuntimeAiConfigService;
import com.bitman.justbuy.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 관리자 전용 API.
 *
 * <p>{@code SecurityConfig} 의 경로 규칙이 유일한 방어선이었다. 규칙 한 줄이 잘못되면
 * 회원 비밀번호 초기화까지 열리므로 클래스 단위 권한 검사를 겹쳐 둔다.
 */
@PreAuthorize("hasRole('ADMIN')")
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
    private final Map<String, RefreshJob> refreshJobs = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private record RefreshJob(UUID ownerId, Instant createdAt, Instant finishedAt, Map<String, Object> payload) {}

    @Autowired(required = false)
    private PrecomputeScheduler precomputeScheduler;

    @Autowired(required = false)
    private BackupService backupService;

    @Autowired(required = false)
    private KiwoomReadOnlyService kiwoomReadOnlyService;

    @Autowired
    private Environment environment;

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

        // Configuration presence only. Never probe providers or return credential values.
        result.put("dataSources", dataSourceReadiness());
        if(kiwoomReadOnlyService!=null){
            Map<String,Object> readiness=kiwoomReadOnlyService.readiness();
            if(readiness!=null){
                Map<String,Object> state=new LinkedHashMap<>();
                for(String key:List.of("status","requested","licensed","configured","enabled","source","environment","scope","allowedApiIds","candidateLimit","maxRequestsPerSecond","runtimeStatus","backgroundRefreshEnabled"))
                    if(readiness.get(key)!=null)state.put(key,readiness.get(key));
                if(readiness.get("queue") instanceof Map<?,?> queue){
                    Map<String,Object> safeQueue=new LinkedHashMap<>();
                    for(String key:List.of("status","queueDepth","pendingJobs","workerActive","capacity","cachedCodes","maxRequestsPerSecond","lastFetchedAt","enqueued","completed","failed","expired","rejected"))
                        if(queue.get(key)!=null)safeQueue.put(key,queue.get(key));
                    state.put("queue",safeQueue);state.put("queueStatus",queue.get("status"));
                }
                result.put("kiwoomReadiness",state);
            }
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
        result.put("analysisStats", MonitorController.analysisStatistics());
        result.put("recentAnalyses", MonitorController.analysisLogSnapshot().stream().limit(10).toList());

        // 응답 시간
        result.put("responseTime", System.currentTimeMillis() - startMs);

        return ResponseEntity.ok(result);
    }

    private List<Map<String, Object>> dataSourceReadiness() {
        boolean kisConfigured = hasConfiguration("bitman.kis.app-key") && hasConfiguration("bitman.kis.app-secret");
        boolean dartConfigured = hasConfiguration("bitman.dart.api-key");
        boolean ecosConfigured = hasConfiguration("bitman.ecos.api-key");
        boolean kiwoomConfigured = hasConfiguration("bitman.kiwoom.app-key") && hasConfiguration("bitman.kiwoom.app-secret");
        String kiwoomEnvironment = environment != null ? environment.getProperty("bitman.kiwoom.environment", "REAL") : "REAL";
        boolean kiwoomMock = "MOCK".equalsIgnoreCase(kiwoomEnvironment.trim());
        String kiwoomName = kiwoomMock ? "키움 시장조회 (KIS 병행·모의)" : "키움 시장조회 (KIS 병행)";
        String kiwoomMessage = kiwoomConfigured
            ? "서버 공인 출발 IP 등록과 정보이용 범위·권한 설정을 확인해 주세요. 실제 연결은 미검증 상태입니다."
            : "키움 앱 키·시크릿 입력, 서버 공인 출발 IP 등록 및 정보이용 범위·권한 설정이 필요합니다. 실제 연결은 미검증 상태입니다.";
        if (kiwoomMock) kiwoomMessage = "모의투자 서버의 시장 조회 자료를 KIS와 함께 조건검색에 사용합니다. 종목·관측일·단위를 검증하며 주문·계좌는 사용하지 않습니다. 실제 조회 결과는 조건검색 실행 내역에서 확인하세요.";
        return List.of(
            sourceReadiness("KIS", "KIS 추가 근거", configurationEnabled("bitman.kis.extended-evidence.enabled"),
                kisConfigured, true, "유료 회원 앱의 KIS 이용 권한 확인이 필요합니다."),
            sourceReadiness("DART", "DART 공시 근거", configurationEnabled("bitman.dart.event-evidence.enabled"),
                dartConfigured, false, dartConfigured ? "공시 근거 설정 확인. 실제 연결은 미검증 상태입니다."
                    : "DART 인증키의 서버 설정이 필요합니다."),
            sourceReadiness("ECOS", "한국은행 ECOS", configurationEnabled("bitman.ecos.enabled"),
                ecosConfigured, false, ecosConfigured ? "일별 공표 통계 설정 확인. 실제 연결과 관측일 검증이 필요합니다."
                    : "ECOS 인증키 발급과 서버 설정이 필요합니다."),
            kiwoomSourceReadiness(kiwoomName, configurationEnabled("bitman.kiwoom.enabled"),
                kiwoomConfigured, !kiwoomMock && !configurationEnabled("bitman.kiwoom.licensed"), kiwoomMessage)
        );
    }

    private Map<String,Object> kiwoomSourceReadiness(String name,boolean enabled,boolean configured,boolean rights,String message) {
        Map<String,Object> result=new LinkedHashMap<>(sourceReadiness("KIWOOM",name,enabled,configured,rights,message));
        if(kiwoomReadOnlyService!=null){
            Map<String,Object> readiness=kiwoomReadOnlyService.readiness();
            if(readiness!=null&&readiness.get("queue") instanceof Map<?,?> queue){
                Map<String,Object> runtime=new LinkedHashMap<>();
                for(String key:List.of("status","queueDepth","pendingJobs","workerActive","capacity","cachedCodes","maxRequestsPerSecond","lastFetchedAt","enqueued","completed","failed","expired","rejected"))
                    if(queue.get(key)!=null)runtime.put(key,queue.get(key));
                runtime.put("candidateLimit",KiwoomReadOnlyService.CANDIDATE_LIMIT);result.put("runtime",runtime);
            }
        }
        return result;
    }

    private boolean hasConfiguration(String property) {
        String value = environment != null ? environment.getProperty(property) : null;
        return value != null && !value.isBlank();
    }

    private boolean configurationEnabled(String property) {
        return environment != null && Boolean.parseBoolean(environment.getProperty(property, "false"));
    }

    private Map<String, Object> sourceReadiness(String id, String name, boolean enabled, boolean credentialsConfigured,
                                              boolean rightsCheckRequired, String message) {
        String state = !enabled ? "DISABLED" : !credentialsConfigured ? "CREDENTIALS_MISSING"
            : rightsCheckRequired ? "RIGHTS_CHECK_REQUIRED" : "CONFIGURED";
        return Map.of("id", id, "name", name, "enabled", enabled, "credentialsConfigured", credentialsConfigured,
            "state", state, "message", message);
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

    public record RefreshAllRequest(List<String> modes) {}

    @PostMapping("/system/refresh-all")
    public ResponseEntity<Map<String, Object>> refreshAll(@AuthenticationPrincipal UUID userId,
                                                         @RequestBody(required = false) RefreshAllRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (precomputeScheduler == null) {
            result.put("status", "error");
            result.put("message", "스케줄러가 비활성화되어 있습니다. bitman.scheduler.enabled=true 설정이 필요합니다.");
            return ResponseEntity.badRequest().body(result);
        }

        if (userId == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "관리자 인증이 필요합니다.");
        List<String> modes;
        try {
            modes = precomputeScheduler.normalizeRefreshModes(request != null ? request.modes() : null);
        } catch (IllegalArgumentException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
        cleanupRefreshJobs();
        if (!refreshRunning.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "error", "message", "분석 갱신 작업이 이미 실행 중입니다."));
        }
        String jobId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        result.put("jobId", jobId);
        result.put("status", "running");
        result.put("startedAt", createdAt.toString());
        refreshJobs.put(jobId, new RefreshJob(userId, createdAt, null, Map.copyOf(result)));
        Thread.startVirtualThread(() -> {
            Map<String, Object> completed = new LinkedHashMap<>(result);
            try {
                Map<String, String> refreshResults = precomputeScheduler.refreshModes(modes);
                long fullSuccess = refreshResults.values().stream().filter("success"::equals).count();
                long errors = refreshResults.values().stream().filter(value -> value.startsWith("error:")).count();
                completed.put("status", fullSuccess == refreshResults.size() ? "ok"
                    : errors == refreshResults.size() ? "error" : "partial");
                completed.put("results", refreshResults);
            } catch (Exception e) {
                completed.put("status", "error");
                completed.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } finally {
                completed.put("completedAt", Instant.now().toString());
                Instant finishedAt = Instant.now();
                refreshJobs.computeIfPresent(jobId, (key, existing) -> new RefreshJob(existing.ownerId(), existing.createdAt(), finishedAt, Map.copyOf(completed)));
                refreshRunning.set(false);
            }
        });
        return ResponseEntity.accepted().body(result);
    }

    @GetMapping("/system/refresh-all/{jobId}")
    public ResponseEntity<Map<String, Object>> refreshStatus(@AuthenticationPrincipal UUID userId, @PathVariable String jobId) {
        if (userId == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "관리자 인증이 필요합니다.");
        cleanupRefreshJobs();
        RefreshJob job = refreshJobs.get(jobId);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (job == null || (!userId.equals(job.ownerId()) && !admin)) throw new ApiException(HttpStatus.NOT_FOUND, "Refresh job not found");
        return ResponseEntity.ok(job.payload());
    }

    private void cleanupRefreshJobs() {
        Instant cutoff = Instant.now().minusSeconds(30 * 60);
        refreshJobs.entrySet().removeIf(entry -> entry.getValue().finishedAt() != null && entry.getValue().finishedAt().isBefore(cutoff));
        if (refreshJobs.size() >= 64) {
            refreshJobs.entrySet().stream().filter(entry -> !"running".equals(entry.getValue().payload().get("status")))
                .min(java.util.Comparator.comparing(entry -> entry.getValue().createdAt()))
                .ifPresent(entry -> refreshJobs.remove(entry.getKey()));
        }
    }
}
