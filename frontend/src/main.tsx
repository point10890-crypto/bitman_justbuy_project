import { StrictMode } from 'react'
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
import ProtectedRoute from './components/guards/ProtectedRoute'
import SubscribedRoute from './components/guards/SubscribedRoute'
import AdminRoute from './components/guards/AdminRoute'
import AdminPage from './pages/AdminPage'
import './index.css'

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
        <Routes>
          {/* 공개 라우트 (독립 전체화면) */}
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/login" element={<LoginPage />} />

          {/* 로그인 필수 (독립 전체화면) */}
          <Route path="/subscribe" element={
            <ProtectedRoute><SubscribePage /></ProtectedRoute>
          } />

          {/* 로그인 + 구독 필수 (AppLayout 포함) — ADMIN은 구독 없이 자동 통과 */}
          <Route element={<SubscribedRoute><AppLayout /></SubscribedRoute>}>
            <Route path="/" element={<HomePage />} />
            <Route path="/supply" element={<SupplyPage />} />
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
        console.log('[SW] registered:', reg.scope)
        setInterval(() => reg.update(), 30 * 60 * 1000)
      })
      .catch((err) => console.warn('[SW] registration failed:', err))
  })
}
