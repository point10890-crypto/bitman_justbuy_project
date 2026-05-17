import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAnalysis, type AnalysisResult } from '../hooks/useAnalysis'
import { useAuth } from '../contexts/AuthContext'
import { useMainConditions } from '../hooks/useMainConditions'
import type { ConditionSectionResponse } from '../api/conditionApi'

type Section = {
  id: string
  icon: 'target' | 'swing' | 'leader' | 'theme' | 'alert'
  title: string
  mode?: string
  query?: string
  columns: string[]
  rows: Array<{ name: string; a: string; b: string; c: string; code?: string; summary?: string }>
}

const sections: Section[] = [
  {
    id: 'short-term',
    icon: 'target',
    title: '단타',
    mode: 'BREAKOUT',
    query: '오늘 장중 단타 돌파 후보 분석',
    columns: ['종목명', '포착가', '현재가', '단기 수익률'],
    rows: [
      { name: '로보티즈', a: '31,200', b: '34,350', c: '+10.09%' },
      { name: '디아이', a: '7,120', b: '7,510', c: '+5.53%' },
      { name: '한미반도체', a: '139,000', b: '145,300', c: '+4.66%' },
    ],
  },
  {
    id: 'swing',
    icon: 'swing',
    title: '스윙',
    mode: 'REVERSAL_EDGE',
    query: '스윙 눌림목과 반전 후보 분석',
    columns: ['종목명', '포착가', '목표구간', '상태'],
    rows: [
      { name: '현대무벡스', a: '31,800', b: '35,000', c: '관찰' },
      { name: '범한퓨얼셀', a: '32,500', b: '38,000', c: '1차' },
      { name: '디앤디파마텍', a: '78,000', b: '86,000', c: '보유' },
    ],
  },
  {
    id: 'leaders',
    icon: 'leader',
    title: '주도주',
    mode: 'FLOW_LEADER',
    query: '기관 외국인 수급 주도 종목 분석',
    columns: ['종목명', '거래대금', '등락률', '강도'],
    rows: [
      { name: '알테오젠', a: '1,230억', b: '+12.4%', c: '강' },
      { name: '한화오션', a: '980억', b: '+8.7%', c: '중' },
      { name: '에코프로', a: '760억', b: '+6.2%', c: '중' },
    ],
  },
  {
    id: 'themes',
    icon: 'theme',
    title: '테마주',
    mode: 'CATALYST_BURST',
    query: '오늘 시장 이슈와 테마 대장주 분석',
    columns: ['테마명', '대장주', '상승률', '강도'],
    rows: [
      { name: '반도체', a: '한미반도체', b: '+8.2%', c: '강' },
      { name: '로봇', a: '로보티즈', b: '+5.1%', c: '중' },
      { name: '방산', a: '한화에어로', b: '+4.7%', c: '중' },
    ],
  },
  {
    id: 'alerts',
    icon: 'alert',
    title: '종목 알림이',
    columns: ['관심종목', '조건', '상태', '채널'],
    rows: [
      { name: '삼성전자', a: '주도주 진입', b: '대기', c: '앱' },
      { name: '에코프로', a: '+5% 돌파', b: '알림', c: '텔레그램' },
      { name: '한미반도체', a: '공시 발생', b: '대기', c: '앱' },
    ],
  },
]

function CategoryIcon({ type }: { type: Section['icon'] }) {
  return <span className={`category-icon category-icon-${type}`} aria-hidden="true" />
}

function maskValue(value: string) {
  if (/^[+-]/.test(value)) return value
  if (/^[0-9,]+/.test(value)) return value.replace(/[0-9]/g, 'X')
  if (value.length <= 2) return value
  return `${value[0]}${value.slice(1).replace(/[가-힣A-Za-z0-9]/g, 'X')}`
}

function rowsFromCondition(section: ConditionSectionResponse | undefined, fallbackRows: Section['rows']) {
  if (!section) return fallbackRows
  if (!section.signals?.length) return []
  return section.signals.slice(0, 3).map(signal => ({
    name: signal.stockName,
    code: signal.stockCode,
    a: signal.capturePrice || signal.currentPrice || '-',
    b: signal.highPrice || signal.currentPrice || '-',
    c: signal.maxReturnPct && signal.maxReturnPct !== '-' ? signal.maxReturnPct : signal.status,
    summary: signal.summary,
  }))
}

