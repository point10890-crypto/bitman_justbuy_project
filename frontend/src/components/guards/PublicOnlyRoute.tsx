import { Navigate, useLocation } from 'react-router-dom'
import { getStoredToken, useAuth } from '../../contexts/AuthContext'
import { memberTierOf } from '../../lib/memberTier'
import { postAuthPath } from '../../lib/subscriptionPlans'
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
  const location = useLocation()
  const hasToken = !!getStoredToken()

  if (isLoading && hasToken) return <LoadingScreen />

  if (user) {
    // 판정은 memberTier 한 곳에서만 한다(백엔드 tierOf 와 같은 규칙).
    const tier = memberTierOf(user)
    const needsRenewal = tier === 'EXPIRED'
    const routeState = location.state as { renewal?: boolean } | null
    const target = user.role === 'ADMIN' ? '/' : postAuthPath(location.search, tier === 'ACTIVE', needsRenewal || !!routeState?.renewal)
    return (
      <Navigate
        to={target}
        state={needsRenewal ? { message: '이용권이 만료되었습니다. 구독을 연장해 주세요.', renewal: true } : undefined}
        replace
      />
    )
  }

  return <>{children}</>
}
