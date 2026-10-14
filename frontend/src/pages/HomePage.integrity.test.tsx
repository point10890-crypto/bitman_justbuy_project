// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { fetchConditionSection, type ConditionSectionResponse, type MainConditionResponse } from '../api/conditionApi'
import HomePage from './HomePage'
import { latestAnalysisPresentation } from '../lib/conditionPresentation'

let main: MainConditionResponse
const { analyzeMock } = vi.hoisted(() => ({ analyzeMock: vi.fn() }))
vi.mock('../hooks/useMainConditions', () => ({ useMainConditions: () => ({ data: main, loading: false, error: null }) }))
vi.mock('../hooks/useAnalysis', () => ({ useAnalysis: () => ({ result: null, loading: false, error: null, analyze: analyzeMock, clear: vi.fn() }) }))
vi.mock('../contexts/AuthContext', () => ({ getStoredToken: () => undefined, useAuth: () => ({ user: { role: 'ADMIN', subscription: 'pro' } }) }))
vi.mock('../api/conditionApi', () => ({ fetchConditionSection: vi.fn() }))

function section(name: string, asOf: string): ConditionSectionResponse {
  return {
    key: 'shortTerm', slug: 'short-term', title: '단타', mode: 'BREAKOUT', endpoint: '/api/conditions/short-term', asOf,
    sourceStatus: 'REALTIME_SCAN', signals: [{
      stockName: name, stockCode: '005930', capturePrice: '10,000', currentPrice: '10,000', highPrice: '-', stopLoss: '-',
      section: 'shortTerm', mode: 'BREAKOUT', rank: 1, maxReturnPct: '+5.21%', status: '실시간 포착',
      ruleScore: 90, aiScore: 0, finalScore: 90, summary: 'observed', evidence: ['KIS'], riskFlags: [], invalidation: '', capturedAt: asOf,
    }],
  }
}
function feed(name: string, asOf: string): MainConditionResponse {
  const current = section(name, asOf)
  const empty = { ...current, signals: [] }
  return {
    asOf, sections: { shortTerm: current, swing: empty, leaders: empty, themes: empty, closingBet: empty, alerts: empty },
    trackRecord: { totalSignals: 0, avgMaxReturnPct: '-', winRate5d: '-' }, notice: '',
  }
}
const tree = () => <MemoryRouter><HomePage /></MemoryRouter>
beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-12T11:00:00+09:00'))
  main = feed('MAIN_0850', '2026-05-18T08:50:00+09:00')
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

it('links to public event research without adding research stocks to existing ranking cards', () => {
  render(tree())
  expect(screen.getByRole('link', { name: '공개 사건 연구' }).getAttribute('href')).toBe('/research/events')
  expect(screen.queryByRole('article', { name: /사건 연구/ })).toBeNull()
})

it('manual section rows use the same response timestamp and yield to a later feed response', async () => {
  vi.mocked(fetchConditionSection).mockResolvedValue(section('MANUAL_0900', '2026-05-18T09:00:00+09:00'))
  const { container, rerender } = render(tree())
  let card = within(container.querySelector('#short-term') as HTMLElement)
  fireEvent.click(card.getByRole('button', { name: '단타 상위 후보 새로고침' }))
  await waitFor(() => expect(card.getByText('MANUAL_0900')).toBeTruthy())
  expect(card.getByText(/09:00 기준/)).toBeTruthy()

  main = feed('LATEST_1200', '2026-05-18T12:00:00+09:00')
  rerender(tree())
  card = within(container.querySelector('#short-term') as HTMLElement)
  expect(card.getByText('LATEST_1200')).toBeTruthy()
  expect(card.queryByText('MANUAL_0900')).toBeNull()
  expect(card.getByText(/12:00 기준/)).toBeTruthy()
})

it('a section refresh started against an older feed cannot replace a later feed', async () => {
  let resolve!: (value: ConditionSectionResponse) => void
  const pending = new Promise<ConditionSectionResponse>(res => { resolve = res })
  vi.mocked(fetchConditionSection).mockReturnValue(pending)
  const { container, rerender } = render(tree())
  const card = within(container.querySelector('#short-term') as HTMLElement)
  fireEvent.click(card.getByRole('button', { name: '단타 상위 후보 새로고침' }))
  main = feed('LATEST_1200', '2026-05-18T12:00:00+09:00')
  rerender(tree())
  await act(async () => { resolve(section('LATE_0900', '2026-05-18T09:00:00+09:00')); await pending })
  expect(card.queryByText('LATE_0900')).toBeNull()
  expect(card.getByText('LATEST_1200')).toBeTruthy()
})

