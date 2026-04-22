import { Navigate } from 'react-router-dom'
import { useAuth, getStoredToken } from '../../contexts/AuthContext'
import type { ReactNode } from 'react'

/**
 * 로그인된 사용자를 공개 페이지(랜딩·로그인·회원가입)에서 차단하는 가드.
 *
 * 동작:
 *   1. 토큰이 localStorage/sessionStorage에 있으면 auth 검증이 끝나기 전에도
 *      스피너를 표시 → 랜딩/로그인 페이지가 순간적으로 노출되는 플래시 방지
 *   2. auth 검증 완료 후 user가 있으면 구독 상태에 따라 즉시 replace 리다이렉트
 *      → 브라우저 히스토리에 공개 페이지가 남지 않음
 *   3. 토큰 없음 → 비로그인 상태 확정 → children 렌더
 */
export default function PublicOnlyRoute({ children }: { children: ReactNode }) {
  const { user, isLoading } = useAuth()
  const hasToken = !!getStoredToken()

  // 토큰이 있는데 아직 검증 중 → 공개 페이지 대신 스피너 표시
  if (isLoading && hasToken) {
    return (
      <div
        className="min-h-dvh flex items-center justify-center"
        style={{ backgroundColor: 'var(--bg-primary)' }}
      >
        <div
          className="w-8 h-8 border-2 border-t-transparent rounded-full animate-spin"
          style={{ borderColor: 'rgba(255,215,0,0.3)', borderTopColor: 'transparent' }}
        />
      </div>
    )
  }

  // 로그인 확정 → 구독 상태에 따라 바로 이동 (replace → 히스토리 교체)
  if (user) {
    if (user.role === 'ADMIN' || user.subscription === 'pro') {
      return <Navigate to="/" replace />
    }
    // pending 또는 free → 구독 페이지로
    return <Navigate to="/subscribe" replace />
  }

  // 미로그인 → 공개 페이지 정상 렌더
  return <>{children}</>
}
