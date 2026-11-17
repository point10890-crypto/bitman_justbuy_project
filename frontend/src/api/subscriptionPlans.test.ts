// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { applySubscription, fetchSubscriptionPlans } from './authApi'

describe('subscription application API', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ id: 'fixture-user' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })))
  })
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

  it.each(['catalog', 'application'])('bounds a stalled %s request without retrying the write', async kind => {
    vi.useFakeTimers()
    vi.mocked(fetch).mockImplementation(() => new Promise(() => {}))
    let failure: unknown
    const request = (kind === 'catalog' ? fetchSubscriptionPlans() : applySubscription('fixture-token', 'fixture'))
      .catch(error => { failure = error })
    await vi.advanceTimersByTimeAsync(20_001)
    expect(failure).toBeInstanceOf(Error)
    expect(vi.mocked(fetch).mock.calls[0][1]?.signal?.aborted).toBe(true)
    expect(fetch).toHaveBeenCalledOnce()
    await request
  })

  it('sends the selected six-month plan with the depositor name', async () => {
    await applySubscription('fixture-token', '테스트 입금자', 'SIX_MONTHS')
    const [, init] = vi.mocked(fetch).mock.calls[0]
    expect(JSON.parse(init!.body as string)).toEqual({ depositorName: '테스트 입금자', plan: 'SIX_MONTHS' })
  })

  it('keeps existing callers on the monthly plan', async () => {
    await applySubscription('fixture-token', '테스트 입금자')
    const [, init] = vi.mocked(fetch).mock.calls[0]
    expect(JSON.parse(init!.body as string)).toEqual({ depositorName: '테스트 입금자', plan: 'MONTHLY' })
  })
})