it('no completed measurements are labeled as unavailable measured five-day performance', () => {
  render(tree())
  const summary = within(screen.getByLabelText('조건검색 성과 요약'))
  expect(summary.getByText('측정 완료 0건')).toBeTruthy()
  expect(summary.getByText('5거래일 평균 자료 없음')).toBeTruthy()
  expect(summary.getByText('5거래일 승률 자료 없음')).toBeTruthy()
  expect(summary.queryByText(/검증 신호|100%|6\.00%/)).toBeNull()
})

it('a manual endpoint carrying an older source snapshot cannot replace a newer main snapshot', async () => {
  main = feed('LATEST_1200', '2026-05-18T12:00:00+09:00')
  vi.mocked(fetchConditionSection).mockResolvedValue(section('STALE_0900', '2026-05-18T09:00:00+09:00'))
  const { container } = render(tree())
  const card = within(container.querySelector('#short-term') as HTMLElement)
  await act(async () => { fireEvent.click(card.getByRole('button', { name: '단타 상위 후보 새로고침' })) })
  expect(card.getByText('LATEST_1200')).toBeTruthy()
  expect(card.queryByText('STALE_0900')).toBeNull()
  expect(card.getByText(/12:00 기준/)).toBeTruthy()
})

it('expected target upside in precomputed cards is labeled separately from observed price changes', () => {
  const expected = { ...section('EXPECTED', '2026-05-18T12:00:00+09:00'), sourceStatus: 'PRECOMPUTED' }
  expected.signals[0] = { ...expected.signals[0], highPrice: '10,600', maxReturnPct: '+6.00%' }
  main.sections.shortTerm = expected
  main.sections.leaders = { ...expected, key: 'leaders', slug: 'leaders', mode: 'FLOW_LEADER' }
  main.sections.themes = { ...expected, key: 'themes', slug: 'themes', mode: 'CATALYST_BURST' }
  const { container } = render(tree())
  for (const id of ['short-term', 'leaders', 'themes']) {
    const card = within(container.querySelector(`#${id}`) as HTMLElement)
    expect(card.getByText('목표 상승 여력')).toBeTruthy()
  }
  const theme = within(container.querySelector('#themes') as HTMLElement)
  expect(theme.getByText('종목명')).toBeTruthy()
  expect(theme.getByText('현재가')).toBeTruthy()
})

const keywordResponse = (query: string, name = '조건 검증 종목') => ({
  query, outcome: 'COMPLETE', appliedConditions: ['현재가 50,000원 이하'], unsupportedTerms: [],
  scope: { label: '조회한 국내 상장 종목', examinedCount: 20, eligibleCount: 19, matchedCount: 1, truncated: false },
  fetchedAt: '2026-10-12T10:00:00+09:00', sourceStatus: 'COMPLETE', warnings: [],
  matches: [{ code: '005930', name, market: 'KOSPI', currentPrice: 49000, changePct: null, volume: null, tradingValueKrw: null,
    quoteSource: 'KIS', quoteCheckedAt: '2026-10-12T10:00:00+09:00', observedAt: null,
    evidence: [{ condition: '현재가 50,000원 이하', observedValue: '49000', unit: '원', source: 'KIS', observedAt: null }] }],
})
const asJson = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
// 같은 질문은 AI 조건검색 엔진에도 간다. 실데이터 요청 횟수를 세는 테스트는 엔진 호출을 따로 응답해 분리한다.
const engineMiss = { strategy: null, source: 'rules', conditions: [], assumptions: [], warnings: [], unsupported: [], unparsed: [], error: '조건 없음', fallback_reason: null }
const withEngine = (dataFetch: (url: string, init?: RequestInit) => Promise<Response>) =>
  vi.fn((url: string, init?: RequestInit) => String(url).includes('/api/engine/') ? Promise.resolve(asJson(engineMiss)) : dataFetch(url, init))

