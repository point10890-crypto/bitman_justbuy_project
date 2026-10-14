import { useEffect, useState } from 'react'
import { engineApi, type BacktestResult, type ScreenResult, type ScreenRow, type Translation } from '../../api/engineApi'

/**
 * 종목·조건 검색 결과 창 안의 AI 조건검색 결과.
 *
 * 같은 검색어를 조건검색 엔진이 일봉 조건식으로 바꿔 검색하고, 그 조건의 과거 성과까지 붙인다.
 * 별도 화면을 만들지 않고 실데이터 검색 결과와 같은 구성(조건 → 범위 → 제한 → 일치 종목)과 스타일을 쓴다.
 */
type State =
  | { phase: 'loading' }
  | { phase: 'done'; translation: Translation; screen: ScreenResult | null }
  | { phase: 'error'; message: string }

const won = (v: number | null | undefined) =>
  typeof v === 'number' && Number.isFinite(v) ? `${Math.round(v).toLocaleString('ko-KR')}원` : '미확인'
const pct = (v: number | null | undefined, digits = 2) =>
  typeof v === 'number' && Number.isFinite(v) ? `${v > 0 ? '+' : ''}${v.toFixed(digits)}%` : '미확인'
const ratioPct = (v: number | null | undefined) =>
  typeof v === 'number' && Number.isFinite(v) ? pct(v * 100) : '미확인'
const times = (v: number | null | undefined) =>
  typeof v === 'number' && Number.isFinite(v) ? `${v.toFixed(1)}배` : '미확인'

