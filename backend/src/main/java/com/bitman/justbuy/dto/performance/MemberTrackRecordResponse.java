package com.bitman.justbuy.dto.performance;

import java.util.List;

/**
 * 회원용 트랙레코드 — 모드별 성적표.
 *
 * <p>구독 상품의 본체는 "종목"이 아니라 "판단의 성적표"다. 지금까지 성과 데이터는
 * DB에 쌓이기만 하고 회원에게는 종가매매 히스토리 한 곳에서만 보였다.
 * 나쁜 구간도 그대로 노출한다 — 숨기면 재구독이 죽는다.
 */
public record MemberTrackRecordResponse(
    String from,
    String to,
    int days,
    List<ModeRecord> modes,
    ModeRecord overall,
    /** 벤치마크 설명 (예: "KOSPI200 ETF 대비"). 산출 불가 시 null. */
    String benchmarkLabel,
    String note
) {

    /** 모드 1개의 집계. 값이 없으면 "-" 로 내려 화면이 0% 를 실적으로 오인하지 않게 한다. */
    public record ModeRecord(
        String mode,
        String title,
        int totalSignals,
        int verifiedCount,
        int wins,
        int losses,
        String winRate,
        String avgReturnPct,
        String avgMaxReturnPct,
        String targetHitRate,
        String stopHitRate,
        /** 같은 구간 시장 평균. 산출 불가 시 "-". */
        String avgBenchmarkReturnPct,
        /** 시장 대비 초과수익. 전략이 좋았는지 장이 좋았는지 가르는 지표. */
        String avgExcessReturnPct,
        String marketBeatRate
    ) {}
}
