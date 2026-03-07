package com.bitman.justbuy.controller;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.dto.AdminResetPasswordRequest;
import com.bitman.justbuy.dto.AdminUpdateUserRequest;
import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.service.AnalysisService;
import com.bitman.justbuy.service.PrecomputeScheduler;
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
    private final List<AiAgent> agents;

    @Autowired(required = false)
    private PrecomputeScheduler precomputeScheduler;

    public AdminController(SubscriptionService subscriptionService,
                           AnalysisService analysisService,
                           List<AiAgent> agents) {
        this.subscriptionService = subscriptionService;
        this.analysisService = analysisService;
        this.agents = agents;
    }

    @GetMapping("/subscriptions/pending")
    public ResponseEntity<List<UserDto>> pendingSubscriptions() {
        return ResponseEntity.ok(subscriptionService.getPendingSubscriptions());
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
        Map<String, Object> result = new LinkedHashMap<>();

        // 서버 상태
        result.put("status", "ok");
        result.put("timestamp", Instant.now().toString());

        // AI 에이전트 상태
        Map<String, Object> agentStatus = new LinkedHashMap<>();
        for (AiAgent agent : agents) {
            agentStatus.put(agent.name(), Map.of("available", agent.isAvailable()));
        }
        result.put("agents", agentStatus);
        result.put("totalAgents", agents.size());
        result.put("availableAgents", agents.stream().filter(AiAgent::isAvailable).count());

        // 캐시 상태
        result.put("cache", analysisService.getCacheStatus());

        // 스케줄러 상태
        result.put("schedulerEnabled", precomputeScheduler != null);

        return ResponseEntity.ok(result);
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
