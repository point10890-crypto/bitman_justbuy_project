import { Navigate, useLocation } from 'react-router-dom'
import { isSubscriptionExpired, useAuth } from '../../contexts/AuthContext'
import type { ReactNode } from 'react'

function LoadingScreen() {
  return (
    <div className="route-loading">
      <div />
    </div>
  )
}

export default function SubscribedRoute({ children }: { children: ReactNode }) {
  const { user, isLoading } = useAuth()
  const location = useLocation()

  if (isLoading) return <LoadingScreen />

  if (!user) {
    if (location.pathname === '/') return <Navigate to="/landing" replace />
    return <Navigate to="/login" state={{ message: '로그인이 필요한 서비스입니다.' }} replace />
  }

  if (user.role === 'ADMIN') return <>{children}</>

  if (location.pathname === '/my') return <>{children}</>

  const expiredPro = user.subscription === 'pro' && isSubscriptionExpired(user.subscriptionEndDate)
  if (user.subscription !== 'pro' || expiredPro) {
    const message = user.subscription === 'pending'
      ? '구독 승인 대기 중입니다. 입금 확인 후 관리자가 승인합니다.'
      : expiredPro || user.subscriptionExpired
        ? '월간 이용권이 만료되었습니다. 구독을 연장해 주세요.'
        : '월간 이용권 구독 후 이용할 수 있습니다.'

    return (
      <Navigate
        to="/subscribe"
        state={{ message, from: location.pathname, renewal: expiredPro || user.subscriptionExpired }}
        replace
      />
    )
  }

  return <>{children}</>
}
