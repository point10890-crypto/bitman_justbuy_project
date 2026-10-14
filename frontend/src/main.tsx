import { StrictMode, Suspense, lazy } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { AuthProvider } from './contexts/AuthContext'
import AppLayout from './layouts/AppLayout'
import HomePage from './pages/HomePage'
import SupplyPage from './pages/SupplyPage'
import MyPage from './pages/MyPage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import SubscribePage from './pages/SubscribePage'
import SubscribedRoute from './components/guards/SubscribedRoute'
import AdminRoute from './components/guards/AdminRoute'
import PublicOnlyRoute from './components/guards/PublicOnlyRoute'
import AdminPage from './pages/AdminPage'
import ClosingBetHistoryPage from './pages/ClosingBetHistoryPage'
import TrackRecordPage from './pages/TrackRecordPage'
import EventResearchPage from './pages/EventResearchPage'
import SubscriptionGateWatcher from './components/guards/SubscriptionGateWatcher'
import LandingPage from './pages/LandingPage'
import './index.css'

// 조건검색 랩은 로컬 엔진(127.0.0.1:8090)이 있어야 동작한다. 운영 빌드에는 엔진이 없으므로
// dev 빌드에서만 연결하고, 운영 번들에서는 코드째 빠지도록 동적 import 를 DEV 분기 안에 둔다.
const ConditionLabPage = import.meta.env.DEV ? lazy(() => import('./pages/ConditionLabPage')) : null

// ─── 에러 캐시 자동 정리 ───
try {
  const raw = localStorage.getItem('bitman_analysis_cache')
  if (raw) {
    const cache = JSON.parse(raw)
    let cleaned = false
    for (const key of Object.keys(cache)) {
      const entry = cache[key]
      if (entry?.data?.metadata?.agentsSucceeded === 0) {
        delete cache[key]
        cleaned = true
      }
    }
    if (cleaned) {
      if (Object.keys(cache).length === 0) {
        localStorage.removeItem('bitman_analysis_cache')
      } else {
        localStorage.setItem('bitman_analysis_cache', JSON.stringify(cache))
      }
    }
  }
} catch { /* ignore */ }

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <BrowserRouter>
        <SubscriptionGateWatcher />
        <Routes>
          {/* 공개 라우트 — 로그인 상태면 즉시 replace 리다이렉트 (히스토리에 남지 않음) */}
          <Route path="/landing"  element={<PublicOnlyRoute><LandingPage /></PublicOnlyRoute>} />
          <Route path="/register" element={<PublicOnlyRoute><RegisterPage /></PublicOnlyRoute>} />
          <Route path="/login"    element={<PublicOnlyRoute><LoginPage /></PublicOnlyRoute>} />

          {/* 플랜 비교는 공개, 신청은 페이지에서 로그인 후 진행 */}
          <Route path="/subscribe" element={<SubscribePage />} />
          {ConditionLabPage && (
            <Route path="/lab" element={<Suspense fallback={null}><ConditionLabPage /></Suspense>} />
          )}

          {/* 로그인 + 구독 필수 (AppLayout 포함) — ADMIN은 구독 없이 자동 통과 */}
          <Route element={<SubscribedRoute><AppLayout /></SubscribedRoute>}>
            <Route path="/" element={<HomePage />} />
            <Route path="/supply" element={<SupplyPage />} />
            <Route path="/history/closing-bet" element={<ClosingBetHistoryPage />} />
            <Route path="/performance" element={<TrackRecordPage />} />
            <Route path="/research/events" element={<EventResearchPage />} />
            <Route path="/my" element={<MyPage />} />
            <Route path="/admin" element={<AdminRoute><AdminPage /></AdminRoute>} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
)

// ─── Service Worker Registration ───
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('/sw.js')
      .then((reg) => {
        if (import.meta.env.DEV) console.log('[SW] registered:', reg.scope)
        setInterval(() => reg.update(), 30 * 60 * 1000)
      })
      .catch((err) => {
        if (import.meta.env.DEV) console.warn('[SW] registration failed:', err)
      })
  })
}
