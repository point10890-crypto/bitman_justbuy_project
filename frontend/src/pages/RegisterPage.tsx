import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { PWAInstallPrompt } from '../components/app/PWAInstallPrompt'
import { isSubscriptionExpired, useAuth } from '../contexts/AuthContext'

export default function RegisterPage() {
  const navigate = useNavigate()
  const { register, user, isLoading } = useAuth()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [agreeTerms, setAgreeTerms] = useState(false)
  const [agreePrivacy, setAgreePrivacy] = useState(false)
  const [agreeRisk, setAgreeRisk] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (isLoading || !user) return

    const activePro = user.subscription === 'pro' && !isSubscriptionExpired(user.subscriptionEndDate)
    navigate(user.role === 'ADMIN' || activePro ? '/' : '/subscribe', { replace: true })
  }, [user, isLoading, navigate])

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')

    if (name.trim().length < 2) {
      setError('이름 또는 닉네임을 2자 이상 입력해 주세요.')
      return
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setError('올바른 이메일을 입력해 주세요.')
      return
    }
    if (password.length < 12 || !/[A-Z]/.test(password) || !/[a-z]/.test(password) || !/[0-9]/.test(password) || !/[^A-Za-z0-9]/.test(password)) {
      setError('비밀번호는 12자 이상이며 대문자, 소문자, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.')
      return
    }
    if (password !== passwordConfirm) {
      setError('비밀번호가 서로 일치하지 않습니다.')
      return
    }
    if (!agreeTerms || !agreePrivacy || !agreeRisk) {
      setError('필수 동의 항목을 확인해 주세요.')
      return
    }

    try {
      setLoading(true)
      await register(name.trim(), email.trim().toLowerCase(), password)
      navigate('/subscribe', { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : '회원가입에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  const allChecked = agreeTerms && agreePrivacy && agreeRisk
  const toggleAll = () => {
    const next = !allChecked
    setAgreeTerms(next)
    setAgreePrivacy(next)
    setAgreeRisk(next)
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
          <h1>회원가입</h1>
          <p>가입 후 월간 이용권을 신청하면 조건검색 TOP3 전체 결과를 볼 수 있습니다.</p>
        </div>

        {error && <div className="auth-error">{error}</div>}

        <form onSubmit={handleSubmit} className="auth-form">
          <label>
            <span>이름 / 닉네임</span>
            <input value={name} onChange={event => setName(event.target.value)} autoComplete="name" placeholder="홍길동" />
          </label>
          <label>
            <span>이메일</span>
            <input type="text" inputMode="email" value={email} onChange={event => setEmail(event.target.value)} autoComplete="email" placeholder="you@example.com" />
          </label>
          <label>
            <span>비밀번호</span>
            <input type="password" value={password} onChange={event => setPassword(event.target.value)} autoComplete="new-password" placeholder="대/소문자+숫자+특수문자 12자 이상" />
          </label>
          <label>
            <span>비밀번호 확인</span>
            <input type="password" value={passwordConfirm} onChange={event => setPasswordConfirm(event.target.value)} autoComplete="new-password" placeholder="비밀번호 재입력" />
          </label>

          <div className="auth-agreement">
            <label><input type="checkbox" checked={allChecked} onChange={toggleAll} /><span>전체 동의</span></label>
            <label><input type="checkbox" checked={agreeTerms} onChange={event => setAgreeTerms(event.target.checked)} /><span>[필수] 이용약관 동의</span></label>
            <label><input type="checkbox" checked={agreePrivacy} onChange={event => setAgreePrivacy(event.target.checked)} /><span>[필수] 개인정보 처리방침 동의</span></label>
            <label><input type="checkbox" checked={agreeRisk} onChange={event => setAgreeRisk(event.target.checked)} /><span>[필수] 투자 판단 책임 안내 확인</span></label>
          </div>

          <button className="auth-submit" type="submit" disabled={loading}>
            {loading ? '가입 처리 중...' : '회원가입 후 구독 신청'}
          </button>
        </form>

        <p className="auth-link-row">
          이미 계정이 있나요? <Link to="/login">로그인</Link>
        </p>
        <div className="auth-install-row">
          <PWAInstallPrompt variant="compact" showWhenDismissed label="앱 설치하기" />
        </div>
      </section>
    </main>
  )
}
