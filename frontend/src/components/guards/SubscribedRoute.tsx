import { Navigate } from 'react-router-dom'
import { useAuth } from '../../contexts/AuthContext'
import type { ReactNode } from 'react'

export default function SubscribedRoute({ children }: { children: ReactNode }) {
  const { user, isLoading } = useAuth()

  if (isLoading) {
    return (
      <div className="min-h-dvh flex items-center justify-center" style={{ backgroundColor: 'var(--bg-primary)' }}>
        <div className="w-8 h-8 border-2 border-t-transparent rounded-full animate-spin" style={{ borderColor: 'rgba(255,215,0,0.3)', borderTopColor: 'transparent' }} />
      </div>
    )
  }

  if (!user) return <Navigate to="/register" replace />
  // ADMIN은 구독 없이도 메인 대시보드 접근 허용
  if (user.role === 'ADMIN') return <>{children}</>
  if (user.subscription !== 'pro') return <Navigate to="/subscribe" replace />

  return <>{children}</>
}