it('hero searches the original condition through the data API without asking an AI to originate stocks', async () => {
  const fetchMock = vi.fn().mockResolvedValue(asJson(keywordResponse('가격 5만원 이하')))
  vi.stubGlobal('fetch', withEngine(fetchMock))
  render(tree())
  fireEvent.change(screen.getByRole('textbox', { name: '분석할 종목명 또는 조건' }), { target: { value: '  가격 5만원 이하  ' } })
  fireEvent.submit(screen.getByRole('search'))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
  const [url, options] = fetchMock.mock.calls[0]
  expect(url).toContain('/api/analysis/keyword-search')
  expect(JSON.parse(options.body)).toEqual({ query: '가격 5만원 이하' })
  expect(analyzeMock).not.toHaveBeenCalled()
  const panel = await screen.findByRole('dialog', { name: '실데이터 조건검색' })
  expect(within(panel).getByText('조건 검증 종목')).toBeTruthy()
  expect(within(panel).getByText('현재가 49,000원')).toBeTruthy()
})

// ---- search upgrade: one search box, the live search and the AI engine merged into one result ----
const dailyStrategy = { id: 'adhoc_1', title: '20일선 위', when: { f: 'gap_ma20', op: '>', v: 0 }, rank_by: { f: 'quality_score', desc: true }, top_n: 10, guard: { exclude_risk: ['chase', 'high_vol'] } }
const translated = (extra: Record<string, unknown> = {}) => ({ ...engineMiss, strategy: dailyStrategy, error: null, conditions: ['20일선 이격 +0.0% 초과'], ...extra })
const riskItem = (code: string, name: string) => ({ code, found: true, name, in_universe: true, explanation: ['20일선 위 (+3.0%)', 'ATR 2.2% · 차분함'],
  risk: { flags: [], score: 0, grade: '낮음', band: { atr_pct: 2.2, typical_pct: 2.5, wide_low_pct: -6.1, wide_high_pct: 7.8, note: '' } } })
const engineRow = (code: string, name: string) => ({ ...riskItem(code, name), market: 'KOSPI', sector: null, industry: null, stage: 'PUBLISHED', excluded_by: [], rank: 1,
  snapshot: { close: 180100, r1: 0.0056, volr: 1.3, dd20: -0.02, dd120: -0.03, gap_ma20: 0.03, gap_ma60: 0.08,
    atr14_pct: 0.022, vol20: 0.012, val_today: 49923178496, val_avg20: 40000000000, rs_sector20: 0.01, quality_score: 0.8 } })
const screenResponse = (rows: ReturnType<typeof engineRow>[], belowTopN: string[] = [], excludedCodes: Record<string, string[]> = {}) => ({
  strategy: dailyStrategy, conditions: ['20일선 이격 +0.0% 초과'], as_of: '2026-10-13', regime: { code: 'UP_TREND', label: '지수가 60일선 위에서 20일 상승' },
  regime_blocked: false, counts: { universe: 868, passing: rows.length + belowTopN.length + Object.keys(excludedCodes).length, guard_excluded: Object.keys(excludedCodes).length, eligible: rows.length + belowTopN.length, published: rows.length },
  results: rows, excluded: [], below_top_n: belowTopN, excluded_codes: excludedCodes, data_version: 'v',
})
const backtestResponse = {
  period: { all: ['2024-01-02', '2026-10-13', 675] },
  horizons: { 5: { all: {}, discovery: { signal_days: 300, excess_vs_universe_pct: 0.47 }, confirm: { signal_days: 200, excess_vs_universe_pct: 0.22, excess_vs_index_pct: -1.9, net_return_pct: -0.35, hit_rate_pct: 47.3 },
    verdict: { code: 'positive_unconfirmed', label: '양의 성과(통계적 확정 전)', detail: '두 구간 모두 양수지만 확인 구간 유의성이 낮습니다' } } },
}
type Routes = { translate: unknown; screen?: unknown; risk?: (codes: string[]) => unknown; keyword: (query: string) => unknown }
function routed(r: Routes) {
  return vi.fn((url: string, init?: RequestInit) => {
    const u = String(url)
    const body = init?.body ? JSON.parse(String(init.body)) : {}
    if (u.endsWith('/api/engine/translate')) return Promise.resolve(asJson(r.translate))
    if (u.endsWith('/api/engine/screen')) return Promise.resolve(asJson(r.screen))
    if (u.endsWith('/api/engine/backtest')) return Promise.resolve(asJson(backtestResponse))
    if (u.endsWith('/api/engine/risk')) return Promise.resolve(asJson({ as_of: '2026-10-13', items: r.risk ? r.risk(body.codes) : [] }))
    return Promise.resolve(asJson(r.keyword(body.query)))
  })
}
const search = async (text: string) => {
  fireEvent.change(screen.getByRole('textbox', { name: '분석할 종목명 또는 조건' }), { target: { value: text } })
  fireEvent.submit(screen.getByRole('search'))
  return screen.findByRole('dialog', { name: '실데이터 조건검색' })
}

