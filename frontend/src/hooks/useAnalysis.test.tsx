// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchLiveAnalysis, fetchPrecomputed, type AnalysisResponse } from '../api/analysisApi'
import { clearAllCache, setCache } from '../lib/analysisCache'
import { useAnalysis } from './useAnalysis'

vi.mock('../api/analysisApi', () => ({ fetchLiveAnalysis: vi.fn(), fetchPrecomputed: vi.fn() }))
vi.mock('../contexts/AuthContext', () => ({ getStoredToken: () => undefined }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const response = (content: string, updatedAt = new Date().toISOString()): AnalysisResponse => ({
  content, stockPicks: [], agents: [], hasSynthesis: false, mode: '분석해줘', updatedAt, isFresh: true,
  metadata: { totalDurationMs: 1, agentsUsed: 1, agentsSucceeded: 1 },
})

describe('analysis request ownership', () => {
  beforeEach(() => { vi.clearAllMocks(); clearAllCache() })
  afterEach(() => { cleanup(); vi.useRealTimers() })

  it('a closed request cannot overwrite the answer to a newer request', async () => {
    const old = deferred<AnalysisResponse>()
    const current = deferred<AnalysisResponse>()
    vi.mocked(fetchLiveAnalysis).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const { result } = renderHook(useAnalysis)
    let oldRun!: Promise<unknown>
    let currentRun!: Promise<unknown>
    act(() => { oldRun = result.current.analyze('old') })
    act(() => { result.current.clear(); currentRun = result.current.analyze('new') })
    await act(async () => { current.resolve(response('NEW')); await currentRun })
    await act(async () => { old.resolve(response('OLD')); await oldRun })

    expect(result.current.result?.content).toBe('NEW')
    expect(result.current.error).toBeNull()
    expect(result.current.loading).toBe(false)
  })

  it('an older failure cannot clear newer loading or set its error', async () => {
    const old = deferred<AnalysisResponse>()
    const current = deferred<AnalysisResponse>()
    vi.mocked(fetchLiveAnalysis).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const { result } = renderHook(useAnalysis)
    let oldRun!: Promise<unknown>
    let currentRun!: Promise<unknown>
    act(() => { oldRun = result.current.analyze('old') })
    act(() => { currentRun = result.current.analyze('new') })
    await act(async () => { old.reject(new Error('old failure')); await oldRun })

    expect(result.current.loading).toBe(true)
    expect(result.current.error).toBeNull()
    await act(async () => { current.resolve(response('NEW')); await currentRun })
    expect(result.current.result?.content).toBe('NEW')
  })

  it('closing an active request immediately clears loading and ignores late failure', async () => {
    const pending = deferred<AnalysisResponse>()
    vi.mocked(fetchLiveAnalysis).mockReturnValueOnce(pending.promise)
    const { result } = renderHook(useAnalysis)
    let run!: Promise<unknown>
    act(() => { run = result.current.analyze('old') })
    act(() => { result.current.clear() })
    expect(result.current.loading).toBe(false)
    await act(async () => { pending.reject(new Error('late failure')); await run })
    expect(result.current.result).toBeNull()
    expect(result.current.error).toBeNull()
  })

  it('two-hour-old live cache must refresh instead of retaining its saved freshness', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-05-18T03:00:00Z'))
    setCache('query', '분석해줘', response('OLD', '2026-05-18T01:00:00Z'))
    vi.mocked(fetchLiveAnalysis).mockResolvedValue(response('FRESH'))
    const { result } = renderHook(useAnalysis)
    await act(async () => { await result.current.analyze('query') })
    expect(result.current.result?.content).toBe('FRESH')
    expect(fetchLiveAnalysis).toHaveBeenCalledTimes(1)
  })

  it('late precomputed result cannot supersede a newer live request', async () => {
    const old = deferred<AnalysisResponse | null>()
    vi.mocked(fetchPrecomputed).mockReturnValue(old.promise)
    vi.mocked(fetchLiveAnalysis).mockResolvedValue(response('NEW'))
    const { result } = renderHook(useAnalysis)
    let oldRun!: Promise<unknown>
    act(() => { oldRun = result.current.analyze('old', 'BREAKOUT') })
    await act(async () => { await result.current.analyze('new') })
    await act(async () => { old.resolve(response('OLD')); await oldRun })
    expect(result.current.result?.content).toBe('NEW')
  })
})
