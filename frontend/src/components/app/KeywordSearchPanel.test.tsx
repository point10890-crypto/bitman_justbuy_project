// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { KeywordSearchPanel } from './KeywordSearchPanel'
import type { KeywordSearchResponse } from '../../api/keywordSearchApi'

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