it('a query the live search understands keeps its live results and gains daily risk and past performance', async () => {
  const fetchMock = routed({ translate: translated(), keyword: q => keywordResponse(q, '실시간 종목'), risk: codes => codes.map(c => riskItem(c, '실시간 종목')) })
  vi.stubGlobal('fetch', fetchMock)
  render(tree())
  const panel = await search('거래대금 100억 이상')
  const matches = await within(panel).findByRole('region', { name: '조건 일치 종목' })
  expect(within(matches).getByText('실시간 종목')).toBeTruthy()
  expect(within(matches).getByText('현재가 49,000원')).toBeTruthy()                     // live quote kept
  expect(await within(matches).findByText(/위험 낮음 · 5거래일 변동 보통 ±2.5%/)).toBeTruthy()  // daily evidence added
  expect(within(panel).getByRole('region', { name: '조건 과거 성과' }).textContent).toContain('양의 성과(통계적 확정 전)')
  expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith('/api/engine/screen'))).toBe(false)  // live search led
  expect(within(screen.getByRole('search')).getAllByRole('button').map(b => b.textContent)).toEqual(['AI 조건검색'])  // one box, one button
})

it('a daily-bar query the live search refuses is answered by the engine in the same dialog', async () => {
  const unsupported = (q: string) => ({ ...keywordResponse(q), outcome: 'UNSUPPORTED', appliedConditions: [], unsupportedTerms: ['해석할 수 없는 조건: 20일선 위 눌림'], matches: [] })
  vi.stubGlobal('fetch', routed({ translate: translated(), screen: screenResponse([engineRow('033780', '엔진 일봉 종목')]), keyword: unsupported }))
  render(tree())
  const panel = await search('20일선 위 눌림')
  const matches = await within(panel).findByRole('region', { name: '조건 일치 종목' })
  expect(within(matches).getByText('엔진 일봉 종목')).toBeTruthy()
  expect(within(panel).getByText('검색 완료 · AI 조건 해석')).toBeTruthy()
  expect(within(within(panel).getByRole('region', { name: '검색 조건과 범위' })).getByText('20일선 이격 +0.0% 초과', { selector: 'li' })).toBeTruthy()
  expect(within(panel).queryByText(/지원하지 않는 조건이 있습니다/)).toBeNull()             // the refusal is replaced, not stacked
})

it('a mixed flow + daily query keeps only stocks that pass both the live flow and the daily conditions', async () => {
  const keyword = (q: string) => q === '외국인 순매수'
    ? { ...keywordResponse(q), appliedConditions: ['외국인 순매수 > 0'], matches: [
        { ...keywordResponse(q).matches[0], code: '000001', name: '둘 다 맞는 종목' },
        { ...keywordResponse(q).matches[0], code: '000002', name: '수급만 맞는 종목' },
        { ...keywordResponse(q).matches[0], code: '000003', name: '위험 제외 종목' }] }
    : { ...keywordResponse(q), outcome: 'UNSUPPORTED', appliedConditions: [], unsupportedTerms: ['해석할 수 없는 조건'], matches: [] }
  const fetchMock = routed({
    translate: translated({ realtime_query: '외국인 순매수' }),
    screen: screenResponse([engineRow('009999', '일봉만 맞는 종목')], ['000001'], { '000003': ['high_vol'] }),
    risk: codes => codes.map(c => riskItem(c, c)),
    keyword,
  })
  vi.stubGlobal('fetch', fetchMock)
  render(tree())
  const panel = await search('외국인 순매수 20일선 위')
  const matches = await within(panel).findByRole('region', { name: '조건 일치 종목' })
  expect(within(matches).getByText('둘 다 맞는 종목')).toBeTruthy()
  expect(within(matches).queryByText('수급만 맞는 종목')).toBeNull()
  expect(within(matches).queryByText('일봉만 맞는 종목')).toBeNull()
  expect(within(panel).getByText(/수급 조건은 맞지만 위험 신호로 제외: 위험 제외 종목/)).toBeTruthy()
  expect(within(panel).getByText('외국인 순매수 > 0', { selector: 'li' })).toBeTruthy()
  const flowCall = fetchMock.mock.calls.find(([u, init]) => String(u).includes('/api/analysis/keyword-search') && JSON.parse(String(init?.body)).query === '외국인 순매수')
  expect(flowCall).toBeTruthy()
})

