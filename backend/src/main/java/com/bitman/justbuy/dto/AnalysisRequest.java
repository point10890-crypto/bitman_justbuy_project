package com.bitman.justbuy.dto;

import jakarta.validation.constraints.NotBlank;

public record AnalysisRequest(
        @NotBlank(message = "query는 필수입니다.") String query,
        @NotBlank(message = "mode는 필수입니다.") String mode
) {}
