// 조건검색 엔진 결과 화면 조각 — 홈 검색 패널과 조건검색 랩이 함께 쓴다.
import { useMemo, useState } from 'react'
import {
  EngineError,
  type BacktestResult, type HorizonStats, type RiskView, type ScreenResult, type ScreenRow, type Translation, type WindowStats,
} from '../../api/engineApi'
import '../../pages/ConditionLabPage.css'

export const VERDICT_TONE: Record<string, string> = {
  robust_positive: 'good', positive_unconfirmed: 'info', unstable: 'flat', negative_unconfirmed: 'flat', robust_negative: 'bad', insufficient: 'flat',
}
const RISK_TONE: Record<string, string> = { 낮음: 'good', 보통: 'flat', 높음: 'bad', '매우 높음': 'bad' }
const WINDOW_LABEL: Record<string, string> = { discovery: '탐색 구간', confirm: '확인 구간', all: '전체' }

const num = (v: number | null | undefined, digits = 2) =>
  v === null || v === undefined || !Number.isFinite(v) ? '—' : v.toFixed(digits)
const signed = (v: number | null | undefined, digits = 2, unit = '%') =>
  v === null || v === undefined || !Number.isFinite(v) ? '—' : `${v > 0 ? '+' : ''}${v.toFixed(digits)}${unit}`
const tone = (v: number | null | undefined) => (v === null || v === undefined || v === 0 ? '' : v > 0 ? 'pos' : 'neg')
export const errorText = (e: unknown) => (e instanceof EngineError || e instanceof Error ? e.message : '알 수 없는 오류')

export function TranslationCard({ t }: { t: Translation }) {
  return (
    <div className="lab-translation" aria-label="조건 번역 결과">
      <div className="lab-translation-head">
        <span className={`lab-chip ${t.source === 'rules' ? 'flat' : 'info'}`}>
          {t.source === 'rules' ? '규칙 번역' : `AI 번역 · ${t.source}`}
        </span>
        {t.fallback_reason && <span className="lab-muted">{t.fallback_reason}</span>}
      </div>
      {t.error && <p className="lab-warn">{t.error}</p>}
      {t.conditions.length > 0 && (
        <ul className="lab-conditions" aria-label="해석한 조건">
          {t.conditions.map((c) => <li key={c}>{c}</li>)}
        </ul>
      )}
      {t.strategy && (
        <p className="lab-muted">
          정렬: {t.strategy.rank_by.f} {t.strategy.rank_by.desc === false ? '낮은 순' : '높은 순'} · 상위 {t.strategy.top_n}개
          {t.strategy.guard?.exclude_risk?.length ? ` · 위험 제외: ${t.strategy.guard.exclude_risk.join(', ')}` : ' · 위험 제외 없음'}
        </p>
      )}
      <NoteList title="이렇게 해석했습니다" items={t.assumptions} />
      <NoteList title="주의" items={t.warnings} className="lab-warn-list" />
      <NoteList title="지원하지 않는 요청" items={t.unsupported} className="lab-bad-list" />
      <NoteList title="이해하지 못한 단어" items={t.unparsed} />
    </div>
  )
}

function NoteList({ title, items, className = '' }: { title: string; items: string[]; className?: string }) {
  if (!items.length) return null
  return (
    <div className={`lab-notes ${className}`}>
      <h3>{title}</h3>
      <ul>{items.map((i) => <li key={i}>{i}</li>)}</ul>
    </div>
  )
}

