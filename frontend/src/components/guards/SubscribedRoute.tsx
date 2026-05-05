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

  if (user.role === 'ADMIN') return <>{children}</>

  if (user.subscription !== 'pro') {
    const isPending = user.subscription === 'pending'
    const message = isPending
      ? '구독 승인 대기 중입니다. 입금 확인 후 처리됩니다.'
      : user.subscriptionExpired
        ? 'PRO 구독이 만료되었습니다. 재구독 신청을 진행해 주세요.'
        : '이 서비스는 PRO 구독 후 이용 가능합니다.'

    return (
      <Navigate
        to="/subscribe"
        state={{ message, from: location.pathname }}
        replace
      />
    )
  }

  return <>{children}</>
}
