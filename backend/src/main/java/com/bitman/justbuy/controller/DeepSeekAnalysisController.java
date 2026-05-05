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
    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;

    public DeepSeekAnalysisController(DeepSeekEndpointService deepSeekEndpointService,
                                      UserRepository userRepository,
                                      SubscriptionService subscriptionService) {
        this.deepSeekEndpointService = deepSeekEndpointService;
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
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
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));

        if (!subscriptionService.isActivePro(user)) {
            boolean isExpired = user.getSubscription() == SubscriptionStatus.PRO
                && user.getSubscriptionEndDate() != null
                && user.getSubscriptionEndDate().isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")));
            String msg = isExpired
                ? "PRO 구독이 만료되었습니다. 재구독을 신청해 주세요."
                : "PRO 구독자만 사용할 수 있습니다.";
            throw new ApiException(HttpStatus.FORBIDDEN, msg);
        }
    }

    public record StructuredSignalRequest(String mode, String rawContent) {}
}
