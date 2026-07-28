package com.bitman.justbuy.controller;

import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.DeepSeekEndpointService;
import com.bitman.justbuy.service.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis/deepseek")
public class DeepSeekAnalysisController {

    private final DeepSeekEndpointService deepSeekEndpointService;
    private final SubscriptionAccessGuard subscriptionAccessGuard;

    public DeepSeekAnalysisController(DeepSeekEndpointService deepSeekEndpointService,
                                      SubscriptionAccessGuard subscriptionAccessGuard) {
        this.deepSeekEndpointService = deepSeekEndpointService;
        this.subscriptionAccessGuard = subscriptionAccessGuard;
    }

    @PostMapping("/structured-signal")
    public ResponseEntity<Map<String, Object>> structuredSignal(@AuthenticationPrincipal UUID userId,
                                                                @RequestBody StructuredSignalRequest request) {
        requireProSubscription(userId);
        return ResponseEntity.ok(deepSeekEndpointService.structuredSignal(request.mode(), request.rawContent()));
    }

    @PostMapping("/validate-picks")
    public ResponseEntity<Map<String, Object>> validatePicks(@AuthenticationPrincipal UUID userId,
                                                             @RequestBody Map<String, Object> request) {
        requireProSubscription(userId);
        return ResponseEntity.ok(deepSeekEndpointService.validatePicks(request));
    }

    @PostMapping("/risk-brief")
    public ResponseEntity<Map<String, Object>> riskBrief(@AuthenticationPrincipal UUID userId,
                                                         @RequestBody Map<String, Object> request) {
        requireProSubscription(userId);
        return ResponseEntity.ok(deepSeekEndpointService.riskBrief(request));
    }

    private void requireProSubscription(UUID userId) {
        subscriptionAccessGuard.requirePro(userId);
    }

    public record StructuredSignalRequest(String mode, String rawContent) {}
}
