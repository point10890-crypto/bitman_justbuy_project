import { useState, useEffect, useRef, useMemo, type FormEvent } from 'react'
import { useAnalysis } from '../hooks/useAnalysis'
import { getRecentHistory, formatTimeAgo, type HistoryEntry } from '../lib/analysisHistory'
import { fetchStockPrices } from '../api/analysisApi'
import FeedbackWidget from '../components/app/FeedbackWidget'
import { buildShareText } from '../utils/shareUtils'

/** 가격 문자열 → 숫자 변환 (쉼표/원/공백 제거) */
const parsePrice = (s?: string) => s ? Number(s.replace(/[^0-9]/g, '')) : 0

function EngineBadge({ name, role, color }: { name: string; role: string; color: string }) {
  return (
    <div className="engine-badge" style={{ backgroundColor: `${color}10`, border: `1px solid ${color}20` }}>
      <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: color, boxShadow: `0 0 6px ${color}60` }} />
      <div className="flex flex-col min-w-0">
        <span className="text-[10px] font-bold truncate" style={{ color }}>{name}</span>
        <span className="text-[8px]" style={{ color: 'var(--text-muted)' }}>{role}</span>
      </div>
    </div>
  )
}

function ReportRenderer({ content }: { content: string }) {
  if (!content) return null
  const lines = content.split('\n')
  const elements: React.ReactNode[] = []
  let listItems: string[] = []
  let key = 0

  const flushList = () => {
    if (listItems.length === 0) return
    elements.push(<ul key={key++}>{listItems.map((item, i) => <li key={i}>{renderInline(item)}</li>)}</ul>)
    listItems = []
  }

  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) { flushList(); continue }
    if (/^#{1}\s+/.test(trimmed)) { flushList(); elements.push(<h1 key={key++}>{renderInline(trimmed.replace(/^#{1}\s+/, ''))}</h1>); continue }
    if (/^#{2}\s+/.test(trimmed)) { flushList(); elements.push(<h2 key={key++}>{renderInline(trimmed.replace(/^#{2,3}\s+/, ''))}</h2>); continue }
    if (/^#{3}\s+/.test(trimmed)) { flushList(); elements.push(<h3 key={key++}>{renderInline(trimmed.replace(/^#{3}\s+/, ''))}</h3>); continue }
    if (/^[-*]\s+/.test(trimmed)) { listItems.push(trimmed.replace(/^[-*]\s+/, '')); continue }
    if (/^[🟢🟡🔴]/.test(trimmed)) {
      flushList()
      const cls = trimmed.startsWith('🟢') ? 'scenario-bull' : trimmed.startsWith('🔴') ? 'scenario-bear' : 'scenario-base'
      elements.push(<p key={key++} className={cls}>{renderInline(trimmed)}</p>)
      continue
    }
    flushList()
    elements.push(<p key={key++}>{renderInline(trimmed)}</p>)
  }
  flushList()
  return <div className="report-content">{elements}</div>
}

function renderInline(text: string): React.ReactNode {
  const parts: React.ReactNode[] = []
  let remaining = text
  let i = 0

  while (remaining.length > 0) {
    const stockMatch = remaining.match(/([가-힣A-Za-z][가-힣A-Za-z0-9·&]{1,15})\s*[\(（](\d{6})[\)）]/)
    const boldMatch = remaining.match(/\*\*(.+?)\*\*/)
    const stockIdx = stockMatch?.index ?? Infinity
    const boldIdx = boldMatch?.index ?? Infinity

    if (stockIdx === Infinity && boldIdx === Infinity) { parts.push(<span key={i++}>{remaining}</span>); break }

    if (stockIdx <= boldIdx && stockMatch) {
      if (stockMatch.index! > 0) parts.push(<span key={i++}>{remaining.slice(0, stockMatch.index!)}</span>)
      parts.push(<span key={i++} className="stock-inline">{stockMatch[1]}<span className="stock-code-inline">{stockMatch[2]}</span></span>)
      const advance = stockMatch.index! + stockMatch[0].length
      remaining = advance > 0 ? remaining.slice(advance) : remaining.slice(1)
    } else if (boldMatch) {
      if (boldMatch.index! > 0) parts.push(<span key={i++}>{remaining.slice(0, boldMatch.index!)}</span>)
      parts.push(<strong key={i++}>{boldMatch[1]}</strong>)
      const advance = boldMatch.index! + boldMatch[0].length
      remaining = advance > 0 ? remaining.slice(advance) : remaining.slice(1)
    }
  }
  return parts.length === 1 ? parts[0] : <>{parts}</>
}

export default function HomePage() {
  const { result, loading, error, analyze, clear } = useAnalysis()
  const [query, setQuery] = useState('')
  const [showResult, setShowResult] = useState(false)
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => { setHistory(getRecentHistory(3)) }, [result])

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    if (!query.trim() || loading) return
    setShowResult(true)
    analyze(query)
  }

  const handleCardClick = (mode: string, defaultQuery: string) => {
    setQuery(defaultQuery)
    setShowResult(true)
    analyze(defaultQuery, mode)
  }

  const handleClose = () => { setShowResult(false); clear() }

  return (
    <>
      {/* ===== 메인 콘텐츠 ===== */}
      <main className="flex-1" style={{ padding: '14px var(--page-px) 10px' }}>
        {/* 그라디언트 보더 래퍼 */}
        <div className="outer-frame-glow" style={{
          borderRadius: '24px',
          padding: '1px',
          background: 'linear-gradient(155deg, rgba(255,215,0,0.4) 0%, rgba(100,108,120,0.3) 20%, rgba(55,62,72,0.45) 45%, rgba(100,108,120,0.3) 75%, rgba(0,200,83,0.25) 100%)',
          boxShadow: '0 8px 32px rgba(0,0,0,0.3)',
        }}>
          {/* 내부 콘텐츠 영역 */}
          <div style={{
            borderRadius: '23px',
            padding: '16px 14px 14px',
            background: 'linear-gradient(180deg, #161B22 0%, #0F1318 100%)',
          }}>
          <div className="flex flex-col" style={{ gap: '18px' }}>

            {/* 검색바 */}
            <form onSubmit={handleSubmit} className="flex items-center gap-2.5" style={{ padding: '10px 14px', borderRadius: '12px', backgroundColor: 'var(--bg-elevated)', border: '1px solid var(--border-default)' }}>
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-muted)', flexShrink: 0 }}>
                <circle cx="11" cy="11" r="8" /><path d="M21 21l-4.35-4.35" />
              </svg>
              <input
                ref={inputRef}
                type="search"
                inputMode="search"
                enterKeyHint="search"
                value={query}
                onChange={e => setQuery(e.target.value.slice(0, 30))}
                maxLength={30}
                placeholder="분석할 종목을 입력해 주세요"
                className="flex-1 bg-transparent text-[13px] outline-none"
                style={{ color: 'var(--text-primary)' }}
                disabled={loading}
              />
              {query && (
                <button type="submit" disabled={loading} className="text-[11px] font-bold px-3 py-1 rounded-lg transition-all" style={{ backgroundColor: loading ? 'rgba(66,165,245,0.1)' : 'rgba(0,200,83,0.15)', color: loading ? 'var(--color-neutral)' : 'var(--color-bull)' }}>
                  {loading ? '분석중...' : '분석'}
                </button>
              )}
            </form>

            {/* 퀵 분석 2x2 그리드 */}
            <div className="flex flex-col" style={{ gap: '0px' }}>
              <div className="section-header">
                <span className="section-title">Quick Analysis</span>
              </div>

              <div className="quick-grid">
                {/* BREAKOUT */}
                <button className="quick-card animate-slide-up" style={{ backgroundImage: 'linear-gradient(135deg, rgba(0,229,255,0.12) 0%, rgba(0,229,255,0.02) 100%)', animationDelay: '0s', animationFillMode: 'backwards' }} onClick={() => handleCardClick('BREAKOUT', '기술적 돌파 매수 후보 분석')}>
                  <span className="text-lg">🚀</span>
                  <div>
                    <div className="flex items-baseline gap-1">
                      <span className="font-bold text-[13px]" style={{ color: '#00E5FF' }}>BREAKOUT</span>
                    </div>
                    <span className="text-[9.5px]" style={{ color: 'var(--text-secondary)' }}>박스·저항 돌파 종목</span>
                    <span className="text-[8px] block mt-0.5" style={{ color: 'var(--text-muted)' }}>기술적 돌파 + 거래량 폭발</span>
                  </div>
                </button>

                {/* FLOW LEADER */}
                <button className="quick-card animate-slide-up" style={{ backgroundImage: 'linear-gradient(135deg, rgba(0,200,83,0.12) 0%, rgba(0,200,83,0.02) 100%)', animationDelay: '0.05s', animationFillMode: 'backwards' }} onClick={() => handleCardClick('FLOW_LEADER', '외국인·기관 수급 주도 종목 분석')}>
                  <span className="text-lg">💹</span>
                  <div>
                    <div className="flex items-baseline gap-1">
                      <span className="font-bold text-[13px]" style={{ color: 'var(--color-bull)' }}>FLOW</span>
                      <span className="font-bold text-[13px]" style={{ color: 'var(--color-bull)' }}>LEADER</span>
                    </div>
                    <span className="text-[9.5px]" style={{ color: 'var(--text-secondary)' }}>외인·기관 수급 주도</span>
                    <span className="text-[8px] block mt-0.5" style={{ color: 'var(--text-muted)' }}>스마트머니 추종 전략</span>
                  </div>
                </button>

                {/* CATALYST BURST */}
                <button className="quick-card animate-slide-up" style={{ backgroundImage: 'linear-gradient(135deg, rgba(255,152,0,0.12) 0%, rgba(255,152,0,0.02) 100%)', animationDelay: '0.1s', animationFillMode: 'backwards' }} onClick={() => handleCardClick('CATALYST_BURST', '재료/이벤트 드리븐 급등 후보 분석')}>
                  <span className="text-lg">⚡</span>
                  <div>
                    <div className="flex items-baseline gap-1">
                      <span className="font-bold text-[13px]" style={{ color: 'var(--color-warning)' }}>CATALYST</span>
                    </div>
                    <span className="text-[9.5px]" style={{ color: 'var(--text-secondary)' }}>재료·이벤트 드리븐</span>
                    <span className="text-[8px] block mt-0.5" style={{ color: 'var(--text-muted)' }}>공시·뉴스 기반 급등 후보</span>
                  </div>
                </button>

                {/* REVERSAL EDGE */}
                <button className="quick-card animate-slide-up" style={{ backgroundImage: 'linear-gradient(135deg, rgba(124,77,255,0.12) 0%, rgba(124,77,255,0.02) 100%)', animationDelay: '0.15s', animationFillMode: 'backwards' }} onClick={() => handleCardClick('REVERSAL_EDGE', '역발상 반전 매수 후보 분석')}>
                  <span className="text-lg">🔄</span>
                  <div>
                    <div className="flex items-baseline gap-1">
                      <span className="font-bold text-[13px]" style={{ color: 'var(--color-grade-s)' }}>REVERSAL</span>
                    </div>
                    <span className="text-[9.5px]" style={{ color: 'var(--text-secondary)' }}>역발상 반전 매수</span>
                    <span className="text-[8px] block mt-0.5" style={{ color: 'var(--text-muted)' }}>과매도·비관 극점 반등</span>
                  </div>
                </button>
              </div>

              {/* 분석해줘 풀와이드 */}
              <button
                className="full-card glow-brand animate-slide-up"
                style={{ marginTop: '10px', animationDelay: '0.2s', animationFillMode: 'backwards' }}
                onClick={() => { query.trim() ? handleCardClick('분석해줘', query) : inputRef.current?.focus() }}
              >
                <div className="w-9 h-9 rounded-xl flex items-center justify-center text-lg robot-icon-pulse flex-shrink-0" style={{ backgroundImage: 'var(--gradient-brand)' }}>🤖</div>
                <div className="flex-1 text-left min-w-0">
                  <div className="flex items-center gap-1.5">
                    <span className="text-gradient-brand font-bold text-[13px]">분석해줘</span>
                    <span className="text-[8px] font-bold px-1.5 py-0.5 rounded" style={{ backgroundColor: 'rgba(124,77,255,0.2)', color: 'var(--color-grade-s)' }}>FULL</span>
                  </div>
                  <span className="text-[9.5px] block truncate" style={{ color: 'var(--text-secondary)' }}>ChatGPT × Grok 2-에이전트 심층분석 · 3라운드 토론</span>
                </div>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-muted)', flexShrink: 0 }}>
                  <polyline points="9 18 15 12 9 6" />
                </svg>
              </button>
            </div>

            {/* AI 엔진 상태 */}
            <div style={{ padding: '12px', borderRadius: '14px', backgroundColor: 'var(--bg-glass)', border: '1px solid var(--border-default)', backdropFilter: 'blur(12px)' }}>
              <div className="section-header" style={{ marginBottom: '6px' }}>
                <span className="section-title">AI Engine Status</span>
                <span className="text-[8.5px] font-bold px-2 py-0.5 rounded-full" style={{ backgroundColor: 'rgba(0,200,83,0.12)', color: 'var(--color-bull)' }}>ALL ONLINE</span>
              </div>
              <div className="engine-grid-2">
                <EngineBadge name="ChatGPT" role="매크로·기술·펀더멘털" color="#10A37F" />
                <EngineBadge name="Grok" role="수급·파생·SNS/X" color="#FF4500" />
              </div>
              <div className="flex items-center gap-2" style={{ marginTop: '8px', paddingTop: '8px', borderTop: '1px solid var(--border-subtle)' }}>
                <span className="text-[8.5px]" style={{ color: 'var(--text-muted)' }}>Dual-Agent V4.0</span>
                <span className="text-[8.5px] font-mono" style={{ color: 'var(--color-bull)' }}>● ACTIVE</span>
                <span className="text-[8.5px] ml-auto" style={{ color: 'var(--text-muted)' }}>3R 토론 · KIS수급 교차검증</span>
              </div>
            </div>

            {/* 최근 분석 */}
            <div>
              <div className="section-header">
                <span className="section-title">Recent Analysis</span>
                {history.length > 0 && (
                  <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>최근 {history.length}건</span>
                )}
              </div>

              {history.length === 0 ? (
                <div style={{ padding: '20px 14px', textAlign: 'center', borderRadius: '12px', backgroundColor: 'var(--bg-glass)', border: '1px solid var(--border-default)' }}>
                  <p className="text-[12px]" style={{ color: 'var(--text-muted)' }}>아직 분석 기록이 없습니다</p>
                  <p className="text-[10px]" style={{ color: 'var(--text-muted)', marginTop: '3px' }}>종목을 검색하거나 Quick Analysis를 눌러보세요</p>
                </div>
              ) : (
                <div className="flex flex-col" style={{ gap: '6px' }}>
                  {history.map((entry, i) => {
                    const vc = entry.verdict === '강세' ? 'var(--color-bull)' : entry.verdict === '약세' ? 'var(--color-bear)' : 'var(--color-neutral)'
                    return (
                      <div
                        key={entry.id}
                        className="history-card animate-slide-up"
                        style={{ animationDelay: `${0.05 + i * 0.08}s`, animationFillMode: 'backwards' }}
                        onClick={() => { setQuery(entry.query); setShowResult(true); analyze(entry.query, entry.mode) }}
                      >
                        <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: `${vc}15`, border: `1px solid ${vc}25` }}>
                          <span className="text-[10px] font-black" style={{ color: vc }}>{entry.verdict}</span>
                        </div>
                        <div className="flex-1 min-w-0">
                          <span className="font-bold text-[12px] truncate block" style={{ color: 'var(--text-primary)' }}>{entry.query}</span>
                          <div className="flex items-center gap-2 mt-0.5">
                            <span className="text-[8.5px] font-bold px-1.5 py-0.5 rounded" style={{ backgroundColor: 'rgba(66,165,245,0.1)', color: 'var(--color-neutral)' }}>{entry.mode || '분석'}</span>
                            <span className="text-[8.5px]" style={{ color: 'var(--text-muted)' }}>{formatTimeAgo(entry.timestamp)}</span>
                          </div>
                        </div>
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-muted)', flexShrink: 0 }}>
                          <polyline points="9 18 15 12 9 6" />
                        </svg>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          </div>
        </div>
        </div>
      </main>

      {/* ===== 분석 결과 오버레이 ===== */}
      {showResult && (
        <div className="fixed inset-0 z-[100]" style={{ backgroundColor: 'rgba(13, 17, 23, 0.97)', padding: '6px', paddingTop: 'max(6px, env(safe-area-inset-top))' }}>
          <div className="outer-frame-glow result-frame w-full h-full" style={{
            borderRadius: '20px',
            padding: '1px',
            background: 'linear-gradient(155deg, rgba(255,215,0,0.45) 0%, rgba(100,108,120,0.3) 20%, rgba(55,62,72,0.5) 45%, rgba(100,108,120,0.3) 75%, rgba(0,200,83,0.3) 100%)',
          }}>
          <div className="w-full h-full flex flex-col" style={{ borderRadius: '19px', background: 'linear-gradient(180deg, #161B22 0%, #0D1117 100%)', overflow: 'hidden' }}>
          {/* 헤더 */}
          <div className="relative flex items-center justify-between" style={{ padding: '8px var(--page-px)', borderBottom: '1px solid var(--border-subtle)' }}>
            <div className="flex items-center gap-2">
              <button type="button" onClick={handleClose} className="w-10 h-10 flex items-center justify-center rounded-xl active:scale-90 transition-transform" style={{ backgroundColor: 'var(--bg-elevated)', WebkitTapHighlightColor: 'transparent' }}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-primary)' }}><polyline points="15 18 9 12 15 6" /></svg>
              </button>
              <span className="text-base">🤖</span>
              <span className="font-bold text-[13px]" style={{ color: 'var(--text-primary)' }}>AI 분석</span>
              {loading && <span className="text-[9px] font-bold px-2 py-0.5 rounded-full animate-pulse" style={{ backgroundColor: 'rgba(66,165,245,0.12)', color: 'var(--color-neutral)' }}>분석중...</span>}
            </div>
            <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 flex items-center gap-1" style={{ pointerEvents: 'none' }}>
              <span className="animate-gold-shimmer font-black text-[14px]" style={{ filter: 'drop-shadow(0 0 5px rgba(255,215,0,0.35))' }}>BitMan</span>
              <span className="font-bold text-[13px]" style={{ color: '#E8E0D0' }}>오늘</span>
              <span className="font-black text-[13px]" style={{ color: '#00C853', filter: 'drop-shadow(0 0 4px rgba(0,200,83,0.3))' }}>뭐사?</span>
            </div>
            <button type="button" onClick={handleClose} className="w-10 h-10 flex items-center justify-center rounded-xl active:scale-90 transition-transform" style={{ backgroundColor: 'var(--bg-elevated)', WebkitTapHighlightColor: 'transparent' }}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-primary)' }}><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" /><polyline points="9 22 9 12 15 12 15 22" /></svg>
            </button>
          </div>

          {/* 쿼리 표시 */}
          <div style={{ padding: '6px var(--page-px)', backgroundColor: 'var(--bg-card)' }}>
            <span className="text-[11px]" style={{ color: 'var(--text-muted)' }}>질문: </span>
            <span className="text-[11px] font-medium" style={{ color: 'var(--text-primary)' }}>{query}</span>
          </div>

          {/* 결과 본문 */}
          <div className="flex-1 overflow-y-auto" style={{ padding: 'var(--space-lg) var(--page-px) 56px' }}>
            {loading && (
              <div className="flex flex-col items-center justify-center gap-4 py-10">
                <div className="w-12 h-12 rounded-2xl flex items-center justify-center text-2xl robot-icon-pulse" style={{ backgroundImage: 'var(--gradient-brand)' }}>🤖</div>
                <div className="text-center">
                  <p className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>ChatGPT × Grok 병렬 분석중...</p>
                  <p className="text-[11px] mt-1" style={{ color: 'var(--text-muted)' }}>3라운드 토론 · 베이즈 추론 · KIS 수급 교차검증</p>
                </div>
                <div className="flex items-center gap-5">
                  {[{ name: 'ChatGPT', color: '#10A37F' }, { name: 'Grok', color: '#FF4500' }].map((a, i) => (
                    <div key={a.name} className="flex flex-col items-center gap-1">
                      <span className="w-3 h-3 rounded-full animate-pulse" style={{ backgroundColor: a.color, animationDelay: `${i * 0.3}s`, boxShadow: `0 0 10px ${a.color}80` }} />
                      <span className="text-[9px] font-bold" style={{ color: a.color }}>{a.name}</span>
                    </div>
                  ))}
                </div>
                <div className="w-44 h-1 rounded-full overflow-hidden" style={{ backgroundColor: 'var(--bg-elevated)' }}>
                  <div className="h-full rounded-full animate-shimmer" style={{ backgroundImage: 'var(--gradient-brand)', width: '60%' }} />
                </div>
              </div>
            )}

            {error && (
              <div className="glass-card" style={{ padding: 'var(--space-lg)' }}>
                <p className="text-[13px] font-bold" style={{ color: 'var(--color-bear)' }}>분석 오류</p>
                <p className="text-[11px] mt-1" style={{ color: 'var(--text-secondary)' }}>{error}</p>
              </div>
            )}

            {result && <AnalysisResultSection result={result} />}

          </div>

          {/* 하단 고정 공유 + 경고 */}
          {result && !loading && <ShareBar result={result} query={query} />}
          <div className="w-full text-center text-[9px] font-medium" style={{ padding: '6px var(--page-px)', backgroundColor: 'rgba(13,17,23,0.95)', backdropFilter: 'blur(10px)', borderTop: '1px solid var(--border-subtle)', color: 'var(--color-warning)' }}>
            ⚠️ 본 정보는 투자자문이 아니며, 투자 판단의 책임은 본인에게 있습니다.
          </div>
          </div>
          </div>
        </div>
      )}
    </>
  )
}

