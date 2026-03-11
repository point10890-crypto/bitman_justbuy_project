import { useState, useEffect } from 'react' // v4
import type { AnalysisResult } from '../../hooks/useAnalysis'
import { fetchStockPrices } from '../../api/analysisApi'
import { ReportRenderer } from './ReportRenderer'
import ConsensusDisplay from './ConsensusDisplay'
import FeedbackWidget from './FeedbackWidget'

interface AnalysisOverlayProps {
  query: string
  loading: boolean
  error: string | null
  result: AnalysisResult | null
  onClose: () => void
}

export function AnalysisOverlay({ query, loading, error, result, onClose }: AnalysisOverlayProps) {
  return (
    <div className="fixed inset-0 z-[100]" style={{ backgroundColor: 'rgba(13, 17, 23, 0.97)', padding: '6px', paddingTop: 'max(6px, env(safe-area-inset-top))' }}>
      {/* 프리미엄 그라디언트 보더 래퍼 */}
      <div className="outer-frame-glow result-frame w-full h-full" style={{
        borderRadius: '20px',
        padding: '1px',
        background: 'linear-gradient(155deg, rgba(255,215,0,0.45) 0%, rgba(100,108,120,0.3) 20%, rgba(55,62,72,0.5) 45%, rgba(100,108,120,0.3) 75%, rgba(0,200,83,0.3) 100%)',
      }}>
      <div className="w-full h-full flex flex-col" style={{ borderRadius: '19px', background: 'linear-gradient(180deg, #161B22 0%, #0D1117 100%)', overflow: 'hidden' }}>
      {/* 헤더 */}
      <div className="relative flex items-center justify-between" style={{ padding: '8px var(--page-px)', borderBottom: '1px solid var(--border-subtle)' }}>
        <div className="flex items-center gap-2">
          <button type="button" onClick={onClose} className="w-10 h-10 flex items-center justify-center rounded-xl active:scale-90 transition-transform" style={{ backgroundColor: 'var(--bg-elevated)', WebkitTapHighlightColor: 'transparent' }}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-primary)' }}><polyline points="15 18 9 12 15 6" /></svg>
          </button>
          <span className="text-base">🤖</span>
          <span className="font-bold text-[13px]" style={{ color: 'var(--text-primary)' }}>AI 분석</span>
          {loading && <span className="text-[9px] font-bold px-2 py-0.5 rounded-full animate-pulse" style={{ backgroundColor: 'rgba(66,165,245,0.12)', color: 'var(--color-neutral)' }}>분석중...</span>}
        </div>
        {/* 센터 타이틀 */}
        <div className="absolute left-[58%] top-1/2 -translate-x-1/2 -translate-y-1/2 flex items-center gap-1" style={{ pointerEvents: 'none' }}>
          <span className="animate-gold-shimmer font-black text-[14px]" style={{ filter: 'drop-shadow(0 0 5px rgba(255,215,0,0.35))' }}>BitMan</span>
          <span className="font-bold text-[13px]" style={{ color: '#E8E0D0' }}>오늘</span>
          <span className="font-black text-[13px]" style={{ color: '#00C853', filter: 'drop-shadow(0 0 4px rgba(0,200,83,0.3))' }}>뭐사?</span>
        </div>
        <button type="button" onClick={onClose} className="w-10 h-10 flex items-center justify-center rounded-xl active:scale-90 transition-transform" style={{ backgroundColor: 'var(--bg-elevated)', WebkitTapHighlightColor: 'transparent' }}>
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
        {loading && <LoadingIndicator />}

        {error && (
          <div className="glass-card" style={{ padding: 'var(--space-lg)' }}>
            <p className="text-[13px] font-bold" style={{ color: 'var(--color-bear)' }}>분석 오류</p>
            <p className="text-[11px] mt-1" style={{ color: 'var(--text-secondary)' }}>{error}</p>
          </div>
        )}

        {result && <AnalysisResultView result={result} />}
      </div>

      {/* 하단 투자 경고 */}
      <div className="w-full text-center text-[9px] font-medium" style={{ padding: '6px var(--page-px)', backgroundColor: 'rgba(13,17,23,0.95)', backdropFilter: 'blur(10px)', borderTop: '1px solid var(--border-subtle)', color: 'var(--color-warning)' }}>
        ⚠️ 본 정보는 투자자문이 아니며, 투자 판단의 책임은 본인에게 있습니다.
      </div>
      </div>{/* inner content */}
      </div>{/* gradient border */}
    </div>
  )
}

function LoadingIndicator() {
  return (
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
  )
}

function AnalysisResultView({ result }: { result: AnalysisResult }) {
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
      {result.stockPicks && result.stockPicks.length > 0 && <StockPickCards picks={result.stockPicks} />}

      {/* AI 합의 분석 */}
      {result.consensus && <ConsensusDisplay consensus={result.consensus} />}

      {/* 구분선 */}
      <div className="flex items-center gap-2.5 py-0.5">
        <div className="flex-1 h-px" style={{ backgroundColor: 'var(--border-subtle)' }} />
        <span className="text-[9px] font-bold" style={{ color: 'var(--text-muted)' }}>상세 분석 리포트</span>
        <div className="flex-1 h-px" style={{ backgroundColor: 'var(--border-subtle)' }} />
      </div>

      <ReportRenderer content={result.content} />

      {/* 피드백 위젯 */}
      <FeedbackWidget
        mode={result.mode || '분석해줘'}
        analysisId={result.updatedAt}
        stockPicks={result.stockPicks?.map(p => ({ name: p.name, code: p.code }))}
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

interface StockPick {
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
  let cleaned = name.replace(/^(?:[0-9,X]+\s*원\s*)+/, '').trim()
  cleaned = cleaned.replace(/\s+(?:등|의|은|는|이|가|을|를|에|도|로|과|와)$/, '').trim()
  return cleaned || name
}

function StockPickCards({ picks }: { picks: StockPick[] }) {
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
