package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.condition.ConditionCaptureTimesResponse;
import com.bitman.justbuy.dto.condition.ConditionSectionResponse;
import com.bitman.justbuy.dto.condition.MainConditionResponse;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.MainConditionService;
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
    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;

    public ConditionController(MainConditionService mainConditionService,
                               UserRepository userRepository,
                               SubscriptionService subscriptionService) {
        this.mainConditionService = mainConditionService;
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/main")
    public ResponseEntity<MainConditionResponse> main(@AuthenticationPrincipal UUID userId) {
        requireProSubscription(userId);
        return ResponseEntity.ok(mainConditionService.getMain());
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
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));

        if (!subscriptionService.isActivePro(user)) {
            boolean isExpired = user.getSubscription() == SubscriptionStatus.PRO
                && user.getSubscriptionEndDate() != null
                && user.getSubscriptionEndDate().isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")));
            String msg = isExpired
                ? "PRO 구독이 만료되었습니다. 재구독 신청을 해주세요."
                : "PRO 구독자만 사용 가능합니다.";
            throw new ApiException(HttpStatus.FORBIDDEN, msg);
        }
    }
}
