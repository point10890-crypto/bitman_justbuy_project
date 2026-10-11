// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { searchKeywordStocks, type KeywordSearchResponse } from './keywordSearchApi'

const empty: KeywordSearchResponse = {
  query: '거래대금 100억 이상', outcome: 'EMPTY', appliedConditions: ['거래대금 100억 이상'], unsupportedTerms: [],
  scope: { label: '조회된 국내 종목', examinedCount: 10, eligibleCount: 9, matchedCount: 0, truncated: false },
  fetchedAt: null, sourceStatus: 'VERIFIED_QUOTES', warnings: [], matches: [],
}
afterEach(() => vi.unstubAllGlobals())

describe('keyword data API contract', () => {
  it('sends the original query with auth and abort ownership without fabricating a missing observation time', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(empty), { headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()
    const result = await searchKeywordStocks('거래대금 100억 이상', 'fixture-token', controller.signal)
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toContain('/api/analysis/keyword-search')
    expect(options.method).toBe('POST')
    expect(JSON.parse(options.body)).toEqual({ query: '거래대금 100억 이상' })
    expect(options.headers.Authorization).toBe('Bearer fixture-token')
    expect(options.signal).toBe(controller.signal)
    expect(options.cache).toBe('no-store')
    expect(result.fetchedAt).toBeNull()
    expect(result.matches).toEqual([])
  })

  it('routes an expired subscription through the shared renewal gate', async () => {
    const gate = vi.fn()
    window.addEventListener('subscription:required', gate)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'SUBSCRIPTION_EXPIRED', error: '이용권이 만료되었습니다.' }), { status: 403 })))
    try {
      await expect(searchKeywordStocks('가격 5만원 이하')).rejects.toThrow('이용권이 만료되었습니다.')
      expect(gate).toHaveBeenCalledTimes(1)
      expect((gate.mock.calls[0][0] as CustomEvent).detail.code).toBe('SUBSCRIPTION_EXPIRED')
    } finally { window.removeEventListener('subscription:required', gate) }
  })

  it('does not substitute stocks or mark success when the server returns a proxy failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502 })))
    await expect(searchKeywordStocks('상승률 3% 이상')).rejects.toThrow('HTTP 502')
  })
})
