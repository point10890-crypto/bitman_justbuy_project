package com.bitman.justbuy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminResetPasswordRequest(
        @NotBlank(message = "새 비밀번호는 필수입니다.") @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.") String newPassword
) {}