interface StockPickItem {
  name: string
  code: string
  action: string
  currentPrice?: string
  targetPrice?: string
  stopLoss?: string
  reason?: string
}

/** 종목명 정제: 가격 접두어(XXX원, 55,000원 등) 제거 */
function cleanPickName(name: string): string {
  if (!name) return name
  // "XXX원 테크윙" → "테크윙", "55,000원 삼성전자" → "삼성전자"
  let cleaned = name.replace(/^(?:[0-9,X]+\s*원\s*)+/, '').trim()
  // 후행 조사 제거
  cleaned = cleaned.replace(/\s+(?:등|의|은|는|이|가|을|를|에|도|로|과|와)$/, '').trim()
  return cleaned || name
}

/** ★ 프론트엔드 실시간 가격 교정 — 백엔드 배포 여부와 무관하게 정확한 가격 표시 */
function correctContentPrices(content: string, picks: StockPickItem[], livePrices: Record<string, string>): string {
  if (!content || Object.keys(livePrices).length === 0) return content
  let result = content
  for (const pick of picks) {
    const realPrice = livePrices[pick.code]
    if (!realPrice || !pick.name) continue
    const nameEsc = pick.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const codeEsc = pick.code.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    // 패턴1: "종목명 (코드) — 현재가 약 XX,XXX원"
    result = result.replace(
      new RegExp(`(${nameEsc}\\s*[\\(（]?${codeEsc}[\\)）]?[^\\n]{0,50}?)(현재가\\s*(?::\\s*)?(?:약\\s*)?)[0-9,]+(?:~[0-9,]+)?\\s*원`, 'g'),
      `$1$2${realPrice}원`)
    // 패턴2: "종목명(코드)...약 XX원"
    result = result.replace(
      new RegExp(`(${nameEsc}\\s*[\\(（]${codeEsc}[\\)）][^\\n]{0,20}?)(약\\s*)[0-9,]+(?:~[0-9,]+)?\\s*원`, 'g'),
      `$1$2${realPrice}원`)
    // 패턴3: "현재 주가"
    result = result.replace(
      new RegExp(`(${nameEsc}[^\\n]{0,60}?)(현재\\s*주가\\s*(?::\\s*)?(?:약\\s*)?)[0-9,]+(?:~[0-9,]+)?\\s*원`, 'g'),
      `$1$2${realPrice}원`)
    // 패턴4: "현재가는 약 XX원"
    result = result.replace(
      new RegExp(`(${nameEsc}[^\\n]{0,60}?)(현재가는?\\s*(?:약\\s*)?)[0-9,]+(?:~[0-9,]+)?\\s*원`, 'g'),
      `$1$2${realPrice}원`)

    // ── 목표가/손절가 비례 보정 (콘텐츠 내부) ──
    const parseN = parsePrice
    const aiP = parseN(pick.currentPrice || '0')
    const realP = parseN(realPrice)
    if (aiP > 0 && realP > 0 && Math.abs(realP / aiP - 1) > 0.05) {
      const r = realP / aiP
      const scaleFmt = (match: string, prefix: string, numStr: string) => {
        const n = parseN(numStr)
        if (n <= 0) return match
        const scaled = Math.round(n * r / 100) * 100
        return prefix + scaled.toLocaleString('ko-KR') + '원'
      }
      // 목표가: XX,XXX원
      result = result.replace(
        new RegExp(`(${nameEsc}[^\\n]{0,120}?목표가\\s*(?::\\s*)?(?:약\\s*)?)([0-9,]+)\\s*원`, 'g'),
        scaleFmt)
      // 손절가: XX,XXX원
      result = result.replace(
        new RegExp(`(${nameEsc}[^\\n]{0,120}?손절(?:가)?\\s*(?::\\s*)?(?:약\\s*)?)([0-9,]+)\\s*원`, 'g'),
        scaleFmt)
      // 목표 XX,XXX원 (short form)
      result = result.replace(
        new RegExp(`(${nameEsc}[^\\n]{0,120}?목표\\s+)([0-9,]+)\\s*원`, 'g'),
        scaleFmt)
      // 손절 XX,XXX원 (short form)
      result = result.replace(
        new RegExp(`(${nameEsc}[^\\n]{0,120}?손절\\s+)([0-9,]+)\\s*원`, 'g'),
        scaleFmt)
    }
  }
  // 실시간 검증 현재가 푸터가 없으면 추가
  if (!result.includes('실시간 검증 현재가')) {
    const lines = Object.entries(livePrices)
      .map(([code, price]) => {
        const p = picks.find(pk => pk.code === code)
        return p ? `  ${p.name}(${code}): ${price}원` : null
      })
      .filter(Boolean)
    if (lines.length > 0) {
      result += `\n\n---\n📡 **실시간 검증 현재가** (네이버금융 기준)\n${lines.join('\n')}\n※ 위 현재가는 네이버금융 실시간 시세로 검증된 가격입니다.`
    }
  }
  return result
}

