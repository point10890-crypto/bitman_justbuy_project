import { useEffect, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import type { MainConditionResponse } from '../api/conditionApi'
import { PWAInstallPrompt } from '../components/app/PWAInstallPrompt'
import { useMarketData } from '../hooks/useMarketData'
import { useMainConditions } from '../hooks/useMainConditions'

const LAST_ALERT_EVENT_KEY = 'bitman_seen_alert_event_key'
const KAKAO_URL = 'https://open.kakao.com/o/sJVLbWUe'

function formatToday() {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    weekday: 'short',
  }).format(new Date())
}

function formatValue(value: number, symbol: string) {
  if (symbol === 'USDKRW') return value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
  if (symbol === 'IXIC') return Math.round(value).toLocaleString('ko-KR')
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

function getStoredAlertEventKey() {
  try {
    return localStorage.getItem(LAST_ALERT_EVENT_KEY) || ''
  } catch {
    return ''
  }
}

function getAlertEventKey(data: MainConditionResponse | null) {
  const alertSection = data?.sections.alerts
  const signals = alertSection?.signals ?? []
  if (!alertSection || signals.length === 0) return ''
  return signals
    .slice(0, 3)
    .map(signal => `${signal.stockCode}:${signal.status}:${signal.capturedAt || signal.summary}`)
    .join('|')
}

function getAlertEventMessage(data: MainConditionResponse | null) {
  const latest = data?.sections.alerts?.signals?.[0]
  if (!latest) return '새 종목 알림이 도착했습니다.'
  const status = latest.status && latest.status !== '-' ? latest.status : latest.summary || '조건 포착'
  return `${latest.stockName} ${status}`
}

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, logout } = useAuth()
  const { data: marketData, refresh: refreshMarketData } = useMarketData()
  const isAdminPage = location.pathname.startsWith('/admin')
  const { data: conditionFeed, refetch: refetchConditions } = useMainConditions(isAdminPage ? 0 : 60_000)
  const [menuOpen, setMenuOpen] = useState(false)
  const [seenAlertEventKey, setSeenAlertEventKey] = useState(getStoredAlertEventKey)
  const [notifiedAlertEventKey, setNotifiedAlertEventKey] = useState('')
  const [hasNewAlert, setHasNewAlert] = useState(false)
  const [alertNotice, setAlertNotice] = useState<string | null>(null)
  const [refreshingMenu, setRefreshingMenu] = useState(false)
  const isAdmin = user?.role === 'ADMIN'
  const currentSection = location.pathname === '/' ? decodeURIComponent(location.hash.slice(1)) : ''
  const isHomeActive = location.pathname === '/' && !currentSection
  const isSectionActive = (section: string) => currentSection === section

  useEffect(() => {
    setMenuOpen(false)
  }, [location.pathname, location.hash])

  useEffect(() => {
    if (location.pathname !== '/' || !location.hash) return
    const section = decodeURIComponent(location.hash.slice(1))
    const timer = window.setTimeout(() => {
      document.getElementById(section)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 80)
    return () => window.clearTimeout(timer)
  }, [location.pathname, location.hash])

  useEffect(() => {
    if (isAdminPage) return
    const eventKey = getAlertEventKey(conditionFeed)
    if (!eventKey) return

    if (currentSection === 'alerts') {
      setSeenAlertEventKey(eventKey)
      try {
        localStorage.setItem(LAST_ALERT_EVENT_KEY, eventKey)
      } catch { /* ignore */ }
      setNotifiedAlertEventKey(eventKey)
      setHasNewAlert(false)
      setAlertNotice(null)
      return
    }

    if (eventKey === seenAlertEventKey) return
    if (eventKey === notifiedAlertEventKey) {
      setHasNewAlert(true)
      return
    }

    setNotifiedAlertEventKey(eventKey)
    setHasNewAlert(true)
    setAlertNotice(getAlertEventMessage(conditionFeed))

    const timer = window.setTimeout(() => {
      setAlertNotice(null)
    }, 5200)
    return () => window.clearTimeout(timer)
  }, [conditionFeed, currentSection, isAdminPage, notifiedAlertEventKey, seenAlertEventKey])

  const goHomeSection = (section: string) => {
    setMenuOpen(false)
    if (location.pathname !== '/' || location.hash !== `#${section}`) {
      navigate(`/#${section}`)
      return
    }
    document.getElementById(section)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const goMenuRoute = (path: string) => {
    setMenuOpen(false)
    navigate(path)
  }

  const goAdminMode = () => {
    setMenuOpen(false)
    navigate('/admin')
  }

  const openKakaoContact = () => {
    setMenuOpen(false)
    window.location.assign(KAKAO_URL)
  }

  const goHomeAction = (action: 'usage' | 'theme' | 'stock') => {
    setMenuOpen(false)
    try {
      if (location.pathname !== '/') {
        sessionStorage.setItem('bitman_home_action', action)
      } else {
        sessionStorage.removeItem('bitman_home_action')
      }
    } catch { /* ignore */ }
    if (location.pathname !== '/') {
      navigate('/')
    }
    window.setTimeout(() => {
      window.dispatchEvent(new CustomEvent('bitman:home-action', { detail: action }))
    }, 120)
  }

  const refreshMenuData = async () => {
    setRefreshingMenu(true)
    try {
      await Promise.all([refetchConditions(), refreshMarketData()])
    } finally {
      setRefreshingMenu(false)
      setMenuOpen(false)
    }
  }

  const handleLogout = () => {
    setMenuOpen(false)
    logout()
    navigate('/landing', { replace: true })
  }

  const goMain = () => {
    setMenuOpen(false)
    if (location.pathname !== '/' || location.hash) {
      navigate('/')
      return
    }
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const goAlerts = () => {
    const eventKey = getAlertEventKey(conditionFeed)
    if (eventKey) {
      setSeenAlertEventKey(eventKey)
      try {
        localStorage.setItem(LAST_ALERT_EVENT_KEY, eventKey)
      } catch { /* ignore */ }
      setNotifiedAlertEventKey(eventKey)
    }
    setHasNewAlert(false)
    setAlertNotice(null)
    goHomeSection('alerts')
  }

  return (
    <div className={`search-app-shell${isAdminPage ? ' admin-mode-shell' : ''}`}>
      <header className="search-app-header">
        <div className="search-header-top">
          <button className="bitman-logo-lockup app-logo-lockup" type="button" onClick={() => navigate('/')}>
            <span className="bitman-logo-mark">B<i /><em /></span>
            <span className="bitman-logo-copy">
              <strong><span>BitMan</span> 오늘 뭐사?</strong>
              <small>AI STOCK ANALYSIS ENGINE</small>
            </span>
          </button>

          <div className="header-actions">
            <div className="header-date">{formatToday()}</div>
            {isAdminPage ? (
              <span className="admin-mode-badge">ADMIN MODE</span>
            ) : (
              <>
                <div className="header-menu">
                  <button
                    className={`hamburger-button${menuOpen ? ' active' : ''}`}
                    type="button"
                    aria-label={menuOpen ? '메뉴 닫기' : '메뉴 열기'}
                    aria-expanded={menuOpen}
                    onClick={() => setMenuOpen(open => !open)}
                  >
                    <span />
                    <span />
                    <span />
                  </button>

                  {menuOpen && (
                    <div className="header-menu-panel" role="menu">
                      <span className="header-menu-label">조건검색</span>
                      <button type="button" role="menuitem" onClick={refreshMenuData} disabled={refreshingMenu}>
                        <strong>{refreshingMenu ? '갱신 중' : '새로고침'}</strong>
                        <span>조건검색 · 시장지표 다시 불러오기</span>
                      </button>
                      <button type="button" role="menuitem" onClick={goMain}>
                        <strong>메인</strong>
                        <span>오늘의 조건검색 홈</span>
                      </button>
                      <button type="button" role="menuitem" onClick={() => goHomeSection('short-term')}>
                        <strong>단타</strong>
                        <span>장중 빠른 포착</span>
                      </button>
                      <button type="button" role="menuitem" onClick={() => goHomeSection('swing')}>
                        <strong>스윙</strong>
                        <span>며칠 보유 관점</span>
                      </button>
                      <button type="button" role="menuitem" onClick={() => goHomeSection('leaders')}>
                        <strong>주도주</strong>
                        <span>시장 중심 종목</span>
                      </button>
                      <button type="button" role="menuitem" onClick={() => goHomeSection('themes')}>
                        <strong>테마주</strong>
                        <span>공시 · 뉴스 테마</span>
                      </button>
                      <button type="button" role="menuitem" onClick={() => goHomeSection('closing-bet')}>
                        <strong>종가매매</strong>
                        <span>장마감 후보 조건검색</span>
                      </button>
                      <button type="button" role="menuitem" onClick={() => goHomeSection('alerts')}>
                        <strong>종목 알림이</strong>
                        <span>관심 종목 알림</span>
                      </button>

                      <span className="header-menu-label header-menu-label-account">빠른 실행</span>
                      <button type="button" role="menuitem" onClick={() => goHomeAction('usage')}>
                        <strong>앱 이용방법</strong>
                        <span>사용법 안내 엔드포인트</span>
                      </button>
                      <button type="button" role="menuitem" onClick={() => goHomeAction('theme')}>
                        <strong>테마분석</strong>
                        <span>테마주 분석 엔드포인트</span>
                      </button>
                      <button type="button" role="menuitem" onClick={() => goHomeAction('stock')}>
                        <strong>종목분석</strong>
                        <span>AI 종목 분석 엔드포인트</span>
                      </button>
                      <PWAInstallPrompt
                        variant="menu-item"
                        role="menuitem"
                        showWhenDismissed
                        label="앱 설치"
                        description="휴대폰 홈 화면에서 바로 실행"
                      />

                      <span className="header-menu-label header-menu-label-account">계정</span>
                      {isAdmin && (
                        <button type="button" role="menuitem" onClick={() => goMenuRoute('/admin')}>
                          <strong>ADMIN</strong>
                          <span>승인 · 구독 · 회원 관리</span>
                        </button>
                      )}
                      <button type="button" role="menuitem" onClick={() => goMenuRoute('/my')}>
                        <strong>MY</strong>
                        <span>내 구독과 계정 관리</span>
                      </button>
                      <button type="button" role="menuitem" onClick={handleLogout}>
                        <strong>OUT</strong>
                        <span>로그아웃</span>
                      </button>
                    </div>
                  )}
                </div>
                {menuOpen && (
                  <button
                    className="header-menu-backdrop"
                    type="button"
                    aria-label="메뉴 닫기"
                    onClick={() => setMenuOpen(false)}
                  />
                )}
              </>
            )}
          </div>
        </div>

        <div className="maker-market-ticker" aria-label="시장 지표">
          <div className="maker-market-track">
            {[...marketData, ...marketData].map((item, index) => (
              <span className="maker-market-chip" key={`${item.symbol}-${index}`}>
                <b>{item.label}</b>
                <strong>{formatValue(item.value, item.symbol)}</strong>
                <em className={item.isUp ? 'up' : 'down'}>
                  {item.isUp ? '▲' : '▼'} {item.changePercent.toFixed(2)}%
                </em>
              </span>
            ))}
          </div>
        </div>
      </header>

      <Outlet />

      {!isAdminPage && alertNotice && (
        <div className="event-alert-toast" role="status" aria-live="polite">
          <span className="event-alert-bell" />
          <div>
            <strong>새 알림</strong>
            <p>{alertNotice}</p>
          </div>
          <button type="button" onClick={goAlerts}>보기</button>
        </div>
      )}

      {!isAdminPage && (
        <PWAInstallPrompt
          variant="banner"
          label="BitMan 앱 설치"
          description="홈 화면에 추가해서 빠르게 확인"
        />
      )}

      {!isAdminPage && (
        <nav className="search-bottom-nav" aria-label="주요 메뉴">
          <button className={isHomeActive ? 'active' : ''} aria-current={isHomeActive ? 'page' : undefined} type="button" onClick={goMain}>
            <span className="nav-line-icon home-icon" />
            <span>홈</span>
          </button>
          <button className={isSectionActive('short-term') ? 'active' : ''} aria-current={isSectionActive('short-term') ? 'page' : undefined} type="button" onClick={() => goHomeSection('short-term')}>
            <span className="nav-line-icon target-icon" />
            <span>단타</span>
          </button>
          <button className={isSectionActive('swing') ? 'active' : ''} aria-current={isSectionActive('swing') ? 'page' : undefined} type="button" onClick={() => goHomeSection('swing')}>
            <span className="nav-line-icon swing-icon" />
            <span>스윙</span>
          </button>
          <button className={isSectionActive('leaders') ? 'active' : ''} aria-current={isSectionActive('leaders') ? 'page' : undefined} type="button" onClick={() => goHomeSection('leaders')}>
            <span className="nav-line-icon chart-icon" />
            <span>주도주</span>
          </button>
          <button className={isSectionActive('themes') ? 'active' : ''} aria-current={isSectionActive('themes') ? 'page' : undefined} type="button" onClick={() => goHomeSection('themes')}>
            <span className="nav-line-icon chart-icon" />
            <span>테마</span>
          </button>
          <button className={isSectionActive('closing-bet') ? 'active' : ''} aria-current={isSectionActive('closing-bet') ? 'page' : undefined} type="button" onClick={() => goHomeSection('closing-bet')}>
            <span className="nav-line-icon chart-icon" />
            <span>종가</span>
          </button>
          <button
            className={`${isSectionActive('alerts') ? 'active' : ''}${hasNewAlert ? ' has-new-alert' : ''}`}
            aria-current={isSectionActive('alerts') ? 'page' : undefined}
            type="button"
            onClick={goAlerts}
          >
            <span className={`nav-line-icon bell-icon${hasNewAlert ? ' bell-alerting' : ''}`} />
            <span>알림</span>
            {hasNewAlert && <span className="nav-alert-dot" aria-hidden="true" />}
          </button>
          <PWAInstallPrompt
            variant="bottom-nav"
            showWhenDismissed
            label="설치"
          />
          {isAdmin ? (
            <button type="button" onClick={goAdminMode}>
              <span className="nav-line-icon admin-icon" />
              <span>ADMIN</span>
            </button>
          ) : (
            <button className="nav-contact-button" type="button" onClick={openKakaoContact} aria-label="카카오톡 문의">
              <span className="kakao-nav-mark">톡</span>
              <span>문의</span>
            </button>
          )}
        </nav>
      )}
    </div>
  )
}
