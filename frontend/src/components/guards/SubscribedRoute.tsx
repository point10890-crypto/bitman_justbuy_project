import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../../contexts/AuthContext'
import { memberTierOf } from '../../lib/memberTier'
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

  // 판정은 memberTier 한 곳에서만 한다 — 백엔드 tierOf 와 같은 규칙이다.
  // 이전에는 이 파일이 자체 조건을 갖고 있어 관리자가 해제한 회원을
  // "구독한 적 없음"으로 오분류했다.
  const tier = memberTierOf(user)

  // NO티어(가입만 하고 구독 이력 없음)는 홈에서 마스킹 미리보기를 볼 수 있게 통과시킨다.
  // 가치를 한 번도 못 본 채 결제 페이지로 튕기면 그대로 이탈한다.
  // 실제 종목/가격은 서버가 가려서 내려주므로 통과시켜도 유료 데이터는 노출되지 않는다.
  // 만료 회원은 이미 가치를 아는 집단이라 재구독 페이지로 바로 보낸다(기존 동작 유지).
  if (location.pathname === '/' && tier === 'NONE') return <>{children}</>

  if (tier !== 'ACTIVE') {
    const isExpired = tier === 'EXPIRED'
    const message = tier === 'PENDING'
      ? '구독 승인 대기 중입니다. 입금 확인 후 관리자가 승인합니다.'
      : isExpired
        ? '월간 이용권이 만료되었습니다. 재구독 신청을 해주세요.'
        : '월간 이용권 구독 후 이용할 수 있습니다.'

    return (
      <Navigate
        to="/subscribe"
        state={{ message, from: location.pathname, renewal: isExpired }}
        replace
      />
    )
  }

  return <>{children}</>
}
