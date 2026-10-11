// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { applySubscription } from './authApi'

describe('subscription application API', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ id: 'fixture-user' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })))
  })
  afterEach(() => { vi.unstubAllGlobals() })

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
