import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import PageHeader from '../components/common/PageHeader'
import GlassCard from '../components/common/GlassCard'
import GoldButton from '../components/common/GoldButton'
import FormInput from '../components/common/FormInput'

function getPasswordStrength(pw: string): { level: number; label: string; color: string } {
  if (!pw) return { level: 0, label: '', color: 'transparent' }
  let score = 0
  if (pw.length >= 8) score++
  if (pw.length >= 12) score++
  if (/[A-Z]/.test(pw)) score++
  if (/[0-9]/.test(pw)) score++
  if (/[^A-Za-z0-9]/.test(pw)) score++
  if (score <= 1) return { level: 1, label: '약함', color: 'var(--color-bear)' }
  if (score <= 3) return { level: 2, label: '보통', color: 'var(--color-warning)' }
  return { level: 3, label: '강함', color: 'var(--color-bull)' }
}

function Checkbox({ checked, onChange, label, error, muted }: {
  checked: boolean; onChange: (v: boolean) => void; label: string; error?: boolean; muted?: boolean
}) {
  return (
    <label className="flex items-center gap-3 cursor-pointer py-1 group">
      <input type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} className="sr-only" />
      <span
        className="flex-shrink-0 w-[22px] h-[22px] rounded-md flex items-center justify-center transition-all duration-200"
        style={{
          backgroundColor: checked ? '#FFD700' : 'transparent',
          border: checked ? 'none' : `1.5px solid ${error ? 'var(--color-bear)' : 'var(--border-default)'}`,
          boxShadow: checked ? '0 2px 8px rgba(255,215,0,0.25)' : 'none',
          transform: checked ? 'scale(1)' : 'scale(1)',
        }}
      >
        {checked && (
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#1A1A1A" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round">
            <path d="M20 6L9 17l-5-5"/>
          </svg>
        )}
      </span>
      <span
        className="text-[13px] font-medium transition-colors duration-200"
        style={{ color: muted ? 'var(--text-muted)' : 'var(--text-secondary)' }}
      >
        {label}
      </span>
    </label>
  )
}