function extractPercent(text?: string) {
  if (!text) return ''
  return text.match(/[+-]\d+(?:\.\d+)?%/)?.[0] || ''
}

function rowsFromEndpointResult(section: Section, result: AnalysisResult): Section['rows'] {
  if (!result.stockPicks?.length) return []
  return result.stockPicks.slice(0, 3).map(pick => {
    const name = pick.name || pick.code || '포착 종목'
    const currentPrice = pick.currentPrice || '-'
    const targetPrice = pick.targetPrice || currentPrice
    const action = pick.action || '주목'
    const percent = extractPercent(pick.reason) || extractPercent(result.content)

    if (section.id === 'leaders') {
      return {
        name,
        code: pick.code,
        a: currentPrice,
        b: percent || targetPrice,
        c: action,
        summary: pick.reason,
      }
    }

    if (section.id === 'themes') {
      return {
        name,
        code: pick.code,
        a: pick.reason ? 'AI 포착' : '테마 포착',
        b: currentPrice,
        c: percent || action,
        summary: pick.reason,
      }
    }

    return {
      name,
      code: pick.code,
      a: currentPrice,
      b: targetPrice,
      c: section.id === 'short-term' ? percent || action : action,
      summary: pick.reason,
    }
  })
}

function sectionSourceStatus(section: ConditionSectionResponse | undefined) {
  if (!section) return '기본 표시'
  if (section.sourceStatus === 'PRECOMPUTED') return '실시간 캐시'
  if (section.sourceStatus === 'STALE_CACHE') return '최근 저장 결과'
  if (section.sourceStatus === 'READY') return '알림 대기'
  if (section.sourceStatus === 'DATA_UNAVAILABLE') return '데이터 준비 중'
  return '조건검색 준비'
}

