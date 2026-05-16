package com.bitman.justbuy.dto;

import com.bitman.justbuy.service.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AdminResetPasswordRequest(
        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        String newPassword
) {}
