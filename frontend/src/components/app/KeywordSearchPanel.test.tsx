// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { KeywordSearchPanel } from './KeywordSearchPanel'
import type { KeywordSearchResponse } from '../../api/keywordSearchApi'
import type { SearchUpgrade } from '../../hooks/useSearchUpgrade'

const result: KeywordSearchResponse = {
  query: '가격 5만원 이하', outcome: 'COMPLETE', appliedConditions: ['현재가 50,000원 이하'], unsupportedTerms: [],
  scope: { label: '거래량 상위 30개 국내 종목', examinedCount: 30, eligibleCount: 29, matchedCount: 1, truncated: true },
  fetchedAt: '2026-10-12T10:00:00+09:00', sourceStatus: 'VERIFIED_QUOTES', warnings: ['전체 시장을 조회한 결과가 아닙니다.'],
  matches: [{ code: '005930', name: '검증 종목', market: 'KOSPI', currentPrice: 49000, changePct: 3.12, volume: 123456, tradingValueKrw: 9876543210,
    quoteSource: 'KIS', quoteCheckedAt: '2026-10-12T10:00:00+09:00', observedAt: '2026-10-12T09:59:58+09:00',
    evidence: [{ condition: '현재가 50,000원 이하', observedValue: '49,000', unit: '원', source: 'KIS', observedAt: '2026-10-12T09:59:58+09:00' }] }],
}
afterEach(cleanup)
const show = (response: KeywordSearchResponse | null, loading = false, error: string | null = null, onClose = vi.fn()) => render(
  <KeywordSearchPanel query="가격 5만원 이하" result={response} loading={loading} error={error} onClose={onClose} />,
)