function AnalysisResultSection({ result }: { result: import('../hooks/useAnalysis').AnalysisResult }) {
  const [livePrices, setLivePrices] = useState<Record<string, string>>({})

  useEffect(() => {
    const codes = result.stockPicks?.map(p => p.code).filter(Boolean) || []
    if (codes.length === 0) return
    fetchStockPrices(codes).then(prices => {
      if (Object.keys(prices).length > 0) setLivePrices(prices)
    })
  }, [result.stockPicks])

  const correctedContent = useMemo(
    () => correctContentPrices(result.content, result.stockPicks || [], livePrices),
    [result.content, result.stockPicks, livePrices]
  )

  return (
    <div className="animate-slide-up" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
      {/* AI 상태 바 */}
      <div className="flex flex-wrap items-center gap-1.5">
        {result.isPrecomputed && (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg text-[9px] font-bold" style={{ backgroundColor: 'rgba(255,215,0,0.08)', border: '1px solid rgba(255,215,0,0.2)', color: '#FFD700' }}>
            ⏰ 예약분석 · {new Date(result.updatedAt).toLocaleString('ko-KR', { hour: '2-digit', minute: '2-digit' })}
          </span>
        )}
        {result.hasSynthesis && (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg text-[9px] font-bold" style={{ backgroundColor: 'rgba(124,77,255,0.08)', border: '1px solid rgba(124,77,255,0.2)', color: 'var(--color-grade-s)' }}>
            🤝 {result.metadata.agentsSucceeded} AI 종합
          </span>
        )}
        {result.agents?.filter(a => a.status === 'success').map(a => {
          const colors: Record<string, string> = { claude: '#FF6B35', gemini: '#4285F4', chatgpt: '#10A37F', perplexity: '#20B2AA', grok: '#FF4500' }
          const c = colors[a.agent] || '#888'
          return <span key={a.agent} className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[8px] font-bold" style={{ backgroundColor: `${c}12`, color: c }}>✓ {a.agent} {(a.durationMs / 1000).toFixed(0)}s</span>
        })}
      </div>

      {/* 종목 추천 카드 */}
      {result.stockPicks && result.stockPicks.length > 0 && <LiveStockPickCards picks={result.stockPicks} livePrices={livePrices} />}

      {/* 구분선 */}
      <div className="flex items-center gap-2.5 py-0.5">
        <div className="flex-1 h-px" style={{ backgroundColor: 'var(--border-subtle)' }} />
        <span className="text-[9px] font-bold" style={{ color: 'var(--text-muted)' }}>상세 분석 리포트</span>
        <div className="flex-1 h-px" style={{ backgroundColor: 'var(--border-subtle)' }} />
      </div>

      <ReportRenderer content={correctedContent} />

      {/* 피드백 위젯 */}
      <FeedbackWidget
        mode={result.mode || '분석해줘'}
        analysisId={result.updatedAt}
        stockPicks={result.stockPicks?.map((p: StockPickItem) => ({ name: p.name, code: p.code }))}
      />

      {/* 메타 정보 */}
      <div className="flex items-center gap-2.5" style={{ paddingTop: 'var(--space-md)', borderTop: '1px solid var(--border-subtle)' }}>
        <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>Multi-Agent V4.0 · {result.metadata.agentsSucceeded}/{result.metadata.agentsUsed} AI</span>
        {result.metadata.totalDurationMs > 0 && <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>{(result.metadata.totalDurationMs / 1000).toFixed(1)}s</span>}
        {result.isPrecomputed && <span className="text-[9px] ml-auto" style={{ color: '#FFD700' }}>⏰ 예약 분석</span>}
      </div>
    </div>
  )
}

