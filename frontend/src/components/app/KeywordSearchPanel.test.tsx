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
