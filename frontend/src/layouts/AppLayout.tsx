import { useState } from 'react'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { useMarketData } from '../hooks/useMarketData'
import { useCountUp, useBlinkOnMount } from '../hooks/useAnimations'
import { usePWAInstall } from '../hooks/usePWAInstall'
import { useAuth } from '../contexts/AuthContext'

function formatValue(value: number, symbol: string): string {
  if (symbol === 'USDKRW') return value.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  if (symbol === 'IXIC') return Math.round(value).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return value.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function formatChange(percent: number): string {
  const sign = percent >= 0 ? '+' : ''
  return `${sign}${percent.toFixed(2)}%`
}

export default function AppLayout() {
  const { data: marketData } = useMarketData()
  const { canInstall, install, dismiss, isiOS, showIOSGuide, closeIOSGuide, showDesktopGuide, closeDesktopGuide, triggerNativeInstall, hasNativePrompt } = usePWAInstall()
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  const [menuClosing, setMenuClosing] = useState(false)

  const isAdmin = user?.role === 'ADMIN'

  const closeMenu = () => {
    setMenuClosing(true)
    setTimeout(() => { setMenuOpen(false); setMenuClosing(false) }, 250)
  }

  const currentPath = location.pathname

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
            <div className="flex items-center gap-2.5 cursor-pointer" onClick={() => navigate('/')}>
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
        <div className="overflow-hidden relative" style={{ padding: '2px var(--page-px) 6px' }}>
          <div className="absolute left-0 top-0 bottom-0 w-5 z-10" style={{ background: 'linear-gradient(to right, var(--bg-primary), transparent)' }} />
          <div className="absolute right-0 top-0 bottom-0 w-5 z-10" style={{ background: 'linear-gradient(to left, var(--bg-primary), transparent)' }} />
          <div className="market-ticker-track flex gap-2.5 w-max">
            {marketData.map((m, i) => (
              <MarketChip key={m.symbol} label={m.label} value={formatValue(m.value, m.symbol)} change={formatChange(m.changePercent)} isUp={m.isUp} delay={i * 150} />
            ))}
            {marketData.map((m, i) => (
              <MarketChip key={`dup-${m.symbol}`} label={m.label} value={formatValue(m.value, m.symbol)} change={formatChange(m.changePercent)} isUp={m.isUp} delay={600 + i * 150} />
            ))}
          </div>
        </div>

        <div className="h-px w-full" style={{ backgroundColor: 'var(--border-subtle)' }} />
      </header>

      {/* ===== 페이지 콘텐츠 ===== */}
      <Outlet />

      {/* ===== 사이드 메뉴 ===== */}
      {menuOpen && (
        <>
          <div className="menu-overlay" onClick={closeMenu} />
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
                <button type="button" onClick={closeMenu} className="w-8 h-8 flex items-center justify-center rounded-lg active:scale-90 transition-transform" style={{ backgroundColor: 'var(--bg-elevated)' }}>
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-secondary)' }}><path d="M18 6L6 18" /><path d="M6 6l12 12" /></svg>
                </button>
              </div>
              <p className="text-[9px] mt-2" style={{ color: 'var(--text-muted)', letterSpacing: '0.08em' }}>AI STOCK ANALYSIS ENGINE</p>
            </div>

            {/* 메뉴 아이템 */}
            <div style={{ padding: '8px 0' }}>
              <button className="menu-item" onClick={() => { closeMenu(); navigate('/') }}>
                <div className="menu-item-icon" style={{ background: 'rgba(0,200,83,0.1)' }}>🏠</div>
                <div><div>홈</div><div className="menu-item-desc">메인 대시보드</div></div>
              </button>

              <button className="menu-item" onClick={() => { closeMenu(); navigate('/', { state: { mode: 'BREAKOUT', query: '기술적 돌파 매수 후보 분석' } }) }}>
                <div className="menu-item-icon" style={{ background: 'rgba(0,229,255,0.1)' }}>🚀</div>
                <div><div style={{ color: '#00E5FF' }}>BREAKOUT</div><div className="menu-item-desc">박스·저항 돌파 종목 · 기술적 돌파 + 거래량 폭발</div></div>
              </button>

              <button className="menu-item" onClick={() => { closeMenu(); navigate('/', { state: { mode: 'FLOW_LEADER', query: '외국인·기관 수급 주도 종목 분석' } }) }}>
                <div className="menu-item-icon" style={{ background: 'rgba(0,200,83,0.1)' }}>💹</div>
                <div><div style={{ color: '#00C853' }}>FLOW LEADER</div><div className="menu-item-desc">외인·기관 수급 주도 · 스마트머니 추종 전략</div></div>
              </button>

              <button className="menu-item" onClick={() => { closeMenu(); navigate('/', { state: { mode: 'CATALYST_BURST', query: '재료/이벤트 드리븐 급등 후보 분석' } }) }}>
                <div className="menu-item-icon" style={{ background: 'rgba(255,152,0,0.1)' }}>⚡</div>
                <div><div style={{ color: '#FF9800' }}>CATALYST</div><div className="menu-item-desc">재료·이벤트 드리븐 · 공시·뉴스 기반 급등 후보</div></div>
              </button>

              <button className="menu-item" onClick={() => { closeMenu(); navigate('/', { state: { mode: 'REVERSAL_EDGE', query: '역발상 반전 매수 후보 분석' } }) }}>
                <div className="menu-item-icon" style={{ background: 'rgba(124,77,255,0.1)' }}>🔄</div>
                <div><div style={{ color: '#7C4DFF' }}>REVERSAL</div><div className="menu-item-desc">역발상 반전 매수 · 과매도·비관 극점 반등</div></div>
              </button>

              <div className="h-px mx-5 my-1" style={{ backgroundColor: 'var(--border-subtle)' }} />

              <button className="menu-item" onClick={() => { closeMenu(); navigate('/my') }}>
                <div className="menu-item-icon" style={{ background: 'rgba(255,215,0,0.1)' }}>👤</div>
                <div><div>마이페이지</div><div className="menu-item-desc">프로필 · 구독 관리</div></div>
              </button>

              {isAdmin && (
                <>
                  <div className="h-px mx-5 my-1" style={{ backgroundColor: 'var(--border-subtle)' }} />
                  <div className="px-5 pt-2 pb-1">
                    <span className="text-[9px] font-black tracking-wider" style={{ color: 'rgba(124,77,255,0.7)' }}>관리</span>
                  </div>
                  <button className="menu-item" onClick={() => { closeMenu(); navigate('/admin') }}>
                    <div className="menu-item-icon" style={{ background: 'rgba(124,77,255,0.1)' }}>📊</div>
                    <div><div>관리자 대시보드</div><div className="menu-item-desc">회원 현황 · 통계</div></div>
                  </button>
                  <button className="menu-item" onClick={() => { closeMenu(); navigate('/admin?tab=subscriptions') }}>
                    <div className="menu-item-icon" style={{ background: 'rgba(0,200,83,0.1)' }}>🛡️</div>
                    <div><div>구독 승인 / 해제</div><div className="menu-item-desc">대기 승인 · PRO 해제</div></div>
                  </button>
                  <button className="menu-item" onClick={() => { closeMenu(); navigate('/admin?tab=members') }}>
                    <div className="menu-item-icon" style={{ background: 'rgba(255,152,0,0.1)' }}>👥</div>
                    <div><div>회원 관리</div><div className="menu-item-desc">전체 회원 목록 · 상태</div></div>
                  </button>
                  <button className="menu-item" onClick={() => { closeMenu(); navigate('/admin?tab=system') }}>
                    <div className="menu-item-icon" style={{ background: 'rgba(255,215,0,0.1)' }}>🔧</div>
                    <div><div>시스템 관리</div><div className="menu-item-desc">서버 상태 · 리프레시</div></div>
                  </button>
                </>
              )}

              <div className="h-px mx-5 my-1" style={{ backgroundColor: 'var(--border-subtle)' }} />

              <a className="menu-item" href="https://open.kakao.com/o/sJVLbWUe" target="_blank" rel="noopener noreferrer" onClick={closeMenu} style={{ textDecoration: 'none', color: 'inherit' }}>
                <div className="menu-item-icon" style={{ background: 'rgba(254,229,0,0.15)' }}>💬</div>
                <div><div>AI 단톡방 입장문의</div><div className="menu-item-desc">카카오톡 오픈채팅</div></div>
              </a>

              <div className="h-px mx-5 my-1" style={{ backgroundColor: 'var(--border-subtle)' }} />

              <button className="menu-item" onClick={() => { closeMenu(); logout(); navigate('/login', { replace: true }) }}>
                <div className="menu-item-icon" style={{ background: 'rgba(255,23,68,0.1)' }}>🚪</div>
                <div><div>로그아웃</div><div className="menu-item-desc">계정에서 로그아웃</div></div>
              </button>
            </div>

            {/* 메뉴 하단 */}
            <div style={{ padding: '12px 20px', borderTop: '1px solid var(--border-subtle)', marginTop: 'auto' }}>
              <div className="flex items-center gap-2">
                <span className="text-[9px] font-mono" style={{ color: 'var(--color-bull)' }}>● ACTIVE</span>
                <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>Dual-Agent V4.0</span>
              </div>
              <p className="text-[8px] mt-1" style={{ color: 'var(--text-muted)' }}>ChatGPT + Grok · 3라운드 토론 · KIS수급 교차검증</p>
            </div>
          </div>
        </>
      )}

      {/* ===== PWA 설치 배너 ===== */}
      {canInstall && (
        <div className="pwa-install-banner animate-slide-up">
          <div className="w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0 logo-gold">
            <span className="font-black text-sm logo-gold-text">B</span>
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-[12px] font-bold" style={{ color: 'var(--text-primary)' }}>
              {isiOS ? '홈 화면에 추가' : '앱으로 설치하기'}
            </p>
            <p className="text-[9.5px]" style={{ color: 'var(--text-muted)' }}>
              {isiOS ? '아래 공유 버튼 → 홈 화면에 추가' : '홈 화면에 추가하여 빠르게 접근'}
            </p>
          </div>
          <button onClick={install} className="flex-shrink-0 px-3 py-1.5 rounded-lg text-[11px] font-bold" style={{ background: 'linear-gradient(135deg, #FFD700, #FF9800)', color: '#0D1117' }}>
            {isiOS ? '방법 보기' : '설치'}
          </button>
          <button onClick={dismiss} className="flex-shrink-0 w-7 h-7 flex items-center justify-center rounded-lg" style={{ backgroundColor: 'rgba(255,255,255,0.06)' }}>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-muted)' }}><path d="M18 6L6 18" /><path d="M6 6l12 12" /></svg>
          </button>
        </div>
      )}

      {/* ===== iOS 설치 가이드 모달 ===== */}
      {showIOSGuide && (
        <>
          <div className="ios-guide-overlay" onClick={closeIOSGuide} />
          <div className="ios-guide-modal">
            <button className="ios-guide-close" onClick={closeIOSGuide}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6L6 18" /><path d="M6 6l12 12" /></svg>
            </button>
            <div className="ios-guide-icon logo-gold">
              <span className="font-black text-lg logo-gold-text">B</span>
            </div>
            <h3 className="ios-guide-title">BitMan 앱 설치하기</h3>
            <p className="ios-guide-subtitle">iPhone 홈 화면에 추가하세요</p>
            <div className="ios-guide-steps">
              <div className="ios-guide-step">
                <div className="ios-guide-step-num">1</div>
                <div>
                  <p className="ios-guide-step-title">하단 공유 버튼 탭</p>
                  <p className="ios-guide-step-desc">Safari 하단의 <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#FFD700" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{display:'inline',verticalAlign:'middle'}}><path d="M4 12v8a2 2 0 002 2h12a2 2 0 002-2v-8"/><polyline points="16 6 12 2 8 6"/><line x1="12" y1="2" x2="12" y2="15"/></svg> 버튼을 누르세요</p>
                </div>
              </div>
              <div className="ios-guide-step">
                <div className="ios-guide-step-num">2</div>
                <div>
                  <p className="ios-guide-step-title">"홈 화면에 추가" 선택</p>
                  <p className="ios-guide-step-desc">메뉴를 아래로 스크롤하면 찾을 수 있습니다</p>
                </div>
              </div>
              <div className="ios-guide-step">
                <div className="ios-guide-step-num">3</div>
                <div>
                  <p className="ios-guide-step-title">"추가" 탭</p>
                  <p className="ios-guide-step-desc">홈 화면에 BitMan 앱 아이콘이 생성됩니다</p>
                </div>
              </div>
            </div>
            <button onClick={closeIOSGuide} className="ios-guide-done">확인</button>
          </div>
        </>
      )}

      {/* ===== 데스크탑 설치 가이드 모달 ===== */}
      {showDesktopGuide && (
        <>
          <div className="ios-guide-overlay" onClick={closeDesktopGuide} />
          <div className="ios-guide-modal">
            <button className="ios-guide-close" onClick={closeDesktopGuide}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6L6 18" /><path d="M6 6l12 12" /></svg>
            </button>
            <div className="ios-guide-icon logo-gold">
              <span className="font-black text-lg logo-gold-text">B</span>
            </div>
            <h3 className="ios-guide-title">BitMan 앱 설치하기</h3>
            <p className="ios-guide-subtitle">홈 화면에 추가하여 앱처럼 사용하세요</p>
            {hasNativePrompt ? (
              <div className="ios-guide-steps" style={{ marginBottom: 16 }}>
                <p className="ios-guide-step-desc" style={{ textAlign: 'center', color: 'var(--text-secondary)', fontSize: 13 }}>아래 버튼을 눌러 바로 설치할 수 있습니다</p>
              </div>
            ) : (
            <div className="ios-guide-steps">
              <div className="ios-guide-step">
                <div className="ios-guide-step-num">1</div>
                <div>
                  <p className="ios-guide-step-title">주소창 오른쪽 설치 아이콘 클릭</p>
                  <p className="ios-guide-step-desc">Chrome: <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#FFD700" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{display:'inline',verticalAlign:'middle'}}><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M12 8v8"/><path d="M8 12h8"/></svg> 아이콘 또는 메뉴(⋮) → "앱 설치"</p>
                </div>
              </div>
              <div className="ios-guide-step">
                <div className="ios-guide-step-num">2</div>
                <div>
                  <p className="ios-guide-step-title">"설치" 버튼 클릭</p>
                  <p className="ios-guide-step-desc">팝업에서 "설치"를 눌러 완료합니다</p>
                </div>
              </div>
            </div>
            )}
            {hasNativePrompt ? (
              <button onClick={() => { triggerNativeInstall(); closeDesktopGuide(); }} className="ios-guide-done">지금 설치하기</button>
            ) : (
              <button onClick={closeDesktopGuide} className="ios-guide-done">확인</button>
            )}
          </div>
        </>
      )}

      {/* ===== 하단 네비게이션 ===== */}
      <nav className="sticky bottom-0 z-50 w-full" style={{ backgroundColor: 'rgba(13,17,23,0.95)', backdropFilter: 'blur(20px)', WebkitBackdropFilter: 'blur(20px)', borderTop: '1px solid var(--border-subtle)' }}>
        <div className="bottom-nav">
          <NavItem icon="🏠" label="홈" active={currentPath === '/'} onClick={() => navigate('/')} />
          <NavItem icon="📊" label="수급" active={currentPath === '/supply'} onClick={() => navigate('/supply')} />
          <NavItem icon="👤" label="마이" active={currentPath === '/my'} onClick={() => navigate('/my')} />
        </div>
      </nav>
    </div>
  )
}

