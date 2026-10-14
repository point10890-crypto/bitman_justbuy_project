// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { ScreenResult, Strategy, Translation } from '../api/engineApi'
import type { KeywordSearchResponse } from '../api/keywordSearchApi'
import { useSearchUpgrade, type SearchUpgrade } from './useSearchUpgrade'

const now = new Date('2026-10-14T03:00:00Z')
const strategy: Strategy = {
  title: '20일선 위', when: { f: 'gap_ma20', op: '>', v: 0 }, rank_by: { f: 'quality_score', desc: true }, top_n: 2,
}
const translation: Translation = {
  strategy, source: 'rules', conditions: ['20일선 위'], assumptions: [], warnings: [], unsupported: [], unparsed: [],
  error: null, fallback_reason: null, realtime_query: '최근 1거래일 외국인 순매수',
}
const screen: ScreenResult = {
  strategy, conditions: ['20일선 위'], as_of: '2026-10-13', regime: { code: 'UP_TREND', label: '상승' },
  regime_blocked: false, counts: { universe: 20, passing: 4, guard_excluded: 0, eligible: 4, published: 2 },
  results: ['000001', '000002'].map((code, index) => ({
    code, name: code, market: 'KOSPI', sector: null, stage: 'PUBLISHED', excluded_by: [], rank: index + 1,
    snapshot: { close: 1000 }, explanation: ['20일선 위'], risk: { flags: [], score: 0, grade: '낮음' },
  })), below_top_n: ['000003', '000004'], excluded: [], excluded_codes: {}, data_version: 'frozen-v1',
}
const progress = { id: 'owned-progress-id', expiresAt: '2026-10-14T03:02:00Z', retryAfterMs: 10_000 }
const response = (codes: string[], extra: Record<string, unknown> = {}): KeywordSearchResponse => ({
  query: translation.realtime_query!, outcome: 'PARTIAL', appliedConditions: ['최근 실제 1거래일 수급 합산', '외국인 순매수'],
  unsupportedTerms: [], scope: { label: '고정 후보 4종목', examinedCount: 4, eligibleCount: 4, matchedCount: codes.length, truncated: true },
  fetchedAt: now.toISOString(), sourceStatus: 'VERIFIED_QUOTES', warnings: ['일부 자료 미확인'],
  matches: codes.map(code => ({
    code, name: code, market: 'KOSPI', currentPrice: 1000, changePct: 1, volume: 10, tradingValueKrw: 10000,
    quoteSource: 'KIS OpenAPI', quoteCheckedAt: now.toISOString(), observedAt: now.toISOString(),
    evidence: [{ condition: '외국인 순매수', observedValue: '10', unit: '주', source: 'KIS OpenAPI', observedAt: '2026-10-13 ~ 2026-10-13' }],
  })), ...extra,
})
const pending = (codes = ['000003'], extra: Record<string, unknown> = {}) => response(codes, {
  flowCoverage: { requestedCount: 4, availableCount: 1, pendingCount: 3, unavailableCount: 0 }, progress, ...extra,
})
const complete = (codes = ['000003', '000002', '000001']) => response(codes, {
  flowCoverage: { requestedCount: 4, availableCount: 4, pendingCount: 0, unavailableCount: 0 }, progress,
})
const refused = response([], { outcome: 'UNSUPPORTED', unsupportedTerms: ['일봉 조건'], warnings: [] })
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
type Followup = (attempt: number, init?: RequestInit) => KeywordSearchResponse | Response | Promise<Response>
function routes(initial: KeywordSearchResponse, followup: Followup) {
  let attempt = 0
  return vi.fn((url: string, init?: RequestInit) => {
    const body = init?.body ? JSON.parse(String(init.body)) : {}
    if (url.endsWith('/api/engine/translate')) return Promise.resolve(json(translation))
    if (url.endsWith('/api/engine/screen')) return Promise.resolve(json(screen))
    if (url.endsWith('/api/engine/backtest')) return Promise.resolve(json(null))
    if (url.endsWith('/api/engine/risk')) return Promise.resolve(json({ as_of: '2026-10-13', items: body.codes.map((code: string) => ({
      code, found: true, explanation: ['확인된 일봉 근거'], risk: { flags: [], score: 0, grade: '낮음' },
    })) }))
    if (url.endsWith('/api/analysis/keyword-search/progress')) {
      const value = followup(++attempt, init)
      return Promise.resolve(value).then(value => value instanceof Response ? value : json(value))
    }
    if (url.endsWith('/api/analysis/keyword-search')) return Promise.resolve(json(initial))
    throw new Error(`Unexpected URL: ${url}`)
  })
}
const tick = async (ms = 0) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }
function engineState(value: SearchUpgrade) {
  if (value.status !== 'done' || value.mode !== 'engine') throw new Error('Expected engine result')
  return value
}
const requestPaths = (fetchMock: ReturnType<typeof routes>) => fetchMock.mock.calls.map(([url]) => url)
beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(now) })
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

