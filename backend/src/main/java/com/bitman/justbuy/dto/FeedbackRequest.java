package com.bitman.justbuy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 사용자 분석 피드백 요청 DTO.
 *
 * 과거에는 Map&lt;String, Object&gt; 로 받아서 깊이/길이 제한이 없었고
 * 악의적 페이로드(거대 문자열, 깊은 nested JSON) 로 DoS 가 가능했다.
 * 이제 모든 필드에 길이/범위 제약을 둔다.
 */
public record FeedbackRequest(
        @Size(max = 32, message = "mode 는 32자 이내")
        String mode,

        @Min(value = 0, message = "rating 은 0~5")
        @Max(value = 5, message = "rating 은 0~5")
        Integer rating,

        @Size(max = 1000, message = "comment 는 1000자 이내")
        String comment,

        @Size(max = 64, message = "analysisId 는 64자 이내")
        String analysisId,

        /** 사용자가 도움이 되었다고 표시한 종목코드 (최대 10개). 향후 가중치 피드백 루프용. */
        @Size(max = 10, message = "helpfulStocks 는 10개 이내")
        List<@Size(max = 6, message = "종목코드는 6자") String> helpfulStocks
) {}