/* ===== 서브 컴포넌트 ===== */

function MarketChip({ label, value, change, isUp, delay = 0 }: {
  label: string; value: string; change: string; isUp: boolean; delay?: number
}) {
  const animatedValue = useCountUp(value, 1400)
  const showChange = useBlinkOnMount(delay + 800)
  return (
    <div className="flex-shrink-0 flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] market-chip" style={{ backgroundColor: 'var(--bg-card)', border: `1px solid ${isUp ? 'rgba(0,200,83,0.12)' : 'rgba(255,23,68,0.12)'}` }}>
      <span className="font-medium" style={{ color: 'var(--text-secondary)' }}>{label}</span>
      <span className="font-bold font-mono tabular-nums" style={{ color: 'var(--text-primary)' }}>{animatedValue}</span>
      <span className={`font-bold font-mono tabular-nums transition-all duration-500 ${showChange ? 'market-change-glow' : ''}`} style={{ color: isUp ? 'var(--color-bull)' : 'var(--color-bear)', opacity: showChange ? 1 : 0, transform: showChange ? 'translateY(0)' : 'translateY(3px)' }}>
        {isUp ? '▲' : '▼'} {change}
      </span>
    </div>
  )
}

function NavItem({ icon, label, active = false, onClick }: { icon: string; label: string; active?: boolean; onClick?: () => void }) {
  return (
    <button className="nav-item" style={{ opacity: active ? 1 : 0.45 }} onClick={onClick}>
      <span className="text-lg">{icon}</span>
      <span className="text-[9px] font-bold" style={{ color: active ? 'var(--color-bull)' : 'var(--text-muted)' }}>{label}</span>
      {active && <div className="w-4 h-0.5 rounded-full" style={{ backgroundColor: 'var(--color-bull)', marginTop: '1px' }} />}
    </button>
  )
}
