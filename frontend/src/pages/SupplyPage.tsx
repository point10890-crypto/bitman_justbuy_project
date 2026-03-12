import { useState, useEffect } from 'react'
import { useAnalysis } from '../hooks/useAnalysis'
import { getRecentHistory, formatTimeAgo, type HistoryEntry } from '../lib/analysisHistory'
import { fetchStockPrices } from '../api/analysisApi'
import FeedbackWidget from '../components/app/FeedbackWidget'

interface StockPickItem {
  name: string
  code: string
  action: string
  currentPrice?: string
  targetPrice?: string
  stopLoss?: string
  reason?: string
}

function cleanPickName(name: string): string {
  if (!name) return name
  let cleaned = name.replace(/^(?:[0-9,X]+\s*원\s*)+/, '').trim()
  cleaned = cleaned.replace(/\s+(?:등|의|은|는|이|가|을|를|에|도|로|과|와)$/, '').trim()
  return cleaned || name
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

export default function SupplyPage() {
  const { result, loading, error, analyze, clear } = useAnalysis()
  const [showResult, setShowResult] = useState(false)
  const [activeQuery, setActiveQuery] = useState('')
  const [history, setHistory] = useState<HistoryEntry[]>([])

  useEffect(() => {
    setHistory(getRecentHistory(20).filter(h => h.mode === '수급분석'))
  }, [result])

  const handleCardClick = (query: string) => {
    setActiveQuery(query)
    setShowResult(true)
    analyze(query, '수급분석')
  }

  const handleClose = () => { setShowResult(false); clear() }

  const supplyCards = [
    { emoji: '🔍', title: '오늘 수급', accent: '분석', color: 'var(--color-warning)', bg: 'rgba(255,152,0,0.1)', desc: '외국인·기관 순매수/순매도 교차 분석', query: '오늘 수급 현황 분석' },
    { emoji: '🌍', title: '외국인', accent: '순매수', color: 'var(--color-bull)', bg: 'rgba(0,200,83,0.1)', desc: '외국인 자금 유입 상위 종목', query: '외국인 순매수 상위 종목 분석' },
    { emoji: '🏦', title: '기관', accent: '순매수', color: 'var(--color-neutral)', bg: 'rgba(66,165,245,0.1)', desc: '기관 자금 유입 상위 종목', query: '기관 순매수 상위 종목 분석' },
    { emoji: '🎯', title: '쌍끌이', accent: '매수', color: 'var(--color-grade-s)', bg: 'rgba(124,77,255,0.1)', desc: '외국인+기관 동시 순매수 종목', query: '외국인 기관 동시 순매수 종목 분석' },
  ]

  return (
    <>
      <main className="flex-1" style={{ padding: '14px var(--page-px) 10px' }}>
        <div className="outer-frame-glow" style={{
          borderRadius: '24px',
          padding: '1px',
          background: 'linear-gradient(155deg, rgba(255,152,0,0.4) 0%, rgba(100,108,120,0.3) 20%, rgba(55,62,72,0.45) 45%, rgba(100,108,120,0.3) 75%, rgba(0,200,83,0.25) 100%)',
          boxShadow: '0 8px 32px rgba(0,0,0,0.3)',
        }}>
          <div style={{
            borderRadius: '23px',
            padding: '16px 14px 14px',
            background: 'linear-gradient(180deg, #161B22 0%, #0F1318 100%)',
          }}>
          <div className="flex flex-col" style={{ gap: '18px' }}>

            {/* 헤더 */}
            <div className="section-header">
              <span className="section-title">Supply & Demand</span>
              <span className="text-[8.5px] font-bold px-2 py-0.5 rounded-full" style={{ backgroundColor: 'rgba(255,152,0,0.12)', color: 'var(--color-warning)' }}>LIVE</span>
            </div>

            {/* 수급 분석 카드들 */}
            {supplyCards.map((card, i) => (
              <button
                key={card.query}
                className="quick-card animate-slide-up"
                style={{ backgroundImage: `linear-gradient(135deg, ${card.bg} 0%, ${card.bg.replace('0.1', '0.02')} 100%)`, animationDelay: `${i * 0.05}s`, animationFillMode: 'backwards', width: '100%' }}
                onClick={() => handleCardClick(card.query)}
                disabled={loading}
              >
                <span className="text-lg">{card.emoji}</span>
                <div className="flex-1 text-left">
                  <div className="flex items-baseline gap-1">
                    <span className="font-bold text-[13px]" style={{ color: 'var(--text-primary)' }}>{card.title}</span>
                    <span className="font-bold text-[13px]" style={{ color: card.color }}>{card.accent}</span>
                  </div>
                  <span className="text-[9.5px]" style={{ color: 'var(--text-secondary)' }}>{card.desc}</span>
                </div>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-muted)', flexShrink: 0 }}><polyline points="9 18 15 12 9 6" /></svg>
              </button>
            ))}

            {/* 자동 갱신 안내 */}
            <div className="flex items-center gap-2" style={{ padding: '8px 12px', borderRadius: '10px', backgroundColor: 'var(--bg-glass)', border: '1px solid var(--border-default)' }}>
              <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>⏰ 자동 갱신: 평일 10:00 · 14:00 (KST)</span>
              <span className="text-[9px] ml-auto font-mono" style={{ color: 'var(--color-bull)' }}>● ACTIVE</span>
            </div>

            {/* 수급 분석 히스토리 */}
            {history.length > 0 && (
              <div>
                <div className="section-header">
                  <span className="section-title">Recent Supply Analysis</span>
                  <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>{history.length}건</span>
                </div>
                <div className="flex flex-col" style={{ gap: '6px' }}>
                  {history.slice(0, 5).map((entry, i) => {
                    const vc = entry.verdict === '강세' ? 'var(--color-bull)' : entry.verdict === '약세' ? 'var(--color-bear)' : 'var(--color-neutral)'
                    return (
                      <div
                        key={entry.id}
                        className="history-card animate-slide-up"
                        style={{ animationDelay: `${0.05 + i * 0.08}s`, animationFillMode: 'backwards' }}
                        onClick={() => { setActiveQuery(entry.query); setShowResult(true); analyze(entry.query, '수급분석') }}
                      >
                        <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: `${vc}15`, border: `1px solid ${vc}25` }}>
                          <span className="text-[10px] font-black" style={{ color: vc }}>{entry.verdict}</span>
                        </div>
                        <div className="flex-1 min-w-0">
                          <span className="font-bold text-[12px] truncate block" style={{ color: 'var(--text-primary)' }}>{entry.query}</span>
                          <span className="text-[8.5px]" style={{ color: 'var(--text-muted)' }}>{formatTimeAgo(entry.timestamp)}</span>
                        </div>
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-muted)', flexShrink: 0 }}><polyline points="9 18 15 12 9 6" /></svg>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}
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
            background: 'linear-gradient(155deg, rgba(255,152,0,0.45) 0%, rgba(100,108,120,0.3) 20%, rgba(55,62,72,0.5) 45%, rgba(100,108,120,0.3) 75%, rgba(0,200,83,0.3) 100%)',
          }}>
          <div className="w-full h-full flex flex-col" style={{ borderRadius: '19px', background: 'linear-gradient(180deg, #161B22 0%, #0D1117 100%)', overflow: 'hidden' }}>
          {/* 헤더 */}
          <div className="relative flex items-center justify-between" style={{ padding: '8px var(--page-px)', borderBottom: '1px solid var(--border-subtle)' }}>
            <div className="flex items-center gap-2">
              <button type="button" onClick={handleClose} className="w-10 h-10 flex items-center justify-center rounded-xl active:scale-90 transition-transform" style={{ backgroundColor: 'var(--bg-elevated)', WebkitTapHighlightColor: 'transparent' }}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-primary)' }}><polyline points="15 18 9 12 15 6" /></svg>
              </button>
              <span className="text-base">📊</span>
              <span className="font-bold text-[13px]" style={{ color: 'var(--text-primary)' }}>수급 분석</span>
              {loading && <span className="text-[9px] font-bold px-2 py-0.5 rounded-full animate-pulse" style={{ backgroundColor: 'rgba(255,152,0,0.12)', color: 'var(--color-warning)' }}>분석중...</span>}
            </div>
            <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 flex items-center gap-1" style={{ pointerEvents: 'none' }}>
              <span className="animate-gold-shimmer font-black text-[14px]" style={{ filter: 'drop-shadow(0 0 5px rgba(255,215,0,0.35))' }}>BitMan</span>
              <span className="font-bold text-[13px]" style={{ color: '#E8E0D0' }}>수급</span>
              <span className="font-black text-[13px]" style={{ color: '#FF9800', filter: 'drop-shadow(0 0 4px rgba(255,152,0,0.3))' }}>뭐야?</span>
            </div>
            <button type="button" onClick={handleClose} className="w-10 h-10 flex items-center justify-center rounded-xl active:scale-90 transition-transform" style={{ backgroundColor: 'var(--bg-elevated)', WebkitTapHighlightColor: 'transparent' }}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-primary)' }}><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" /><polyline points="9 22 9 12 15 12 15 22" /></svg>
            </button>
          </div>

          {/* 쿼리 표시 */}
          <div style={{ padding: '6px var(--page-px)', backgroundColor: 'var(--bg-card)' }}>
            <span className="text-[11px]" style={{ color: 'var(--text-muted)' }}>분석: </span>
            <span className="text-[11px] font-medium" style={{ color: 'var(--text-primary)' }}>{activeQuery}</span>
          </div>

          {/* 결과 본문 */}
          <div className="flex-1 overflow-y-auto" style={{ padding: 'var(--space-lg) var(--page-px) 56px' }}>
            {loading && (
              <div className="flex flex-col items-center justify-center gap-4 py-10">
                <div className="w-12 h-12 rounded-2xl flex items-center justify-center text-2xl robot-icon-pulse" style={{ backgroundImage: 'var(--gradient-brand)' }}>📊</div>
                <div className="text-center">
                  <p className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>5 AI 에이전트 수급 분석중...</p>
                  <p className="text-[11px] mt-1" style={{ color: 'var(--text-muted)' }}>외국인·기관 매매동향 · 수급 교차 분석</p>
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
                  <span className="text-[9px] font-bold" style={{ color: 'var(--text-muted)' }}>수급 상세 분석</span>
                  <div className="flex-1 h-px" style={{ backgroundColor: 'var(--border-subtle)' }} />
                </div>

                <ReportRenderer content={result.content} />

                {/* 피드백 위젯 */}
                <FeedbackWidget
                  mode="수급분석"
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