export default function RegisterPage() {
  const navigate = useNavigate()
  const { register } = useAuth()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [agreeTerms, setAgreeTerms] = useState(false)
  const [agreePrivacy, setAgreePrivacy] = useState(false)
  const [agreeMarketing, setAgreeMarketing] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [globalError, setGlobalError] = useState('')
  const [loading, setLoading] = useState(false)
  const [success, setSuccess] = useState(false)

  const strength = getPasswordStrength(password)
  const allAgreed = agreeTerms && agreePrivacy && agreeMarketing

  const validate = (): boolean => {
    const errs: Record<string, string> = {}
    if (!name.trim() || name.trim().length < 2) errs.name = '이름을 2자 이상 입력해 주세요.'
    if (!email.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errs.email = '올바른 이메일 형식을 입력해 주세요.'
    if (password.length < 8) errs.password = '비밀번호는 8자 이상이어야 합니다.'
    else if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) errs.password = '영문과 숫자를 모두 포함해 주세요.'
    if (password !== passwordConfirm) errs.passwordConfirm = '비밀번호가 일치하지 않습니다.'
    if (!agreeTerms) errs.terms = '이용약관에 동의해 주세요.'
    if (!agreePrivacy) errs.privacy = '개인정보 처리방침에 동의해 주세요.'
    setErrors(errs)
    return Object.keys(errs).length === 0
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setGlobalError('')
    if (!validate()) return

    try {
      setLoading(true)
      await register(name.trim(), email.trim(), password)
      setSuccess(true)
      setTimeout(() => navigate('/login', { replace: true }), 1500)
    } catch (err) {
      setGlobalError(err instanceof Error ? err.message : '회원가입에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  const handleAgreeAll = () => {
    const next = !allAgreed
    setAgreeTerms(next)
    setAgreePrivacy(next)
    setAgreeMarketing(next)
  }

  if (success) {
    return (
      <div className="min-h-dvh flex flex-col" style={{ backgroundColor: 'var(--bg-primary)' }}>
        <PageHeader showBack={false} />
        <div className="flex-1 flex flex-col items-center justify-center gap-5 px-6 animate-slide-up">
          <div
            className="w-20 h-20 rounded-full flex items-center justify-center"
            style={{
              background: 'linear-gradient(135deg, rgba(0,200,83,0.25), rgba(0,200,83,0.1))',
              border: '2px solid var(--color-bull)',
              boxShadow: '0 0 40px rgba(0,200,83,0.2)',
            }}
          >
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="var(--color-bull)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M20 6L9 17l-5-5"/>
            </svg>
          </div>
          <div className="text-center">
            <h2 className="font-black text-xl mb-2" style={{ color: 'var(--text-primary)' }}>회원가입 완료!</h2>
            <p className="text-[14px] font-medium" style={{ color: 'var(--text-secondary)' }}>로그인 페이지로 이동합니다...</p>
          </div>
          <div className="w-32 h-1 rounded-full overflow-hidden mt-2" style={{ backgroundColor: 'var(--bg-elevated)' }}>
            <div className="h-full rounded-full" style={{
              backgroundColor: 'var(--color-bull)',
              animation: 'progress-fill 1.5s ease-out forwards',
            }} />
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-dvh flex flex-col" style={{ backgroundColor: 'var(--bg-primary)' }}>
      <PageHeader />

      <div className="flex-1 overflow-y-auto" style={{ padding: '0 20px 32px', maxWidth: '440px', width: '100%', margin: '0 auto' }}>
        <GlassCard>
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            {/* Title */}
            <div className="text-center mb-2 animate-slide-up" style={{ animationFillMode: 'backwards' }}>
              <h1 className="animate-gold-shimmer font-black text-[22px] mb-2" style={{ filter: 'drop-shadow(0 0 6px rgba(255,215,0,0.35))', letterSpacing: '-0.02em' }}>
                회원가입
              </h1>
              <p className="text-[13px] font-medium" style={{ color: 'var(--text-secondary)' }}>BitMan AI 분석 서비스에 오신 것을 환영합니다</p>
            </div>

            {/* Global error */}
            {globalError && (
              <div className="flex items-center gap-2.5 px-3.5 py-3 rounded-xl text-[13px] font-medium animate-slide-up" style={{
                backgroundColor: 'rgba(255,23,68,0.08)',
                border: '1px solid rgba(255,23,68,0.2)',
                color: 'var(--color-bear)',
              }}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="flex-shrink-0"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
                {globalError}
              </div>
            )}

            {/* Inputs */}
            <FormInput
              label="이름"
              type="text"
              placeholder="이름을 입력해 주세요"
              value={name}
              onChange={e => setName(e.target.value)}
              error={errors.name}
              autoComplete="name"
              delay={0.1}
              icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>}
            />

            <FormInput
              label="이메일"
              type="email"
              placeholder="이메일 주소를 입력해 주세요"
              value={email}
              onChange={e => setEmail(e.target.value)}
              error={errors.email}
              autoComplete="email"
              delay={0.15}
              icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="M22 7l-10 6L2 7"/></svg>}
            />

            <div className="animate-slide-up" style={{ animationDelay: '0.2s', animationFillMode: 'backwards' }}>
              <FormInput
                label="비밀번호"
                type="password"
                placeholder="비밀번호 (8자 이상, 영문+숫자)"
                value={password}
                onChange={e => setPassword(e.target.value)}
                error={errors.password}
                autoComplete="new-password"
                delay={0}
                icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/></svg>}
              />
              {/* Password strength */}
              {password && (
                <div className="flex items-center gap-2.5 mt-2.5 px-0.5">
                  <div className="flex gap-1.5 flex-1">
                    {[1, 2, 3].map(i => (
                      <div
                        key={i}
                        className="flex-1 h-1.5 rounded-full transition-all duration-300"
                        style={{
                          backgroundColor: i <= strength.level ? strength.color : 'var(--border-default)',
                          boxShadow: i <= strength.level ? `0 0 6px ${strength.color === 'var(--color-bull)' ? 'rgba(0,200,83,0.3)' : strength.color === 'var(--color-warning)' ? 'rgba(255,152,0,0.3)' : 'rgba(255,23,68,0.3)'}` : 'none',
                        }}
                      />
                    ))}
                  </div>
                  <span className="text-[11px] font-bold" style={{ color: strength.color }}>{strength.label}</span>
                </div>
              )}
            </div>

            <FormInput
              label="비밀번호 확인"
              type="password"
              placeholder="비밀번호를 다시 입력해 주세요"
              value={passwordConfirm}
              onChange={e => setPasswordConfirm(e.target.value)}
              error={errors.passwordConfirm}
              autoComplete="new-password"
              delay={0.25}
              icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 11.08V12a10 10 0 11-5.93-9.14"/><path d="M22 4L12 14.01l-3-3"/></svg>}
            />

            {/* Agreement checkboxes */}
            <div className="flex flex-col gap-1 mt-1 animate-slide-up" style={{ animationDelay: '0.3s', animationFillMode: 'backwards' }}>
              <div className="pb-2 mb-1" style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                <Checkbox checked={allAgreed} onChange={handleAgreeAll} label="전체 동의" />
              </div>
              <Checkbox checked={agreeTerms} onChange={setAgreeTerms} label="[필수] 이용약관에 동의합니다" error={!!errors.terms} />
              <Checkbox checked={agreePrivacy} onChange={setAgreePrivacy} label="[필수] 개인정보 처리방침에 동의합니다" error={!!errors.privacy} />
              <Checkbox checked={agreeMarketing} onChange={setAgreeMarketing} label="[선택] 마케팅 수신에 동의합니다" muted />
            </div>

            {/* Submit */}
            <div className="mt-1">
              <GoldButton type="submit" loading={loading} delay={0.35}>회원가입</GoldButton>
            </div>

            {/* Security badge */}
            <div className="flex items-center justify-center gap-1.5 animate-slide-up" style={{ animationDelay: '0.5s', animationFillMode: 'backwards' }}>
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-muted)', opacity: 0.6 }}>
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/>
              </svg>
              <span className="text-[10px] font-medium" style={{ color: 'var(--text-muted)', opacity: 0.6 }}>SSL 보안 연결 | 개인정보 보호</span>
            </div>
          </form>
        </GlassCard>

        {/* Bottom link */}
        <div className="flex justify-center items-center gap-2 mt-5 text-[13px] animate-slide-up" style={{ animationDelay: '0.5s', animationFillMode: 'backwards' }}>
          <span style={{ color: 'var(--text-muted)' }}>이미 계정이 있으신가요?</span>
          <Link
            to="/login"
            className="font-bold transition-opacity duration-200"
            style={{ color: '#FFD700' }}
            onMouseEnter={e => { e.currentTarget.style.opacity = '0.8' }}
            onMouseLeave={e => { e.currentTarget.style.opacity = '1' }}
          >
            로그인
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
