/**
 * 회원 티어 판정 — 백엔드 `SubscriptionService.tierOf` 의 프론트 대응물.
 *
 * 이 규칙은 AuthContext, SubscribedRoute, PublicOnlyRoute 에 각각 복제돼 있었다.
 * 세 곳이 조금씩 다른 조건을 쓰다 보니 "만료 회원"과 "한 번도 구독 안 한 회원"의
 * 경계가 화면마다 달라졌다. 재구독 유도와 신규 구독 유도가 완전히 다른 흐름이므로
 * 판정은 여기 한 곳에서만 한다.
 */

export type MemberTier = 'ACTIVE' | 'PENDING' | 'EXPIRED' | 'NONE'

export interface MemberInput {
  role: 'USER' | 'ADMIN'
  /** 백엔드 원본(대문자) 또는 정규화된 소문자 모두 받는다. */
  subscription: string
  subscriptionEndDate?: string | null
  subscriptionApprovedAt?: string | null
}

/** 오늘(KST) 날짜 키. 백엔드가 KST 기준으로 만료를 판정하므로 맞춘다. */
function todayKst(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}

/**
 * 종료일이 지났는지. 종료일 당일은 아직 유효하다(백엔드와 동일).
 */
export function isSubscriptionExpired(endDate?: string | null): boolean {
  if (!endDate) return false
  return todayKst() > endDate.slice(0, 10)
}

/**
 * 구독 이력이 있는지 — EXPIRED 와 NONE 을 가르는 단일 기준.
 *
 * 백엔드 `SubscriptionService.hasSubscriptionHistory` 와 같은 조건이다.
 * 관리자가 해제한 회원은 종료일이 오늘로 남으므로 여기서 잡힌다.
 */
export function hasSubscriptionHistory(member: MemberInput): boolean {
  return !!member.subscriptionApprovedAt || !!member.subscriptionEndDate
}

export function memberTierOf(member: MemberInput): MemberTier {
  if (member.role === 'ADMIN') return 'ACTIVE'

  const status = String(member.subscription || '').toLowerCase()
  const expired = isSubscriptionExpired(member.subscriptionEndDate)

  if (status === 'pro' && !expired) return 'ACTIVE'
  // 연장 신청 중인 기존 구독자는 남은 기간 동안 접근을 유지한다.
  if (status === 'pending' && member.subscriptionEndDate && !expired) return 'ACTIVE'
  if (status === 'pending') return 'PENDING'
  if (hasSubscriptionHistory(member)) return 'EXPIRED'
  return 'NONE'
}
