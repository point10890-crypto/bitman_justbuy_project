import { useState, useEffect, useRef, type FormEvent } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
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

// 오늘의 분석 모드 (잠금 미리보기용)
const ANALYSIS_MODES = [
  { key: 'BREAKOUT',       icon: '🚀', label: '돌파매수',  desc: '기술적 돌파 종목' },
  { key: 'FLOW_LEADER',    icon: '💹', label: '수급주도',  desc: '외국인·기관 수급' },
  { key: 'CATALYST_BURST', icon: '⚡', label: '급등재료',  desc: '재료·이벤트 드리븐' },
  { key: 'REVERSAL_EDGE',  icon: '🔄', label: '반전매수',  desc: '역발상 반전 후보' },
]

const PRO_FEATURES = [
  { icon: '🤖', text: 'ChatGPT × Grok 2-에이전트 병렬 분석' },
  { icon: '📊', text: '4가지 컨셉 모드 무제한 이용' },
  { icon: '📝', text: '프리미엄 심층 리포트 & 종목 리스트' },
  { icon: '📡', text: '실시간 시장 데이터 & 현재가 보정' },
  { icon: '💬', text: '3라운드 AI 교차 토론 결과 제공' },
  { icon: '🔔', text: '매 시간 자동 업데이트 (장중 오전 8시~오후 2시)' },
]