it.each(['UNAVAILABLE', 'UNSUPPORTED'] as const)('a mixed query with HTTP200 %s flow evidence never publishes daily-only condition matches', async outcome => {
  vi.stubGlobal('fetch', routed({
    translate: translated({ realtime_query: '외국인 순매수' }),
    screen: screenResponse([engineRow('009999', '검증 안 된 일봉 종목')]),
    keyword: q => ({ ...keywordResponse(q), outcome: q === '외국인 순매수' ? outcome : 'UNSUPPORTED',
      appliedConditions: [], unsupportedTerms: ['외국인 순매수 확인 불가'], warnings: ['실시간 수급을 확인하지 못했습니다.'], matches: [] }),
  }))
  render(tree())
  const panel = await search('외국인 순매수 60일선 위 저변동 10개')
  await within(panel).findByText('수급 조건 조회 실패')
  expect(within(panel).queryByRole('region', { name: '조건 일치 종목' })).toBeNull()
  expect(within(panel).queryByText('검증 안 된 일봉 종목')).toBeNull()
  expect(within(panel).queryByText('조건 일치 0건')).toBeNull()
  expect(within(panel).getByText(/실시간 수급을 확인하지 못했습니다/)).toBeTruthy()
  expect(within(panel).queryByText(/not iterable/)).toBeNull()
})

it('a mixed query with a flow transport failure never falls back to daily-only matches', async () => {
  vi.stubGlobal('fetch', routed({
    translate: translated({ realtime_query: '외국인 순매수' }),
    screen: screenResponse([engineRow('009999', '검증 안 된 일봉 종목')]),
    keyword: q => {
      if (q === '외국인 순매수') throw new Error('수급 연결 실패')
      return { ...keywordResponse(q), outcome: 'UNSUPPORTED', appliedConditions: [], unsupportedTerms: [], matches: [] }
    },
  }))
  render(tree())
  const panel = await search('외국인 순매수 60일선 위 저변동 10개')
  await within(panel).findByText('수급 조건 조회 실패')
  expect(within(panel).queryByRole('region', { name: '조건 일치 종목' })).toBeNull()
  expect(within(panel).queryByText('검증 안 된 일봉 종목')).toBeNull()
})

it('failed risk enrichment keeps the confirmed flow and daily intersection', async () => {
  vi.stubGlobal('fetch', routed({
    translate: translated({ realtime_query: '외국인 순매수' }),
    screen: screenResponse([engineRow('009999', '일봉만 맞는 종목')], ['000001']),
    keyword: q => q === '외국인 순매수'
      ? { ...keywordResponse(q, '교집합 검증 종목'), appliedConditions: ['외국인 순매수 > 0'], matches: [{ ...keywordResponse(q, '교집합 검증 종목').matches[0], code: '000001' }] }
      : { ...keywordResponse(q), outcome: 'UNSUPPORTED', appliedConditions: [], unsupportedTerms: [], matches: [] },
    risk: () => { throw new Error('일봉 위험 보강 실패') },
  }))
  render(tree())
  const panel = await search('외국인 순매수 60일선 위 저변동 10개')
  const matches = await within(panel).findByRole('region', { name: '조건 일치 종목' })
  expect(within(matches).getByText('교집합 검증 종목')).toBeTruthy()
  expect(within(matches).queryByText('일봉만 맞는 종목')).toBeNull()
  expect(within(panel).getByText('검색 완료 · 수급과 일봉 조건 결합')).toBeTruthy()
  expect(within(panel).queryByText('수급 조건 조회 실패')).toBeNull()
  expect(within(panel).getByText(/위험.*보강.*확인하지 못/)).toBeTruthy()
})

