package com.bitman.justbuy.dto;

import com.bitman.justbuy.entity.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record UserDto(
    UUID id,
    String email,
    String name,
    String role,
    String subscription,
    String depositorName,
    LocalDate subscriptionEndDate,
    LocalDateTime createdAt
) {
    public static UserDto from(User user) {
        return new UserDto(
            user.getId(),
            user.getEmail(),
            user.getName(),
            user.getRole().name(),
            user.getSubscription().name(),
            user.getDepositorName(),
            user.getSubscriptionEndDate(),
            user.getCreatedAt()
        );
    }
}