function LiveStockPickCards({ picks, livePrices }: { picks: StockPickItem[]; livePrices: Record<string, string> }) {

  const actionMap: Record<string, { bg: string; border: string; color: string; icon: string; label: string }> = {
    '매수': { bg: 'rgba(0,200,83,0.1)', border: 'rgba(0,200,83,0.3)', color: '#00C853', icon: '▲', label: 'BUY' },
    '매도': { bg: 'rgba(255,23,68,0.1)', border: 'rgba(255,23,68,0.3)', color: '#FF1744', icon: '▼', label: 'SELL' },
    '관망': { bg: 'rgba(255,152,0,0.1)', border: 'rgba(255,152,0,0.3)', color: '#FF9800', icon: '■', label: 'HOLD' },
    '주목': { bg: 'rgba(66,165,245,0.1)', border: 'rgba(66,165,245,0.3)', color: '#42A5F5', icon: '★', label: 'WATCH' },
  }
  const defaultAction = { bg: 'rgba(66,165,245,0.1)', border: 'rgba(66,165,245,0.3)', color: '#42A5F5', icon: '★', label: 'WATCH' }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)' }}>
      <div className="flex items-center gap-2">
        <span className="text-base">🎯</span>
        <span className="text-[13px] font-black" style={{ color: 'var(--text-primary)' }}>추천 종목 TOP {picks.length}</span>
        <div className="flex-1 h-px" style={{ backgroundColor: 'var(--border-subtle)' }} />
      </div>
      {picks.map((pick, i) => {
        const ac = actionMap[pick.action] || defaultAction
        const livePrice = livePrices[pick.code]
        const displayPrice = livePrice || pick.currentPrice
        const isLive = !!livePrice

        // ── 목표가/손절가 비례 보정 ──
        // AI가 잘못된 현재가 기준으로 목표가/손절가를 산출했을 수 있으므로
        // 실시간 가격과 AI 현재가의 비율로 비례 보정
        const aiCurrent = parsePrice(pick.currentPrice)
        const realCurrent = parsePrice(livePrice)
        const ratio = (isLive && aiCurrent > 0 && realCurrent > 0 && Math.abs(realCurrent / aiCurrent - 1) > 0.05)
          ? realCurrent / aiCurrent : 0
        const scalePrice = (priceStr?: string) => {
          if (!priceStr || ratio === 0) return priceStr
          const n = parsePrice(priceStr)
          if (n <= 0) return priceStr
          const scaled = Math.round(n * ratio / 100) * 100
          return scaled.toLocaleString('ko-KR')
        }
        const displayTarget = ratio > 0 ? scalePrice(pick.targetPrice) : pick.targetPrice
        const displayStopLoss = ratio > 0 ? scalePrice(pick.stopLoss) : pick.stopLoss

        return (
          <div key={pick.code} className="relative overflow-hidden rounded-xl animate-slide-up" style={{ backgroundColor: ac.bg, border: `1px solid ${ac.border}`, padding: '10px 12px 10px 16px', animationDelay: `${i * 0.06}s`, animationFillMode: 'backwards' }}>
            <div className="absolute left-0 top-0 bottom-0 w-[3px] rounded-l-xl" style={{ backgroundColor: ac.color }} />
            <div className="flex items-center gap-2.5">
              <div className="w-6 h-6 rounded-lg flex items-center justify-center flex-shrink-0 font-black text-[11px]" style={{ backgroundColor: `${ac.color}20`, color: ac.color }}>{i + 1}</div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-1.5">
                  <span className="font-black text-[14px]" style={{ color: 'var(--text-primary)' }}>{cleanPickName(pick.name)}</span>
                  <span className="flex-shrink-0 px-1.5 py-0.5 rounded font-mono text-[9px] font-bold" style={{ backgroundColor: 'rgba(255,255,255,0.06)', color: 'var(--text-secondary)' }}>{pick.code}</span>
                </div>
                <div className="flex items-center gap-2.5 mt-0.5">
                  {displayPrice && (
                    <span className="text-[11px] font-bold" style={{ color: 'var(--text-primary)' }}>
                      {displayPrice}원
                      {isLive && <span className="ml-1 text-[8px] px-1 py-px rounded" style={{ backgroundColor: 'rgba(0,200,83,0.15)', color: '#00C853' }}>LIVE</span>}
                    </span>
                  )}
                  {displayTarget && <span className="text-[9px]" style={{ color: '#00C853' }}>목표 {displayTarget}원</span>}
                  {displayStopLoss && <span className="text-[9px]" style={{ color: '#FF1744' }}>손절 {displayStopLoss}원</span>}
                </div>
                {pick.reason && <p className="text-[9px] mt-0.5 truncate" style={{ color: 'var(--text-muted)' }}>{pick.reason}</p>}
              </div>
              <div className="flex flex-col items-center gap-0.5 flex-shrink-0">
                <span className="text-base font-black" style={{ color: ac.color }}>{ac.icon}</span>
                <span className="px-1.5 py-0.5 rounded-full text-[9px] font-black" style={{ backgroundColor: ac.color, color: '#0D1117' }}>{ac.label}</span>
              </div>
            </div>
          </div>
        )
      })}
    </div>
  )
}

