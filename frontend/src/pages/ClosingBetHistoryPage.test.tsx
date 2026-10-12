// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { fetchClosingBetPerformance, type JonggaPerformanceResponse, type JonggaPerformanceRow } from '../api/conditionApi'
import ClosingBetHistoryPage from './ClosingBetHistoryPage'

vi.mock('../contexts/AuthContext', () => ({ getStoredToken: () => 'fixture-admin', useAuth: () => ({ user: { role: 'ADMIN', subscription: 'pro' } }) }))
vi.mock('../api/conditionApi', () => ({ fetchClosingBetPerformance: vi.fn() }))
const row = (result: string): JonggaPerformanceRow => ({rank:1,stockName:'팬오션',stockCode:'028670',grade:'A',score:12,entryPrice:'6,470',targetPrice:'6,793',stopLoss:'6,275',closePrice:'-',closeReturnPct:'-',maxReturnPct:'-',benchmarkReturnPct:'-',excessReturnPct:'-',hitTarget:false,hitStop:false,result})
const response = (rows:JonggaPerformanceRow[]):JonggaPerformanceResponse => ({from:'2026-10-01',to:'2026-10-12',mode:'JONGGA_V2',title:'종가매매',totalSignals:rows.length,verifiedCount:0,wins:0,losses:0,flats:0,avgCloseReturnPct:'-',avgMaxReturnPct:'-',winRate:'-',targetHitRate:'-',stopHitRate:'-',avgBenchmarkReturnPct:'-',avgExcessReturnPct:'-',marketBeatRate:'-',days:[{date:'2026-10-08',verified:false,avgCloseReturnPct:'-',rows}],note:'실측 가능한 결과만 집계'})

describe('closing result verification states', () => {
  afterEach(cleanup)
  it('labels unmeasurable rows separately from results that are still waiting', async () => {
    vi.mocked(fetchClosingBetPerformance).mockResolvedValue(response([row('검증불가')]))
    render(<MemoryRouter><ClosingBetHistoryPage /></MemoryRouter>)
    expect(await screen.findByText('전체 검증불가 · 성과 집계 제외')).toBeTruthy()
    expect(screen.queryByText('익일 성과 미검증')).toBeNull()
  })
  it('preserves the waiting label when there is no invalid measurement', async () => {
    vi.mocked(fetchClosingBetPerformance).mockResolvedValue(response([row('미검증')]))
    render(<MemoryRouter><ClosingBetHistoryPage /></MemoryRouter>)
    expect(await screen.findByText('익일 성과 검증 대기')).toBeTruthy()
  })
})
