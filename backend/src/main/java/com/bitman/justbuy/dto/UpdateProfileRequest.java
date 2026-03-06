package com.bitman.justbuy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "이름은 필수입니다.") @Size(min = 2, message = "이름을 2자 이상 입력해 주세요.") String name
) {}
