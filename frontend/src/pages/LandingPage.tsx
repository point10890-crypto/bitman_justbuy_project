import { Link, Navigate, useNavigate } from 'react-router-dom'
import { PWAInstallPrompt } from '../components/app/PWAInstallPrompt'
import { useAuth } from '../contexts/AuthContext'
import { AgentMascot } from '../components/app/AgentMascot'
import { formatWon, SUBSCRIPTION_PLANS, subscribePath } from '../lib/subscriptionPlans'
import './LandingPage.css'

const KAKAO_URL = 'https://open.kakao.com/o/sJVLbWUe'

const featureCards = [
  { title: '단타', desc: '장중 거래대금과 체결 흐름을 기준으로 빠른 후보를 포착합니다.' },
  { title: '스윙', desc: '눌림목, 추세 회복, 재무와 공시 안정성을 함께 봅니다.' },
  { title: '주도주 포착', desc: '시장 자금이 몰리는 종목과 섹터 대표 종목을 따로 정리합니다.' },
  { title: '테마 분석', desc: '뉴스와 공시 흐름을 테마 단위로 묶고 대장주를 확인합니다.' },
  { title: '종목 알림이', desc: '관심 종목이 검색식에 포착되거나 목표 조건에 도달하면 알려줍니다.' },
  { title: 'AI 요약', desc: 'KIS, DART 데이터 기반으로 포착 이유와 주의점을 짧게 요약합니다.' },
]

export default function LandingPage() {
  const navigate = useNavigate()
  const { user, isLoading } = useAuth()

  if (isLoading) {
    return (
      <div className="landing-loading">
        <div />
      </div>
    )
  }

  if (user) return <Navigate to="/" replace />

  return (
    <main className="bm-landing agent-landing with-public-contact">
      <nav className="bm-landing-nav">
        <button className="bitman-logo-lockup" type="button" onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}>
          <span className="bitman-logo-mark">
            B
            <i />
            <em />
          </span>
          <span className="bitman-logo-copy">
            <strong><span>BitMan</span> 오늘 뭐사?</strong>
            <small>AI STOCK ANALYSIS ENGINE</small>
          </span>
        </button>
        <div>
          <button type="button" onClick={() => navigate('/login')}>로그인</button>
          <PWAInstallPrompt
            variant="nav-button"
            className="bm-install-button"
            showWhenDismissed
            label="앱 설치"
          />
          <button type="button" onClick={() => navigate('/subscribe')}>구독 플랜 보기</button>
        </div>
      </nav>

      <section className="bm-hero">
        <div className="bm-hero-media">
          <div className="agent-landing-mascot">
            <AgentMascot />
            <span>나의 AI 분석 파트너, BitMan</span>
          </div>
        </div>

        <div className="bm-hero-copy">
          <p className="bm-eyebrow">나의 AI 분석 파트너</p>
          <h1>오늘의 종목,<br />BitMan과 함께.</h1>
          <p className="bm-hero-desc">
            단타, 스윙, 주도주, 테마, 종목 알림이를 한 화면에서 확인하세요.
            KIS 조건검색과 DART 공시, AI 요약을 연결해 오늘 볼 종목을 빠르게 정리합니다.
          </p>
          <div className="bm-hero-actions">
            <button type="button" onClick={() => document.getElementById('pricing')?.scrollIntoView({ behavior: 'smooth' })}>구독 플랜 보기</button>
            <PWAInstallPrompt
              variant="hero-button"
              className="bm-install-button"
              showWhenDismissed
              label="앱 설치하기"
            />
            <button type="button" onClick={() => navigate('/register')}>무료 회원가입</button>
          </div>
          <div className="bm-proof-row">
            <span>단타 TOP3</span>
            <span>스윙 TOP3</span>
            <span>주도주 TOP3</span>
            <span>종목 알림</span>
          </div>
        </div>
      </section>

      <section className="bm-section" id="features">
        <div className="bm-section-head">
          <p>핵심 기능</p>
          <h2>매일 보는 화면은 단순하게, 운영 데이터는 깊게</h2>
        </div>
        <div className="bm-feature-grid">
          {featureCards.map(card => (
            <article key={card.title}>
              <h3>{card.title}</h3>
              <p>{card.desc}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="bm-section bm-flow-section">
        <div className="bm-section-head">
          <p>사용 흐름</p>
          <h2>이용권을 선택하고, 오늘 포착된 종목을 확인하세요</h2>
        </div>
        <div className="bm-flow">
          <div><strong>1</strong><span>플랜 선택</span></div>
          <div><strong>2</strong><span>무료 가입 · 로그인</span></div>
          <div><strong>3</strong><span>입금 · 구독 신청</span></div>
          <div><strong>4</strong><span>승인 후 전체 공개</span></div>
        </div>
      </section>

      <section className="bm-pricing bm-plan-pricing" id="pricing">
        <div className="bm-plan-intro">
          <h2>나에게 맞는 이용 기간을 선택하세요</h2>
          <p>두 이용권 모두 전체 종목, AI 요약과 종목 알림이를 제공합니다. 선택한 플랜은 가입 후에도 이어집니다.</p>
        </div>
        <div className="bm-plan-grid">
          {SUBSCRIPTION_PLANS.map(plan => (
            <article className={`bm-plan-card${plan.id === 'SIX_MONTHS' ? ' six-months' : ''}`} key={plan.id}>
              <div className="bm-plan-heading"><h3>{plan.label} 이용권</h3>{plan.id === 'SIX_MONTHS' && <span>30,000원 절약</span>}</div>
              <strong className="bm-price">{formatWon(plan.price)}</strong>
              <p className="bm-plan-payment">{plan.id === 'SIX_MONTHS' ? '6개월 일시 납부 · 월 25,000원꼴' : '1개월 일시 납부'}</p>
              <p>{plan.id === 'SIX_MONTHS' ? '1개월 이용권 6회 대비 30,000원 저렴합니다.' : '한 달 동안 BitMan 전체 분석을 이용하세요.'}</p>
              <Link to={subscribePath(plan.id)}>{plan.label} 구독 선택</Link>
            </article>
          ))}
        </div>
        <p className="bm-plan-note">가입은 무료이며, 계좌 입금 확인 후 이용권이 활성화됩니다. 자동 결제는 되지 않습니다.</p>
      </section>

      <footer className="bm-footer">
        <strong>BitMan 오늘 뭐사? AI 조건검색기</strong>
        <span>본 서비스는 투자 참고 정보를 제공하며 투자 판단과 책임은 본인에게 있습니다.</span>
      </footer>
      <a className="public-contact-bottom" href={KAKAO_URL} target="_blank" rel="noreferrer">
        <span className="kakao-nav-mark">톡</span>
        <strong>카카오톡 문의</strong>
      </a>
    </main>
  )
}
