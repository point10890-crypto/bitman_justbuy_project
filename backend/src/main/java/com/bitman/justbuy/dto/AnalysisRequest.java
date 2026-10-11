package com.bitman.justbuy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnalysisRequest(
        @NotBlank(message = "query는 필수입니다.") @Size(max = 2000, message = "query는 2000자 이내로 입력해 주세요.") String query,
        @NotBlank(message = "mode는 필수입니다.") @Size(max = 120, message = "mode는 120자 이내로 입력해 주세요.") String mode
) {}
