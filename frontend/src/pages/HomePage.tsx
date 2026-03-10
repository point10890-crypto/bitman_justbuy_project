import { useState, useEffect, useRef, type FormEvent } from 'react'
import { useAnalysis } from '../hooks/useAnalysis'
import { getRecentHistory, formatTimeAgo, type HistoryEntry } from '../lib/analysisHistory'
import { fetchStockPrices } from '../api/analysisApi'

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
      remaining = remaining.slice(stockMatch.index! + stockMatch[0].length)
    } else if (boldMatch) {
      if (boldMatch.index! > 0) parts.push(<span key={i++}>{remaining.slice(0, boldMatch.index!)}</span>)
      parts.push(<strong key={i++}>{boldMatch[1]}</strong>)
      remaining = remaining.slice(boldMatch.index! + boldMatch[0].length)
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
                <button className="quick-card animate-slide-up" style={{ backgroundImage: 'linear-gradient(135deg, rgba(0,200,83,0.1) 0%, rgba(0,200,83,0.02) 100%)', animationDelay: '0s', animationFillMode: 'backwards' }} onClick={() => handleCardClick('오늘뭐사', '오늘 뭐 살까? 당일 매매 추천')}>
                  <span className="text-lg">📈</span>
                  <div>
                    <div className="flex items-baseline gap-1">
                      <span className="font-bold text-[13px]" style={{ color: 'var(--text-primary)' }}>오늘</span>
                      <span className="font-bold text-[13px]" style={{ color: 'var(--color-bull)' }}>뭐사?</span>
                    </div>
                    <span className="text-[9.5px]" style={{ color: 'var(--text-secondary)' }}>당일 종가·시초가 매매</span>
                    <span className="text-[8px] block mt-0.5" style={{ color: 'var(--text-muted)' }}>⏰ 매일 08:00 자동갱신</span>
                  </div>
                </button>

                <button className="quick-card animate-slide-up" style={{ backgroundImage: 'linear-gradient(135deg, rgba(66,165,245,0.1) 0%, rgba(66,165,245,0.02) 100%)', animationDelay: '0.05s', animationFillMode: 'backwards' }} onClick={() => handleCardClick('스윙매매', '스윙매매 후보 종목 분석')}>
                  <span className="text-lg">⚡</span>
                  <div>
                    <div className="flex items-baseline gap-1">
                      <span className="font-bold text-[13px]" style={{ color: 'var(--text-primary)' }}>스윙매매</span>
                      <span className="font-bold text-[13px]" style={{ color: 'var(--color-neutral)' }}>뭐사?</span>
                    </div>
                    <span className="text-[9.5px]" style={{ color: 'var(--text-secondary)' }}>3일~3주 스윙 후보</span>
                    <span className="text-[8px] block mt-0.5" style={{ color: 'var(--text-muted)' }}>⏰ 3일마다 07:00 자동갱신</span>
                  </div>
                </button>

                <button className="quick-card animate-slide-up" style={{ backgroundImage: 'linear-gradient(135deg, rgba(124,77,255,0.1) 0%, rgba(124,77,255,0.02) 100%)', animationDelay: '0.1s', animationFillMode: 'backwards' }} onClick={() => handleCardClick('종가매매', '종가매매 후보 종목 분석')}>
                  <span className="text-lg">💎</span>
                  <div>
                    <div className="flex items-baseline gap-1">
                      <span className="font-bold text-[13px]" style={{ color: 'var(--text-primary)' }}>종가매매</span>
                      <span className="font-bold text-[13px]" style={{ color: 'var(--color-grade-s)' }}>뭐사?</span>
                    </div>
                    <span className="text-[9.5px]" style={{ color: 'var(--text-secondary)' }}>종가·시초가 단타 매매</span>
                    <span className="text-[8px] block mt-0.5" style={{ color: 'var(--text-muted)' }}>⏰ 매일 15:10 자동갱신</span>
                  </div>
                </button>

                <button className="quick-card animate-slide-up" style={{ backgroundImage: 'linear-gradient(135deg, rgba(255,152,0,0.1) 0%, rgba(255,152,0,0.02) 100%)', animationDelay: '0.15s', animationFillMode: 'backwards' }} onClick={() => handleCardClick('수급분석', '오늘 수급 현황 분석')}>
                  <span className="text-lg">🔍</span>
                  <div>
                    <div className="flex items-baseline gap-1">
                      <span className="font-bold text-[13px]" style={{ color: 'var(--text-primary)' }}>수급</span>
                      <span className="font-bold text-[13px]" style={{ color: 'var(--color-warning)' }}>뭐야?</span>
                    </div>
                    <span className="text-[9.5px]" style={{ color: 'var(--text-secondary)' }}>외인·기관 교차 분석</span>
                    <span className="text-[8px] block mt-0.5" style={{ color: 'var(--text-muted)' }}>⏰ 매일 10:00·14:00 자동갱신</span>
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
                  <span className="text-[9.5px] block truncate" style={{ color: 'var(--text-secondary)' }}>5 AI 병렬 심층 분석 · Claude + Gemini + ChatGP...</span>
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
              <div className="engine-grid-3">
                <EngineBadge name="Claude" role="종합·공시" color="#FF6B35" />
                <EngineBadge name="Gemini" role="차트·섹터" color="#4285F4" />
                <EngineBadge name="ChatGPT" role="심층·글로벌" color="#10A37F" />
              </div>
              <div className="engine-grid-2" style={{ marginTop: '6px' }}>
                <EngineBadge name="Perplexity" role="실시간 웹" color="#20B2AA" />
                <EngineBadge name="Grok" role="소셜·감성" color="#FF4500" />
              </div>
              <div className="flex items-center gap-2" style={{ marginTop: '8px', paddingTop: '8px', borderTop: '1px solid var(--border-subtle)' }}>
                <span className="text-[8.5px]" style={{ color: 'var(--text-muted)' }}>Multi-Agent V3.0</span>
                <span className="text-[8.5px] font-mono" style={{ color: 'var(--color-bull)' }}>● ACTIVE</span>
                <span className="text-[8.5px] ml-auto" style={{ color: 'var(--text-muted)' }}>W(공시)=0.95 · W(연기금)=0.85</span>
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
                  <p className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>5 AI 에이전트 병렬 분석중...</p>
                  <p className="text-[11px] mt-1" style={{ color: 'var(--text-muted)' }}>베이즈 추론 · 시나리오 분석 · 리스크 매트릭스</p>
                </div>
                <div className="flex items-center gap-3">
                  {[{ name: 'Claude', color: '#FF6B35' }, { name: 'Gemini', color: '#4285F4' }, { name: 'ChatGPT', color: '#10A37F' }, { name: 'Perplexity', color: '#20B2AA' }, { name: 'Grok', color: '#FF4500' }].map((a, i) => (
                    <div key={a.name} className="flex flex-col items-center gap-1">
                      <span className="w-2.5 h-2.5 rounded-full animate-pulse" style={{ backgroundColor: a.color, animationDelay: `${i * 0.2}s`, boxShadow: `0 0 8px ${a.color}80` }} />
                      <span className="text-[8px]" style={{ color: a.color }}>{a.name}</span>
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

            {result && (
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
                {result.stockPicks && result.stockPicks.length > 0 && <LiveStockPickCards picks={result.stockPicks} />}

                {/* 구분선 */}
                <div className="flex items-center gap-2.5 py-0.5">
                  <div className="flex-1 h-px" style={{ backgroundColor: 'var(--border-subtle)' }} />
                  <span className="text-[9px] font-bold" style={{ color: 'var(--text-muted)' }}>상세 분석 리포트</span>
                  <div className="flex-1 h-px" style={{ backgroundColor: 'var(--border-subtle)' }} />
                </div>

                <ReportRenderer content={result.content} />

                {/* 메타 정보 */}
                <div className="flex items-center gap-2.5" style={{ paddingTop: 'var(--space-md)', borderTop: '1px solid var(--border-subtle)' }}>
                  <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>Multi-Agent V3.0 · {result.metadata.agentsSucceeded}/{result.metadata.agentsUsed} AI</span>
                  {result.metadata.totalDurationMs > 0 && <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>{(result.metadata.totalDurationMs / 1000).toFixed(1)}s</span>}
                  {result.isPrecomputed && <span className="text-[9px] ml-auto" style={{ color: '#FFD700' }}>⏰ 예약 분석</span>}
                </div>
              </div>
            )}
          </div>

          {/* 하단 투자 경고 */}
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

function LiveStockPickCards({ picks }: { picks: StockPickItem[] }) {
  const [livePrices, setLivePrices] = useState<Record<string, string>>({})

  useEffect(() => {
    const codes = picks.map(p => p.code).filter(Boolean)
    if (codes.length === 0) return
    fetchStockPrices(codes).then(prices => {
      if (Object.keys(prices).length > 0) setLivePrices(prices)
    })
  }, [picks])

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
                  {pick.targetPrice && <span className="text-[9px]" style={{ color: '#00C853' }}>목표 {pick.targetPrice}원</span>}
                  {pick.stopLoss && <span className="text-[9px]" style={{ color: '#FF1744' }}>손절 {pick.stopLoss}원</span>}
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