// ─── 잠금된 분석 미리보기 카드 ───
function LockedAnalysisPreview({ fromLocked }: { fromLocked: boolean }) {
  return (
    <div className="animate-slide-up" style={{ animationFillMode: 'backwards' }}>
      {/* 안내 배너 */}
      <div
        className="flex items-center gap-2.5 px-4 py-3 rounded-xl mb-3"
        style={{
          backgroundColor: fromLocked ? 'rgba(255,152,0,0.08)' : 'rgba(124,77,255,0.08)',
          border: `1px solid ${fromLocked ? 'rgba(255,152,0,0.2)' : 'rgba(124,77,255,0.2)'}`,
        }}
      >
        <span className="text-base flex-shrink-0">{fromLocked ? '🔒' : '📊'}</span>
        <div>
          <p className="text-[12px] font-bold" style={{ color: fromLocked ? 'var(--color-warning)' : 'var(--color-grade-s)' }}>
            {fromLocked ? 'PRO 전용 서비스입니다' : '오늘의 분석 결과가 준비되어 있습니다'}
          </p>
          <p className="text-[11px] mt-0.5" style={{ color: 'var(--text-muted)' }}>
            구독 후 4가지 AI 분석 리포트를 즉시 열람하세요
          </p>
        </div>
      </div>

      {/* 모드 카드 그리드 */}
      <div className="grid grid-cols-2 gap-2">
        {ANALYSIS_MODES.map((mode, i) => (
          <div
            key={mode.key}
            className="relative rounded-xl overflow-hidden"
            style={{
              padding: '12px',
              backgroundColor: 'rgba(255,255,255,0.03)',
              border: '1px solid var(--border-subtle)',
              animationDelay: `${0.05 + i * 0.05}s`,
            }}
          >
            {/* 블러 오버레이 */}
            <div
              className="absolute inset-0 flex flex-col items-center justify-center gap-1 z-10"
              style={{
                background: 'rgba(10,10,15,0.7)',
                backdropFilter: 'blur(3px)',
              }}
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="rgba(255,215,0,0.6)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/>
              </svg>
              <span className="text-[10px] font-bold" style={{ color: 'rgba(255,215,0,0.7)' }}>PRO 전용</span>
            </div>

            {/* 배경 콘텐츠 (블러됨) */}
            <div style={{ filter: 'blur(2px)', userSelect: 'none' }}>
              <div className="flex items-center gap-1.5 mb-2">
                <span className="text-lg">{mode.icon}</span>
                <span className="text-[11px] font-bold" style={{ color: 'var(--text-primary)' }}>{mode.label}</span>
              </div>
              <p className="text-[10px]" style={{ color: 'var(--text-muted)' }}>{mode.desc}</p>
              <div className="mt-2 flex gap-1">
                {['██████', '████', '█████'].map((b, j) => (
                  <span key={j} className="text-[8px]" style={{ color: 'var(--text-muted)', opacity: 0.4 }}>{b}</span>
                ))}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

// ─── 구독 방법 3단계 안내 ───
function HowToSubscribe({ copied, onCopy }: { copied: boolean; onCopy: () => void }) {
  const steps = [
    {
      num: '1',
      title: '계좌 이체',
      desc: `₩30,000 → ${BANK_NAME} ${BANK_ACCOUNT}`,
      action: (
        <button
          type="button"
          onClick={onCopy}
          className="mt-2 flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold transition-all duration-200"
          style={{
            backgroundColor: copied ? 'rgba(0,200,83,0.15)' : 'rgba(255,215,0,0.1)',
            color: copied ? 'var(--color-bull)' : '#FFD700',
            border: `1px solid ${copied ? 'rgba(0,200,83,0.3)' : 'rgba(255,215,0,0.2)'}`,
          }}
        >
          {copied ? (
            <><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3"><path d="M20 6L9 17l-5-5"/></svg>복사됨</>
          ) : (
            <><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>계좌 복사</>
          )}
        </button>
      ),
    },
    {
      num: '2',
      title: '입금자명 입력 후 신청',
      desc: '아래 입금자명을 이체 시 사용한 이름과 동일하게 입력하세요.',
    },
    {
      num: '3',
      title: '승인 후 즉시 이용',
      desc: '입금 확인 후 영업시간(09:00~18:00) 기준 1시간 이내 승인됩니다.',
    },
  ]

  return (
    <GlassCard>
      <div className="flex flex-col gap-4">
        {/* 헤더 */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-sm">💳</span>
            <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>구독 방법</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="font-black text-[18px]" style={{ color: '#FFD700' }}>₩30,000</span>
            <span className="text-[11px]" style={{ color: 'var(--text-muted)' }}>/ 30일</span>
          </div>
        </div>

        {/* 단계 */}
        <div className="flex flex-col gap-3">
          {steps.map((step) => (
            <div key={step.num} className="flex gap-3">
              {/* 번호 원 */}
              <div
                className="flex-shrink-0 w-6 h-6 rounded-full flex items-center justify-center text-[11px] font-black mt-0.5"
                style={{
                  backgroundColor: 'rgba(255,215,0,0.12)',
                  color: '#FFD700',
                  border: '1px solid rgba(255,215,0,0.25)',
                }}
              >
                {step.num}
              </div>
              <div className="flex-1">
                <p className="text-[12px] font-bold mb-0.5" style={{ color: 'var(--text-primary)' }}>{step.title}</p>
                <p className="text-[11px] leading-relaxed" style={{ color: 'var(--text-muted)' }}>{step.desc}</p>
                {step.action}
              </div>
            </div>
          ))}
        </div>

        {/* 주의사항 */}
        <div
          className="flex items-start gap-2 px-3 py-2.5 rounded-lg"
          style={{ backgroundColor: 'rgba(255,152,0,0.06)', border: '1px solid rgba(255,152,0,0.15)' }}
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="var(--color-warning)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="flex-shrink-0 mt-0.5">
            <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <p className="text-[11px] leading-relaxed" style={{ color: 'var(--color-warning)' }}>
            입금자명이 실제 이체 이름과 다를 경우 승인이 지연될 수 있습니다.
          </p>
        </div>
      </div>
    </GlassCard>
  )
}

// ─── 문의 링크 ───
function ContactLinks() {
  return (
    <div className="flex gap-2">
      <a href={KAKAO_URL} target="_blank" rel="noopener noreferrer"
        className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-xl text-[11px] font-bold transition-all duration-200"
        style={{ backgroundColor: 'rgba(254,229,0,0.08)', color: '#FEE500', border: '1px solid rgba(254,229,0,0.2)' }}
        onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(254,229,0,0.15)' }}
        onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(254,229,0,0.08)' }}
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 3C6.5 3 2 6.58 2 11c0 2.84 1.86 5.33 4.64 6.73-.14.52-.92 3.33-.95 3.55 0 0-.02.16.08.22.1.06.22.03.22.03.29-.04 3.37-2.2 3.9-2.57.7.1 1.41.14 2.11.14 5.5 0 10-3.58 10-8s-4.5-8-10-8z"/></svg>
        카카오톡 문의
      </a>
      <a href={TELEGRAM_URL} target="_blank" rel="noopener noreferrer"
        className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-xl text-[11px] font-bold transition-all duration-200"
        style={{ backgroundColor: 'rgba(0,136,204,0.08)', color: '#0088CC', border: '1px solid rgba(0,136,204,0.2)' }}
        onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(0,136,204,0.15)' }}
        onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(0,136,204,0.08)' }}
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6.54l-1.83 8.63c-.12.56-.47.7-.96.43l-2.65-1.95-1.28 1.23c-.14.14-.26.26-.54.26l.19-2.73 4.97-4.49c.22-.19-.05-.3-.33-.12l-6.15 3.87-2.65-.83c-.57-.18-.58-.57.12-.85l10.37-4c.48-.17.9.12.74.85z"/></svg>
        텔레그램 문의
      </a>
    </div>
  )
}

// ─── PRO 상태 (자동 리다이렉트) ───
function ProRedirectScreen({ justApproved }: { justApproved: boolean }) {
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

// ─── PENDING 상태 — 3단계 프로그레스 ───
function PendingScreen({ user, copied, onCopy, onRefresh }: {
  user: { depositorName?: string | null } | null
  copied: boolean
  onCopy: () => void
  onRefresh: () => void
}) {
  const steps = [
    { label: '신청 완료', done: true },
    { label: '입금 확인', current: true },
    { label: '서비스 시작', done: false },
  ]

  return (
    <div className="min-h-dvh flex flex-col" style={{ backgroundColor: 'var(--bg-primary)' }}>
      <PageHeader />
      <div className="flex-1 overflow-y-auto" style={{ padding: '0 16px 32px', maxWidth: '440px', width: '100%', margin: '0 auto' }}>

        {/* 타이틀 */}
        <div className="text-center mb-5 animate-slide-up" style={{ animationFillMode: 'backwards' }}>
          <h1 className="animate-gold-shimmer font-black text-[22px] mb-1.5" style={{ filter: 'drop-shadow(0 0 6px rgba(255,215,0,0.35))', letterSpacing: '-0.02em' }}>
            구독 신청 완료
          </h1>
          <p className="text-[13px] font-medium" style={{ color: 'var(--text-secondary)' }}>
            입금 확인 후 자동으로 승인됩니다
          </p>
        </div>

        <div className="flex flex-col gap-4">
          {/* 3단계 프로그레스 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.05s', animationFillMode: 'backwards' }}>
            <GlassCard>
              <div className="flex items-center justify-between px-2">
                {steps.map((step, i) => (
                  <div key={i} className="flex flex-col items-center gap-1.5 flex-1 relative">
                    {/* 연결선 (가운데) */}
                    {i < steps.length - 1 && (
                      <div
                        className="absolute top-4 left-1/2 w-full h-px"
                        style={{
                          backgroundColor: step.done ? 'rgba(0,200,83,0.4)' : 'var(--border-subtle)',
                          zIndex: 0,
                        }}
                      />
                    )}
                    {/* 원 */}
                    <div
                      className="relative z-10 w-8 h-8 rounded-full flex items-center justify-center text-[14px]"
                      style={{
                        backgroundColor: step.done
                          ? 'rgba(0,200,83,0.15)'
                          : step.current
                            ? 'rgba(255,152,0,0.15)'
                            : 'rgba(255,255,255,0.05)',
                        border: step.done
                          ? '1.5px solid rgba(0,200,83,0.5)'
                          : step.current
                            ? '1.5px solid rgba(255,152,0,0.5)'
                            : '1.5px solid var(--border-subtle)',
                        boxShadow: step.current ? '0 0 12px rgba(255,152,0,0.3)' : 'none',
                      }}
                    >
                      {step.done ? (
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--color-bull)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M20 6L9 17l-5-5"/></svg>
                      ) : step.current ? (
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--color-warning)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                      ) : (
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/></svg>
                      )}
                    </div>
                    <span
                      className="text-[10px] font-bold text-center leading-tight"
                      style={{
                        color: step.done
                          ? 'var(--color-bull)'
                          : step.current
                            ? 'var(--color-warning)'
                            : 'var(--text-muted)',
                      }}
                    >
                      {step.label}
                    </span>
                  </div>
                ))}
              </div>
            </GlassCard>
          </div>

          {/* 상태 카드 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.1s', animationFillMode: 'backwards' }}>
            <GlassCard>
              <div className="flex flex-col gap-3">
                <div className="flex items-center gap-2">
                  <div
                    className="w-9 h-9 rounded-full flex items-center justify-center flex-shrink-0"
                    style={{ backgroundColor: 'rgba(255,152,0,0.12)', border: '1.5px solid rgba(255,152,0,0.25)' }}
                  >
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--color-warning)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
                    </svg>
                  </div>
                  <div>
                    <p className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>입금 확인 중</p>
                    <p className="text-[11px]" style={{ color: 'var(--text-muted)' }}>
                      입금자명: <span className="font-bold" style={{ color: '#FFD700' }}>{user?.depositorName || '—'}</span>
                    </p>
                  </div>
                </div>

                <div
                  className="flex items-start gap-2 px-3 py-2.5 rounded-lg"
                  style={{ backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)' }}
                >
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="var(--color-bull)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="flex-shrink-0 mt-0.5">
                    <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
                  </svg>
                  <p className="text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                    <span className="font-bold" style={{ color: 'var(--color-bull)' }}>영업시간(09:00~18:00) 기준 1시간 이내</span> 처리됩니다.
                    승인되면 4가지 AI 분석 리포트를 즉시 열람하실 수 있습니다.
                  </p>
                </div>
              </div>
            </GlassCard>
          </div>

          {/* 계좌 복사 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.15s', animationFillMode: 'backwards' }}>
            <div
              className="flex items-center justify-between cursor-pointer rounded-xl transition-all duration-200"
              style={{ padding: '12px 14px', backgroundColor: 'rgba(255,215,0,0.06)', border: '1px solid rgba(255,215,0,0.15)' }}
              onClick={onCopy}
              onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,215,0,0.1)' }}
              onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(255,215,0,0.06)' }}
            >
              <div>
                <p className="text-[10px] font-medium mb-0.5" style={{ color: 'var(--text-muted)' }}>계좌정보</p>
                <p className="text-[13px] font-bold" style={{ color: '#FFD700' }}>
                  {BANK_NAME} {BANK_ACCOUNT} {ACCOUNT_HOLDER}
                </p>
              </div>
              <button type="button" className="flex items-center gap-1 px-2.5 py-1 rounded-lg text-[10px] font-bold flex-shrink-0 ml-2" style={{
                backgroundColor: copied ? 'rgba(0,200,83,0.15)' : 'rgba(255,215,0,0.12)',
                color: copied ? 'var(--color-bull)' : '#FFD700',
                border: `1px solid ${copied ? 'rgba(0,200,83,0.3)' : 'rgba(255,215,0,0.25)'}`,
              }}>
                {copied ? '복사됨' : '복사'}
              </button>
            </div>
          </div>

          {/* 문의 링크 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.2s', animationFillMode: 'backwards' }}>
            <ContactLinks />
          </div>

          {/* 승인 상태 확인 버튼 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.25s', animationFillMode: 'backwards' }}>
            <button
              type="button"
              onClick={onRefresh}
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

// ─── 메인 컴포넌트 ───
export default function SubscribePage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, applySubscription, refreshUser } = useAuth()
  const [loading, setLoading] = useState(false)
  const [depositorName, setDepositorName] = useState('')
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [justApproved, setJustApproved] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const subscription = user?.subscription ?? 'free'

  // SubscribedRoute 또는 LoginPage에서 전달된 메시지 (잠금 차단 여부 감지)
  const fromLocked = !!(location.state as { message?: string } | null)?.message

  // PRO → 자동 이동
  useEffect(() => {
    if (subscription === 'pro' && !justApproved) {
      const timer = setTimeout(() => navigate('/', { replace: true }), 500)
      return () => clearTimeout(timer)
    }
    if (justApproved) {
      const timer = setTimeout(() => navigate('/', { replace: true }), 3000)
      return () => clearTimeout(timer)
    }
  }, [subscription, justApproved, navigate])

  // PENDING → 30초 폴링
  useEffect(() => {
    if (subscription !== 'pending') return
    pollRef.current = setInterval(() => refreshUser(), 30_000)
    return () => { if (pollRef.current) clearInterval(pollRef.current) }
  }, [subscription, refreshUser])

  // pending → pro 전환 감지
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
    } catch { /* clipboard not available */ }
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

  // ─── PRO ───
  if (subscription === 'pro') {
    return <ProRedirectScreen justApproved={justApproved} />
  }

  // ─── PENDING ───
  if (subscription === 'pending') {
    return (
      <PendingScreen
        user={user}
        copied={copied}
        onCopy={handleCopyAccount}
        onRefresh={() => refreshUser()}
      />
    )
  }

  // ─── FREE — 구독 유도 ───
  return (
    <div className="min-h-dvh flex flex-col" style={{ backgroundColor: 'var(--bg-primary)' }}>
      <PageHeader />

      <div className="flex-1 overflow-y-auto" style={{ padding: '0 16px 32px', maxWidth: '440px', width: '100%', margin: '0 auto' }}>

        {/* 타이틀 */}
        <div className="text-center mb-4 animate-slide-up" style={{ animationFillMode: 'backwards' }}>
          <h1 className="animate-gold-shimmer font-black text-[22px] mb-1.5" style={{ filter: 'drop-shadow(0 0 6px rgba(255,215,0,0.35))', letterSpacing: '-0.02em' }}>
            PRO 구독 신청
          </h1>
          <p className="text-[13px] font-medium" style={{ color: 'var(--text-secondary)' }}>
            ChatGPT × Grok AI 분석 서비스 구독
          </p>
        </div>

        <form onSubmit={handleSubmit} className="flex flex-col gap-4">

          {/* 1. 잠금된 분석 미리보기 */}
          <LockedAnalysisPreview fromLocked={fromLocked} />

          {/* 2. 구독 방법 3단계 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.1s', animationFillMode: 'backwards' }}>
            <HowToSubscribe copied={copied} onCopy={handleCopyAccount} />
          </div>

          {/* 3. PRO 혜택 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.15s', animationFillMode: 'backwards' }}>
            <GlassCard>
              <div className="flex flex-col gap-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="text-sm">✨</span>
                    <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>PRO 혜택</span>
                  </div>
                  <span className="text-[9px] font-black px-2 py-0.5 rounded-full" style={{ backgroundColor: 'rgba(124,77,255,0.15)', color: 'var(--color-grade-s)', border: '1px solid rgba(124,77,255,0.3)' }}>
                    PRO
                  </span>
                </div>
                <div className="flex flex-col gap-2">
                  {PRO_FEATURES.map(f => (
                    <div key={f.text} className="flex items-start gap-2.5">
                      <span className="flex-shrink-0 text-[14px] mt-0.5">{f.icon}</span>
                      <span className="text-[12px] leading-snug" style={{ color: 'var(--text-secondary)' }}>{f.text}</span>
                    </div>
                  ))}
                </div>
              </div>
            </GlassCard>
          </div>

          {/* 4. 문의 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.2s', animationFillMode: 'backwards' }}>
            <ContactLinks />
          </div>

          {/* 5. 입금자명 입력 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.25s', animationFillMode: 'backwards' }}>
            <FormInput
              label="입금자명"
              type="text"
              placeholder="이체 시 사용하신 이름을 입력하세요"
              value={depositorName}
              onChange={e => setDepositorName(e.target.value)}
              error={error}
              delay={0}
              icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>}
            />
          </div>

          {/* 6. 신청 버튼 */}
          <div className="animate-slide-up" style={{ animationDelay: '0.3s', animationFillMode: 'backwards' }}>
            <GoldButton type="submit" loading={loading} delay={0}>
              구독 신청하기
            </GoldButton>
          </div>

        </form>
      </div>

      <div className="text-center px-4" style={{ paddingBottom: 'max(16px, env(safe-area-inset-bottom))' }}>
        <p className="text-[10px]" style={{ color: 'var(--text-muted)', opacity: 0.5 }}>
          본 정보는 투자자문이 아니며, 투자 판단의 책임은 본인에게 있습니다.
        </p>
      </div>
    </div>
  )
}
