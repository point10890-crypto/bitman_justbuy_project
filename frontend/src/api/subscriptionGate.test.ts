import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { subscriptionCodeOf, notifySubscriptionGate, SUBSCRIPTION_CODES } from './subscriptionGate'

/**
 * 구독 403 을 잡아 재구독 흐름으로 넘기는 신호.
 *
 * 이 판정이 conditionApi 한 곳에만 있어서, 분석·성과 화면을 쓰던 만료 회원은
 * "API error 403" 만 보고 그 자리에 머물렀다. 이제 모든 API 가 같은 곳을 통과한다.
 */
describe('subscriptionCodeOf', () => {
  it('recognises an expired subscription so the client can offer renewal', () => {
    expect(subscriptionCodeOf(403, '{"error":"만료","code":"SUBSCRIPTION_EXPIRED"}'))
      .toBe('SUBSCRIPTION_EXPIRED')
  })

  it('recognises the pending and required codes too', () => {
    expect(subscriptionCodeOf(403, '{"code":"SUBSCRIPTION_PENDING"}')).toBe('SUBSCRIPTION_PENDING')
    expect(subscriptionCodeOf(403, '{"code":"SUBSCRIPTION_REQUIRED"}')).toBe('SUBSCRIPTION_REQUIRED')
  })

  it('ignores a 403 that is not about a subscription', () => {
    expect(subscriptionCodeOf(403, '{"error":"관리자만 접근 가능합니다."}')).toBeNull()
  })

  it('ignores other statuses even when a code is present', () => {
    // 401 은 재로그인 흐름이지 재구독 흐름이 아니다.
    expect(subscriptionCodeOf(401, '{"code":"SUBSCRIPTION_EXPIRED"}')).toBeNull()
  })

  it('survives a non-JSON body such as a proxy error page', () => {
    expect(subscriptionCodeOf(403, '<html>Forbidden</html>')).toBeNull()
    expect(subscriptionCodeOf(403, '')).toBeNull()
  })

  it('exposes exactly the codes the server can send', () => {
    expect([...SUBSCRIPTION_CODES].sort()).toEqual(
      ['SUBSCRIPTION_EXPIRED', 'SUBSCRIPTION_PENDING', 'SUBSCRIPTION_REQUIRED'],
    )
  })
})

describe('notifySubscriptionGate', () => {
  let dispatched: CustomEvent[] = []
  let originalWindow: unknown

  beforeEach(() => {
    dispatched = []
    originalWindow = (globalThis as Record<string, unknown>).window
    ;(globalThis as Record<string, unknown>).window = {
      dispatchEvent: (event: CustomEvent) => { dispatched.push(event); return true },
      CustomEvent,
    }
  })

  afterEach(() => {
    ;(globalThis as Record<string, unknown>).window = originalWindow
  })

  it('signals the router when a subscription expired', () => {
    const code = notifySubscriptionGate(403, '{"error":"만료됨","code":"SUBSCRIPTION_EXPIRED"}')

    expect(code).toBe('SUBSCRIPTION_EXPIRED')
    expect(dispatched).toHaveLength(1)
    expect(dispatched[0].type).toBe('subscription:required')
    expect(dispatched[0].detail).toEqual({ code: 'SUBSCRIPTION_EXPIRED', message: '만료됨' })
  })

  it('stays silent for unrelated failures', () => {
    expect(notifySubscriptionGate(500, 'boom')).toBeNull()
    expect(notifySubscriptionGate(403, '{"error":"관리자 전용"}')).toBeNull()
    expect(dispatched).toHaveLength(0)
  })

  it('does not throw when there is no window (SSR / test runner)', () => {
    ;(globalThis as Record<string, unknown>).window = undefined
    expect(() => notifySubscriptionGate(403, '{"code":"SUBSCRIPTION_EXPIRED"}')).not.toThrow()
  })
})

describe('vi is available', () => {
  it('keeps the suite honest', () => {
    expect(vi).toBeDefined()
  })
})
