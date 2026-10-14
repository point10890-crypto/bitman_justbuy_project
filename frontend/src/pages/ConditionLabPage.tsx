import { useCallback, useEffect, useState } from 'react'
import {
  engineApi,
  type BacktestResult, type EngineHealth, type LibraryStrategy, type RegimeNow,
  type RiskCheckItem, type ScreenResult, type Strategy, type Translation,
} from '../api/engineApi'
import { BacktestView, BandCell, RiskCell, ScreenView, TranslationCard, errorText } from '../components/app/ConditionEngineViews'
import './ConditionLabPage.css'

type Tab = 'ask' | 'library' | 'risk'
type Source = { kind: 'adhoc'; strategy: Strategy } | { kind: 'library'; id: string; title: string; retired: boolean }

const EXAMPLES = [
  '20일선 위 눌림인데 거래량 1.5배 붙은 종목',
  '60일선 위 저변동 대형주 10개',
  '신고가 근처 코스닥 반도체',
  '오늘 7% 이상 오른 거래량 급증 종목',
]

const REGIME_TONE: Record<string, string> = { UP_TREND: 'up', PULLBACK: 'flat', REBOUND: 'flat', DOWN_TREND: 'down' }
const STATUS_TONE: Record<string, string> = { candidate: 'good', regime_gated: 'flat', monitoring: 'info', retired: 'bad' }

