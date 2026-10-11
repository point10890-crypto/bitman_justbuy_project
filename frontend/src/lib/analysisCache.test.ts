// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { AnalysisResponse } from '../api/analysisApi'
import { clearAllCache, getCached, setCache } from './analysisCache'

const result = (updatedAt: string, mode = '분석해줘', isFresh = true): AnalysisResponse => ({
  content: 'observed', mode, updatedAt, isFresh, agents: [], stockPicks: [], hasSynthesis: false,
  metadata: { agentsUsed: 1, agentsSucceeded: 1, totalDurationMs: 1 },
})
beforeEach(() => { clearAllCache(); vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-05-18T03:00:00Z')) })
afterEach(() => { vi.useRealTimers() })

it('freshness uses the response observation time even when it was just saved', () => {
  setCache('query', '분석해줘', result('2026-05-18T01:00:00Z'))
  expect(getCached('query', '분석해줘')?.isFresh).toBe(false)
})

it('a cache entry that was fresh expires under the 30-minute live policy', () => {
  setCache('query', '분석해줘', result('2026-05-18T03:00:00Z'))
  vi.setSystemTime(new Date('2026-05-18T03:31:00Z'))
  expect(getCached('query', '분석해줘')?.isFresh).toBe(false)
})

it('precomputed cache expires at market close even before its 175-minute TTL', () => {
  vi.setSystemTime(new Date('2026-05-18T06:31:00Z'))
  setCache('query', 'BREAKOUT', result('2026-05-18T05:50:00Z', 'BREAKOUT'), 'precomputed')
  expect(getCached('query', 'BREAKOUT')?.isFresh).toBe(false)
})

it.each(['invalid date', '2026-05-18T04:00:00Z'])('invalid or future timestamps cannot be fresh: %s', updatedAt => {
  setCache('query', '분석해줘', result(updatedAt))
  expect(getCached('query', '분석해줘')?.isFresh).toBe(false)
})

it('server-stale flag is preserved for a recent timestamp', () => {
  setCache('query', '분석해줘', result('2026-05-18T03:00:00Z', '분석해줘', false))
  expect(getCached('query', '분석해줘')?.isFresh).toBe(false)
})

it('a live fallback for a concept mode uses the live 30-minute policy', () => {
  setCache('query', 'BREAKOUT', result('2026-05-18T02:29:00Z', 'BREAKOUT'))
  expect(getCached('query', 'BREAKOUT')?.isFresh).toBe(false)
})
