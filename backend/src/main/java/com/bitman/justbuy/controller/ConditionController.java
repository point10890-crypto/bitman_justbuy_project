package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.condition.ConditionCaptureTimesResponse;
import com.bitman.justbuy.dto.condition.ConditionSectionResponse;
import com.bitman.justbuy.dto.condition.MainConditionResponse;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.MainConditionService;
import com.bitman.justbuy.service.MemberTier;
import com.bitman.justbuy.service.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ConditionController {

    private final MainConditionService mainConditionService;
    private final SubscriptionAccessGuard subscriptionAccessGuard;

    public ConditionController(MainConditionService mainConditionService,
                               SubscriptionAccessGuard subscriptionAccessGuard) {
        this.mainConditionService = mainConditionService;
        this.subscriptionAccessGuard = subscriptionAccessGuard;
    }

    /**
     * 홈 화면. 구독자는 전체, 미구독자는 <b>서버에서 마스킹된 미리보기</b>를 받는다.
     *
     * <p>미구독자를 결제 페이지로 바로 튕기면 서비스 가치를 한 번도 보지 못한 채 이탈한다.
     * 종목을 특정할 수 있는 값만 가리고 구성·포착시각·성과 요약은 보여줘 구독으로 잇는다.
     * 다른 유료 엔드포인트(섹션 상세, 히스토리 등)는 그대로 막힌다.
     */
    @GetMapping("/main")
    public ResponseEntity<MainConditionResponse> main(@AuthenticationPrincipal UUID userId) {
        MemberTier tier = subscriptionAccessGuard.tierOf(userId);
        if (tier.canAccessPaidContent()) {
            return ResponseEntity.ok(mainConditionService.getMain());
        }
        return ResponseEntity.ok(mainConditionService.getMainPreview(tier.name()));
    }

    @GetMapping("/conditions/capture-times")
    public ResponseEntity<ConditionCaptureTimesResponse> captureTimes(@AuthenticationPrincipal UUID userId) {
        requireProSubscription(userId);
        return ResponseEntity.ok(mainConditionService.getCaptureTimes());
    }

    @GetMapping("/conditions/{section}/capture-times")
    public ResponseEntity<ConditionCaptureTimesResponse> sectionCaptureTimes(@AuthenticationPrincipal UUID userId,
                                                                             @PathVariable String section) {
        requireProSubscription(userId);
        return ResponseEntity.ok(mainConditionService.getCaptureTimes(section));
    }

    @GetMapping("/conditions/{section}")
    public ResponseEntity<ConditionSectionResponse> section(@AuthenticationPrincipal UUID userId,
                                                            @PathVariable String section) {
        requireProSubscription(userId);
        return ResponseEntity.ok(mainConditionService.getSection(section));
    }

    private void requireProSubscription(UUID userId) {
        subscriptionAccessGuard.requirePro(userId);
    }
}
