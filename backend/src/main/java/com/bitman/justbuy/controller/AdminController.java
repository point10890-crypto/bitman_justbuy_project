package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.AdminResetPasswordRequest;
import com.bitman.justbuy.dto.AdminUpdateUserRequest;
import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SubscriptionService subscriptionService;

    public AdminController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
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
}
