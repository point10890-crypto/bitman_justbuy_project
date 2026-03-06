package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.SubscriptionApplyRequest;
import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping("/apply")
    public ResponseEntity<UserDto> apply(@AuthenticationPrincipal UUID userId,
                                          @Valid @RequestBody SubscriptionApplyRequest request) {
        return ResponseEntity.ok(subscriptionService.applyForSubscription(userId, request.depositorName()));
    }
}
