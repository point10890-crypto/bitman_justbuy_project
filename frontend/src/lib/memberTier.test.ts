import { describe, it, expect } from 'vitest'
import { isSubscriptionExpired, memberTierOf, type MemberInput } from './memberTier'

/**
 * 회원 티어는 백엔드 SubscriptionService.tierOf 와 같은 답을 내야 한다.
 * 이 규칙이 AuthContext / SubscribedRoute / PublicOnlyRoute 세 곳에 복제돼 있었고,
 * 한 곳만 고치면 나머지가 조용히 어긋난다.
 */

function member(overrides: Partial<MemberInput> = {}): MemberInput {
  return {
    role: 'USER',
    subscription: 'FREE',
    subscriptionEndDate: null,
    subscriptionApprovedAt: null,
    ...overrides,
  }
}

const YESTERDAY = new Date(Date.now() - 86400000).toISOString().slice(0, 10)
const NEXT_WEEK = new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10)

describe('isSubscriptionExpired', () => {
  it('treats the end date itself as still valid', () => {
    const todayKst = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date())
    expect(isSubscriptionExpired(todayKst)).toBe(false)
  })

  it('treats a past end date as expired', () => {
    expect(isSubscriptionExpired(YESTERDAY)).toBe(true)
  })

  it('treats a missing end date as not expired', () => {
    expect(isSubscriptionExpired(null)).toBe(false)
  })
})

describe('memberTierOf', () => {
  it('treats an admin as always active', () => {
    expect(memberTierOf(member({ role: 'ADMIN', subscription: 'FREE' }))).toBe('ACTIVE')
  })

  it('treats a paid member inside their window as active', () => {
    expect(memberTierOf(member({ subscription: 'PRO', subscriptionEndDate: NEXT_WEEK }))).toBe('ACTIVE')
  })

  it('keeps a renewal applicant active while their paid window lasts', () => {
    expect(memberTierOf(member({ subscription: 'PENDING', subscriptionEndDate: NEXT_WEEK }))).toBe('ACTIVE')
  })

  it('treats a first-time applicant as pending', () => {
    expect(memberTierOf(member({ subscription: 'PENDING' }))).toBe('PENDING')
  })

  it('treats a lapsed member as expired even after the batch downgraded them', () => {
    expect(memberTierOf(member({ subscription: 'FREE', subscriptionEndDate: YESTERDAY }))).toBe('EXPIRED')
  })

  it('treats an admin-revoked member as expired, not as a newcomer', () => {
    // 관리자 해제는 종료일을 오늘로 남긴다. 이력이 있으므로 재구독 대상이다.
    const todayKst = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date())
    expect(memberTierOf(member({
      subscription: 'FREE',
      subscriptionEndDate: todayKst,
      subscriptionApprovedAt: '2026-01-01T00:00:00',
    }))).toBe('EXPIRED')
  })

  it('treats a member who never subscribed as none', () => {
    expect(memberTierOf(member())).toBe('NONE')
  })
})
