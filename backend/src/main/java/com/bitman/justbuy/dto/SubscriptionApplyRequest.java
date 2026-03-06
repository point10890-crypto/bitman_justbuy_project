package com.bitman.justbuy.dto;

import jakarta.validation.constraints.NotBlank;

public record SubscriptionApplyRequest(
    @NotBlank String depositorName
) {}
