// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { fetchConditionSection, type ConditionSectionResponse, type MainConditionResponse } from '../api/conditionApi'
import HomePage from './HomePage'

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
beforeEach(() => { vi.clearAllMocks(); main = feed('MAIN_0850', '2026-05-18T08:50:00+09:00') })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

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
  fetchedAt: '2026-10-12T10:00:00+09:00', sourceStatus: 'VERIFIED_QUOTES', warnings: [],
  matches: [{ code: '005930', name, market: 'KOSPI', currentPrice: 49000, changePct: null, volume: null, tradingValueKrw: null,
    quoteSource: 'KIS', quoteCheckedAt: '2026-10-12T10:00:00+09:00', observedAt: null,
    evidence: [{ condition: '현재가 50,000원 이하', observedValue: '49000', unit: '원', source: 'KIS', observedAt: null }] }],
})
const asJson = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })

it('hero searches the original condition through the data API without asking an AI to originate stocks', async () => {
  const fetchMock = vi.fn().mockResolvedValue(asJson(keywordResponse('가격 5만원 이하')))
  vi.stubGlobal('fetch', fetchMock)
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

it('closing a keyword search aborts its request and rejects a late response after a newer search', async () => {
  let completeOld!: (response: Response) => void
  const fetchMock = vi.fn()
    .mockReturnValueOnce(new Promise<Response>(resolve => { completeOld = resolve }))
    .mockResolvedValueOnce(asJson(keywordResponse('가격 3만원 이하', '최신 검색 종목')))
  vi.stubGlobal('fetch', fetchMock)
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
  vi.stubGlobal('fetch', fetchMock)
  render(tree())
  fireEvent.change(screen.getByRole('textbox', { name: '분석할 종목명 또는 조건' }), { target: { value: '가격 5만원 이하' } })
  fireEvent.submit(screen.getByRole('search'))
  await screen.findByText('조건 검증 종목')
  fireEvent.click(within(screen.getByRole('dialog', { name: '실데이터 조건검색' })).getByRole('button', { name: '닫기' }))
  fireEvent.submit(screen.getByRole('search'))
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
})
