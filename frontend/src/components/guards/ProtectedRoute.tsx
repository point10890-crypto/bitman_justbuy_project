import { Navigate } from 'react-router-dom'
import { useAuth } from '../../contexts/AuthContext'
import type { ReactNode } from 'react'

function LoadingScreen() {
  return (
    <div className="route-loading">
      <div />
    </div>
  )
}

export default function ProtectedRoute({ children }: { children: ReactNode }) {
  const { user, isLoading } = useAuth()

  if (isLoading) return <LoadingScreen />

  if (!user) {
    return <Navigate to="/login" state={{ message: '로그인이 필요한 서비스입니다.' }} replace />
  }

  return <>{children}</>
}
