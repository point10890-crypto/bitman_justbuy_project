import { useState, useEffect, useRef, type FormEvent } from 'react'
import { useMarketData } from './hooks/useMarketData'
import { useAnalysis } from './hooks/useAnalysis'
import { usePWAInstall } from './hooks/usePWAInstall'
import { getRecentHistory, formatTimeAgo, type HistoryEntry } from './lib/analysisHistory'
import { MarketTickerBar } from './components/app/MarketTicker'
import { EngineStatusPanel } from './components/app/EngineBadge'
import { AnalysisOverlay } from './components/app/AnalysisOverlay'
import { SideMenu } from './components/app/SideMenu'
import { BottomNav } from './components/app/BottomNav'
import './index.css'

function App() {
  const { data: marketData } = useMarketData()
  const { result, loading, error, analyze, clear } = useAnalysis()
  const [query, setQuery] = useState('')
  const [showResult, setShowResult] = useState(false)
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [menuOpen, setMenuOpen] = useState(false)
  const [menuClosing, setMenuClosing] = useState(false)
  const { canInstall, install, dismiss } = usePWAInstall()
  const inputRef = useRef<HTMLInputElement>(null)

  const closeMenu = () => {
    setMenuClosing(true)
    setTimeout(() => { setMenuOpen(false); setMenuClosing(false) }, 250)
  }

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
    <div className="min-h-dvh flex flex-col" style={{ backgroundColor: 'var(--bg-primary)' }}>
      {/* ===== 헤더 ===== */}
      <header className="sticky top-0 z-50 w-full" style={{ backgroundColor: 'var(--bg-primary)' }}>
        {/* 골드 톱 라인 */}
        <div className="h-[2px] w-full animate-gradient" style={{ backgroundImage: 'var(--gradient-gold-line)', backgroundSize: '200% 200%' }} />

        {/* safe-area 상단 여백 + 로고 */}
        <div style={{ paddingTop: 'max(12px, env(safe-area-inset-top))' }}>
          <div className="flex items-center justify-between" style={{ padding: '10px var(--page-px) 8px' }}>
            {/* 로고 */}
            <div className="flex items-center gap-2.5">
              <div className="relative w-9 h-9 rounded-xl flex items-center justify-center animate-float logo-gold">
                <span className="font-black text-lg leading-none logo-gold-text">B</span>
                <span className="absolute -top-0.5 -right-0.5 w-2.5 h-2.5 rounded-full border-2 animate-pulse" style={{ backgroundColor: '#FFD700', borderColor: 'var(--bg-primary)' }} />
              </div>
              <div className="flex flex-col">
                <div className="flex items-baseline gap-1.5">
                  <span className="animate-gold-shimmer font-black text-[17px] tracking-tight" style={{ fontFamily: "'Geist', -apple-system, sans-serif", filter: 'drop-shadow(0 0 5px rgba(255,215,0,0.35))' }}>BitMan</span>
                  <span className="font-bold text-[15px]" style={{ color: '#E8E0D0' }}>오늘</span>
                  <span className="animate-gold-shimmer font-black text-[15px]" style={{ filter: 'drop-shadow(0 0 5px rgba(255,215,0,0.35))' }}>뭐사?</span>
                </div>
                <span className="text-[9px] font-semibold tracking-[0.12em] uppercase" style={{ color: '#6B6355' }}>AI Stock Analysis Engine</span>
              </div>
            </div>

            {/* 라이브 뱃지 + 햄버거 */}
            <div className="flex items-center gap-2">
              <div className="badge-gold-live flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold">
                <span className="w-1.5 h-1.5 rounded-full animate-pulse" style={{ backgroundColor: '#FFD700' }} />
                LIVE
              </div>
              <button type="button" className="hamburger-btn" onClick={() => setMenuOpen(true)} aria-label="메뉴 열기">
                <span /><span /><span />
              </button>
            </div>
          </div>
        </div>

        {/* 시장 티커 바 */}
        <MarketTickerBar marketData={marketData} />

        <div className="h-px w-full" style={{ backgroundColor: 'var(--border-subtle)' }} />
      </header>

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
            <EngineStatusPanel />

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
        <AnalysisOverlay
          query={query}
          loading={loading}
          error={error}
          result={result}
          onClose={handleClose}
        />
      )}

      {/* ===== 사이드 메뉴 ===== */}
      {menuOpen && (
        <SideMenu
          menuClosing={menuClosing}
          onClose={closeMenu}
          onHome={() => setShowResult(false)}
          onAnalyze={handleCardClick}
          onFocusSearch={() => inputRef.current?.focus()}
        />
      )}

      {/* ===== PWA 설치 배너 ===== */}
      {canInstall && (
        <div className="animate-slide-up" style={{
          position: 'sticky', bottom: 56, zIndex: 50,
          margin: '0 8px 4px', padding: '10px 14px',
          borderRadius: '14px',
          background: 'linear-gradient(135deg, rgba(255,215,0,0.12) 0%, rgba(0,200,83,0.08) 100%)',
          border: '1px solid rgba(255,215,0,0.25)',
          backdropFilter: 'blur(12px)',
          display: 'flex', alignItems: 'center', gap: '10px',
        }}>
          <div className="w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0 logo-gold">
            <span className="font-black text-sm logo-gold-text">B</span>
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-[12px] font-bold" style={{ color: 'var(--text-primary)' }}>앱으로 설치하기</p>
            <p className="text-[9.5px]" style={{ color: 'var(--text-muted)' }}>홈 화면에 추가하여 빠르게 접근</p>
          </div>
          <button onClick={install} className="flex-shrink-0 px-3 py-1.5 rounded-lg text-[11px] font-bold" style={{ background: 'linear-gradient(135deg, #FFD700, #FF9800)', color: '#0D1117' }}>설치</button>
          <button onClick={dismiss} className="flex-shrink-0 w-7 h-7 flex items-center justify-center rounded-lg" style={{ backgroundColor: 'rgba(255,255,255,0.06)' }}>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-muted)' }}><path d="M18 6L6 18" /><path d="M6 6l12 12" /></svg>
          </button>
        </div>
      )}

      {/* ===== 하단 네비게이션 ===== */}
      <BottomNav />
    </div>
  )
}

export default App
