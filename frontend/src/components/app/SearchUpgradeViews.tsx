// 종목·조건 검색 결과 창에 들어가는 AI 조건검색 조각. 결과 창의 기존 클래스(keyword-*)만 쓴다.
import type { BacktestResult, RiskCheckItem, ScreenRow } from '../../api/engineApi'

const won = (v: number | null | undefined) =>
  typeof v === 'number' && Number.isFinite(v) ? `${Math.round(v).toLocaleString('ko-KR')}원` : '미확인'
const pct = (v: number | null | undefined, digits = 2) =>
  typeof v === 'number' && Number.isFinite(v) ? `${v > 0 ? '+' : ''}${v.toFixed(digits)}%` : '미확인'
const ratioPct = (v: number | null | undefined) =>
  typeof v === 'number' && Number.isFinite(v) ? pct(v * 100) : '미확인'
const times = (v: number | null | undefined) =>
  typeof v === 'number' && Number.isFinite(v) ? `${v.toFixed(1)}배` : '미확인'

function riskLine(risk: RiskCheckItem['risk']) {
  if (!risk) return '위험 미확인'
  const band = risk.band
  return `위험 ${risk.grade}${risk.flags.length ? ` · ${risk.flags.map(f => f.label).join(', ')}` : ''}`
    + (band ? ` · 5거래일 변동 보통 ±${band.typical_pct}%, 넓게 ${pct(band.wide_low_pct, 1)} ~ ${pct(band.wide_high_pct, 1)} (목표가 아님)` : '')
}

/** 실데이터 종목 카드 아래에 붙는 일봉 근거와 위험 등급. */
export function DailyEvidence({ item, asOf }: { item: RiskCheckItem; asOf: string | null }) {
  if (!item.found) return null
  return (
    <>
      <p className="keyword-match-source">일봉{asOf ? ` ${asOf}` : ''} 기준 · {riskLine(item.risk)}</p>
      {item.explanation && item.explanation.length > 0 && (
        <ul className="keyword-match-evidence" aria-label={`${item.name ?? item.code} 일봉 근거`}>
          {item.explanation.slice(0, 6).map(e => <li key={e}><strong>{e}</strong></li>)}
        </ul>
      )}
    </>
  )
}

/** 실데이터 시세 없이 일봉 조건으로만 찾은 종목 카드. */
export function EngineMatchCard({ r, asOf }: { r: ScreenRow; asOf: string }) {
  const s = r.snapshot
  return (
    <article className="keyword-match">
      <div className="keyword-match-heading"><strong>{r.name ?? r.code}</strong><span>{r.code} · {r.market || '시장 미확인'}{r.sector ? ` · ${r.sector}` : ''}</span></div>
      <div className="keyword-match-values">
        <span>종가 {won(s.close)}</span>
        <span>등락률 {ratioPct(s.r1)}</span>
        <span>거래량 20일 평균의 {times(s.volr)}</span>
        <span>거래대금 {won(s.val_today)}</span>
      </div>
      <p className="keyword-match-source">일봉 {asOf} 확정 종가 기준 · {riskLine(r.risk)}</p>
      <ul className="keyword-match-evidence">
        {r.explanation.map(e => <li key={e}><strong>{e}</strong></li>)}
      </ul>
    </article>
  )
}

/** 검색 조건을 과거에 그대로 썼다면 — 같은 날 다른 종목 대비 5거래일 성과. */
export function PastPerformance({ backtest, conditions = [], dailyOnly = false }: { backtest: BacktestResult; conditions?: string[]; dailyOnly?: boolean }) {
  const h5 = backtest.horizons?.['5']
  if (!h5) return null
  return (
    <section className="keyword-search-scope" aria-label="조건 과거 성과">
      <h3>{dailyOnly ? '일봉 조건만의 과거 성과' : '이 조건의 과거 성과'}</h3>
      {dailyOnly && <p>수급 조건을 결합한 성과는 검증하지 않았습니다.</p>}
      {conditions.length > 0 && <ul>{conditions.map(c => <li key={c}>{c}</li>)}</ul>}
      <p><strong>{h5.verdict.label}</strong> — {h5.verdict.detail}</p>
      <p>5거래일 보유, 같은 날 다른 종목 대비: 탐색 구간 {pct(h5.discovery.excess_vs_universe_pct)} · 확인 구간 {pct(h5.confirm.excess_vs_universe_pct)}</p>
      <p>확인 구간 지수 대비 {pct(h5.confirm.excess_vs_index_pct)} · 비용 후 {pct(h5.confirm.net_return_pct)} · 적중률 {h5.confirm.hit_rate_pct ?? '미확인'}%</p>
      {backtest.period?.all && <p>검증 기간 {backtest.period.all[0]} ~ {backtest.period.all[1]} · 다음 날 시가 진입 · 왕복 비용 0.23% 반영</p>}
    </section>
  )
}
