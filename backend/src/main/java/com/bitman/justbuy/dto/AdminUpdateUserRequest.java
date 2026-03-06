package com.bitman.justbuy.dto;

import jakarta.validation.constraints.Email;

public record AdminUpdateUserRequest(
        String name,
        @Email(message = "올바른 이메일 형식이 아닙니다.") String email,
        String subscription
) {}