it('replaces a pending flow intersection with final ranked matches without rerunning engine stages', async () => {
  const fetchMock = routes(pending(), () => complete())
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useSearchUpgrade('최근 1거래일 외국인 순매수 20일선 위 2개', { result: refused, loading: false, error: null }))
  await tick()
  expect(engineState(result.current).combined?.map(row => row.code)).toEqual(['000003'])
  await tick(10_000)
  const final = engineState(result.current)
  expect(final.combined?.map(row => row.code)).toEqual(['000001', '000002'])
  expect(final.screen.data_version).toBe('frozen-v1')
  expect(final.combined?.[0].daily?.explanation).toEqual(['확인된 일봉 근거'])
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/api/engine/translate'))).toHaveLength(1)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/api/engine/screen'))).toHaveLength(1)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/api/engine/backtest'))).toHaveLength(1)
  const call = fetchMock.mock.calls.find(([url]) => url.endsWith('/keyword-search/progress'))!
  expect(JSON.parse(String(call[1]?.body))).toEqual({ progressId: 'owned-progress-id' })
  await tick(120_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(1)
})

it('stops after three additional progress requests when the frozen flow work remains pending', async () => {
  const fetchMock = routes(pending(), () => pending())
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useSearchUpgrade('외국인 순매수 20일선 위', { result: refused, loading: false, error: null }))
  await tick()
  await tick(120_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(3)
  expect(engineState(result.current).realtime?.trackingExhausted).toBe(true)
  expect(engineState(result.current).combined?.map(row => row.code)).toEqual(['000003'])
})

it.each(['transport', 'UNAVAILABLE', 'UNSUPPORTED'] as const)('keeps confirmed intersection and daily evidence after a %s progress failure', async kind => {
  const fetchMock = routes(pending(), () => {
    if (kind === 'transport') throw new Error('진행 확인 연결 실패')
    return response([], { outcome: kind })
  })
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useSearchUpgrade('외국인 순매수 20일선 위', { result: refused, loading: false, error: null }))
  await tick()
  const daily = engineState(result.current).combined?.[0].daily
  await tick(10_000)
  expect(engineState(result.current).combined?.map(row => row.code)).toEqual(['000003'])
  expect(engineState(result.current).combined?.[0].daily).toEqual(daily)
  expect(engineState(result.current).realtime?.error).toBeNull()
  expect(engineState(result.current).realtime?.trackingError).toBeTruthy()
  await tick(120_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(1)
})

it.each(['engine', 'keyword'] as const)('finishes %s flow tracking when the required pending source completes as unavailable', async mode => {
  const initial = pending([], {
    flowCoverage: { requestedCount: 1, availableCount: 0, pendingCount: 1, unavailableCount: 0 },
  })
  const terminal = response([], {
    outcome: 'UNAVAILABLE',
    flowCoverage: { requestedCount: 1, availableCount: 0, pendingCount: 0, unavailableCount: 1 },
    progress: null,
    providers: [{ source: 'KIWOOM_REST_MOCK', environment: 'MOCK', status: 'PARTIAL', checkedAt: now.toISOString(),
      scope: '추가 시세 교차 조회', requestedCount: 1, availableCount: 0, pendingCount: 1 }],
  })
  const fetchMock = routes(initial, () => terminal)
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useSearchUpgrade('최근 1거래일 외국인 순매수 20일선 위', {
    result: mode === 'engine' ? refused : initial, loading: false, error: null,
  }))
  await tick()
  await tick(10_000)
  const final = result.current
  expect(final.status).toBe('done')
  if (final.status !== 'done') throw new Error('Expected completed search')
  const flowResult = final.mode === 'engine' ? final.realtime?.result : final.updatedResult
  const trackingError = final.mode === 'engine' ? final.realtime?.trackingError : final.trackingError
  expect(flowResult?.flowCoverage).toEqual({ requestedCount: 1, availableCount: 0, pendingCount: 0, unavailableCount: 1 })
  expect(flowResult?.outcome).toBe('UNAVAILABLE')
  expect(trackingError).not.toBeTruthy()
  if (final.mode === 'engine') expect(final.combined).toEqual([])
  await tick(120_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(1)
})

