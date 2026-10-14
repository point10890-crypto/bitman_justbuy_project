// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ConditionLabPage from './ConditionLabPage'

const health = {
  state: 'ready', error: null, data_version: '2026-10-13:2591', last_session: '2026-10-13', codes: 2591,
  provisional_dates: ['2026-10-14'], unrepairable_provisional: { rows: 11790, codes: 182 },
  last_final_date: '2026-10-13', translator: 'openai→gemini→rules',
}
const regime = { as_of: '2026-10-13', code: 'DOWN_TREND', label: '지수가 60일선 아래에서 20일 하락', history: [] }
const strategies = {
  evidence_basis: '근거 수치: 탐색 / 확인 구간',
  strategies: [
    { id: 'lowvol_uptrend', status: 'candidate', status_label: '후보 (확정 전)', title: '저변동 우상향', summary: '60일선 위',
      evidence: '5일 +0.47% / +0.22%', when: { f: 'gap_ma60', op: '>', v: 0 }, rank_by: { f: 'vol20', desc: false }, top_n: 10 },
    { id: 'chase_gainers', status: 'retired', status_label: '검증 탈락', title: '급등주 추격', summary: '당일 상승률 상위',
      evidence: '5일 -2.58% / -4.02%', when: { f: 'r1', op: '>=', v: 0.05 }, rank_by: { f: 'r1' }, top_n: 5 },
  ],
}
const strategy = {
  id: 'adhoc_1', title: '20일선 위 눌림', when: { all: [{ f: 'gap_ma20', op: '>', v: 0 }] },
  rank_by: { f: 'quality_score', desc: true }, top_n: 5, guard: { exclude_risk: ['chase', 'high_vol'] },
}
const translation = {
  strategy, source: 'rules', conditions: ['20일선 이격 +0.0% 초과'], assumptions: ['눌림 = 20일 고가 대비 -3~-10%'],
  warnings: [], unsupported: ['투자자별 수급 데이터는 아직 엔진에 없습니다'], unparsed: [], error: null,
  fallback_reason: 'AI 번역 호출에 실패해 규칙 번역을 사용했습니다',
}
const row = (code: string, name: string, flags: Array<{ id: string; label: string }> = []) => ({
  code, name, market: 'KOSPI', sector: '담배 제조업', stage: flags.length ? 'GUARD_EXCLUDED' : 'PUBLISHED',
  excluded_by: flags.map((f) => f.id), rank: 1, snapshot: {}, explanation: ['20일 고가에 붙어 있음', 'ATR 2.2% · 차분함'],
  risk: { flags: flags.map((f) => ({ ...f, rule: '규칙', evidence: '근거' })), score: flags.length * 2, grade: flags.length ? '보통' : '낮음',
    band: { atr_pct: 2.24, typical_pct: 2.5, wide_low_pct: -6.1, wide_high_pct: 7.8, note: '목표가가 아닙니다.' } },
})
const screenResult = {
  strategy, conditions: ['20일선 이격 +0.0% 초과'], as_of: '2026-10-13', regime, regime_blocked: false,
  counts: { universe: 868, passing: 278, guard_excluded: 1, eligible: 192, published: 1 },
  results: [row('033780', 'KT&G')], excluded: [row('039440', '급등주', [{ id: 'chase', label: '급등 추격' }])],
  below_top_n: [], data_version: '2026-10-13:2591', run_id: 'x',
}
const win = (excess: number) => ({ signal_days: 200, excess_vs_universe_pct: excess, excess_vs_index_pct: -1, net_return_pct: 0.1, hit_rate_pct: 51, t_stat: 1.2 })
const backtestResult = {
  conditions: [], entry: 'open1', method: '같은 날 유니버스 평균 대비', data_version: 'v',
  period: { all: ['2024-01-02', '2026-10-13', 675] },
  horizons: { 5: { all: win(0.38), discovery: win(0.47), confirm: win(0.22),
    verdict: { code: 'positive_unconfirmed', label: '양의 성과(통계적 확정 전)', detail: '확인 구간 유의성이 낮습니다' } } },
  by_regime: { UP_TREND: { signal_days: 300, excess_vs_universe_pct: 0.57 }, DOWN_TREND: { signal_days: 140, excess_vs_universe_pct: -0.08 } },
  by_year: { 2025: { signal_days: 240, excess_vs_universe_pct: 0.19 } },
  curve: [{ date: '2024-06-03', excess_pct: 0.5, strategy_pct: 1, index_pct: -1 }, { date: '2026-10-13', excess_pct: 9, strategy_pct: 4, index_pct: 2 }],
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function route(url: string, init?: RequestInit) {
  const path = url.replace(/^\/api\/engine/, '')
  if (path === '/health') return json(health)
  if (path === '/regime') return json(regime)
  if (path === '/strategies') return json(strategies)
  if (path === '/translate') return json(translation)
  if (path === '/screen') return json(screenResult)
  if (path === '/backtest') return json(backtestResult)
  if (path === '/risk') {
    const { codes } = JSON.parse(String(init?.body))
    return json({ as_of: '2026-10-13', items: codes.map((c: string) => c === '999999' ? { code: c, found: false } : { ...row(c, '삼성전자'), found: true, in_universe: true }) })
  }
  return json({ detail: 'not found' }, 404)
}

beforeEach(() => { vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => Promise.resolve(route(url, init)))) })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

