package com.bitman.justbuy.controller;

import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.DeepSeekEndpointService;
import com.bitman.justbuy.service.SubscriptionService;
import com.bitman.justbuy.service.LiveAnalysisQuotaService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final LiveAnalysisQuotaService liveAnalysisQuotaService;

    @org.springframework.beans.factory.annotation.Autowired
    public DeepSeekAnalysisController(DeepSeekEndpointService deepSeekEndpointService,
                                     SubscriptionAccessGuard subscriptionAccessGuard,
                                     LiveAnalysisQuotaService liveAnalysisQuotaService) {
        this.deepSeekEndpointService = deepSeekEndpointService;
        this.subscriptionAccessGuard = subscriptionAccessGuard;
        this.liveAnalysisQuotaService = liveAnalysisQuotaService;
    }

    public DeepSeekAnalysisController(DeepSeekEndpointService deepSeekEndpointService,
                                      SubscriptionAccessGuard subscriptionAccessGuard) {
        this(deepSeekEndpointService, subscriptionAccessGuard, new LiveAnalysisQuotaService());
    }

    @PostMapping("/structured-signal")
    public ResponseEntity<Map<String, Object>> structuredSignal(@AuthenticationPrincipal UUID userId,
                                                                @RequestBody StructuredSignalRequest request) {
        requireProSubscription(userId);
        if (request == null || request.rawContent() == null || request.rawContent().isBlank()
                || request.rawContent().length() > 2000 || request.mode() == null || request.mode().isBlank()
                || request.mode().length() > 120) throw new ApiException(HttpStatus.BAD_REQUEST, "분석 입력 크기를 확인해 주세요.");
        consumeQuota(userId);
        return ResponseEntity.ok(deepSeekEndpointService.structuredSignal(request.mode(), request.rawContent()));
    }

    @PostMapping("/validate-picks")
    public ResponseEntity<Map<String, Object>> validatePicks(@AuthenticationPrincipal UUID userId,
                                                             @RequestBody Map<String, Object> request) {
        requireProSubscription(userId);
        validateMapInput(request);
        consumeQuota(userId);
        return ResponseEntity.ok(deepSeekEndpointService.validatePicks(request));
    }

    @PostMapping("/risk-brief")
    public ResponseEntity<Map<String, Object>> riskBrief(@AuthenticationPrincipal UUID userId,
                                                         @RequestBody Map<String, Object> request) {
        requireProSubscription(userId);
        validateMapInput(request);
        consumeQuota(userId);
        return ResponseEntity.ok(deepSeekEndpointService.riskBrief(request));
    }

    private void requireProSubscription(UUID userId) {
        subscriptionAccessGuard.requirePro(userId);
    }

    private void validateMapInput(Map<String, Object> request) {
        try {
            if (request == null || request.isEmpty() || new ObjectMapper().writeValueAsString(request).length() > 2000)
                throw new ApiException(HttpStatus.BAD_REQUEST, "분석 입력 크기를 확인해 주세요.");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "올바른 분석 입력이 필요합니다.");
        }
    }

    private void consumeQuota(UUID userId) {
        if (!liveAnalysisQuotaService.tryConsume(userId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                "오늘 라이브 분석 " + liveAnalysisQuotaService.quota() + "회를 모두 사용했습니다.",
                "LIVE_ANALYSIS_QUOTA_EXCEEDED");
        }
    }

    public record StructuredSignalRequest(String mode, String rawContent) {}
}
