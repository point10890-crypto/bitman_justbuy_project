import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import PageHeader from '../components/common/PageHeader'
import GlassCard from '../components/common/GlassCard'
import GoldButton from '../components/common/GoldButton'
import FormInput from '../components/common/FormInput'

export default function LoginPage() {
  const navigate = useNavigate()
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError('')

    if (!email.trim()) { setError('이메일을 입력해 주세요.'); return }
    if (!password) { setError('비밀번호를 입력해 주세요.'); return }

    try {
      setLoading(true)
      await login(email.trim(), password)
      const stored = localStorage.getItem('bitman_auth_user')
      const userData = stored ? JSON.parse(stored) : null
      if (userData?.role === 'ADMIN') {
        navigate('/', { replace: true })
      } else if (userData?.subscription === 'pro') {
        navigate('/', { replace: true })
      } else {
        navigate('/subscribe', { replace: true })
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '로그인에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-dvh flex flex-col" style={{ backgroundColor: 'var(--bg-primary)' }}>
      <PageHeader />

      <div className="flex-1 flex flex-col justify-center" style={{ padding: '0 20px 32px', maxWidth: '440px', width: '100%', margin: '0 auto' }}>
        <GlassCard>
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            {/* Title */}
            <div className="text-center mb-3 animate-slide-up" style={{ animationFillMode: 'backwards' }}>
              <h1 className="animate-gold-shimmer font-black text-[22px] mb-2" style={{ filter: 'drop-shadow(0 0 6px rgba(255,215,0,0.35))', letterSpacing: '-0.02em' }}>
                로그인
              </h1>
              <p className="text-[13px] font-medium" style={{ color: 'var(--text-secondary)' }}>AI 주식 분석 서비스에 오신 것을 환영합니다</p>
            </div>

            {/* Error */}
            {error && (
              <div className="flex items-center gap-2.5 px-3.5 py-3 rounded-xl text-[13px] font-medium animate-slide-up" style={{
                backgroundColor: 'rgba(255,23,68,0.08)',
                border: '1px solid rgba(255,23,68,0.2)',
                color: 'var(--color-bear)',
              }}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="flex-shrink-0"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
                {error}
              </div>
            )}

            {/* Inputs */}
            <FormInput
              label="이메일"
              type="email"
              placeholder="이메일 주소를 입력해 주세요"
              value={email}
              onChange={e => setEmail(e.target.value)}
              autoComplete="email"
              delay={0.1}
              icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="M22 7l-10 6L2 7"/></svg>}
            />

            <FormInput
              label="비밀번호"
              type="password"
              placeholder="비밀번호를 입력해 주세요"
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="current-password"
              delay={0.15}
              icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/></svg>}
            />

            {/* Submit */}
            <div className="mt-1">
              <GoldButton type="submit" loading={loading} delay={0.2}>로그인</GoldButton>
            </div>

          </form>
        </GlassCard>

        {/* Bottom links */}
        <div className="flex justify-center items-center gap-2 mt-5 text-[13px] animate-slide-up" style={{ animationDelay: '0.35s', animationFillMode: 'backwards' }}>
          <span style={{ color: 'var(--text-muted)' }}>계정이 없으신가요?</span>
          <Link
            to="/register"
            className="font-bold transition-opacity duration-200"
            style={{ color: '#FFD700' }}
            onMouseEnter={e => { e.currentTarget.style.opacity = '0.8' }}
            onMouseLeave={e => { e.currentTarget.style.opacity = '1' }}
          >
            회원가입
          </Link>
        </div>
      </div>

      {/* Footer */}
      <div className="text-center px-4" style={{ paddingBottom: 'max(16px, env(safe-area-inset-bottom))' }}>
        <p className="text-[10px]" style={{ color: 'var(--text-muted)', opacity: 0.5 }}>
          본 정보는 투자자문이 아니며, 투자 판단의 책임은 본인에게 있습니다.
        </p>
      </div>
    </div>
  )
}
