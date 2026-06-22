package com.bitman.justbuy.dto.condition;

import java.util.List;

/**
 * AI 검증 에이전트가 증거 패킷을 보고 내린 판정.
 *
 * verdict 허용값: VALID / WATCH / OVERHEATED / REJECT.
 * 파싱 실패나 AI 비활성 시 이 객체는 생성되지 않고, 호출자는 룰 점수만으로 폴백한다.
 */
public record EvidenceVerdict(
    String stockCode,
    int aiScore,
    String verdict,
    String summary,
    List<String> evidence,
    List<String> riskFlags,
    String invalidation,
    String displayStatus
) {
    public boolean rejected() {
        return "REJECT".equalsIgnoreCase(verdict);
    }
}
