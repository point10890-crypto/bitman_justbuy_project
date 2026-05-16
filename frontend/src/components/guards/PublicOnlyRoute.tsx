import { Navigate } from 'react-router-dom'
import { getStoredToken, isSubscriptionExpired, useAuth } from '../../contexts/AuthContext'
import type { ReactNode } from 'react'

function LoadingScreen() {
  return (
    <div className="route-loading">
      <div />
    </div>
  )
}

export default function PublicOnlyRoute({ children }: { children: ReactNode }) {
  const { user, isLoading } = useAuth()
  const hasToken = !!getStoredToken()

  if (isLoading && hasToken) return <LoadingScreen />

  if (user) {
    const needsRenewal = user.subscriptionExpired || (user.subscription === 'pro' && isSubscriptionExpired(user.subscriptionEndDate))
    if (user.role === 'ADMIN' || (user.subscription === 'pro' && !needsRenewal)) {
      return <Navigate to="/" replace />
    }
    return (
      <Navigate
        to="/subscribe"
        state={needsRenewal ? { message: '월간 이용권이 만료되었습니다. 구독을 연장해 주세요.', renewal: true } : undefined}
        replace
      />
    )
  }

  return <>{children}</>
}