function ShareBar({ result, query }: { result: import('../hooks/useAnalysis').AnalysisResult; query: string }) {
  const [status, setStatus] = useState<'idle' | 'shared' | 'copied' | 'failed'>('idle')

  const handleShare = async () => {
    const text = buildShareText(result, query)
    try {
      if (navigator.share) {
        await navigator.share({ title: 'BitMan AI 분석 결과', text })
        setStatus('shared')
      } else {
        await navigator.clipboard.writeText(text)
        setStatus('copied')
      }
    } catch (e) {
      // clipboard fallback for older browsers
      try {
        const ta = document.createElement('textarea')
        ta.value = text
        ta.style.cssText = 'position:fixed;opacity:0;left:-9999px'
        document.body.appendChild(ta)
        ta.select()
        document.execCommand('copy')
        document.body.removeChild(ta)
        setStatus('copied')
      } catch {
        setStatus('failed')
      }
    }
    setTimeout(() => setStatus('idle'), 2000)
  }

  return (
    <div style={{ padding: '6px var(--page-px)', backgroundColor: 'rgba(13,17,23,0.95)', backdropFilter: 'blur(10px)', borderTop: '1px solid var(--border-subtle)' }}>
      <button
        type="button"
        onClick={handleShare}
        className="w-full flex items-center justify-center gap-2 rounded-xl active:scale-95 transition-all"
        style={{
          padding: '9px 16px',
          background: status === 'copied' || status === 'shared'
            ? 'rgba(0,200,83,0.15)'
            : 'linear-gradient(135deg, rgba(0,200,83,0.12) 0%, rgba(66,165,245,0.12) 100%)',
          border: `1px solid ${status === 'copied' || status === 'shared' ? 'rgba(0,200,83,0.4)' : 'rgba(0,200,83,0.25)'}`,
          WebkitTapHighlightColor: 'transparent',
        }}
      >
        {status === 'copied' || status === 'shared' ? (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#00C853" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
        ) : (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#00C853" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><line x1="8.59" y1="13.51" x2="15.42" y2="17.49" /><line x1="15.41" y1="6.51" x2="8.59" y2="10.49" /></svg>
        )}
        <span className="text-[12px] font-bold" style={{ color: status === 'copied' ? '#00C853' : status === 'shared' ? '#00C853' : '#E8E0D0' }}>
          {status === 'copied' ? '클립보드에 복사됨!' : status === 'shared' ? '공유 완료!' : status === 'failed' ? '다시 시도해주세요' : '카카오톡 공유하기'}
        </span>
      </button>
    </div>
  )
}