it('a mixed query preserves daily engine rank and requested top count after intersection', async () => {
  const strategy = { ...dailyStrategy, top_n: 2 }
  const liveCodes = ['000003', '000004', '000001', '000002']
  vi.stubGlobal('fetch', routed({
    translate: translated({ strategy, realtime_query: '외국인 순매수' }),
    screen: { ...screenResponse([engineRow('000002', '일봉 순위 1'), { ...engineRow('000001', '일봉 순위 2'), rank: 2 }], ['000004', '000003']), strategy },
    keyword: q => q === '외국인 순매수'
      ? { ...keywordResponse(q), appliedConditions: ['외국인 순매수 > 0'], matches: liveCodes.map(code => ({ ...keywordResponse(q).matches[0], code, name: `수급 종목 ${code}` })) }
      : { ...keywordResponse(q), outcome: 'UNSUPPORTED', appliedConditions: [], unsupportedTerms: [], matches: [] },
    risk: codes => codes.map(code => riskItem(code, code)),
  }))
  render(tree())
  const panel = await search('외국인 순매수 60일선 위 저변동 2개')
  await within(panel).findByText('검색 완료 · 수급과 일봉 조건 결합')
  const matches = within(panel).getByRole('region', { name: '조건 일치 종목' })
  expect(within(matches).getAllByRole('article').map(article => within(article).getByText(/수급 종목/).textContent))
    .toEqual(['수급 종목 000002', '수급 종목 000001'])
})

it('a mixed query labels past performance as daily conditions only rather than combined flow performance', async () => {
  vi.stubGlobal('fetch', routed({
    translate: translated({ realtime_query: '외국인 순매수' }),
    screen: screenResponse([engineRow('009999', '일봉 종목')]),
    keyword: q => q === '외국인 순매수'
      ? { ...keywordResponse(q), outcome: 'EMPTY', appliedConditions: ['외국인 순매수 > 0'], matches: [] }
      : { ...keywordResponse(q), outcome: 'UNSUPPORTED', appliedConditions: [], unsupportedTerms: [], matches: [] },
  }))
  render(tree())
  const panel = await search('외국인 순매수 60일선 위 저변동 10개')
  await within(panel).findByText('조건 일치 0건')
  const performance = within(panel).getByRole('region', { name: '조건 과거 성과' })
  expect(within(performance).getByText('일봉 조건만의 과거 성과')).toBeTruthy()
  expect(within(performance).getByText('수급 조건을 결합한 성과는 검증하지 않았습니다.')).toBeTruthy()
  expect(within(performance).getByText('20일선 이격 +0.0% 초과')).toBeTruthy()
})

it('a keyword-led mixed query also identifies backtest as daily-only performance', async () => {
  vi.stubGlobal('fetch', routed({
    translate: translated({ strategy: { ...dailyStrategy, title: '거래량 급증', when: { f: 'volr', op: '>=', v: 2 } },
      conditions: ['거래량 20일 평균의 2배 이상'], realtime_query: '외국인 순매수' }),
    keyword: q => ({ ...keywordResponse(q), appliedConditions: ['외국인 순매수 > 0', '거래량 20일 평균의 2배 이상'] }),
    risk: codes => codes.map(code => riskItem(code, '조건 검증 종목')),
  }))
  render(tree())
  const panel = await search('외국인 순매수 거래량 급증')
  const performance = await within(panel).findByRole('region', { name: '조건 과거 성과' })
  expect(within(performance).getByText('일봉 조건만의 과거 성과')).toBeTruthy()
  expect(within(performance).getByText('수급 조건을 결합한 성과는 검증하지 않았습니다.')).toBeTruthy()
  expect(within(performance).getByText('거래량 20일 평균의 2배 이상')).toBeTruthy()
})

