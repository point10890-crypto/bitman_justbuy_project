import { Navigate, useLocation } from 'react-router-dom'
import { useAuth, getStoredToken } from '../../contexts/AuthContext'
import type { ReactNode } from 'react'

export default function SubscribedRoute({ children }: { children: ReactNode }) {
  const { user, isLoading } = useAuth()
  const location = useLocation()

  if (isLoading) {
    return (
      <div className="min-h-dvh flex items-center justify-center" style={{ backgroundColor: 'var(--bg-primary)' }}>
        <div className="w-8 h-8 border-2 border-t-transparent rounded-full animate-spin" style={{ borderColor: 'rgba(255,215,0,0.3)', borderTopColor: 'transparent' }} />
      </div>
    )
  }

  if (!user) {
    // 토큰이 있으면 로그인 직후 state 반영 대기 — 스피너 표시 (landing 리다이렉트 방지)
    if (getStoredToken()) {
      return (
        <div className="min-h-dvh flex items-center justify-center" style={{ backgroundColor: 'var(--bg-primary)' }}>
          <div className="w-8 h-8 border-2 border-t-transparent rounded-full animate-spin" style={{ borderColor: 'rgba(255,215,0,0.3)', borderTopColor: 'transparent' }} />
        </div>
      )
    }
    if (location.pathname === '/') {
      return <Navigate to="/landing" replace />
    }
    return <Navigate to="/login" state={{ message: '로그인이 필요한 서비스입니다.' }} replace />
  }

  // ADMIN은 구독 없이도 메인 대시보드 접근 허용
  if (user.role === 'ADMIN') return <>{children}</>

  // PRO 플랜 만료 여부 확인
  if (user.subscription !== 'pro') {
    // pending → 구독 페이지로 (입금 확인 중 안내)
    // free (또는 만료) → 구독 유도 페이지로 (잠금 해제 컨셉)
    const isPending = user.subscription === 'pending'
    return (
      <Navigate
        to="/subscribe"
        state={{
          message: isPending
            ? '구독 승인 대기 중입니다. 입금 확인 후 처리됩니다.'
            : '이 서비스는 PRO 구독 후 이용 가능합니다.',
          from: location.pathname,
        }}
        replace
      />
    )
  }

  return <>{children}</>
}
