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
    LocalDateTime subscriptionRequestedAt,
    LocalDate subscriptionEndDate,
    LocalDateTime subscriptionApprovedAt,
    LocalDateTime createdAt,
    /** 텔레그램 연결 여부. chat id 원문은 내려주지 않는다(노출 시 타인이 사칭 발송 가능). */
    boolean telegramLinked
) {
    public static UserDto from(User user) {
        return new UserDto(
            user.getId(),
            user.getEmail(),
            user.getName(),
            user.getRole().name(),
            user.getSubscription().name(),
            user.getDepositorName(),
            user.getSubscriptionRequestedAt(),
            user.getSubscriptionEndDate(),
            user.getSubscriptionApprovedAt(),
            user.getCreatedAt(),
            user.getTelegramChatId() != null && !user.getTelegramChatId().isBlank()
        );
    }
}