it('retains confirmed flow evidence when an unavailable progress response has inconsistent coverage counts', async () => {
  const fetchMock = routes(pending(), () => response([], {
    outcome: 'UNAVAILABLE',
    flowCoverage: { requestedCount: 1, availableCount: 0, pendingCount: 0, unavailableCount: 2 },
    progress,
  }))
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useSearchUpgrade('외국인 순매수 20일선 위', { result: refused, loading: false, error: null }))
  await tick()
  await tick(10_000)
  expect(engineState(result.current).combined?.map(row => row.code)).toEqual(['000003'])
  expect(engineState(result.current).realtime?.result?.flowCoverage?.pendingCount).toBe(3)
  expect(engineState(result.current).realtime?.trackingError).toBeTruthy()
  await tick(120_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(1)
})

it.each([
  { flowCoverage: null },
  { flowCoverage: { requestedCount: 4, availableCount: 1, pendingCount: 4, unavailableCount: 0 } },
  { flowCoverage: { requestedCount: 4, availableCount: -1, pendingCount: 4, unavailableCount: 1 } },
  { flowCoverage: { requestedCount: 4, availableCount: 1.5, pendingCount: 2.5, unavailableCount: 0 } },
  { flowCoverage: { requestedCount: 601, availableCount: 1, pendingCount: 600, unavailableCount: 0 } },
  { progress: null },
  { progress: { ...progress, expiresAt: 'invalid' } },
])('does not poll old or invalid progress metadata: %j', async extra => {
  const fetchMock = routes(pending(['000003'], extra), () => complete())
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useSearchUpgrade('외국인 순매수 20일선 위', { result: refused, loading: false, error: null }))
  await tick()
  await tick(120_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(0)
  expect(engineState(result.current).combined?.map(row => row.code)).toEqual(['000003'])
})

it('refreshes keyword-led flow results through progress without rerunning translation or backtest', async () => {
  const initial = pending()
  const fetchMock = routes(initial, () => complete(['000001']))
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useSearchUpgrade('최근 1거래일 외국인 순매수', { result: initial, loading: false, error: null }))
  await tick()
  await tick(10_000)
  const final = result.current
  expect(final.status === 'done' && final.mode === 'keyword' && final.updatedResult?.matches.map(row => row.code)).toEqual(['000001'])
  expect(final.status === 'done' && final.mode === 'keyword' && final.risk['000001']?.explanation).toEqual(['확인된 일봉 근거'])
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/api/engine/translate'))).toHaveLength(1)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/api/engine/backtest'))).toHaveLength(1)
})

it.each(['engine', 'keyword'] as const)('pins %s risk enrichment to the original daily date when later flow matches arrive', async mode => {
  const initial = pending()
  const normalRoutes = routes(initial, () => complete(['000001']))
  let riskRequests = 0
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (!url.endsWith('/api/engine/risk')) return normalRoutes(url, init)
    const body = JSON.parse(String(init?.body))
    riskRequests++
    if ((mode === 'engine' || riskRequests > 1) && body.as_of !== '2026-10-13') {
      throw new Error('Risk lookup would use a newer daily snapshot without the original as_of')
    }
    return Promise.resolve(json({
      as_of: riskRequests === 1 ? '2026-10-13' : '2026-10-14',
      items: body.codes.map((code: string) => ({ code, found: true, explanation: ['고정 일봉 근거'], risk: { flags: [], score: 0, grade: '낮음' } })),
    }))
  })
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useSearchUpgrade('외국인 순매수 20일선 위', {
    result: mode === 'engine' ? refused : initial, loading: false, error: null,
  }))
  await tick()
  await tick(10_000)
  const final = result.current
  if (final.status !== 'done') throw new Error('Expected completed search')
  if (final.mode === 'engine') {
    expect(final.combined?.[0].daily?.explanation).toEqual(['고정 일봉 근거'])
    expect(final.screen.as_of).toBe('2026-10-13')
  } else {
    expect(final.risk['000001']?.explanation).toEqual(['고정 일봉 근거'])
    expect(final.riskAsOf).toBe('2026-10-13')
  }
  const calls = fetchMock.mock.calls.filter(([url]) => url.endsWith('/api/engine/risk'))
  expect(JSON.parse(String(calls.at(-1)?.[1]?.body)).as_of).toBe('2026-10-13')
})

