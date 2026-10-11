import { useEffect, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { PWAInstallPrompt } from '../components/app/PWAInstallPrompt'
import { useAuth } from '../contexts/AuthContext'
import { authPath, formatWon, getSubscriptionPlan, postAuthPath, subscriptionPlanFromSearch } from '../lib/subscriptionPlans'
import { memberTierOf } from '../lib/memberTier'

const KAKAO_URL = 'https://open.kakao.com/o/sJVLbWUe'

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { login, user, isLoading } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(() => localStorage.getItem('bitman_remember') !== '0')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const params = new URLSearchParams(location.search)
  const selectedPlan = params.has('plan') ? subscriptionPlanFromSearch(location.search) : undefined
  const renewal = params.get('renewal') === '1' || !!(location.state as { renewal?: boolean } | null)?.renewal

  useEffect(() => {
    if (isLoading || !user) return

    const tier = memberTierOf(user)
    const expired = tier === 'EXPIRED'
    navigate(user.role === 'ADMIN' ? '/' : postAuthPath(location.search, tier === 'ACTIVE', renewal || expired, user.subscriptionPlan), {
      replace: true,
      state: expired
        ? { message: '이용권이 만료되었습니다. 구독을 연장해 주세요.', renewal: true }
        : undefined,
    })
  }, [user, isLoading, navigate, location.search, renewal])

  useEffect(() => {
    const state = location.state as { message?: string; renewal?: boolean } | null
    if (state?.message) {
      setError(state.message)
      navigate(`${location.pathname}${location.search}`, { replace: true, state: { renewal: state.renewal } })
    }
  }, [location, navigate])

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    if (!email.trim()) {
      setError('이메일을 입력해 주세요.')
      return
    }
    if (!password) {
      setError('비밀번호를 입력해 주세요.')
      return
    }

    try {
      setLoading(true)
      const loggedInUser = await login(email.trim().toLowerCase(), password, rememberMe)
      const tier = memberTierOf(loggedInUser)
      const expired = tier === 'EXPIRED'
      navigate(loggedInUser.role === 'ADMIN' ? '/' : postAuthPath(location.search, tier === 'ACTIVE', renewal || expired, loggedInUser.subscriptionPlan), {
          replace: true,
          state: expired
            ? { message: '이용권이 만료되었습니다. 구독을 연장해 주세요.', renewal: true }
            : undefined,
        })
    } catch (err) {
      setError(err instanceof Error ? err.message : '로그인에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="auth-page with-public-contact">
      <section className="auth-card">
        <button className="bitman-logo-lockup auth-logo" type="button" onClick={() => navigate('/landing')}>
          <span className="bitman-logo-mark">B<i /><em /></span>
          <span className="bitman-logo-copy">
            <strong><span>BitMan</span> 오늘 뭐사?</strong>
            <small>AI STOCK ANALYSIS ENGINE</small>
          </span>
        </button>

        <div className="auth-title">
          <h1>로그인</h1>
          <p>{selectedPlan ? `로그인 후 선택한 ${getSubscriptionPlan(selectedPlan).label} 이용권 (${formatWon(getSubscriptionPlan(selectedPlan).price)}) 신청을 이어갑니다.` : '기존 계정으로 로그인해 이용권을 확인하거나 재구독을 신청하세요.'}</p>
        </div>

        {error && <div className="auth-error">{error}</div>}

        <form onSubmit={handleSubmit} className="auth-form">
          <label>
            <span>이메일</span>
            <input
              type="text"
              inputMode="email"
              value={email}
              onChange={event => setEmail(event.target.value)}
              autoComplete="email"
              placeholder="you@example.com"
            />
          </label>
          <label>
            <span>비밀번호</span>
            <input
              type="password"
              value={password}
              onChange={event => setPassword(event.target.value)}
              autoComplete="current-password"
              placeholder="비밀번호"
            />
          </label>

          <label className="auth-check">
            <input type="checkbox" checked={rememberMe} onChange={event => setRememberMe(event.target.checked)} />
            <span>로그인 유지</span>
          </label>

          <button className="auth-submit" type="submit" disabled={loading}>
            {loading ? '로그인 중...' : '로그인'}
          </button>
        </form>

        <p className="auth-link-row">
          계정이 없나요? <Link to={authPath('register', selectedPlan, renewal)}>회원가입</Link>
        </p>
        <div className="auth-install-row">
          <PWAInstallPrompt variant="compact" showWhenDismissed label="앱 설치하기" />
        </div>
        <a className="auth-contact-button" href={KAKAO_URL} target="_blank" rel="noreferrer">
          <span className="kakao-nav-mark">톡</span>
          <strong>카카오톡 문의</strong>
        </a>
      </section>
      <a className="public-contact-bottom" href={KAKAO_URL} target="_blank" rel="noreferrer">
        <span className="kakao-nav-mark">톡</span>
        <strong>카카오톡 문의</strong>
      </a>
    </main>
  )
}