export default function ConditionLabPage() {
  const [tab, setTab] = useState<Tab>('ask')
  const [health, setHealth] = useState<EngineHealth | null>(null)
  const [regime, setRegime] = useState<RegimeNow | null>(null)
  const [library, setLibrary] = useState<{ strategies: LibraryStrategy[]; evidence_basis: string } | null>(null)
  const [bootError, setBootError] = useState<string | null>(null)

  const [text, setText] = useState('')
  const [translation, setTranslation] = useState<Translation | null>(null)
  const [source, setSource] = useState<Source | null>(null)
  const [screen, setScreen] = useState<ScreenResult | null>(null)
  const [backtest, setBacktest] = useState<BacktestResult | null>(null)
  const [busy, setBusy] = useState<'translate' | 'screen' | 'backtest' | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const ctl = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    const boot = async () => {
      try {
        const h = await engineApi.health(ctl.signal)
        setHealth(h)
        setBootError(null)
        if (h.state !== 'ready') {
          timer = setTimeout(boot, 2000)      // 엔진이 데이터를 올리는 중 — 준비될 때까지 다시 묻는다
          return
        }
        const [r, lib] = await Promise.all([engineApi.regime(ctl.signal), engineApi.strategies(ctl.signal)])
        setRegime(r)
        setLibrary(lib)
      } catch (e) {
        if (!ctl.signal.aborted) setBootError(errorText(e))
      }
    }
    boot()
    return () => { ctl.abort(); if (timer) clearTimeout(timer) }
  }, [])

  const run = useCallback(async (src: Source, what: 'screen' | 'backtest') => {
    setBusy(what)
    setError(null)
    const req = src.kind === 'adhoc' ? { strategy: src.strategy } : { strategy_id: src.id }
    try {
      if (what === 'screen') setScreen(await engineApi.screen(req))
      else setBacktest(await engineApi.backtest(req))
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(null)
    }
  }, [])

  const translate = async (input = text) => {
    const q = input.trim()
    if (!q) return
    setText(q)
    setBusy('translate')
    setError(null)
    setScreen(null)
    setBacktest(null)
    try {
      const t = await engineApi.translate(q)
      setTranslation(t)
      if (t.strategy) {
        const src: Source = { kind: 'adhoc', strategy: t.strategy }
        setSource(src)
        setBusy(null)
        await run(src, 'screen')
      } else {
        setSource(null)
      }
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(null)
    }
  }

  const openLibrary = async (s: LibraryStrategy, what: 'screen' | 'backtest') => {
    const src: Source = { kind: 'library', id: s.id, title: s.title, retired: s.status === 'retired' }
    setTab('ask')
    setTranslation(null)
    setSource(src)
    setScreen(null)
    setBacktest(null)
    await run(src, what)
  }

  const ready = health?.state === 'ready'

  return (
    <main className="lab-page">
      <header className="lab-header">
        <p className="lab-kicker">로컬 개발 · 조건검색 엔진 v0.1</p>
        <h1>조건검색 랩</h1>
        <p className="lab-lede">
          말로 조건을 쓰면 엔진이 검증 가능한 조건식으로 바꾸고, 같은 날 다른 종목과 비교한 과거 성과까지 보여줍니다.
          종목 추천이나 목표가는 만들지 않습니다.
        </p>
        <StatusStrip health={health} regime={regime} bootError={bootError} />
      </header>

      <nav className="lab-tabs" role="tablist" aria-label="조건검색 랩 메뉴">
        {([['ask', '자연어 검색'], ['library', '검증된 전략'], ['risk', '위험 점검']] as const).map(([id, label]) => (
          <button key={id} role="tab" type="button" aria-selected={tab === id}
            className={tab === id ? 'active' : ''} onClick={() => setTab(id)}>{label}</button>
        ))}
      </nav>

      {error && <p className="lab-error" role="alert">{error}</p>}

      {tab === 'ask' && (
        <>
          <section className="lab-panel" aria-label="자연어 조건 입력">
            <form className="lab-ask" onSubmit={(e) => { e.preventDefault(); translate() }}>
              <label htmlFor="lab-query">찾고 싶은 종목을 말로 적어 주세요</label>
              <div className="lab-ask-row">
                <input id="lab-query" value={text} maxLength={300} placeholder="예: 20일선 위 눌림인데 거래량 1.5배 붙은 반도체 5개"
                  onChange={(e) => setText(e.target.value)} disabled={!ready || busy !== null} />
                <button type="submit" className="lab-primary" disabled={!ready || busy !== null || !text.trim()}>
                  {busy === 'translate' ? '번역 중…' : busy === 'screen' ? '검색 중…' : '검색'}
                </button>
              </div>
              <div className="lab-examples">
                {EXAMPLES.map((ex) => (
                  <button key={ex} type="button" onClick={() => translate(ex)} disabled={!ready || busy !== null}>{ex}</button>
                ))}
              </div>
            </form>
            {translation && <TranslationCard t={translation} />}
          </section>

          {source && (
            <section className="lab-panel" aria-label="검색 결과">
              <div className="lab-panel-head">
                <div>
                  <h2>{source.kind === 'library' ? source.title : source.strategy.title}</h2>
                  {screen && <p className="lab-muted">{screen.as_of} 종가 기준 · 조건: {screen.conditions.join(' · ')}</p>}
                </div>
                <div className="lab-actions">
                  {source.kind === 'library' && source.retired
                    ? <span className="lab-chip bad">검증 탈락 · 오늘 검색은 하지 않습니다</span>
                    : (
                      <button type="button" onClick={() => run(source, 'screen')} disabled={busy !== null}>
                        {busy === 'screen' ? '검색 중…' : screen ? '다시 검색' : '오늘 검색'}
                      </button>
                    )}
                  <button type="button" className="lab-primary" onClick={() => run(source, 'backtest')} disabled={busy !== null}>
                    {busy === 'backtest' ? '백테스트 중…' : '과거 성과 검증'}
                  </button>
                </div>
              </div>
              {screen && <ScreenView s={screen} />}
            </section>
          )}

          {backtest && <BacktestView b={backtest} />}
        </>
      )}

      {tab === 'library' && (
        <LibraryView library={library} ready={ready} busy={busy !== null} onRun={openLibrary} />
      )}

      {tab === 'risk' && <RiskCheck ready={ready} />}
    </main>
  )
}

function StatusStrip({ health, regime, bootError }: { health: EngineHealth | null; regime: RegimeNow | null; bootError: string | null }) {
  if (bootError) return <p className="lab-error" role="alert">{bootError}</p>
  if (!health) return <p className="lab-status" role="status">엔진 상태 확인 중…</p>
  if (health.state !== 'ready') {
    return <p className="lab-status" role="status">{health.state === 'error' ? `엔진 오류: ${health.error}` : '엔진이 시세 데이터를 불러오는 중입니다…'}</p>
  }
  const pending = health.provisional_dates.filter((d) => d > (health.last_final_date ?? ''))
  return (
    <dl className="lab-status-grid" aria-label="엔진 상태">
      <div><dt>기준 거래일</dt><dd>{health.last_session}</dd></div>
      <div><dt>종목 수</dt><dd>{health.codes.toLocaleString('ko-KR')}</dd></div>
      <div>
        <dt>시장 국면</dt>
        <dd>{regime ? <span className={`lab-chip ${REGIME_TONE[regime.code] ?? ''}`} title={regime.label}>{regime.label}</span> : '—'}</dd>
      </div>
      <div><dt>번역기</dt><dd>{health.translator}</dd></div>
      <div className="lab-status-note">
        <dt>데이터 원칙</dt>
        <dd>
          장 마감(15:40) 뒤 받은 확정 봉만 씁니다.
          {pending.length > 0 && <> 장중 잠정 봉 {pending.join(', ')}은 계산에서 뺐습니다.</>}
          {health.unrepairable_provisional && health.unrepairable_provisional.rows > 0 && (
            <> 코넥스·상장폐지 {health.unrepairable_provisional.codes}종목의 잠정 봉 {health.unrepairable_provisional.rows.toLocaleString('ko-KR')}행은 확정 값을 받을 수 없어 제외했습니다.</>
          )}
        </dd>
      </div>
    </dl>
  )
}