const bodyOf = (path: string) => {
  const call = vi.mocked(fetch).mock.calls.find(([u]) => u === `/api/engine${path}`)
  return call ? JSON.parse(String(call[1]?.body)) : undefined
}

describe('condition lab', () => {
  it('shows data freshness, regime and the provisional-bar policy', async () => {
    render(<ConditionLabPage />)
    expect(await screen.findByText('지수가 60일선 아래에서 20일 하락')).toBeTruthy()
    const status = screen.getByLabelText('엔진 상태')
    expect(status.textContent).toContain('2026-10-13')
    expect(status.textContent).toContain('2026-10-14')                 // today's mid-session bar is excluded
    expect(status.textContent).toContain('182종목')
  })

  it('translates, screens and explains what it assumed and refused', async () => {
    render(<ConditionLabPage />)
    await screen.findByText('지수가 60일선 아래에서 20일 하락')
    fireEvent.change(screen.getByLabelText('찾고 싶은 종목을 말로 적어 주세요'), { target: { value: '외국인 순매수 20일선 위 눌림' } })
    fireEvent.click(screen.getByRole('button', { name: '검색' }))

    expect(await screen.findByText('KT&G')).toBeTruthy()
    expect(bodyOf('/translate')).toEqual({ text: '외국인 순매수 20일선 위 눌림' })
    expect(bodyOf('/screen')).toEqual({ strategy })                     // the validated DSL, not the raw text
    const card = screen.getByLabelText('조건 번역 결과')
    expect(card.textContent).toContain('규칙 번역')
    expect(card.textContent).toContain('AI 번역 호출에 실패')
    expect(card.textContent).toContain('수급 데이터는 아직 엔진에 없습니다')
    expect(screen.getByLabelText('단계별 종목 수').textContent).toContain('868')
    expect(screen.getByText('보통 ±2.5%')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: /위험 신호로 뺀 종목 1개 보기/ }))
    expect(screen.getByText('급등주')).toBeTruthy()
  })

  it('runs a backtest and shows both windows and the verdict', async () => {
    render(<ConditionLabPage />)
    await screen.findByText('지수가 60일선 아래에서 20일 하락')
    fireEvent.click(screen.getByRole('button', { name: '20일선 위 눌림인데 거래량 1.5배 붙은 종목' }))
    await screen.findByText('KT&G')
    fireEvent.click(screen.getByRole('button', { name: '과거 성과 검증' }))
    const panel = await screen.findByLabelText('과거 성과 검증')
    expect(panel.textContent).toContain('5일 판정: 양의 성과(통계적 확정 전)')
    expect(panel.textContent).toContain('탐색 구간')
    expect(panel.textContent).toContain('+0.22%')
    expect(within(panel).getByRole('img', { name: '누적 성과 곡선' })).toBeTruthy()
  })

  it('never offers today-search for a retired strategy, only its evidence', async () => {
    render(<ConditionLabPage />)
    await screen.findByText('지수가 60일선 아래에서 20일 하락')
    fireEvent.click(screen.getByRole('tab', { name: '검증된 전략' }))
    const retired = (await screen.findByText('급등주 추격')).closest('article')!
    expect(within(retired).queryByRole('button', { name: '오늘 검색' })).toBeNull()
    fireEvent.click(within(retired).getByRole('button', { name: '탈락 근거 보기' }))
    await screen.findByLabelText('과거 성과 검증')
    expect(bodyOf('/backtest')).toEqual({ strategy_id: 'chase_gainers' })
    expect(screen.queryByRole('button', { name: /^(오늘|다시) 검색$/ })).toBeNull()
    expect(screen.getByText('검증 탈락 · 오늘 검색은 하지 않습니다')).toBeTruthy()
    expect(vi.mocked(fetch).mock.calls.some(([u]) => u === '/api/engine/screen')).toBe(false)
  })

  it('checks risk for typed codes and reports unknown ones', async () => {
    render(<ConditionLabPage />)
    await screen.findByText('지수가 60일선 아래에서 20일 하락')
    fireEvent.click(screen.getByRole('tab', { name: '위험 점검' }))
    fireEvent.change(screen.getByLabelText(/종목코드/), { target: { value: '005930, 999999 005930' } })
    fireEvent.click(screen.getByRole('button', { name: '점검' }))
    expect(await screen.findByText('삼성전자')).toBeTruthy()
    expect(screen.getByText('데이터 없음')).toBeTruthy()
    expect(bodyOf('/risk')).toEqual({ codes: ['005930', '999999'] })   // de-duplicated
  })

  it('says plainly when the engine is not running', async () => {
    vi.mocked(fetch).mockImplementation(() => Promise.reject(new TypeError('Failed to fetch')))
    render(<ConditionLabPage />)
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('조건검색 엔진에 연결할 수 없습니다'))
  })
})
