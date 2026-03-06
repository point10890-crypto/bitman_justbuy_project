interface SideMenuProps {
  menuClosing: boolean
  onClose: () => void
  onHome: () => void
  onAnalyze: (mode: string, query: string) => void
  onFocusSearch: () => void
}

export function SideMenu({ menuClosing, onClose, onHome, onAnalyze, onFocusSearch }: SideMenuProps) {
  return (
    <>
      <div className="menu-overlay" onClick={onClose} />
      <div className={`menu-panel${menuClosing ? ' closing' : ''}`}>
        {/* 메뉴 헤더 */}
        <div style={{ padding: 'max(20px, env(safe-area-inset-top)) 20px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-lg flex items-center justify-center logo-gold">
                <span className="font-black text-sm logo-gold-text">B</span>
              </div>
              <span className="animate-gold-shimmer font-black text-[15px]">BitMan</span>
            </div>
            <button type="button" onClick={onClose} className="w-8 h-8 flex items-center justify-center rounded-lg active:scale-90 transition-transform" style={{ backgroundColor: 'var(--bg-elevated)' }}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-secondary)' }}><path d="M18 6L6 18" /><path d="M6 6l12 12" /></svg>
            </button>
          </div>
          <p className="text-[9px] mt-2" style={{ color: 'var(--text-muted)', letterSpacing: '0.08em' }}>AI STOCK ANALYSIS ENGINE</p>
        </div>

        {/* 메뉴 아이템 */}
        <div style={{ padding: '8px 0' }}>
          <button className="menu-item" onClick={() => { onClose(); onHome() }}>
            <div className="menu-item-icon" style={{ background: 'rgba(0,200,83,0.1)' }}>🏠</div>
            <div><div>홈</div><div className="menu-item-desc">메인 대시보드</div></div>
          </button>

          <button className="menu-item" onClick={() => { onClose(); onAnalyze('오늘뭐사', '오늘 뭐 살까? 당일 매매 추천') }}>
            <div className="menu-item-icon" style={{ background: 'rgba(0,200,83,0.1)' }}>📈</div>
            <div><div>오늘 뭐사?</div><div className="menu-item-desc">당일 매매 추천 · 오전 8시/12시 갱신</div></div>
          </button>

          <button className="menu-item" onClick={() => { onClose(); onAnalyze('스윙매매', '스윙매매 후보 종목 분석') }}>
            <div className="menu-item-icon" style={{ background: 'rgba(66,165,245,0.1)' }}>⚡</div>
            <div><div>스윙매매</div><div className="menu-item-desc">3일~3주 스윙 후보 · 3일 간격 갱신</div></div>
          </button>

          <button className="menu-item" onClick={() => { onClose(); onAnalyze('종가매매', '종가매매 후보 종목 분석') }}>
            <div className="menu-item-icon" style={{ background: 'rgba(124,77,255,0.1)' }}>💎</div>
            <div><div>종가매매</div><div className="menu-item-desc">종가·시초가 단타 · 오후 3시 갱신</div></div>
          </button>

          <button className="menu-item" onClick={() => { onClose(); onAnalyze('수급분석', '오늘 수급 현황 분석') }}>
            <div className="menu-item-icon" style={{ background: 'rgba(255,152,0,0.1)' }}>🔍</div>
            <div><div>수급 분석</div><div className="menu-item-desc">외인·기관 수급 · 10시/12시/2시 갱신</div></div>
          </button>

          <div className="h-px mx-5 my-1" style={{ backgroundColor: 'var(--border-subtle)' }} />

          <button className="menu-item" onClick={() => { onClose(); onFocusSearch() }}>
            <div className="menu-item-icon" style={{ background: 'rgba(124,77,255,0.1)' }}>🤖</div>
            <div><div>분석해줘</div><div className="menu-item-desc">5 AI 병렬 심층 분석</div></div>
          </button>
        </div>

        {/* 메뉴 하단 */}
        <div style={{ padding: '12px 20px', borderTop: '1px solid var(--border-subtle)', marginTop: 'auto' }}>
          <div className="flex items-center gap-2">
            <span className="text-[9px] font-mono" style={{ color: 'var(--color-bull)' }}>● ACTIVE</span>
            <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>Multi-Agent V3.0</span>
          </div>
          <p className="text-[8px] mt-1" style={{ color: 'var(--text-muted)' }}>Claude + Gemini + ChatGPT + Perplexity + Grok</p>
        </div>
      </div>
    </>
  )
}
