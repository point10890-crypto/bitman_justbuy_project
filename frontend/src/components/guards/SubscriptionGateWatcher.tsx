import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../contexts/AuthContext'

const SUBSCRIBE_PATH = '/subscribe'

type SubscriptionRequiredDetail = {
  code?: string
  message?: string
}

/**
 * 구독 때문에 API 가 403 을 주면 재구독(또는 구독) 페이지로 즉시 이동시킨다.
 *
 * <p>라우트 가드({@code SubscribedRoute})는 <b>클라이언트가 알고 있는</b> 구독 상태로 판단한다.
 * 그래서 앱을 켜 둔 채 자정을 넘겨 구독이 만료되거나, 저장된 사용자 정보가 낡았을 때는
 * 가드를 통과한 뒤 API 에서 403 이 나고 사용자는 화면에 에러 문구만 보게 된다.
 * 그 순간을 잡아 재구독 흐름으로 넘긴다.
 *
 * <p>이동 전에 서버 기준으로 사용자 정보를 다시 읽어, 구독 페이지가 "연장 / 신규 / 승인대기"
 * 중 맞는 화면을 띄우게 한다.
 */
export default function SubscriptionGateWatcher() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, refreshUser } = useAuth()

  useEffect(() => {
    const onSubscriptionRequired = (event: Event) => {
      const detail = (event as CustomEvent<SubscriptionRequiredDetail>).detail || {}

      // 관리자는 구독과 무관하게 이용하므로 이동시키지 않는다 (오작동 방지).
      if (user?.role === 'ADMIN') return
      // 이미 구독 관련 화면이면 그대로 둔다.
      if (location.pathname === SUBSCRIBE_PATH || location.pathname === '/my') return

      const expired = detail.code === 'SUBSCRIPTION_EXPIRED'
      const pending = detail.code === 'SUBSCRIPTION_PENDING'

      // 서버 상태로 맞춘 뒤 이동. 실패해도 이동은 진행한다.
      void Promise.resolve(refreshUser())
        .catch(() => { /* 갱신 실패는 무시 — 아래 이동이 더 중요하다 */ })
        .finally(() => {
          navigate(SUBSCRIBE_PATH, {
            replace: true,
            state: {
              message: detail.message
                || (expired ? '월간 이용권이 만료되었습니다. 재구독 신청을 해주세요.' : undefined),
              renewal: expired,
              renewalPending: pending,
              from: location.pathname,
            },
          })
        })
    }

    window.addEventListener('subscription:required', onSubscriptionRequired)
    return () => window.removeEventListener('subscription:required', onSubscriptionRequired)
  }, [navigate, location.pathname, refreshUser, user?.role])

  return null
}
