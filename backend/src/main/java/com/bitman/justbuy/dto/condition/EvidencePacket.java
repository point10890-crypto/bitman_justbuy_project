package com.bitman.justbuy.dto.condition;

import java.util.List;
import java.util.Map;

/**
 * AI 검증 에이전트에 전달하는 증거 패킷.
 *
 * 결정론적 룰 엔진이 만든 후보의 "사실"만 담는다.
 * DeepSeek 검증기는 오직 이 패킷만 보고 판단하며, 패킷에 없는 수치를 만들어내지 않도록 강제된다.
 */
public record EvidencePacket(
    String section,
    String mode,
    String asOf,
    String stockCode,
    String stockName,
    String currentPrice,
    String changeRate,
    int ruleScore,
    List<String> ruleEvidence,
    Map<String, Object> metrics
) {}
