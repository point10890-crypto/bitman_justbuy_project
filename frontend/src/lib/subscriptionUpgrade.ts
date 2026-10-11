import type { SubscriptionPlanId } from '../api/authApi'
import { memberTierOf, todayKst, type MemberInput } from './memberTier'
import { getSubscriptionPlan } from './subscriptionPlans'

interface UpgradeMember extends MemberInput {
  subscriptionPlan?: SubscriptionPlanId | null
  requestedSubscriptionPlan?: SubscriptionPlanId | null
  subscriptionRenewalPending?: boolean
  subscriptionRequestedAt?: string | null
}

function calendarDate(value?: string | null): Date | undefined {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined
  const date = new Date(`${value}T00:00:00Z`)
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value) return undefined
  return date
}

function monthlyWithExpiry(member: UpgradeMember | null | undefined): member is UpgradeMember {
  return !!member && member.role !== 'ADMIN' && getSubscriptionPlan(member.subscriptionPlan).id === 'MONTHLY'
    && !!calendarDate(member.subscriptionEndDate)
}

function hasPendingRequest(member: UpgradeMember): boolean {
  return member.subscription.toLowerCase() === 'pending' || !!member.subscriptionRenewalPending
}

export function canUpgradeToSixMonths(member: UpgradeMember | null | undefined): boolean {
  return monthlyWithExpiry(member) && !hasPendingRequest(member) && memberTierOf(member) === 'ACTIVE'
}

export function isSixMonthUpgradePending(member: UpgradeMember | null | undefined): boolean {
  if (!monthlyWithExpiry(member) || !hasPendingRequest(member) || member.requestedSubscriptionPlan !== 'SIX_MONTHS') return false
  if (memberTierOf(member) === 'ACTIVE') return true
  // Keep the request's identity when the old monthly period ends while waiting.
  const requestedOn = member.subscriptionRequestedAt?.slice(0, 10)
  return !!calendarDate(requestedOn) && requestedOn! <= member.subscriptionEndDate!
}

/** Same calendar-month/clamping rule as Spring LocalDate.plusMonths(6). */
export function subscriptionUpgradeEndDate(endDate?: string | null, today = todayKst()): string | undefined {
  const currentEnd = calendarDate(endDate)
  const approvalDay = calendarDate(today)
  if (!currentEnd || !approvalDay) return undefined
  const base = new Date(Math.max(currentEnd.getTime(), approvalDay.getTime()))
  const day = base.getUTCDate()
  base.setUTCDate(1)
  base.setUTCMonth(base.getUTCMonth() + getSubscriptionPlan('SIX_MONTHS').months)
  const lastDay = new Date(base)
  lastDay.setUTCMonth(lastDay.getUTCMonth() + 1)
  lastDay.setUTCDate(0)
  base.setUTCDate(Math.min(day, lastDay.getUTCDate()))
  const result = base.toISOString().slice(0, 10)
  return calendarDate(result) ? result : undefined
}