function ResultPanel({
  query,
  loading,
  error,
  result,
  onClose,
}: {
  query: string
  loading: boolean
  error: string | null
  result: AnalysisResult | null
  onClose: () => void
}) {
  return (
    <div className="maker-analysis-panel">
      <div className="maker-analysis-sheet">
        <header>
          <button type="button" onClick={onClose}>←</button>
          <div>
            <strong>AI 조건검색 분석</strong>
            <span>{query}</span>
          </div>
          <button type="button" onClick={onClose}>닫기</button>
        </header>

        <div className="maker-analysis-body">
          {loading && <div className="analysis-loading">AI 분석을 진행 중입니다. 보통 1~3분 정도 걸립니다.</div>}
          {error && <div className="auth-error">{error}</div>}
          {result && (
            <>
              <div className="analysis-meta">
                <span>{result.isPrecomputed ? '예약 분석' : '실시간 분석'}</span>
                <span>{result.metadata.agentsSucceeded}/{result.metadata.agentsUsed} AI</span>
                <span>{new Date(result.updatedAt).toLocaleString('ko-KR')}</span>
              </div>
              {result.stockPicks?.length > 0 && (
                <section className="analysis-pick-list">
                  <h3>포착 종목</h3>
                  {result.stockPicks.slice(0, 5).map((pick, index) => (
                    <article key={`${pick.code}-${index}`}>
                      <b>{index + 1}</b>
                      <div>
                        <strong>{pick.name}</strong>
                        <span>{pick.code}</span>
                        {pick.reason && <p>{pick.reason}</p>}
                      </div>
                      <em>{pick.action}</em>
                    </article>
                  ))}
                </section>
              )}
              <section className="analysis-report">
                <h3>AI 요약</h3>
                <p>{result.content || '분석 결과 본문이 없습니다.'}</p>
              </section>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

export default function HomePage() {
  const navigate = useNavigate()
  const { user } = useAuth()
  const { result, loading, error, analyze, clear } = useAnalysis()
  const { data: mainConditions, loading: conditionsLoading, error: conditionsError } = useMainConditions()
  const [query, setQuery] = useState('')
  const [activeQuery, setActiveQuery] = useState('')
  const [panelOpen, setPanelOpen] = useState(false)
  const [endpointRows, setEndpointRows] = useState<Record<string, Section['rows']>>({})
  const [endpointStatus, setEndpointStatus] = useState<Record<string, string>>({})
  const [endpointLoading, setEndpointLoading] = useState<string | null>(null)
  const handledActionRef = useRef('')
  const queryRef = useRef('')
  const locked = user?.role !== 'ADMIN' && user?.subscription !== 'pro'

  useEffect(() => {
    queryRef.current = query
  }, [query])

  const runAnalysis = (analysisQuery: string, mode?: string) => {
    setActiveQuery(analysisQuery)
    setPanelOpen(true)
    analyze(analysisQuery, mode)
  }

  const handleSearch = (event: FormEvent) => {
    event.preventDefault()
    if (!query.trim()) return
    runAnalysis(`${query.trim()} 종목 분석`, '분석해줘')
  }

  const closePanel = () => {
    setPanelOpen(false)
    clear()
  }

  const loadLegacyEndpoint = async (section: Section) => {
    if (!section.mode) return
    const analysisQuery = section.query || `${section.title} 최신 분석`
    setActiveQuery(analysisQuery)
    setPanelOpen(true)
    setEndpointLoading(section.id)
    setEndpointStatus(prev => ({ ...prev, [section.id]: '기존 엔드포인트 호출 중' }))

    const latest = await analyze(analysisQuery, section.mode)
    if (latest?.stockPicks?.length) {
      const nextRows = rowsFromEndpointResult(section, latest)
      setEndpointRows(prev => ({ ...prev, [section.id]: nextRows }))
      setEndpointStatus(prev => ({ ...prev, [section.id]: latest.isPrecomputed ? '기존 엔드포인트 반영' : '실시간 분석 반영' }))
    } else {
      setEndpointStatus(prev => ({ ...prev, [section.id]: '결과 없음' }))
    }
    setEndpointLoading(null)
  }

  const runUsageEndpoint = () => {
    runAnalysis('BitMan 오늘 뭐사 앱 이용방법: 단타, 스윙, 주도주, 테마주, 종목 알림이, 구독 신청 흐름을 처음 쓰는 이용자에게 간단히 안내', '분석해줘')
  }

  const runThemeEndpoint = () => {
    const themeSection = sections.find(section => section.id === 'themes')
    if (themeSection) loadLegacyEndpoint(themeSection)
  }

  const runStockEndpoint = () => {
    const nextQuery = queryRef.current.trim()
    runAnalysis(nextQuery ? `${nextQuery} 종목 분석` : '오늘 관심 종목 분석', '분석해줘')
  }

  const runQuickAction = (action: string) => {
    if (!action) return
    if (action === 'usage') runUsageEndpoint()
    if (action === 'theme') runThemeEndpoint()
    if (action === 'stock') runStockEndpoint()
  }

  const runQueuedAction = (action: string) => {
    if (!action || handledActionRef.current === action) return
    handledActionRef.current = action
    runQuickAction(action)
    window.setTimeout(() => {
      if (handledActionRef.current === action) handledActionRef.current = ''
    }, 500)
  }

  useEffect(() => {
    let action = ''
    try {
      action = sessionStorage.getItem('bitman_home_action') || ''
      if (action) sessionStorage.removeItem('bitman_home_action')
    } catch { /* ignore */ }

    if (!action) {
      handledActionRef.current = ''
    } else {
      window.setTimeout(() => runQueuedAction(action), 120)
    }

    const onHomeAction = (event: Event) => {
      const nextAction = (event as CustomEvent<string>).detail || ''
      runQueuedAction(nextAction)
    }

    window.addEventListener('bitman:home-action', onHomeAction)
    return () => window.removeEventListener('bitman:home-action', onHomeAction)
  }, [])

  const displayedSections = sections.map(section => {
    const apiSection =
      section.id === 'short-term' ? mainConditions?.sections.shortTerm :
      section.id === 'swing' ? mainConditions?.sections.swing :
      section.id === 'leaders' ? mainConditions?.sections.leaders :
      section.id === 'themes' ? mainConditions?.sections.themes :
      section.id === 'alerts' ? mainConditions?.sections.alerts :
      undefined

    return {
      ...section,
      rows: endpointRows[section.id] || rowsFromCondition(apiSection, section.rows),
      sourceStatus: endpointLoading === section.id ? '기존 엔드포인트 호출 중' : endpointStatus[section.id] || sectionSourceStatus(apiSection),
      asOf: apiSection?.asOf,
    }
  })

  return (
    <main className="search-home-page">
      <form className="maker-search-box" onSubmit={handleSearch}>
        <input
          value={query}
          onChange={event => setQuery(event.target.value)}
          placeholder="종목명 또는 조건을 입력하세요"
        />
        <button type="submit">분석</button>
      </form>

      {conditionsError && (
        <div className="condition-feed-status">
          조건검색 결과를 불러오지 못해 기본 화면을 표시합니다.
        </div>
      )}

      {displayedSections.map(section => (
        <section id={section.id} className="search-rank-card" key={section.id}>
          <div className="rank-card-title-row">
            <CategoryIcon type={section.icon} />
            <div className="rank-title-copy">
              <h2>{section.title}</h2>
              <span>{conditionsLoading ? '조건검색 갱신 중' : section.sourceStatus}</span>
            </div>
            <button
              className="top3-badge"
              type="button"
              onClick={() => section.mode ? runAnalysis(section.query || section.title, section.mode) : document.getElementById('alerts')?.scrollIntoView({ behavior: 'smooth' })}
            >
              TOP3
            </button>
          </div>

          <div className="rank-table" style={{ ['--rank-cols' as string]: '1.1fr .75fr .75fr .75fr' }}>
            <div className="rank-table-head">
              {section.columns.map(column => <span key={column}>{column}</span>)}
            </div>
            {section.rows.map((row, index) => (
              <button
                className="rank-table-row"
                type="button"
                key={`${section.id}-${row.name}`}
                onClick={() => section.mode && runAnalysis(`${row.name} ${section.title} 상세 분석`, section.mode)}
              >
                <span className="rank-name"><strong>{index + 1}</strong><span>{locked ? maskValue(row.name) : row.name}</span></span>
                <span>{locked ? maskValue(row.a) : row.a}</span>
                <span>{locked ? maskValue(row.b) : row.b}</span>
                <span className={row.c.startsWith('+') ? 'profit-text' : ''}>{locked ? maskValue(row.c) : row.c}</span>
              </button>
            ))}
            {section.rows.length === 0 && (
              <div className="rank-table-empty">
                조건검색 데이터가 준비되면 최신 후보가 표시됩니다.
              </div>
            )}
          </div>

          {section.mode && (
            <button
              className="section-endpoint-button"
              type="button"
              disabled={endpointLoading === section.id || loading}
              onClick={() => loadLegacyEndpoint(section)}
            >
              {endpointLoading === section.id ? '기존 분석 엔드포인트 호출 중...' : '기존 분석 엔드포인트로 최신 결과 불러오기'}
            </button>
          )}
        </section>
      ))}

      <div className="home-action-grid">
        <button type="button" onClick={runUsageEndpoint}>앱 이용방법</button>
        <a className="contact-tile" href="https://open.kakao.com/o/sJVLbWUe" target="_blank" rel="noreferrer">
          <span className="kakao-mark kakao-mark-small">톡</span>
          <span>문의 하기</span>
        </a>
        <button type="button" onClick={runThemeEndpoint}>테마분석</button>
        <button type="button" onClick={runStockEndpoint}>종목분석</button>
      </div>

      <a className="community-cta contact-cta" href="https://open.kakao.com/o/sJVLbWUe" target="_blank" rel="noreferrer">
        <span className="kakao-mark">톡</span>
        <strong>카카오톡 문의 하기(클릭)</strong>
        <span className="circle-arrow">→</span>
      </a>

      <p className="investment-notice">
        투자 유의: 본 서비스의 조건검색 결과와 AI 분석은 투자 참고용 정보이며, 특정 종목의 매수/매도 추천이나 수익 보장을 의미하지 않습니다. 최종 투자 판단과 책임은 이용자 본인에게 있습니다.
      </p>

      {locked && (
        <button className="subscribe-sticky-cta" type="button" onClick={() => navigate('/subscribe')}>
          월간 이용권 구독하고 전체 종목 보기
        </button>
      )}

      {panelOpen && (
        <ResultPanel query={activeQuery} loading={loading} error={error} result={result} onClose={closePanel} />
      )}
    </main>
  )
}
