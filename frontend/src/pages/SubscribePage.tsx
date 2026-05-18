import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { PWAInstallPrompt } from '../components/app/PWAInstallPrompt'
import { useAuth } from '../contexts/AuthContext'

const BANK_ACCOUNT = '2259-02-04-057670'
const BANK_NAME = '국민은행'
const ACCOUNT_HOLDER = '이종민'
const KAKAO_URL = 'https://open.kakao.com/o/sJVLbWUe'

function PlanFeature({ children }: { children: React.ReactNode }) {
  return (
    <li>
      <span>✓</span>
      <strong>{children}</strong>
    </li>
  )
}

function SubscriptionActions({ onManage, onLogout }: { onManage: () => void; onLogout: () => void }) {
  return (
    <div className="subscription-secondary-actions">
      <PWAInstallPrompt variant="compact" showWhenDismissed label="앱 설치" />
      <button type="button" onClick={onManage}>내 계정 관리</button>
      <button type="button" onClick={onLogout}>로그아웃</button>
    </div>
  )
}

function SubscriptionPending({
  onRefresh,
  onManage,
  onLogout,
}: {
  onRefresh: () => void
  onManage: () => void
  onLogout: () => void
}) {
  return (
    <main className="subscription-page">
      <section className="subscription-card pending">
        <button className="bitman-logo-lockup auth-logo" type="button">
          <span className="bitman-logo-mark">B<i /><em /></span>
          <span className="bitman-logo-copy">
            <strong><span>BitMan</span> 오늘 뭐사?</strong>
            <small>AI STOCK ANALYSIS ENGINE</small>
          </span>
        </button>

        <div className="subscription-state-icon">⌛</div>
        <h1>구독 승인 대기 중</h1>
        <p>입금 확인 후 관리자가 월간 이용권을 활성화합니다. 승인되면 자동으로 앱 홈에서 전체 결과를 볼 수 있습니다.</p>

        <div className="subscription-steps">
          <div className="done"><strong>1</strong><span>신청 완료</span></div>
          <div className="active"><strong>2</strong><span>입금 확인</span></div>
          <div><strong>3</strong><span>이용 시작</span></div>
        </div>

        <button className="auth-submit" type="button" onClick={onRefresh}>승인 상태 새로고침</button>
        <a className="subscription-contact" href={KAKAO_URL} target="_blank" rel="noreferrer">문의하기</a>
        <SubscriptionActions onManage={onManage} onLogout={onLogout} />
      </section>
    </main>
  )
}

export default function SubscribePage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, applySubscription, refreshUser, logout } = useAuth()
  const [depositorName, setDepositorName] = useState(user?.name || '')
  const [copied, setCopied] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const routeState = location.state as { message?: string; renewal?: boolean; renewalPending?: boolean } | null

  useEffect(() => {
    if (user?.subscription === 'pro'
      && !user.subscriptionExpired
      && !user.subscriptionRenewalPending
      && !routeState?.renewal
      && !routeState?.renewalPending) {
      navigate('/', { replace: true })
    }
  }, [user, navigate, routeState?.renewal, routeState?.renewalPending])

  useEffect(() => {
    if (!depositorName && user?.name) setDepositorName(user.name)
  }, [depositorName, user?.name])

  useEffect(() => {
    if (user?.subscription !== 'pending' && !user?.subscriptionRenewalPending) return
    pollRef.current = setInterval(() => refreshUser(), 30_000)
    return () => {
      if (pollRef.current) clearInterval(pollRef.current)
    }
  }, [user?.subscription, user?.subscriptionRenewalPending, refreshUser])

  const handleLogout = () => {
    logout()
    window.location.replace('/landing')
  }

  if (user?.subscription === 'pending') {
    return (
      <SubscriptionPending
        onRefresh={refreshUser}
        onManage={() => navigate('/my')}
        onLogout={handleLogout}
      />
    )
  }

  if (user?.subscriptionRenewalPending || routeState?.renewalPending) {
    return (
      <SubscriptionPending
        onRefresh={refreshUser}
        onManage={() => navigate('/my')}
        onLogout={handleLogout}
      />
    )
  }

  const copyAccount = async () => {
    await navigator.clipboard.writeText(`${BANK_NAME} ${BANK_ACCOUNT} ${ACCOUNT_HOLDER}`)
    setCopied(true)
    setTimeout(() => setCopied(false), 1600)
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    if (!depositorName.trim()) {
      setError('입금자명을 입력해 주세요.')
      return
    }
    try {
      setLoading(true)
      const wasRenewal = user?.subscription === 'pro' && !user.subscriptionExpired
      await applySubscription(depositorName.trim())
      if (wasRenewal) {
        navigate('/my', { replace: true })
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '구독 신청에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="subscription-page">
      <section className="subscription-card">
        <button className="bitman-logo-lockup auth-logo" type="button" onClick={() => navigate('/landing')}>
          <span className="bitman-logo-mark">B<i /><em /></span>
          <span className="bitman-logo-copy">
            <strong><span>BitMan</span> 오늘 뭐사?</strong>
            <small>AI STOCK ANALYSIS ENGINE</small>
          </span>
        </button>

        <div className="subscription-title">
          <p>Monthly Plan</p>
          <h1>{routeState?.renewal ? '월간 이용권 연장' : '월간 이용권'}</h1>
          <span>30일 구독권</span>
        </div>

        {routeState?.message && <div className="subscription-route-message">{routeState.message}</div>}

        <ul className="subscription-features">
          <PlanFeature>단타 TOP3 전체 공개</PlanFeature>
          <PlanFeature>스윙 TOP3 전체 공개</PlanFeature>
          <PlanFeature>주도주, 테마, 종목 알림이 이용</PlanFeature>
          <PlanFeature>KIS/DART 기반 AI 요약 확인</PlanFeature>
        </ul>

        <div className="bank-box">
          <div>
            <span>입금 계좌</span>
            <strong>{BANK_NAME} {BANK_ACCOUNT}</strong>
            <small>예금주 {ACCOUNT_HOLDER}</small>
          </div>
          <button type="button" onClick={copyAccount}>{copied ? '복사됨' : '복사'}</button>
        </div>

        {error && <div className="auth-error">{error}</div>}

        <form className="auth-form" onSubmit={handleSubmit}>
          <label>
            <span>입금자명</span>
            <input value={depositorName} onChange={event => setDepositorName(event.target.value)} placeholder="실제 입금자명" />
          </label>
          <button className="auth-submit" type="submit" disabled={loading}>
            {loading ? '신청 중...' : '구독 신청하기'}
          </button>
        </form>

        <p className="subscription-note">입금자명과 신청자명이 다르면 승인이 지연될 수 있습니다.</p>
        <SubscriptionActions onManage={() => navigate('/my')} onLogout={handleLogout} />
      </section>
    </main>
  )
}