it('an engine outage never blocks the live search', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string) => String(url).includes('/api/engine/')
    ? Promise.resolve(new Response('{"detail":"down"}', { status: 503 }))
    : Promise.resolve(asJson(keywordResponse('가격 5만원 이하')))))
  render(tree())
  const panel = await search('가격 5만원 이하')
  expect(await within(panel).findByText('조건 검증 종목')).toBeTruthy()
})

it('closing a keyword search aborts its request and rejects a late response after a newer search', async () => {
  let completeOld!: (response: Response) => void
  const fetchMock = vi.fn()
    .mockReturnValueOnce(new Promise<Response>(resolve => { completeOld = resolve }))
    .mockResolvedValueOnce(asJson(keywordResponse('가격 3만원 이하', '최신 검색 종목')))
  vi.stubGlobal('fetch', withEngine(fetchMock))
  render(tree())
  const input = screen.getByRole('textbox', { name: '분석할 종목명 또는 조건' })
  fireEvent.change(input, { target: { value: '가격 5만원 이하' } })
  fireEvent.submit(screen.getByRole('search'))
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
  fireEvent.click(within(screen.getByRole('dialog', { name: '실데이터 조건검색' })).getByRole('button', { name: '닫기' }))
  expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true)
  fireEvent.change(input, { target: { value: '가격 3만원 이하' } })
  fireEvent.submit(screen.getByRole('search'))
  await screen.findByText('최신 검색 종목')
  await act(async () => { completeOld(asJson(keywordResponse('가격 5만원 이하', '이전 검색 종목'))) })
  expect(screen.queryByText('이전 검색 종목')).toBeNull()
  expect(screen.getByText('최신 검색 종목')).toBeTruthy()
})

it('each repeated keyword search makes a new data request instead of reusing the analysis cache', async () => {
  const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(asJson(keywordResponse('가격 5만원 이하'))))
  vi.stubGlobal('fetch', withEngine(fetchMock))
  render(tree())
  fireEvent.change(screen.getByRole('textbox', { name: '분석할 종목명 또는 조건' }), { target: { value: '가격 5만원 이하' } })
  fireEvent.submit(screen.getByRole('search'))
  await screen.findByText('조건 검증 종목')
  fireEvent.click(within(screen.getByRole('dialog', { name: '실데이터 조건검색' })).getByRole('button', { name: '닫기' }))
  fireEvent.submit(screen.getByRole('search'))
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
})

const latestAnalysis = (outcome: 'COMPLETE' | 'PARTIAL' | 'EMPTY' | 'FAILED', publishedCount = 0) => ({
  checkedAt: '2026-10-12T10:32:00+09:00', outcome, agentsUsed: 2,
  agentsSucceeded: outcome === 'FAILED' ? 0 : outcome === 'PARTIAL' ? 1 : 2, publishedCount,
})

it('completed empty analysis is shown for every AI mode using the latest check instead of an old cache time', () => {
  const ids = ['short-term', 'swing', 'leaders', 'themes']
  const keys = ['shortTerm', 'swing', 'leaders', 'themes'] as const
  const modes = ['BREAKOUT', 'REVERSAL_EDGE', 'FLOW_LEADER', 'CATALYST_BURST']
  keys.forEach((key, index) => {
    main.sections[key] = {
      ...main.sections[key], slug: ids[index], mode: modes[index], sourceStatus: 'DATA_UNAVAILABLE',
      asOf: '2026-10-11T20:47:00+09:00', signals: [], latestAnalysis: latestAnalysis('EMPTY'),
    }
  })
  const { container } = render(tree())
  for (const id of ids) {
    const card = within(container.querySelector(`#${id}`) as HTMLElement)
    expect(card.getByText('오늘 10:32 분석 완료 · 현재 조건을 통과한 종목이 없습니다.')).toBeTruthy()
    expect(card.queryByText('조건검색 데이터가 준비되면 최신 후보가 표시됩니다.')).toBeNull()
    expect(card.queryByText(/20:47 기준/)).toBeNull()
  }
  expect(analyzeMock).not.toHaveBeenCalled()
})

