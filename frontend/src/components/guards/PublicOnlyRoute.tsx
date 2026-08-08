import { Navigate } from 'react-router-dom'
import { getStoredToken, useAuth } from '../../contexts/AuthContext'
import { memberTierOf } from '../../lib/memberTier'
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
    // 판정은 memberTier 한 곳에서만 한다(백엔드 tierOf 와 같은 규칙).
    const tier = memberTierOf(user)
    const needsRenewal = tier === 'EXPIRED'
    if (tier === 'ACTIVE') {
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