it('does not overlap progress requests while the preceding response is pending', async () => {
  let resolve!: (value: Response) => void
  const fetchMock = routes(pending(), attempt => attempt === 1 ? new Promise<Response>(done => { resolve = done }) : json(complete()))
  vi.stubGlobal('fetch', fetchMock)
  renderHook(() => useSearchUpgrade('외국인 순매수 20일선 위', { result: refused, loading: false, error: null }))
  await tick()
  await tick(10_000)
  await tick(30_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(1)
  await act(async () => { resolve(json(pending())); await Promise.resolve() })
  await tick(10_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(2)
})

it.each([0, 100_000])('clamps requested progress interval %i to the allowed 10–30 seconds', async retryAfterMs => {
  const fetchMock = routes(pending(['000003'], { progress: { ...progress, retryAfterMs } }), () => complete())
  vi.stubGlobal('fetch', fetchMock)
  renderHook(() => useSearchUpgrade('외국인 순매수 20일선 위', { result: refused, loading: false, error: null }))
  await tick()
  const interval = retryAfterMs === 0 ? 10_000 : 30_000
  await tick(interval - 1)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(0)
  await tick(1)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(1)
})

it('stops with a terminal tracking state when progress expires before the next request', async () => {
  const fetchMock = routes(pending(['000003'], { progress: { ...progress, expiresAt: '2026-10-14T03:00:05Z' } }), () => complete())
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useSearchUpgrade('외국인 순매수 20일선 위', { result: refused, loading: false, error: null }))
  await tick()
  await tick(10_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(0)
  expect(engineState(result.current).realtime?.trackingExhausted).toBe(true)
})

it.each(['unmount', 'close', 'query change'] as const)('cancels an in-flight progress request on %s and rejects its late result', async change => {
  let resolve!: (value: Response) => void
  const fetchMock = routes(pending(), () => new Promise<Response>(done => { resolve = done }))
  vi.stubGlobal('fetch', fetchMock)
  const { result, unmount, rerender } = renderHook(({ query }) => useSearchUpgrade(query, { result: refused, loading: false, error: null }), { initialProps: { query: '외국인 순매수 20일선 위' } })
  await tick()
  await tick(10_000)
  const call = fetchMock.mock.calls.find(([url]) => url.endsWith('/keyword-search/progress'))!
  if (change === 'unmount') unmount()
  else rerender({ query: change === 'close' ? '' : '새 검색' })
  expect(call[1]?.signal?.aborted).toBe(true)
  await act(async () => { resolve(json(complete(['000002']))); await Promise.resolve() })
  if (change === 'close') expect(result.current.status).toBe('idle')
  else if (change === 'query change') expect(engineState(result.current).combined?.map(row => row.code)).toEqual(['000003'])
  if (change === 'query change') unmount()
  await tick(120_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(1)
})

it('clears the pending progress timer when the search closes before it fires', async () => {
  const fetchMock = routes(pending(), () => complete())
  vi.stubGlobal('fetch', fetchMock)
  const { rerender } = renderHook(({ query }) => useSearchUpgrade(query, { result: refused, loading: false, error: null }), { initialProps: { query: '외국인 순매수 20일선 위' } })
  await tick()
  rerender({ query: '' })
  await tick(120_000)
  expect(requestPaths(fetchMock).filter(url => url.endsWith('/keyword-search/progress'))).toHaveLength(0)
})