export function ScreenView({ s }: { s: ScreenResult }) {
  const [showExcluded, setShowExcluded] = useState(false)
  const steps = [
    ['분석 대상', s.counts.universe], ['조건 통과', s.counts.passing], ['위험 제외', s.counts.guard_excluded],
    ['후보', s.counts.eligible], ['표시', s.counts.published],
  ] as const
  return (
    <>
      <ol className="lab-funnel" aria-label="단계별 종목 수">
        {steps.map(([label, n]) => <li key={label}><strong>{n.toLocaleString('ko-KR')}</strong><span>{label}</span></li>)}
      </ol>
      {s.regime_blocked && (
        <p className="lab-warn">현재 시장 국면({s.regime.label})에서는 이 전략을 쓰지 않습니다. 검증 결과 이 국면에서 성과가 나빴습니다.</p>
      )}
      {s.results.length === 0 && !s.regime_blocked && <p className="lab-empty">조건을 모두 만족하는 종목이 없습니다.</p>}
      {s.results.length > 0 && (
        <div className="lab-table-wrap">
          <table className="lab-table">
            <thead>
              <tr><th>#</th><th>종목</th><th>왜 걸렸나</th><th>위험</th><th>5일 변동 범위</th></tr>
            </thead>
            <tbody>
              {s.results.map((r, i) => (
                <tr key={r.code}>
                  <td className="lab-num">{i + 1}</td>
                  <td><StockCell r={r} /></td>
                  <td><ul className="lab-why">{r.explanation.map((e) => <li key={e}>{e}</li>)}</ul></td>
                  <td><RiskCell risk={r.risk} /></td>
                  <td><BandCell risk={r.risk} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {s.excluded.length > 0 && (
        <div className="lab-excluded">
          <button type="button" aria-expanded={showExcluded} onClick={() => setShowExcluded((v) => !v)}>
            조건은 맞지만 위험 신호로 뺀 종목 {s.counts.guard_excluded}개 {showExcluded ? '접기' : '보기'}
          </button>
          {showExcluded && (
            <ul>
              {s.excluded.map((r) => (
                <li key={r.code}>
                  <StockCell r={r} />
                  <span className="lab-flags">{r.risk.flags.map((f) => <span key={f.id} className="lab-chip bad" title={f.evidence}>{f.label}</span>)}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </>
  )
}

function StockCell({ r }: { r: ScreenRow }) {
  return (
    <div className="lab-stock">
      <strong>{r.name ?? r.code}</strong>
      <span className="lab-muted">{r.code} · {r.market ?? '시장 미상'}{r.sector ? ` · ${r.sector}` : ''}</span>
    </div>
  )
}

export function RiskCell({ risk }: { risk: RiskView }) {
  return (
    <div className="lab-risk">
      <span className={`lab-chip ${RISK_TONE[risk.grade] ?? ''}`}>{risk.grade}</span>
      {risk.flags.map((f) => <span key={f.id} className="lab-flag" title={`${f.rule} — ${f.evidence}`}>{f.label}</span>)}
    </div>
  )
}

export function BandCell({ risk }: { risk: RiskView }) {
  const b = risk.band
  if (!b) return <span className="lab-muted">—</span>
  return (
    <div className="lab-band" title={b.note}>
      <span>보통 ±{num(b.typical_pct, 1)}%</span>
      <span className="lab-muted">넓게 {signed(b.wide_low_pct, 1)} ~ {signed(b.wide_high_pct, 1)}</span>
    </div>
  )
}

export function BacktestView({ b }: { b: BacktestResult }) {
  const h5 = b.horizons['5']
  return (
    <section className="lab-panel" aria-label="과거 성과 검증">
      <div className="lab-panel-head">
        <div>
          <h2>과거 성과 검증</h2>
          <p className="lab-muted">{b.period.all?.[0]} ~ {b.period.all?.[1]} · 다음 날 시가 진입 · {b.method}</p>
        </div>
        {h5 && <span className={`lab-verdict ${VERDICT_TONE[h5.verdict.code] ?? ''}`} title={h5.verdict.detail}>5일 판정: {h5.verdict.label}</span>}
      </div>
      {h5 && <p className="lab-muted">{h5.verdict.detail}</p>}

      <div className="lab-table-wrap">
        <table className="lab-table lab-bt-table">
          <thead>
            <tr><th>보유</th><th>구간</th><th>같은 날 대비 초과</th><th>지수 대비</th><th>비용 후 수익</th><th>적중률</th><th>t값</th><th>신호일</th></tr>
          </thead>
          <tbody>
            {Object.entries(b.horizons).flatMap(([h, st]) => (['discovery', 'confirm'] as const).map((w) => (
              <HorizonRow key={`${h}-${w}`} h={h} w={w} s={st[w]} first={w === 'discovery'} verdict={st} />
            )))}
          </tbody>
        </table>
      </div>

      <div className="lab-bt-grid">
        <div>
          <h3>시장 국면별 5일 초과수익</h3>
          <Bars rows={Object.entries(b.by_regime).map(([k, v]) => [k, v.excess_vs_universe_pct ?? null, v.signal_days])} />
        </div>
        <div>
          <h3>연도별 5일 초과수익</h3>
          <Bars rows={Object.entries(b.by_year).map(([k, v]) => [k, v.excess_vs_universe_pct ?? null, v.signal_days])} />
        </div>
      </div>

      {b.guard_effect && (
        <p className="lab-muted">
          위험 제외({b.guard_effect.excluded_flags.join(', ')}) 적용 {signed(b.guard_effect.with_guard_excess_pct)} · 미적용 {signed(b.guard_effect.without_guard_excess_pct)}
        </p>
      )}
      {b.curve.length > 1 && <Curve curve={b.curve} />}
    </section>
  )
}

function HorizonRow({ h, w, s, first, verdict }: { h: string; w: 'discovery' | 'confirm'; s: WindowStats; first: boolean; verdict: HorizonStats }) {
  return (
    <tr className={first ? 'lab-row-first' : ''}>
      {first && <td rowSpan={2} className="lab-num" title={verdict.verdict.detail}>{h}일<br /><span className={`lab-mini ${VERDICT_TONE[verdict.verdict.code] ?? ''}`}>{verdict.verdict.label}</span></td>}
      <td>{WINDOW_LABEL[w]}</td>
      <td className={`lab-num ${tone(s.excess_vs_universe_pct)}`}>{signed(s.excess_vs_universe_pct)}</td>
      <td className={`lab-num ${tone(s.excess_vs_index_pct)}`}>{signed(s.excess_vs_index_pct)}</td>
      <td className={`lab-num ${tone(s.net_return_pct)}`}>{signed(s.net_return_pct)}</td>
      <td className="lab-num">{s.hit_rate_pct === null || s.hit_rate_pct === undefined ? '—' : `${num(s.hit_rate_pct, 1)}%`}</td>
      <td className="lab-num">{num(s.t_stat)}</td>
      <td className="lab-num">{s.signal_days}</td>
    </tr>
  )
}

const REGIME_NAME: Record<string, string> = { UP_TREND: '상승', PULLBACK: '눌림', DOWN_TREND: '하락', REBOUND: '반등' }

function Bars({ rows }: { rows: Array<[string, number | null, number]> }) {
  const max = Math.max(0.5, ...rows.map(([, v]) => Math.abs(v ?? 0)))
  return (
    <ul className="lab-bars">
      {rows.map(([k, v, n]) => (
        <li key={k}>
          <span className="lab-bar-label">{REGIME_NAME[k] ?? k}</span>
          <span className="lab-bar-track">
            <span className={`lab-bar ${tone(v)}`} style={v !== null && v < 0
              ? { right: '50%', width: `${(Math.abs(v) / max) * 50}%` }
              : { left: '50%', width: `${(Math.abs(v ?? 0) / max) * 50}%` }} />
          </span>
          <span className={`lab-num ${tone(v)}`}>{signed(v)}</span>
          <span className="lab-muted">{n}일</span>
        </li>
      ))}
    </ul>
  )
}

function Curve({ curve }: { curve: BacktestResult['curve'] }) {
  const { paths, min, max } = useMemo(() => {
    const series = (['excess_pct', 'strategy_pct', 'index_pct'] as const).map((k) => curve.map((p) => p[k]))
    const all = series.flat()
    const lo = Math.min(0, ...all), hi = Math.max(0, ...all)
    const span = hi - lo || 1
    const x = (i: number) => (i / (curve.length - 1)) * 600
    const y = (v: number) => 180 - ((v - lo) / span) * 170 - 5
    return {
      paths: series.map((s) => s.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')),
      min: lo, max: hi,
    }
  }, [curve])
  const zeroY = 180 - ((0 - min) / ((max - min) || 1)) * 170 - 5
  const last = curve[curve.length - 1]
  return (
    <figure className="lab-curve">
      <figcaption>
        <h3>누적 곡선 (매일 자금 1/5씩 5일 보유 · 비용 전)</h3>
        <span className="lab-legend"><i className="ex" />같은 날 대비 초과 {signed(last.excess_pct, 1)}</span>
        <span className="lab-legend"><i className="st" />전략 수익 {signed(last.strategy_pct, 1)}</span>
        <span className="lab-legend"><i className="ix" />지수 {signed(last.index_pct, 1)}</span>
      </figcaption>
      <svg viewBox="0 0 600 180" preserveAspectRatio="none" role="img" aria-label="누적 성과 곡선">
        <line x1="0" x2="600" y1={zeroY} y2={zeroY} className="zero" />
        <path d={paths[2]} className="ix" />
        <path d={paths[1]} className="st" />
        <path d={paths[0]} className="ex" />
      </svg>
      <div className="lab-curve-axis"><span>{curve[0].date}</span><span>{last.date}</span></div>
    </figure>
  )
}