export function KeywordEngineResults({ query }: { query: string }) {
  const [state, setState] = useState<State>({ phase: 'loading' })
  const [backtest, setBacktest] = useState<BacktestResult | null>(null)
  const [backtestFailed, setBacktestFailed] = useState(false)

  useEffect(() => {
    const ctl = new AbortController()
    setState({ phase: 'loading' })
    setBacktest(null)
    setBacktestFailed(false)
    ;(async () => {
      try {
        const translation = await engineApi.translate(query, ctl.signal)
        if (!translation.strategy) {
          setState({ phase: 'done', translation, screen: null })
          return
        }
        const screen = await engineApi.screen({ strategy: translation.strategy }, ctl.signal)
        if (ctl.signal.aborted) return
        setState({ phase: 'done', translation, screen })
        try {
          const bt = await engineApi.backtest({ strategy: translation.strategy })
          if (!ctl.signal.aborted) setBacktest(bt)
        } catch {
          if (!ctl.signal.aborted) setBacktestFailed(true)
        }
      } catch (e) {
        if (!ctl.signal.aborted) setState({ phase: 'error', message: e instanceof Error ? e.message : 'AI 조건검색을 처리하지 못했습니다.' })
      }
    })()
    return () => ctl.abort()
  }, [query])

  if (state.phase === 'loading') {
    return <div className="analysis-loading" role="status">AI가 검색어를 조건식으로 바꿔 일봉 데이터에서 찾고 있습니다.</div>
  }
  if (state.phase === 'error') {
    return <div className="auth-error" role="alert">AI 조건검색: {state.message}</div>
  }

  const { translation: t, screen } = state
  const limits = [
    ...(t.fallback_reason ? [t.fallback_reason] : []),
    ...t.warnings,
    ...t.unsupported.map(u => `적용하지 못한 조건: ${u}`),
    ...(t.unparsed.length ? [`이해하지 못한 단어: ${t.unparsed.join(', ')}`] : []),
    ...(screen?.regime_blocked ? [`현재 시장 국면(${screen.regime.label})에서는 이 조건을 쓰지 않습니다. 검증 결과 이 국면에서 성과가 나빴습니다.`] : []),
    ...(screen && screen.excluded.length
      ? [`위험 신호로 제외: ${screen.excluded.slice(0, 6).map(r => `${r.name ?? r.code}(${r.risk.flags.map(f => f.label).join('·')})`).join(', ')}${screen.counts.guard_excluded > 6 ? ` 외 ${screen.counts.guard_excluded - 6}개` : ''}`]
      : []),
  ]
  const h5 = backtest?.horizons?.['5']

  return (
    <>
      <section className="keyword-search-scope" aria-label="AI 조건 해석">
        <h3>AI 조건 해석 · 일봉 조건검색</h3>
        {t.conditions.length > 0
          ? <ul>{t.conditions.map(c => <li key={c}>{c}</li>)}</ul>
          : <p>{t.error ?? '일봉 데이터로 만들 수 있는 조건이 없습니다.'}</p>}
        {t.assumptions.map(a => <p key={a}>{a}</p>)}
        {screen && (
          <>
            <p>검색 범위: {screen.as_of} 확정 종가 · 시장 국면 {screen.regime.label}</p>
            <p>분석 대상 {screen.counts.universe.toLocaleString('ko-KR')}개 · 조건 통과 {screen.counts.passing.toLocaleString('ko-KR')}개 · 위험 신호 제외 {screen.counts.guard_excluded.toLocaleString('ko-KR')}개 · 조건 일치 {screen.counts.published.toLocaleString('ko-KR')}개</p>
          </>
        )}
      </section>

      {limits.length > 0 && (
        <section className="keyword-search-warnings" aria-label="AI 조건검색 제한과 확인 필요 사항">
          {limits.map(l => <p key={l}>{l}</p>)}
        </section>
      )}

      {screen && (h5 || backtestFailed) && (
        <section className="keyword-search-scope" aria-label="조건 과거 성과">
          <h3>이 조건의 과거 성과</h3>
          {h5 ? (
            <>
              <p><strong>{h5.verdict.label}</strong> — {h5.verdict.detail}</p>
              <p>5거래일 보유, 같은 날 다른 종목 대비: 탐색 구간 {pct(h5.discovery.excess_vs_universe_pct)} · 확인 구간 {pct(h5.confirm.excess_vs_universe_pct)}</p>
              <p>확인 구간 지수 대비 {pct(h5.confirm.excess_vs_index_pct)} · 비용 후 {pct(h5.confirm.net_return_pct)} · 적중률 {h5.confirm.hit_rate_pct ?? '미확인'}%</p>
              {backtest?.period.all && <p>검증 기간 {backtest.period.all[0]} ~ {backtest.period.all[1]} · 다음 날 시가 진입 · 왕복 비용 0.23% 반영</p>}
            </>
          ) : <p>과거 성과를 불러오지 못했습니다.</p>}
        </section>
      )}

      {screen && screen.results.length > 0 && (
        <section className="keyword-search-matches" aria-label="일봉 조건 일치 종목">
          <h3>일봉 조건 일치 종목</h3>
          {screen.results.map(r => <EngineMatch key={r.code} r={r} />)}
        </section>
      )}
      {screen && screen.results.length === 0 && !screen.regime_blocked && (
        <p className="keyword-search-empty">일봉 조건에 맞는 종목이 없습니다.</p>
      )}
    </>
  )
}

function EngineMatch({ r }: { r: ScreenRow }) {
  const s = r.snapshot
  const band = r.risk.band
  return (
    <article className="keyword-match">
      <div className="keyword-match-heading"><strong>{r.name ?? r.code}</strong><span>{r.code} · {r.market || '시장 미확인'}{r.sector ? ` · ${r.sector}` : ''}</span></div>
      <div className="keyword-match-values">
        <span>종가 {won(s.close)}</span>
        <span>등락률 {ratioPct(s.r1)}</span>
        <span>거래량 20일 평균의 {times(s.volr)}</span>
        <span>거래대금 {won(s.val_today)}</span>
      </div>
      <p className="keyword-match-source">
        위험 {r.risk.grade}{r.risk.flags.length ? ` · ${r.risk.flags.map(f => f.label).join(', ')}` : ''}
        {band && ` · 5거래일 변동 보통 ±${band.typical_pct}%, 넓게 ${pct(band.wide_low_pct, 1)} ~ ${pct(band.wide_high_pct, 1)} (목표가 아님)`}
      </p>
      <ul className="keyword-match-evidence">
        {r.explanation.map(e => <li key={e}><strong>{e}</strong></li>)}
      </ul>
    </article>
  )
}
