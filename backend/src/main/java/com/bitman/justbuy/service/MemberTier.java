package com.bitman.justbuy.service;

/**
 * 회원 구독 티어 — 접근 판정과 통계가 같은 기준을 쓰도록 하는 단일 출처.
 *
 * <p>{@code FREE} 상태 하나로는 "가입만 하고 한 번도 구독한 적 없는 회원"과
 * "구독했다가 만료된 회원"을 구분할 수 없다. 두 집단은 유도 문구도, 집계 의미도 다르다.
 */
public enum MemberTier {

    /** 유효한 PRO(또는 관리자). 전체 이용 가능. */
    ACTIVE,
    /** 신청 후 관리자 승인 대기. */
    PENDING,
    /** 구독 이력이 있고 기간이 끝났다. 재구독 대상. */
    EXPIRED,
    /** 구독 이력이 없다. 신규 구독 유도 대상 (NO티어). */
    NONE;

    public boolean canAccessPaidContent() {
        return this == ACTIVE;
    }

    /** 가입만 하고 결제 이력이 없는 회원인지. */
    public boolean isNeverSubscribed() {
        return this == NONE;
    }
}
