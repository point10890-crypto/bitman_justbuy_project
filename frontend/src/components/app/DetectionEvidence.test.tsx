// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { DetectionEvidence } from './DetectionEvidence'
import type { ConditionSignal } from '../../api/conditionApi'

afterEach(cleanup)
it('distinguishes measured passes, failures and unverified AI assertions with provenance', () => {
  const signal: ConditionSignal = {
    section: 'shortTerm', mode: 'BREAKOUT', rank: 1, stockName: '테스트종목', stockCode: '005930',
    capturePrice: '100', currentPrice: '100', highPrice: '-', stopLoss: '-', maxReturnPct: '-', status: '후보',
    ruleScore: 0, aiScore: 90, finalScore: 90, summary: 'AI: 정배열·실적 성장 통과',
    evidence: ['정배열'], riskFlags: ['돌파 실패'], invalidation: 'VWAP 이탈', capturedAt: '2026-10-14T09:30:00+09:00',
    conditionChecks: [
      { id: 'liquidity.0', category: 'liquidity', criterion: '누적 100억 이상', status: 'PASS', observedValue: '120억', source: 'KIS', observedAt: '2026-10-14T10:00:00+09:00' },
      { id: 'technical.2', category: 'technical', criterion: '상승률 3~15%', status: 'FAIL', observedValue: '18%', source: 'KIS', observedAt: '2026-10-14T09:30:00+09:00' },
    ],
  }
  render(<DetectionEvidence signal={signal} onAnalyze={vi.fn()} />)
  const panel = within(screen.getByRole('region', { name: '테스트종목 검출 근거' }))
  expect(panel.getAllByText('통과')).toHaveLength(1)
  expect(panel.getAllByText('미충족')).toHaveLength(1)
  expect(panel.getAllByText('미확인')).toHaveLength(3)
  expect(panel.getByText('120억')).toBeTruthy()
  expect(panel.getByText(/출처: KIS.*10:00/)).toBeTruthy()
  expect(panel.getByText('VWAP 이탈')).toBeTruthy()
  expect(panel.getByText(/AI: 정배열/).closest('details')?.open).toBe(false)
})