it('preserves previous candidates and their dated source timestamp beside a newer empty analysis', () => {
  main.sections.swing = {
    ...section('이전 스윙 후보', '2026-10-11T20:47:00+09:00'), key: 'swing', slug: 'swing', mode: 'REVERSAL_EDGE',
    sourceStatus: 'STALE_CACHE', latestAnalysis: latestAnalysis('EMPTY'),
  }
  const { container } = render(tree())
  const card = within(container.querySelector('#swing') as HTMLElement)
  expect(card.getByText('이전 스윙 후보')).toBeTruthy()
  expect(card.getByText(/2026\.10\.11 20:47 기준/)).toBeTruthy()
  expect(card.getByText(/오늘 10:32 분석 완료 · 현재 조건을 통과한 종목이 없습니다\./)).toBeTruthy()
  expect(card.getByText(/표에는 이전에 저장한 후보가 표시됩니다\./)).toBeTruthy()
})

it('distinguishes a failed analysis from a successful search with no eligible stocks', () => {
  main.sections.swing = {
    ...main.sections.swing, mode: 'REVERSAL_EDGE', sourceStatus: 'DATA_UNAVAILABLE', signals: [], latestAnalysis: latestAnalysis('FAILED'),
  }
  const { container } = render(tree())
  const card = within(container.querySelector('#swing') as HTMLElement)
  expect(card.getByText('오늘 10:32 분석 결과를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.')).toBeTruthy()
  expect(card.queryByText(/분석 완료 · 현재 조건을 통과한 종목이 없습니다/)).toBeNull()
})

it('shows partial agent completion without claiming that every agent completed', () => {
  main.sections.swing = {
    ...section('확인된 스윙 후보', '2026-10-12T10:32:00+09:00'), mode: 'REVERSAL_EDGE',
    sourceStatus: 'PRECOMPUTED', latestAnalysis: latestAnalysis('PARTIAL', 1),
  }
  const { container } = render(tree())
  const card = within(container.querySelector('#swing') as HTMLElement)
  expect(card.getByText('확인된 스윙 후보')).toBeTruthy()
  expect(card.getByText('오늘 10:32 일부 AI 분석 완료 (1/2) · 확인된 결과만 표시합니다.')).toBeTruthy()
})

it('keeps the preparation guidance when analysis metadata is absent or its timestamp cannot be verified', () => {
  main.sections.swing = { ...main.sections.swing, mode: 'REVERSAL_EDGE', sourceStatus: 'DATA_UNAVAILABLE', signals: [] }
  main.sections.themes = {
    ...main.sections.themes, mode: 'CATALYST_BURST', sourceStatus: 'DATA_UNAVAILABLE', signals: [],
    latestAnalysis: { ...latestAnalysis('EMPTY'), checkedAt: 'invalid-date' },
  }
  const { container } = render(tree())
  for (const id of ['swing', 'themes']) {
    const card = within(container.querySelector(`#${id}`) as HTMLElement)
    expect(card.getByText('조건검색 데이터가 준비되면 최신 후보가 표시됩니다.')).toBeTruthy()
    expect(card.queryByText(/분석 완료/)).toBeNull()
  }
})

it.each([
  ['REALTIME_SCAN', 'EMPTY'], ['REALTIME_SCAN', 'FAILED'],
  ['LEADER_SCAN', 'EMPTY'], ['LEADER_SCAN', 'FAILED'],
] as const)('keeps independent %s candidates separate from a previous AI %s attempt', (sourceStatus, outcome) => {
  const current = section('실시간 후보 1', '2026-10-12T10:40:00+09:00')
  const snapshot: ConditionSectionResponse = {
    ...current, key: 'leaders', slug: 'leaders', mode: 'FLOW_LEADER', sourceStatus,
    signals: [1, 2, 3].map(index => ({ ...current.signals[0], stockName: `실시간 후보 ${index}` })),
    latestAnalysis: latestAnalysis(outcome),
  }
  expect(latestAnalysisPresentation(snapshot)).toBeNull()
  main.sections.leaders = snapshot
  const { container } = render(tree())
  const card = within(container.querySelector('#leaders') as HTMLElement)
  for (const index of [1, 2, 3]) expect(card.getByText(`실시간 후보 ${index}`)).toBeTruthy()
  expect(card.queryByText(/조건을 통과한 종목이 없습니다|분석 결과를 확인하지 못했습니다/)).toBeNull()
})