describe('keyword search evidence presentation', () => {
  it('shows applied rules, bounded search counts, measured values and observation source/time', () => {
    show(result)
    const dialog = within(screen.getByRole('dialog', { name: '실데이터 조건검색' }))
    expect(dialog.getByText('검색 범위: 거래량 상위 30개 국내 종목')).toBeTruthy()
    expect(dialog.getByText('시세 확인 30개 · 시세 조건 통과 29개 · 조건 일치 1개')).toBeTruthy()
    expect(dialog.getByText('검색 범위 제한')).toBeTruthy()
    expect(dialog.getByText('현재가 49,000원')).toBeTruthy()
    expect(dialog.getByText('등락률 +3.12%')).toBeTruthy()
    expect(dialog.getByText('거래량 123,456주')).toBeTruthy()
    expect(dialog.getByText('거래대금 9,876,543,210원')).toBeTruthy()
    expect(dialog.getByText(/시세 출처 KIS/)).toBeTruthy()
    expect(dialog.getByText(/시세 관측 시각.*09:59:58.*KST/)).toBeTruthy()
    expect(dialog.getByText(/조회 시각.*10:00:00.*KST/)).toBeTruthy()
    expect(dialog.queryByText('실시간 분석')).toBeNull()
    expect(dialog.queryByText('매수')).toBeNull()
  })

  it('keeps unprovided numeric metrics and times unknown instead of inserting zeros or current time', () => {
    show({ ...result, fetchedAt: null, matches: [{ ...result.matches[0], changePct: null, volume: null, tradingValueKrw: null, quoteCheckedAt: null, observedAt: null }] })
    expect(screen.getByText('등락률 미확인')).toBeTruthy()
    expect(screen.getByText('거래량 미확인')).toBeTruthy()
    expect(screen.getByText('거래대금 미확인')).toBeTruthy()
    expect(screen.getByText('조회 시각 미확인')).toBeTruthy()
    expect(screen.getByText('시세 관측 시각 미확인')).toBeTruthy()
  })

  it('shows both data providers and the Kiwoom server environment without labelling market observations as synthetic', () => {
    show({ ...result, providers: [
      { source: 'KIS', environment: 'REAL', status: 'AVAILABLE', checkedAt: '2026-10-12T10:00:00+09:00', scope: '주시세 30종목' },
      { source: 'KIWOOM_REST_MOCK', environment: 'MOCK', status: 'AVAILABLE', checkedAt: '2026-10-12T10:00:02+09:00', scope: '상위 3종목 시세·일봉·수급 교차조회' },
    ] })
    const sources = within(screen.getByRole('region', { name: '조회 출처와 상태' }))
    expect(sources.getByText('KIS · 실전 서버 시장조회 · 조건검색에 사용')).toBeTruthy()
    expect(sources.getByText('키움 REST · 모의투자 서버 시장조회 · 조건검색에 사용')).toBeTruthy()
    expect(sources.getByText('상위 3종목 시세·일봉·수급 교차조회')).toBeTruthy()
    expect(sources.getByText(/조회 결과 확인.*10:00:02.*KST/)).toBeTruthy()
    expect(screen.queryByText(/가상 데이터|모의 데이터/)).toBeNull()
  })

  it('does not imply a provider was used when its observation is unavailable or its state is unknown', () => {
    show({ ...result, providers: [
      { source: 'NAVER_FINANCE', environment: 'REAL', status: 'COMPLETE', checkedAt: result.fetchedAt, scope: '주시세 30종목' },
      { source: 'KIWOOM_REST_MOCK', environment: 'MOCK', status: 'UNAVAILABLE', checkedAt: null, scope: null },
      { source: 'KIS', environment: '', status: 'UNRECOGNIZED', checkedAt: 'invalid', scope: null },
    ] })
    const sources = within(screen.getByRole('region', { name: '조회 출처와 상태' }))
    expect(sources.getByText('네이버 금융 · 실전 서버 시장조회 · 조건검색에 사용')).toBeTruthy()
    expect(sources.getByText('키움 REST · 모의투자 서버 시장조회 · 조회 미확인')).toBeTruthy()
    expect(sources.getByText('KIS · 환경 미확인 · 조회 상태 미확인')).toBeTruthy()
    expect(sources.getAllByText('조회 결과 확인 미확인')).toHaveLength(2)
    expect(screen.getByText('현재가 49,000원')).toBeTruthy()
  })

  it('keeps independently observed prices and times separate for each provider', () => {
    show({ ...result, matches: [{ ...result.matches[0], quoteEnvironment: 'REAL', sourceObservations: [
      { source: 'KIS', environment: 'REAL', currentPrice: 49000, changePct: 3.12, volume: 123456, tradingValueKrw: 9876543210,
        fetchedAt: '2026-10-12T10:00:00+09:00', observedAt: '2026-10-12T09:59:58+09:00' },
      { source: 'KIWOOM_REST_MOCK', environment: 'MOCK', currentPrice: 49100, changePct: 3.33, volume: null, tradingValueKrw: null,
        fetchedAt: '2026-10-12T10:00:02+09:00', observedAt: '2026-10-12T10:00:01+09:00' },
    ], evidence: [{ condition: '외국인 순매수', observedValue: '12000', unit: '주', source: 'KIWOOM_REST_MOCK',
      environment: 'MOCK', fetchedAt: '2026-10-12T10:00:03+09:00', observedAt: '2026-10-06 ~ 2026-10-12' }] }] })
    const observations = within(screen.getByRole('list', { name: '검증 종목 공급자별 조회값' }))
    const rows = observations.getAllByRole('listitem')
    expect(within(rows[0]).getByText('KIS · 실전 서버 시장조회')).toBeTruthy()
    expect(within(rows[0]).getByText(/현재가 49,000원.*등락률 \+3.12%/)).toBeTruthy()
    expect(within(rows[1]).getByText('키움 REST · 모의투자 서버 시장조회')).toBeTruthy()
    expect(within(rows[1]).getByText(/현재가 49,100원.*등락률 \+3.33%.*거래량 미확인.*거래대금 미확인/)).toBeTruthy()
    expect(within(rows[1]).getByText(/조회.*10:00:02.*관측.*10:00:01.*KST/)).toBeTruthy()
    expect(screen.getByText('관측 기간 2026-10-06 ~ 2026-10-12')).toBeTruthy()
    expect(screen.getByText(/근거 조회 시각.*10:00:03.*KST/)).toBeTruthy()
    expect(screen.getByText(/실측 12000주.*출처 키움 REST.*모의투자 서버 시장조회/)).toBeTruthy()
  })

  it('distinguishes background enrichment from a failed provider while preserving measured matches', () => {
    show({ ...result, providers: [{ source: 'KIWOOM_REST_MOCK', environment: 'MOCK', status: 'PENDING', checkedAt: null,
      scope: '검증된 후보 최대 20종목', requestedCount: 20, availableCount: 0, pendingCount: 20 }] })
    expect(screen.getByText('키움 REST · 모의투자 서버 시장조회 · 추가 조회 대기')).toBeTruthy()
    expect(screen.getByText('근거 확인 0 / 요청 20종목 · 추가 확인 대기 20종목')).toBeTruthy()
    expect(screen.getByText('확인된 종목은 먼저 표시합니다. 추가 조회가 끝난 뒤 같은 조건으로 다시 검색해 주세요.')).toBeTruthy()
    expect(screen.getByText('현재가 49,000원')).toBeTruthy()
    expect(screen.queryByText(/조회 미확인/)).toBeNull()
  })

  it('shows observed coverage and pending work separately without creating missing prices or a polling loop', () => {
    vi.useFakeTimers()
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    show({ ...result, outcome: 'PARTIAL', providers: [{ source: 'KIWOOM_REST_MOCK', environment: 'MOCK', status: 'PARTIAL',
      checkedAt: result.fetchedAt, scope: '동일 후보 교차 조회', requestedCount: 20, availableCount: 7, pendingCount: 16 }] })
    expect(screen.getByText('근거 확인 7 / 요청 20종목 · 추가 확인 대기 16종목')).toBeTruthy()
    vi.advanceTimersByTime(120_000)
    expect(fetchSpy).not.toHaveBeenCalled()
    fetchSpy.mockRestore()
    vi.useRealTimers()
  })

  it.each([
    ['EMPTY', '조회한 범위에서 조건에 맞는 종목이 없습니다.'],
    ['UNAVAILABLE', '실데이터를 조회하지 못했습니다. 잠시 후 다시 검색해 주세요.'],
    ['UNSUPPORTED', '지원하지 않는 조건이 있습니다. 아래 조건을 바꿔 다시 검색해 주세요.'],
  ] as const)('distinguishes %s from a successful match', (outcome, message) => {
    show({ ...result, outcome, matches: [], scope: { ...result.scope, matchedCount: 0 }, warnings: [] })
    expect(screen.getByText(message)).toBeTruthy()
    expect(screen.queryByText('검증 종목')).toBeNull()
  })

  it('exposes partial-data warnings and unsupported terms alongside the applied conditions', () => {
    show({ ...result, outcome: 'PARTIAL', unsupportedTerms: ['신고가'], warnings: ['일부 종목의 시세를 확인하지 못했습니다.'] })
    expect(screen.getByText('일부 데이터로 검색')).toBeTruthy()
    expect(screen.getByText('적용하지 못한 조건: 신고가')).toBeTruthy()
    expect(screen.getByText('일부 종목의 시세를 확인하지 못했습니다.')).toBeTruthy()
    expect(screen.getByText('검증 종목')).toBeTruthy()
  })

  it('retains the actual trading-day observation interval separately from a point-in-time quote', () => {
    show({ ...result, matches: [{ ...result.matches[0], evidence: [{ condition: '외국인 순매수', observedValue: '123456', unit: '주', source: 'KIS OpenAPI', observedAt: '2026-10-08 ~ 2026-10-12' }] }] })
    expect(screen.getByText('관측 기간 2026-10-08 ~ 2026-10-12')).toBeTruthy()
    expect(screen.getByText(/시세 관측 시각.*09:59:58.*KST/)).toBeTruthy()
  })

  it.each(['invalid observation', '1', '2026-10-12 ~ 2026-10-08', '2026-02-30 ~ 2026-03-02', '2026/10/08 ~ 2026/10/12'])(
    'cannot turn an invalid observation %s into a known time or period', observedAt => {
      show({ ...result, matches: [{ ...result.matches[0], evidence: [{ condition: '외국인 순매수', observedValue: '123456', unit: '주', source: 'KIS OpenAPI', observedAt }] }] })
      expect(screen.getByText('관측 미확인')).toBeTruthy()
      expect(screen.queryByText(/^관측 기간/)).toBeNull()
    },
  )

  it('exposes cancellable data loading without claiming AI providers are running', () => {
    const onClose = vi.fn()
    show(null, true, null, onClose)
    expect(screen.getByRole('status').textContent).toContain('실데이터를 조회하고 조건을 확인하고 있습니다.')
    expect(screen.queryByText(/AI 에이전트 병렬/)).toBeNull()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

const mixed = (live: KeywordSearchResponse): Extract<SearchUpgrade, { mode: 'engine' }> => ({
  status: 'done', mode: 'engine',
  translation: {
    strategy: { title: '20일선 위', when: { f: 'gap_ma20', op: '>', v: 0 }, rank_by: { f: 'quality_score', desc: true }, top_n: 3 },
    source: 'rules', conditions: ['20일선 위'], assumptions: [], warnings: [], unsupported: [], unparsed: [], error: null,
    fallback_reason: null, realtime_query: '최근 1거래일 외국인 순매수',
  },
  screen: {
    strategy: { title: '20일선 위', when: { f: 'gap_ma20', op: '>', v: 0 }, rank_by: { f: 'quality_score', desc: true }, top_n: 3 },
    conditions: ['20일선 위'], as_of: '2026-10-13', regime: { code: 'UP_TREND', label: '상승 국면' }, regime_blocked: false,
    counts: { universe: 868, passing: 3, guard_excluded: 0, eligible: 3, published: 0 },
    results: [], excluded: [], below_top_n: [], excluded_codes: {}, data_version: 'v',
  },
  backtest: null, realtime: { query: '최근 1거래일 외국인 순매수', result: live, error: null },
  combined: [], combinedExcluded: [], riskError: null,
})
const showMixed = (response: KeywordSearchResponse, upgrade = mixed(response)) => render(
  <KeywordSearchPanel query="최근 1거래일 외국인 순매수 20일선 위 3개" result={null} loading={false} error={null} onClose={vi.fn()} upgrade={upgrade} />,
)

describe('required flow readiness presentation', () => {
  it('distinguishes the live candidate lookup cap from the final mixed-result cap without changing source conditions', () => {
    const live = { ...result, appliedConditions: ['최근 실제 1거래일 수급 합산', '결과 최대 10종목'] }
    showMixed(live)
    const scope = within(screen.getByRole('region', { name: '검색 조건과 범위' }))
    expect(scope.getByText('수급 조회 결과 최대 10종목')).toBeTruthy()
    expect(scope.getByText('최종 결합 결과 최대 3종목')).toBeTruthy()
    expect(scope.queryByText('결과 최대 10종목')).toBeNull()
    expect(live.appliedConditions).toEqual(['최근 실제 1거래일 수급 합산', '결과 최대 10종목'])
  })

  it('does not call a completed partial search processing when only optional provider work is pending', () => {
    const live = { ...result, outcome: 'PARTIAL' as const,
      flowCoverage: { requestedCount: 20, availableCount: 20, pendingCount: 0, unavailableCount: 0 },
      providers: [{ source: 'KIWOOM_REST_MOCK', environment: 'MOCK', status: 'PARTIAL', checkedAt: result.fetchedAt,
        scope: '시세·일봉 추가 조회', requestedCount: 20, availableCount: 6, pendingCount: 14 }],
    }
    showMixed(live)
    expect(screen.getByRole('status').textContent).toContain('확인된 범위 조건 일치 0건')
    expect(screen.getByRole('status').textContent).not.toMatch(/확인 중|진행 중/)
    expect(screen.queryByRole('region', { name: '조건 일치 종목' })).toBeNull()
    expect(screen.getByText('수급 근거 확인 20 / 요청 20종목 · 추가 확인 대기 0종목 · 미확인 0종목')).toBeTruthy()
  })

  it('shows actual missing required flow jobs as processing rather than verified zero', () => {
    const live = { ...result, outcome: 'PARTIAL' as const, matches: [], scope: { ...result.scope, matchedCount: 0 },
      flowCoverage: { requestedCount: 20, availableCount: 0, pendingCount: 20, unavailableCount: 0 },
    }
    showMixed(live)
    expect(screen.getByRole('status').textContent).toContain('수급 조회 진행 중')
    expect(screen.getByRole('status').textContent).not.toContain('조건 일치 0건')
  })

  it('treats missing flow after the queue stops as unavailable evidence rather than continuing work', () => {
    const live = { ...result, outcome: 'PARTIAL' as const,
      flowCoverage: { requestedCount: 20, availableCount: 6, pendingCount: 0, unavailableCount: 14 },
    }
    showMixed(live)
    expect(screen.getByRole('status').textContent).toContain('수급 일부 자료 미확인')
    expect(screen.getByRole('status').textContent).not.toMatch(/확인 중|진행 중/)
  })

  it('does not infer active flow work from a legacy partial outcome without coverage', () => {
    showMixed({ ...result, outcome: 'PARTIAL' })
    expect(screen.getByRole('status').textContent).not.toMatch(/확인 중|진행 중/)
    expect(screen.getByRole('status').textContent).toContain('일부 자료 미확인')
  })

  it('also distinguishes required flow processing in a keyword-led response', () => {
    const live = { ...result, outcome: 'PARTIAL' as const, matches: [], scope: { ...result.scope, matchedCount: 0 },
      flowCoverage: { requestedCount: 20, availableCount: 0, pendingCount: 20, unavailableCount: 0 },
    }
    show(live)
    expect(screen.getByRole('status').textContent).toContain('수급 조회 진행 중')
  })

  it('does not turn missing research evidence into proven zero when all required flow facts are available', () => {
    const live = { ...result, query: '외국인 순매수 PER 15 이하', outcome: 'UNAVAILABLE' as const, sourceStatus: 'UNAVAILABLE',
      appliedConditions: ['외국인 순매수 > 0', 'PER 15 이하'], matches: [], scope: { ...result.scope, matchedCount: 0 },
      warnings: ['PER 실측 근거 미확인 종목은 제외했습니다.'],
      flowCoverage: { requestedCount: 1, availableCount: 1, pendingCount: 0, unavailableCount: 0 },
    }
    show(live)
    expect(screen.getByRole('status').textContent).toContain('데이터 조회 불가')
    expect(screen.getByRole('status').textContent).not.toContain('조건 일치 0건')
    expect(screen.getByText('PER 실측 근거 미확인 종목은 제외했습니다.')).toBeTruthy()
    expect(screen.queryByRole('region', { name: '조건 일치 종목' })).toBeNull()
  })

  it('keeps a partial overall result distinct from completed flow facts when research data is missing', () => {
    show({ ...result, outcome: 'PARTIAL', appliedConditions: ['외국인 순매수 > 0', 'PER 15 이하'],
      matches: [], scope: { ...result.scope, matchedCount: 0 }, warnings: ['PER 실측 근거 미확인 종목은 제외했습니다.'],
      flowCoverage: { requestedCount: 1, availableCount: 1, pendingCount: 0, unavailableCount: 0 },
    })
    expect(screen.getByRole('status').textContent).toContain('일부 데이터로 검색')
    expect(screen.getByRole('status').textContent).not.toContain('조건 일치 0건')
    expect(screen.getByText('수급 근거 확인 1 / 요청 1종목 · 추가 확인 대기 0종목 · 미확인 0종목')).toBeTruthy()
  })

  it('shows refreshed keyword results without publishing the stale original response', () => {
    const updatedResult = { ...result, matches: [{ ...result.matches[0], code: '000660', name: '새로운 확인 종목' }],
      flowCoverage: { requestedCount: 20, availableCount: 20, pendingCount: 0, unavailableCount: 0 },
    }
    const upgrade: Extract<SearchUpgrade, { mode: 'keyword' }> = {
      status: 'done', mode: 'keyword', translation: null, risk: {}, riskAsOf: null, backtest: null, updatedResult,
    }
    render(<KeywordSearchPanel query="최근 1거래일 외국인 순매수" result={result} loading={false} error={null} onClose={vi.fn()} upgrade={upgrade} />)
    expect(screen.getByText('새로운 확인 종목')).toBeTruthy()
    expect(screen.queryByText('검증 종목')).toBeNull()
  })

  it.each(['exhausted', 'error'] as const)('keeps verified matches visible when automatic tracking stops with %s', reason => {
    const live = { ...result, outcome: 'PARTIAL' as const,
      flowCoverage: { requestedCount: 20, availableCount: 6, pendingCount: 14, unavailableCount: 0 },
    }
    const upgrade = mixed(live)
    upgrade.combined = [{ code: '005930', live: result.matches[0], daily: null }]
    upgrade.realtime = { ...upgrade.realtime!, trackingExhausted: reason === 'exhausted',
      trackingError: reason === 'error' ? '진행 조회 연결 실패' : null,
    }
    showMixed(live, upgrade)
    expect(screen.getByText('검증 종목')).toBeTruthy()
    expect(screen.getByRole('status').textContent).toContain(reason === 'exhausted' ? '수급 자동 확인 종료' : '수급 추가 확인 중단')
    expect(screen.getByRole('status').textContent).not.toContain('수급 조회 진행 중')
    expect(screen.getByText('수급 근거 확인 6 / 요청 20종목 · 추가 확인 대기 14종목 · 미확인 0종목')).toBeTruthy()
    expect(screen.queryByText('수급 조건 조회 실패')).toBeNull()
  })
})
