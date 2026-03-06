import { useState, useEffect, useRef, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import PageHeader from '../components/common/PageHeader'
import GlassCard from '../components/common/GlassCard'
import GoldButton from '../components/common/GoldButton'
import FormInput from '../components/common/FormInput'

const BANK_ACCOUNT = '2259-02-04-057670'
const BANK_NAME = '국민은행'
const ACCOUNT_HOLDER = '이종민'
const KAKAO_URL = 'https://open.kakao.com/o/sJVLbWUe'
const TELEGRAM_URL = 'https://t.me/+411gMUrGnNc2YzU1'

const engineColors: Record<string, string> = {
  Claude: '#FF6B35',
  Gemini: '#4285F4',
  ChatGPT: '#10A37F',
  Perplexity: '#20B2AA',
  Grok: '#FF4500',
}

const proFeatures = [
  '무제한 분석',
  'AI 5개 전체 엔진',
  '프리미엄 심층 리포트',
  '실시간 시장 데이터',
  '멀티 AI 종합분석',
  '맞춤 알림 설정',
]

export default function SubscribePage() {
  const navigate = useNavigate()
  const { user, applySubscription, refreshUser } = useAuth()
  const [loading, setLoading] = useState(false)
  const [depositorName, setDepositorName] = useState('')
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [justApproved, setJustApproved] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const subscription = user?.subscription ?? 'free'

  // ─── PRO 상태: 자동 메인 이동 (3초 후) ───
  useEffect(() => {
    if (subscription === 'pro' && !justApproved) {
      // 이미 PRO인 사용자가 /subscribe에 접근 → 즉시 리다이렉트
      const timer = setTimeout(() => navigate('/', { replace: true }), 500)
      return () => clearTimeout(timer)
    }
    if (justApproved) {
      // 방금 승인된 경우 → 축하 메시지 후 3초 뒤 이동
      const timer = setTimeout(() => navigate('/', { replace: true }), 3000)
      return () => clearTimeout(timer)
    }
  }, [subscription, justApproved, navigate])

  // ─── Pending 상태: 30초마다 자동 폴링 ───
  useEffect(() => {
    if (subscription !== 'pending') return

    const poll = async () => {
      await refreshUser()
    }
    pollRef.current = setInterval(poll, 30_000) // 30초 간격

    return () => {
      if (pollRef.current) clearInterval(pollRef.current)
    }
  }, [subscription, refreshUser])

  // ─── pending → pro 전환 감지 ───
  const prevSubscription = useRef(subscription)
  useEffect(() => {
    if (prevSubscription.current === 'pending' && subscription === 'pro') {
      setJustApproved(true)
    }
    prevSubscription.current = subscription
  }, [subscription])

  const handleCopyAccount = async () => {
    try {
      await navigator.clipboard.writeText(`${BANK_NAME} ${BANK_ACCOUNT} ${ACCOUNT_HOLDER}`)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      /* clipboard not available */
    }
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError('')
    if (!depositorName.trim()) {
      setError('입금자명을 입력해 주세요.')
      return
    }
    try {
      setLoading(true)
      await applySubscription(depositorName.trim())
    } catch (err) {
      setError(err instanceof Error ? err.message : '구독 신청에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  const handleRefresh = async () => {
    await refreshUser()
  }

  // ─── PRO 구독 활성 상태 (자동 리다이렉트) ───
  if (subscription === 'pro') {
    return (
      <div className="min-h-dvh flex flex-col" style={{ backgroundColor: 'var(--bg-primary)' }}>
        <PageHeader showBack={false} />
        <div className="flex-1 flex flex-col items-center justify-center gap-5 px-6 animate-slide-up">
          <div
            className="w-20 h-20 rounded-full flex items-center justify-center"
            style={{
              background: 'linear-gradient(135deg, rgba(124,77,255,0.25), rgba(255,215,0,0.2))',
              border: '2px solid rgba(124,77,255,0.4)',
              boxShadow: '0 0 40px rgba(124,77,255,0.2)',
            }}
          >
            <span className="text-4xl">👑</span>
          </div>
          <div className="text-center">
            <div className="mb-2">
              <span className="text-[9px] font-black px-2.5 py-1 rounded-full" style={{ backgroundColor: 'rgba(124,77,255,0.15)', color: 'var(--color-grade-s)', border: '1px solid rgba(124,77,255,0.3)' }}>PRO</span>
            </div>
            <h2 className="font-black text-xl mb-2" style={{ color: 'var(--text-primary)' }}>
              {justApproved ? '구독이 승인되었습니다!' : 'PRO 구독 활성 중'}
            </h2>
            <p className="text-[13px] font-medium" style={{ color: 'var(--text-secondary)' }}>
              {justApproved ? '잠시 후 서비스 메인으로 이동합니다...' : '메인으로 이동 중...'}
            </p>
          </div>
          <div className="w-32 h-1 rounded-full overflow-hidden mt-2" style={{ backgroundColor: 'var(--bg-elevated)' }}>
            <div className="h-full rounded-full" style={{
              backgroundColor: '#7C4DFF',
              animation: `progress-fill ${justApproved ? '3s' : '0.5s'} ease-out forwards`,
            }} />
          </div>
        </div>
      </div>
    )
  }

  // ─── 승인 대기 상태 ───
  if (subscription === 'pending') {
    return (
      <div className="min-h-dvh flex flex-col" style={{ backgroundColor: 'var(--bg-primary)' }}>
        <PageHeader />
        <div className="flex-1 overflow-y-auto" style={{ padding: '0 16px 32px', maxWidth: '440px', width: '100%', margin: '0 auto' }}>
          <div className="text-center mb-5 animate-slide-up" style={{ animationFillMode: 'backwards' }}>
            <h1 className="animate-gold-shimmer font-black text-[22px] mb-2" style={{ filter: 'drop-shadow(0 0 6px rgba(255,215,0,0.35))', letterSpacing: '-0.02em' }}>
              구독 신청 완료
            </h1>
            <p className="text-[13px] font-medium" style={{ color: 'var(--text-secondary)' }}>
              입금 확인 후 승인됩니다
            </p>
          </div>

          <div className="flex flex-col gap-4">
            {/* 접수 완료 카드 */}
            <div className="animate-slide-up" style={{ animationDelay: '0.05s', animationFillMode: 'backwards' }}>
              <GlassCard>
                <div className="flex flex-col items-center gap-3 text-center">
                  <div className="w-14 h-14 rounded-full flex items-center justify-center" style={{ backgroundColor: 'rgba(255,152,0,0.12)', border: '1.5px solid rgba(255,152,0,0.25)' }}>
                    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="var(--color-warning)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
                    </svg>
                  </div>
                  <h2 className="text-[16px] font-bold" style={{ color: 'var(--text-primary)' }}>승인 대기 중</h2>
                  <p className="text-[12px]" style={{ color: 'var(--text-secondary)' }}>
                    입금자명: <span className="font-bold" style={{ color: '#FFD700' }}>{user?.depositorName || '—'}</span>
                  </p>
                  <p className="text-[11px] leading-relaxed" style={{ color: 'var(--text-muted)' }}>
                    입금 확인이 완료되면 PRO 구독이 활성화됩니다.<br />
                    문의사항은 카카오톡 또는 텔레그램으로 연락해 주세요.
                  </p>
                </div>
              </GlassCard>
            </div>

            {/* 계좌 정보 */}
            <div className="animate-slide-up" style={{ animationDelay: '0.1s', animationFillMode: 'backwards' }}>
              <GlassCard>
                <div className="flex flex-col gap-3">
                  <div
                    className="flex items-center justify-between cursor-pointer transition-all duration-200 rounded-lg"
                    style={{ padding: '10px 12px', backgroundColor: 'rgba(255,215,0,0.06)', border: '1px solid rgba(255,215,0,0.15)' }}
                    onClick={handleCopyAccount}
                  >
                    <div>
                      <p className="text-[10px] font-medium mb-0.5" style={{ color: 'var(--text-muted)' }}>계좌정보</p>
                      <p className="text-[13px] font-bold" style={{ color: '#FFD700' }}>
                        {BANK_NAME} {BANK_ACCOUNT} {ACCOUNT_HOLDER}
                      </p>
                    </div>
                    <button type="button" className="flex items-center gap-1 px-2.5 py-1 rounded-lg text-[10px] font-bold" style={{
                      backgroundColor: copied ? 'rgba(0,200,83,0.15)' : 'rgba(255,215,0,0.12)',
                      color: copied ? 'var(--color-bull)' : '#FFD700',
                      border: `1px solid ${copied ? 'rgba(0,200,83,0.3)' : 'rgba(255,215,0,0.25)'}`,
                    }}>
                      {copied ? '복사됨' : '복사'}
                    </button>
                  </div>

                  {/* Links */}
                  <div className="flex gap-2">
                    <a href={KAKAO_URL} target="_blank" rel="noopener noreferrer"
                      className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-[11px] font-bold"
                      style={{ backgroundColor: 'rgba(254,229,0,0.08)', color: '#FEE500', border: '1px solid rgba(254,229,0,0.2)' }}>
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 3C6.5 3 2 6.58 2 11c0 2.84 1.86 5.33 4.64 6.73-.14.52-.92 3.33-.95 3.55 0 0-.02.16.08.22.1.06.22.03.22.03.29-.04 3.37-2.2 3.9-2.57.7.1 1.41.14 2.11.14 5.5 0 10-3.58 10-8s-4.5-8-10-8z"/></svg>
                      카카오톡
                    </a>
                    <a href={TELEGRAM_URL} target="_blank" rel="noopener noreferrer"
                      className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-[11px] font-bold"
                      style={{ backgroundColor: 'rgba(0,136,204,0.08)', color: '#0088CC', border: '1px solid rgba(0,136,204,0.2)' }}>
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6.54l-1.83 8.63c-.12.56-.47.7-.96.43l-2.65-1.95-1.28 1.23c-.14.14-.26.26-.54.26l.19-2.73 4.97-4.49c.22-.19-.05-.3-.33-.12l-6.15 3.87-2.65-.83c-.57-.18-.58-.57.12-.85l10.37-4c.48-.17.9.12.74.85z"/></svg>
                      텔레그램
                    </a>
                  </div>
                </div>
              </GlassCard>
            </div>

            {/* 상태 새로고침 */}
            <div className="animate-slide-up" style={{ animationDelay: '0.15s', animationFillMode: 'backwards' }}>
              <button
                type="button"
                onClick={handleRefresh}
                className="w-full py-3 rounded-xl text-[13px] font-bold transition-all duration-200"
                style={{
                  backgroundColor: 'rgba(255,215,0,0.06)',
                  border: '1px solid rgba(255,215,0,0.2)',
                  color: '#FFD700',
                }}
                onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,215,0,0.12)' }}
                onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(255,215,0,0.06)' }}
              >
                🔄 승인 상태 확인
              </button>
              <p className="text-[10px] text-center mt-1.5" style={{ color: 'var(--text-muted)' }}>
                30초마다 자동 확인 · 승인 시 자동으로 서비스 이동
              </p>
            </div>
          </div>
        </div>
      </div>
    )
  }

  // ─── 미구독 (free) — 구독 신청 폼 ───
  return (
    <div className="min-h-dvh flex flex-col" style={{ backgroundColor: 'var(--bg-primary)' }}>
      <PageHeader />

      <div className="flex-1 overflow-y-auto" style={{ padding: '0 16px 32px', maxWidth: '440px', width: '100%', margin: '0 auto' }}>
        {/* Title */}
        <div className="text-center mb-5 animate-slide-up" style={{ animationFillMode: 'backwards' }}>
          <h1 className="animate-gold-shimmer font-black text-[22px] mb-2" style={{ filter: 'drop-shadow(0 0 6px rgba(255,215,0,0.35))', letterSpacing: '-0.02em' }}>
            주식분석 구독신청
          </h1>
          <p className="text-[13px] font-medium" style={{ color: 'var(--text-secondary)' }}>
            PRO 플랜 · AI 5개 엔진 무제한 분석
          </p>
        </div>

        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          {/* 구독 안내 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.05s', animationFillMode: 'backwards' }}>
            <GlassCard>
              <div className="flex flex-col gap-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="text-sm">💳</span>
                    <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>구독 안내</span>
                  </div>
                  <span className="text-[9px] font-black px-2 py-0.5 rounded-full" style={{ backgroundColor: 'rgba(124,77,255,0.15)', color: 'var(--color-grade-s)', border: '1px solid rgba(124,77,255,0.3)' }}>
                    PRO
                  </span>
                </div>

                <div className="flex items-baseline gap-1">
                  <span className="font-black text-[24px]" style={{ color: '#FFD700' }}>₩30,000</span>
                  <span className="text-[13px] font-medium" style={{ color: 'var(--text-muted)' }}>/ 30일</span>
                </div>

                <div className="flex flex-col gap-1.5" style={{ padding: '10px 12px', borderRadius: '10px', backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)' }}>
                  <p className="text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                    신청이 접수 된 후 입금확인을 통해 승인이 이뤄진 후 30일동안 구독 가능합니다.
                  </p>
                  <p className="text-[11px]" style={{ color: 'var(--color-warning)' }}>
                    *입력하신 입금자명과 다르게 입금하시면 승인이 늦어질 수 있습니다.
                  </p>
                </div>

                {/* Bank account */}
                <div
                  className="flex items-center justify-between cursor-pointer transition-all duration-200 rounded-lg"
                  style={{ padding: '10px 12px', backgroundColor: 'rgba(255,215,0,0.06)', border: '1px solid rgba(255,215,0,0.15)' }}
                  onClick={handleCopyAccount}
                  onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,215,0,0.1)' }}
                  onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(255,215,0,0.06)' }}
                >
                  <div>
                    <p className="text-[10px] font-medium mb-0.5" style={{ color: 'var(--text-muted)' }}>계좌정보</p>
                    <p className="text-[13px] font-bold" style={{ color: '#FFD700' }}>
                      {BANK_NAME} {BANK_ACCOUNT} {ACCOUNT_HOLDER}
                    </p>
                  </div>
                  <button
                    type="button"
                    className="flex items-center gap-1 px-2.5 py-1 rounded-lg text-[10px] font-bold transition-all duration-200"
                    style={{
                      backgroundColor: copied ? 'rgba(0,200,83,0.15)' : 'rgba(255,215,0,0.12)',
                      color: copied ? 'var(--color-bull)' : '#FFD700',
                      border: `1px solid ${copied ? 'rgba(0,200,83,0.3)' : 'rgba(255,215,0,0.25)'}`,
                    }}
                  >
                    {copied ? (
                      <><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3"><path d="M20 6L9 17l-5-5"/></svg>복사됨</>
                    ) : (
                      <><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>복사</>
                    )}
                  </button>
                </div>

                {/* Links */}
                <div className="flex gap-2">
                  <a href={KAKAO_URL} target="_blank" rel="noopener noreferrer"
                    className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-[11px] font-bold transition-all duration-200"
                    style={{ backgroundColor: 'rgba(254,229,0,0.08)', color: '#FEE500', border: '1px solid rgba(254,229,0,0.2)' }}
                    onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(254,229,0,0.15)' }}
                    onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(254,229,0,0.08)' }}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 3C6.5 3 2 6.58 2 11c0 2.84 1.86 5.33 4.64 6.73-.14.52-.92 3.33-.95 3.55 0 0-.02.16.08.22.1.06.22.03.22.03.29-.04 3.37-2.2 3.9-2.57.7.1 1.41.14 2.11.14 5.5 0 10-3.58 10-8s-4.5-8-10-8z"/></svg>
                    카카오톡
                  </a>
                  <a href={TELEGRAM_URL} target="_blank" rel="noopener noreferrer"
                    className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-[11px] font-bold transition-all duration-200"
                    style={{ backgroundColor: 'rgba(0,136,204,0.08)', color: '#0088CC', border: '1px solid rgba(0,136,204,0.2)' }}
                    onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(0,136,204,0.15)' }}
                    onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(0,136,204,0.08)' }}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6.54l-1.83 8.63c-.12.56-.47.7-.96.43l-2.65-1.95-1.28 1.23c-.14.14-.26.26-.54.26l.19-2.73 4.97-4.49c.22-.19-.05-.3-.33-.12l-6.15 3.87-2.65-.83c-.57-.18-.58-.57.12-.85l10.37-4c.48-.17.9.12.74.85z"/></svg>
                    텔레그램
                  </a>
                </div>
              </div>
            </GlassCard>
          </div>

          {/* PRO 혜택 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.1s', animationFillMode: 'backwards' }}>
            <GlassCard>
              <div className="flex flex-col gap-3">
                <div className="flex items-center gap-2">
                  <span className="text-sm">🤖</span>
                  <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>PRO 혜택</span>
                </div>
                <div className="flex flex-wrap gap-1.5">
                  {Object.entries(engineColors).map(([engine, color]) => (
                    <span key={engine} className="text-[10px] font-bold px-2 py-1 rounded-lg"
                      style={{ backgroundColor: `${color}12`, color, border: `1px solid ${color}25` }}>
                      {engine}
                    </span>
                  ))}
                </div>
                <div className="flex flex-col gap-1.5">
                  {proFeatures.map(feature => (
                    <div key={feature} className="flex items-center gap-2">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--color-bull)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="flex-shrink-0">
                        <path d="M20 6L9 17l-5-5"/>
                      </svg>
                      <span className="text-[12px]" style={{ color: 'var(--text-secondary)' }}>{feature}</span>
                    </div>
                  ))}
                </div>
              </div>
            </GlassCard>
          </div>

          {/* 입금자명 입력 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.15s', animationFillMode: 'backwards' }}>
            <FormInput
              label="입금자명"
              type="text"
              placeholder="입금자명을 입력하세요"
              value={depositorName}
              onChange={e => setDepositorName(e.target.value)}
              error={error}
              delay={0}
              icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>}
            />
          </div>

          {/* Submit button */}
          <div className="mt-1 animate-slide-up" style={{ animationDelay: '0.2s', animationFillMode: 'backwards' }}>
            <GoldButton type="submit" loading={loading} delay={0}>
              구독신청
            </GoldButton>
          </div>
        </form>
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
