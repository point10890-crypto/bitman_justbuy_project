// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { loginUser, fetchCurrentUser } from './authApi'
import { engineApi } from './engineApi'
import { searchKeywordStocks } from './keywordSearchApi'

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

it.each(['login', 'restore'] as const)('releases a stalled %s request without declaring an expired session', async kind => {
  vi.useFakeTimers()
  let signal!: AbortSignal
  const unauthorized = vi.fn()
  window.addEventListener('auth:unauthorized', unauthorized)
  vi.stubGlobal('fetch', vi.fn((_url, init) => {
    signal = init.signal
    return new Promise<Response>(() => {})
  }))
  try {
    const failure = (kind === 'login' ? loginUser('fixture@example.invalid', 'dummy') : fetchCurrentUser('dummy')).catch(e => e)
    await vi.advanceTimersByTimeAsync(20_000)
    expect((await failure).message).toMatch(/지연|늦어지고/)
    expect(signal.aborted).toBe(true)
    expect(unauthorized).not.toHaveBeenCalled()
  } finally { window.removeEventListener('auth:unauthorized', unauthorized) }
})

it('bounds stalled response-body reading as well as the login connection', async () => {
  vi.useFakeTimers()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: () => new Promise(() => {}) }))
  const failure = loginUser('fixture@example.invalid', 'dummy').catch(e => e)
  await vi.advanceTimersByTimeAsync(20_000)
  expect((await failure).message).toContain('다시 시도')
})

it.each(['keyword', 'engine'] as const)('ends stalled %s searches with a retry message', async kind => {
  vi.useFakeTimers()
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
  const failure = (kind === 'keyword' ? searchKeywordStocks('20일선 위') : engineApi.translate('20일선 위')).catch(e => e)
  await vi.advanceTimersByTimeAsync(75_000)
  expect((await failure).message).toContain('다시 검색')
})

it('cancels an in-flight search immediately when its dialog closes', async () => {
  vi.useFakeTimers()
  let requestSignal!: AbortSignal
  vi.stubGlobal('fetch', vi.fn((_url, init) => {
    requestSignal = init.signal
    return new Promise(() => {})
  }))
  const controller = new AbortController()
  const failure = searchKeywordStocks('20일선 위', undefined, controller.signal).catch(e => e)
  controller.abort()
  expect((await failure).name).toBe('AbortError')
  expect(requestSignal.aborted).toBe(true)
  expect(vi.getTimerCount()).toBe(0)
})
