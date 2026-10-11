import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '../contexts/AuthContext'
import type { UserDto } from '../api/authApi'
import { canUpgradeToSixMonths, isSixMonthUpgradePending, subscriptionUpgradeEndDate } from './subscriptionUpgrade'

function monthlyMember(overrides: Partial<User> = {}): User {
  return {
    id: 'upgrade-fixture', email: 'sample@example.invalid', name: '샘플회원', role: 'USER',
    subscription: 'pro', subscriptionPlan: 'MONTHLY', subscriptionEndDate: '2026-10-31',
    subscriptionApprovedAt: '2026-10-01', createdAt: '2026-10-01', ...overrides,
  }
}

function monthlyDto(overrides: Partial<UserDto> = {}): UserDto {
  return {
    id: 'upgrade-dto-fixture', email: 'sample@example.invalid', name: '샘플회원', role: 'USER',
    subscription: 'PRO', subscriptionPlan: 'MONTHLY', requestedSubscriptionPlan: null,
    subscriptionEndDate: '2026-10-31', subscriptionApprovedAt: '2026-10-01',
    depositorName: null, subscriptionRequestedAt: null, createdAt: '2026-10-01', ...overrides,
  }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-11T12:00:00+09:00'))
})
afterEach(() => vi.useRealTimers())

describe('active monthly member six-month upgrade eligibility', () => {
  it('allows finite active monthly members in frontend and API shapes, including legacy monthly plans', () => {
    expect(canUpgradeToSixMonths(monthlyMember())).toBe(true)
    expect(canUpgradeToSixMonths(monthlyDto())).toBe(true)
    expect(canUpgradeToSixMonths(monthlyMember({ subscriptionPlan: undefined }))).toBe(true)
    expect(canUpgradeToSixMonths(monthlyDto({ subscriptionPlan: null }))).toBe(true)
    expect(canUpgradeToSixMonths(monthlyMember({ subscriptionEndDate: '2026-10-11' }))).toBe(true)
  })

  it('keeps existing six-month, expired, free, admin and unlimited members out of the upgrade offer', () => {
    expect(canUpgradeToSixMonths(null)).toBe(false)
    expect(canUpgradeToSixMonths(monthlyMember({ subscriptionPlan: 'SIX_MONTHS' }))).toBe(false)
    expect(canUpgradeToSixMonths(monthlyMember({ subscriptionEndDate: '2026-10-10' }))).toBe(false)
    expect(canUpgradeToSixMonths(monthlyMember({ subscription: 'free' }))).toBe(false)
    expect(canUpgradeToSixMonths(monthlyMember({ role: 'ADMIN' }))).toBe(false)
    expect(canUpgradeToSixMonths(monthlyMember({ subscriptionEndDate: undefined }))).toBe(false)
    expect(canUpgradeToSixMonths(monthlyDto({ subscriptionEndDate: null }))).toBe(false)
  })

  it('prevents a duplicate upgrade offer while an application is pending', () => {
    expect(canUpgradeToSixMonths(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS' }))).toBe(false)
    expect(canUpgradeToSixMonths(monthlyMember({ subscriptionRenewalPending: true, requestedSubscriptionPlan: 'SIX_MONTHS' }))).toBe(false)
    expect(canUpgradeToSixMonths(monthlyMember({ subscription: 'pending', requestedSubscriptionPlan: 'MONTHLY' }))).toBe(false)
  })

  it.each(['2026-13-01', '2026-02-30', '2025-02-29', 'invalid', ''])('does not offer an upgrade for invalid current expiry %s', endDate => {
    expect(canUpgradeToSixMonths(monthlyMember({ subscriptionEndDate: endDate }))).toBe(false)
  })
})

describe('six-month upgrade approval waiting state', () => {
  it('identifies a six-month request from the current monthly plan in either account shape', () => {
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS' }))).toBe(true)
    expect(isSixMonthUpgradePending(monthlyMember({ subscriptionRenewalPending: true, requestedSubscriptionPlan: 'SIX_MONTHS' }))).toBe(true)
    expect(isSixMonthUpgradePending(monthlyMember({ subscription: 'pending', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionPlan: undefined }))).toBe(true)
  })

  it('keeps an upgrade requested during the old monthly period classified as an upgrade after that period expires', () => {
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2026-10-10', subscriptionRequestedAt: '2026-10-09T15:00:00' }))).toBe(true)
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2026-10-10', subscriptionRequestedAt: '2026-10-10T23:59:59' }))).toBe(true)
  })

  it('keeps six-month re-subscriptions requested after expiry out of the upgrade waiting state', () => {
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2026-10-10', subscriptionRequestedAt: '2026-10-11T09:00:00' }))).toBe(false)
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2026-10-10', subscriptionRequestedAt: 'invalid' }))).toBe(false)
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2026-10-10', subscriptionRequestedAt: '2026-02-30T09:00:00' }))).toBe(false)
  })

  it('leaves expired re-subscriptions, ordinary monthly requests and current six-month renewals in their own flow', () => {
    expect(isSixMonthUpgradePending(null)).toBe(false)
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2026-10-10' }))).toBe(false)
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'MONTHLY' }))).toBe(false)
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionPlan: 'SIX_MONTHS' }))).toBe(false)
    expect(isSixMonthUpgradePending(monthlyMember({ requestedSubscriptionPlan: 'SIX_MONTHS' }))).toBe(false)
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: null }))).toBe(false)
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', role: 'ADMIN' }))).toBe(false)
    expect(isSixMonthUpgradePending(monthlyDto({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2026-02-30' }))).toBe(false)
  })
})

describe('six-month upgrade expected end date', () => {
  it.each([
    ['2026-01-31', '2026-01-01', '2026-07-31'],
    ['2026-08-31', '2026-08-01', '2027-02-28'],
    ['2023-08-31', '2023-08-01', '2024-02-29'],
    ['2024-08-31', '2024-08-01', '2025-02-28'],
    ['2026-12-31', '2026-12-01', '2027-06-30'],
  ])('adds calendar months to %s and clamps the target month end', (endDate, today, expected) => {
    expect(subscriptionUpgradeEndDate(endDate, today)).toBe(expected)
  })

  it('uses the later of the current end date and approval-day estimate when a request waits past expiry', () => {
    expect(subscriptionUpgradeEndDate('2026-10-31', '2026-10-11')).toBe('2027-04-30')
    expect(subscriptionUpgradeEndDate('2026-10-31', '2026-11-02')).toBe('2027-05-02')
    expect(subscriptionUpgradeEndDate('2026-10-11', '2026-10-11')).toBe('2027-04-11')
  })

  it('uses the KST calendar date for an omitted approval-day estimate', () => {
    vi.setSystemTime(new Date('2026-10-10T16:00:00Z'))
    expect(subscriptionUpgradeEndDate('2026-10-10')).toBe('2027-04-11')
  })

  it.each([undefined, null, '', 'not-a-date', '2026-13-01', '2026-02-30', '2025-02-29', '2026-01-01T00:00:00Z'])('refuses an invalid end date %s instead of guessing an extension', endDate => {
    expect(subscriptionUpgradeEndDate(endDate, '2026-10-11')).toBeUndefined()
  })

  it('refuses a malformed explicit approval date', () => {
    expect(subscriptionUpgradeEndDate('2026-10-31', 'invalid')).toBeUndefined()
    expect(subscriptionUpgradeEndDate('2026-10-31', '2026-02-30')).toBeUndefined()
  })
})
