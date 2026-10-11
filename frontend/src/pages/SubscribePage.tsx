import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { PWAInstallPrompt } from '../components/app/PWAInstallPrompt'
import { SubscriptionPaymentDetails } from '../components/app/SubscriptionPaymentDetails'
import { SubscriptionExpiredNotice } from '../components/app/SubscriptionExpiredNotice'
import { useAuth } from '../contexts/AuthContext'
import { isSubscriptionExpired, memberTierOf } from '../lib/memberTier'
import { SUBSCRIPTION_BANK_ACCOUNT } from '../lib/subscriptionPayment'
import { fetchSubscriptionPlans, type SubscriptionPlanDto, type SubscriptionPlanId } from '../api/authApi'
import {
  authPath, formatWon, getSubscriptionPlan, SUBSCRIPTION_PLANS,
  subscribePath, subscriptionPlanFromSearch, validateSubscriptionPlans,
} from '../lib/subscriptionPlans'
import './SubscribePage.css'

const { accountNumber: BANK_ACCOUNT, bankName: BANK_NAME, accountHolder: ACCOUNT_HOLDER } = SUBSCRIPTION_BANK_ACCOUNT
const KAKAO_URL = 'https://open.kakao.com/o/sJVLbWUe'

function PlanFeature({ children }: { children: React.ReactNode }) {
  return <li><span aria-hidden="true">✓</span><strong>{children}</strong></li>
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

function BankAccountBox({ copied, onCopy }: { copied: boolean; onCopy: () => void }) {
  return (
    <div className="bank-box">
      <div>
        <span>입금 계좌</span>
        <strong>{BANK_NAME} {BANK_ACCOUNT}</strong>
        <small>예금주 {ACCOUNT_HOLDER}</small>
      </div>
      <button type="button" onClick={onCopy}>{copied ? '복사됨' : '계좌 복사'}</button>
    </div>
  )
}

function SubscriptionLogo({ onClick }: { onClick: () => void }) {
  return (
    <button className="bitman-logo-lockup auth-logo" type="button" onClick={onClick}>
      <span className="bitman-logo-mark">B<i /><em /></span>
      <span className="bitman-logo-copy">
        <strong><span>BitMan</span> 오늘 뭐사?</strong>
        <small>AI STOCK ANALYSIS ENGINE</small>
      </span>
    </button>
  )
}

function SubscriptionSteps({ pending = false }: { pending?: boolean }) {
  return (
    <ol className="subscription-steps subscription-flow-steps" aria-label="구독 진행 순서">
      <li className="done"><strong>1</strong><span>플랜 선택</span></li>
      <li className={pending ? 'done' : 'active'}><strong>2</strong><span>입금 · 신청</span></li>
      <li className={pending ? 'active' : ''}><strong>3</strong><span>승인 후 이용</span></li>
    </ol>
  )
}

export default function SubscribePage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, isLoading, applySubscription, refreshUser, logout } = useAuth()
  const [depositorName, setDepositorName] = useState(user?.depositorName || user?.name || '')
  const [copied, setCopied] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [catalog, setCatalog] = useState<readonly SubscriptionPlanDto[]>(SUBSCRIPTION_PLANS)
  const [catalogReady, setCatalogReady] = useState(false)
  const [catalogError, setCatalogError] = useState('')
  const [catalogAttempt, setCatalogAttempt] = useState(0)
  const pendingSeen = useRef(false)
  const submitting = useRef(false)
  const copyTimeout = useRef<ReturnType<typeof setTimeout> | null>(null)
  const routeState = location.state as { message?: string; renewal?: boolean; renewalPending?: boolean } | null
  const params = new URLSearchParams(location.search)
  const tier = user ? memberTierOf(user) : 'NONE'
  const pending = user?.subscription === 'pending' || !!user?.subscriptionRenewalPending
  const expired = tier === 'EXPIRED' && !pending
  const selectedId = expired && !params.has('plan')
    ? getSubscriptionPlan(user?.subscriptionPlan).id
    : subscriptionPlanFromSearch(location.search)
  const selectedPlan = catalog.find(plan => plan.id === selectedId) || getSubscriptionPlan(selectedId)
  const activePro = tier === 'ACTIVE'
  const unlimitedAccess = user?.role === 'ADMIN' || (activePro && !user?.subscriptionEndDate)
  const renewal = !!routeState?.renewal || !!routeState?.renewalPending || params.get('renewal') === '1' || expired
    || (activePro && params.has('plan'))

  useEffect(() => {
    if (isLoading || !expired) return
    if (!params.has('plan') || params.get('renewal') !== '1') {
      navigate(subscribePath(selectedId, true), { replace: true, state: location.state })
    }
  }, [isLoading, expired, location.search, location.state, selectedId, navigate])

  useEffect(() => {
    let cancelled = false
    setCatalogReady(false)
    setCatalogError('')
    fetchSubscriptionPlans().then(plans => {
      if (cancelled) return
      if (!validateSubscriptionPlans(plans)) throw new Error('이용권 가격을 확인할 수 없습니다. 다시 확인해 주세요.')
      setCatalog(plans)
      setCatalogReady(true)
    }).catch(err => {
      if (!cancelled) setCatalogError(err instanceof Error ? err.message : '이용권 정보를 불러오지 못했습니다.')
    })
    return () => { cancelled = true }
  }, [catalogAttempt])

  useEffect(() => {
    if (pending) pendingSeen.current = true
    if (activePro && !pending && (pendingSeen.current || !renewal)) navigate('/', { replace: true })
  }, [activePro, pending, renewal, navigate])

  useEffect(() => {
    if (user) setDepositorName(user.depositorName || user.name)
  }, [user?.id])

  useEffect(() => {
    if (!pending) return
    const interval = setInterval(() => refreshUser(), 30_000)
    return () => clearInterval(interval)
  }, [pending, refreshUser])

  useEffect(() => () => {
    if (copyTimeout.current) clearTimeout(copyTimeout.current)
  }, [])

  const handleLogout = () => { logout(); window.location.replace('/landing') }
  const copyAccount = async () => {
    try {
      await navigator.clipboard.writeText(BANK_ACCOUNT)
      setCopied(true)
      if (copyTimeout.current) clearTimeout(copyTimeout.current)
      copyTimeout.current = setTimeout(() => setCopied(false), 1600)
    } catch { setError('계좌를 복사하지 못했습니다. 위 계좌번호를 직접 확인해 주세요.') }
  }
  const selectPlan = (plan: SubscriptionPlanId) => {
    navigate(subscribePath(plan, renewal), { replace: true, state: routeState })
    setError('')
  }
  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    if (!user || unlimitedAccess || !catalogReady || submitting.current) return
    if (!depositorName.trim()) { setError('입금자명을 입력해 주세요.'); return }
    try {
      submitting.current = true
      setLoading(true)
      await applySubscription(depositorName.trim(), selectedId)
      if (activePro) navigate('/my', { replace: true })
    } catch (err) { setError(err instanceof Error ? err.message : '구독 신청에 실패했습니다.') }
    finally { submitting.current = false; setLoading(false) }
  }

  if (isLoading) return <main className="route-loading"><div /></main>
  if (unlimitedAccess) return <Navigate to="/" replace />

  if (pending && user) {
    const requested = getSubscriptionPlan(user.requestedSubscriptionPlan)
    const expiredRenewal = !!(user.subscriptionApprovedAt || user.subscriptionEndDate) && isSubscriptionExpired(user.subscriptionEndDate)
    return (
      <main className="subscription-page subscription-flow-page">
        <section className="subscription-card pending">
          <SubscriptionLogo onClick={() => navigate(activePro ? '/' : '/landing')} />
          <div className="subscription-state-icon" aria-hidden="true">⌛</div>
          <h1>{activePro ? '구독 연장 승인 대기 중' : expiredRenewal ? '재구독 승인 대기 중' : '구독 승인 대기 중'}</h1>
          <p>입금 확인 후 {requested.label} 이용권이 활성화됩니다. {activePro ? '현재 이용권은 남은 기간 동안 계속 이용할 수 있습니다.' : '승인되면 자동으로 앱 홈으로 연결됩니다.'}</p>
          <SubscriptionPaymentDetails plan={user.requestedSubscriptionPlan} depositorName={user.depositorName} />
          <SubscriptionSteps pending />
          <button className="auth-submit" type="button" onClick={() => refreshUser()}>승인 상태 새로고침</button>
          <a className="subscription-contact" href={KAKAO_URL} target="_blank" rel="noreferrer">입금 · 승인 문의</a>
          <SubscriptionActions onManage={() => navigate('/my')} onLogout={handleLogout} />
        </section>
      </main>
    )
  }

  return (
    <main className="subscription-page subscription-flow-page">
      <section className="subscription-card">
        <SubscriptionLogo onClick={() => navigate(user ? '/' : '/landing')} />
        <div className="subscription-title">
          <h1>{expired ? '재구독 신청' : renewal ? '이용권 연장' : 'BitMan 구독'}</h1>
          <p>{expired ? '이용 기간과 입금자명을 확인하고 재구독을 신청하세요.' : '이용 기간을 선택하고, 입금 후 신청하면 전체 분석 결과를 볼 수 있습니다.'}</p>
        </div>
        {expired && <SubscriptionExpiredNotice plan={user?.subscriptionPlan} endDate={user?.subscriptionEndDate} />}
        {!expired && routeState?.message && <div className="subscription-route-message">{routeState.message}</div>}
        {activePro && renewal && <p className="subscription-renewal-note">남은 이용 기간에 선택한 기간이 더해집니다. 승인 대기 중에도 현재 이용권은 유지됩니다.</p>}
        <fieldset className="subscription-plan-options">
          <legend>이용권 선택</legend>
          {catalog.map(plan => (
            <label key={plan.id} className={`subscription-plan-option${selectedId === plan.id ? ' selected' : ''}`}>
              <input type="radio" name="subscriptionPlan" value={plan.id} checked={selectedId === plan.id} onChange={() => selectPlan(plan.id)} />
              <span className="subscription-plan-period">{plan.label}</span>
              {plan.id === 'SIX_MONTHS' && <span className="subscription-plan-saving">30,000원 절약</span>}
              <strong>{formatWon(plan.price)}</strong>
              <small>{plan.id === 'SIX_MONTHS' ? '월 25,000원꼴 · 일시 납부' : '1개월 이용 · 일시 납부'}</small>
            </label>
          ))}
        </fieldset>
        <div className="subscription-price"><span>{selectedPlan.label} 입금 금액</span><strong>{formatWon(selectedPlan.price)}</strong></div>
        <SubscriptionSteps />
        {!expired && <ul className="subscription-features">
          <PlanFeature>단타 · 스윙 · 주도주 TOP3 전체 공개</PlanFeature>
          <PlanFeature>테마 분석과 종목 알림 이용</PlanFeature>
          <PlanFeature>KIS/DART 기반 AI 요약 확인</PlanFeature>
        </ul>}
        {catalogError ? <div className="subscription-catalog-error" role="alert"><p>{catalogError}</p><button type="button" onClick={() => setCatalogAttempt(attempt => attempt + 1)}>이용권 다시 확인</button></div>
          : !catalogReady && <p className="subscription-note" role="status">이용권 정보를 확인하고 있습니다.</p>}
        {!user ? (
          <div className="subscription-guest-actions">
            <p>가입은 무료입니다. 선택한 {selectedPlan.label} 이용권은 가입 후 그대로 이어집니다.</p>
            <Link className="auth-submit" to={authPath('register', selectedId, renewal)}>무료 회원가입 후 구독 신청</Link>
            <Link className="subscription-login-link" to={authPath('login', params.has('plan') ? selectedId : undefined, renewal)}>이미 계정이 있나요? 로그인</Link>
          </div>
        ) : (
          <>
            <BankAccountBox copied={copied} onCopy={copyAccount} />
            {error && <div className="auth-error" role="alert">{error}</div>}
            <form className="auth-form" onSubmit={handleSubmit}>
              <label><span>입금자명</span><input value={depositorName} onChange={event => setDepositorName(event.target.value)} autoComplete="name" placeholder="실제 입금자명" /></label>
              <button className="auth-submit" type="submit" disabled={loading || !catalogReady}>{loading ? '신청 중...' : expired ? '재구독 신청하기' : '구독 신청하기'}</button>
            </form>
            <p className="subscription-note">선택한 금액을 입금한 뒤 신청해 주세요. 입금자명은 실제 송금한 이름과 같아야 합니다. 자동 결제는 되지 않습니다.</p>
            <SubscriptionActions onManage={() => navigate('/my')} onLogout={handleLogout} />
          </>
        )}
        <a className="subscription-contact" href={KAKAO_URL} target="_blank" rel="noreferrer">구독 문의</a>
      </section>
    </main>
  )
}
