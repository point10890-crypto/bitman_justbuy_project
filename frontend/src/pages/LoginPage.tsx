import { useEffect, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { PWAInstallPrompt } from '../components/app/PWAInstallPrompt'
import { isSubscriptionExpired, useAuth } from '../contexts/AuthContext'

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { login, user, isLoading } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(() => localStorage.getItem('bitman_remember') === '1')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (isLoading || !user) return

    const activePro = user.subscription === 'pro' && !isSubscriptionExpired(user.subscriptionEndDate)
    if (user.role === 'ADMIN' || activePro) {
      navigate('/', { replace: true })
      return
    }
    navigate('/subscribe', {
      replace: true,
      state: user.subscriptionExpired
        ? { message: '월간 이용권이 만료되었습니다. 구독을 연장해 주세요.', renewal: true }
        : undefined,
    })
  }, [user, isLoading, navigate])

  useEffect(() => {
    const state = location.state as { message?: string } | null
    if (state?.message) {
      setError(state.message)
      navigate(location.pathname, { replace: true, state: {} })
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
      if (loggedInUser.role === 'ADMIN') {
        navigate('/', { replace: true })
      } else if (loggedInUser.subscription === 'pro' && !loggedInUser.subscriptionExpired) {
        navigate('/', { replace: true })
      } else {
        navigate('/subscribe', {
          replace: true,
          state: loggedInUser.subscriptionExpired
            ? { message: '월간 이용권이 만료되었습니다. 구독을 연장해 주세요.', renewal: true }
            : undefined,
        })
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '로그인에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="auth-page">
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
          <p>구독 중인 계정으로 오늘 포착된 조건검색 결과를 확인하세요.</p>
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
          계정이 없나요? <Link to="/register">회원가입</Link>
        </p>
        <div className="auth-install-row">
          <PWAInstallPrompt variant="compact" showWhenDismissed label="앱 설치하기" />
        </div>
      </section>
    </main>
  )
}