function LibraryView({ library, ready, busy, onRun }: {
  library: { strategies: LibraryStrategy[]; evidence_basis: string } | null
  ready: boolean
  busy: boolean
  onRun: (s: LibraryStrategy, what: 'screen' | 'backtest') => void
}) {
  if (!library) return <p className="lab-status" role="status">전략 목록을 불러오는 중…</p>
  return (
    <section className="lab-panel" aria-label="검증된 전략">
      <p className="lab-muted">{library.evidence_basis}</p>
      <div className="lab-cards">
        {library.strategies.map((s) => (
          <article key={s.id} className={`lab-card ${s.status}`}>
            <header>
              <h3>{s.title}</h3>
              <span className={`lab-chip ${STATUS_TONE[s.status] ?? ''}`}>{s.status_label}</span>
            </header>
            <p>{s.summary}</p>
            <p className="lab-evidence">{s.evidence}</p>
            <div className="lab-actions">
              {s.status !== 'retired' && (
                <button type="button" onClick={() => onRun(s, 'screen')} disabled={!ready || busy}>오늘 검색</button>
              )}
              <button type="button" className={s.status === 'retired' ? 'lab-primary' : ''} onClick={() => onRun(s, 'backtest')} disabled={!ready || busy}>
                {s.status === 'retired' ? '탈락 근거 보기' : '과거 성과'}
              </button>
            </div>
          </article>
        ))}
      </div>
    </section>
  )
}

function RiskCheck({ ready }: { ready: boolean }) {
  const [codes, setCodes] = useState('')
  const [items, setItems] = useState<RiskCheckItem[] | null>(null)
  const [asOf, setAsOf] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const check = async () => {
    const list = Array.from(new Set(codes.split(/[\s,]+/).map((c) => c.trim()).filter(Boolean)))
    if (!list.length) return
    setBusy(true)
    setError(null)
    try {
      const r = await engineApi.risk(list.slice(0, 50))
      setItems(r.items)
      setAsOf(r.as_of)
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="lab-panel" aria-label="위험 점검">
      <form className="lab-ask" onSubmit={(e) => { e.preventDefault(); check() }}>
        <label htmlFor="lab-codes">가지고 있거나 보고 있는 종목코드 (쉼표·공백 구분, 최대 50개)</label>
        <div className="lab-ask-row">
          <input id="lab-codes" value={codes} placeholder="005930, 000660" onChange={(e) => setCodes(e.target.value)} disabled={!ready || busy} />
          <button type="submit" className="lab-primary" disabled={!ready || busy || !codes.trim()}>{busy ? '점검 중…' : '점검'}</button>
        </div>
      </form>
      {error && <p className="lab-error" role="alert">{error}</p>}
      {items && (
        <div className="lab-table-wrap">
          <p className="lab-muted">{asOf} 종가 기준</p>
          <table className="lab-table">
            <thead><tr><th>종목</th><th>상태</th><th>위험</th><th>5일 변동 범위</th></tr></thead>
            <tbody>
              {items.map((it) => (
                <tr key={it.code}>
                  <td><div className="lab-stock"><strong>{it.name ?? it.code}</strong><span className="lab-muted">{it.code}</span></div></td>
                  <td>{!it.found ? <span className="lab-muted">데이터 없음</span>
                    : <ul className="lab-why">{(it.explanation ?? []).slice(0, 4).map((e) => <li key={e}>{e}</li>)}{!it.in_universe && <li className="lab-muted">거래대금·이력 부족으로 검색 대상 밖</li>}</ul>}</td>
                  <td>{it.risk ? <RiskCell risk={it.risk} /> : '—'}</td>
                  <td>{it.risk ? <BandCell risk={it.risk} /> : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
