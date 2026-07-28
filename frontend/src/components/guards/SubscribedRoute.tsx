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

  // 만료 판정은 두 시점을 모두 덮어야 한다.
  // 자정 배치 전: PRO + 지난 종료일 / 배치 후: FREE + 지난 종료일(종료일은 보존됨).
  const expiredPro =
    (user.subscription === 'pro' || user.subscription === 'free')
    && isSubscriptionExpired(user.subscriptionEndDate)

  if (user.subscription !== 'pro' || expiredPro) {
    const message = user.subscription === 'pending'
      ? '구독 승인 대기 중입니다. 입금 확인 후 관리자가 승인합니다.'
      : expiredPro || user.subscriptionExpired
        ? '월간 이용권이 만료되었습니다. 재구독 신청을 해주세요.'
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
