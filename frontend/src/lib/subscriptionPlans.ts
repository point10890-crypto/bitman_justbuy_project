import type { SubscriptionPlanDto, SubscriptionPlanId } from '../api/authApi'

export const SUBSCRIPTION_PLANS: readonly SubscriptionPlanDto[] = [
  { id: 'MONTHLY', label: '1개월', months: 1, price: 30_000 },
  { id: 'SIX_MONTHS', label: '6개월', months: 6, price: 150_000 },
]

export function parseSubscriptionPlan(value: string | null | undefined): SubscriptionPlanId {
  return value === 'SIX_MONTHS' ? 'SIX_MONTHS' : 'MONTHLY'
}

export function getSubscriptionPlan(value: string | null | undefined): SubscriptionPlanDto {
  const id = parseSubscriptionPlan(value)
  return SUBSCRIPTION_PLANS.find(plan => plan.id === id)!
}

export function planLabel(value: string | null | undefined) {
  return getSubscriptionPlan(value).label
}

export function formatWon(amount: number) {
  return `${amount.toLocaleString('ko-KR')}원`
}

export function subscriptionPlanFromSearch(search: string) {
  return parseSubscriptionPlan(new URLSearchParams(search).get('plan'))
}

export function subscribePath(plan: SubscriptionPlanId = 'MONTHLY', renewal = false) {
  return `/subscribe?plan=${plan}${renewal ? '&renewal=1' : ''}`
}

export function authPath(page: 'login' | 'register', plan?: SubscriptionPlanId, renewal = false) {
  if (!plan) return `/${page}${renewal ? '?renewal=1' : ''}`
  return `/${page}?plan=${plan}${renewal ? '&renewal=1' : ''}`
}

export function postAuthPath(search: string, activeMember: boolean, renewal = false, defaultPlan?: SubscriptionPlanId) {
  const params = new URLSearchParams(search)
  const needsRenewal = renewal || params.get('renewal') === '1'
  if (params.has('plan') || needsRenewal) {
    const plan = params.has('plan') ? subscriptionPlanFromSearch(search) : parseSubscriptionPlan(defaultPlan)
    return subscribePath(plan, activeMember || needsRenewal)
  }
  return activeMember ? '/' : '/subscribe'
}

export function validateSubscriptionPlans(value: unknown): value is SubscriptionPlanDto[] {
  if (!Array.isArray(value) || value.length !== SUBSCRIPTION_PLANS.length) return false
  const seen = new Set<string>()
  return value.every(item => {
    if (!item || typeof item !== 'object') return false
    const expected = SUBSCRIPTION_PLANS.find(plan => plan.id === item.id)
    if (!expected || seen.has(item.id) || item.months !== expected.months
      || typeof item.label !== 'string' || !item.label.trim()
      || item.price !== expected.price) return false
    seen.add(item.id)
    return true
  })
}
