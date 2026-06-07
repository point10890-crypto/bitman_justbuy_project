import { Navigate, useNavigate } from 'react-router-dom'
import { PWAInstallPrompt } from '../components/app/PWAInstallPrompt'
import { useAuth } from '../contexts/AuthContext'

const KAKAO_URL = 'https://open.kakao.com/o/sJVLbWUe'

const previewRows = [
  { label: '단타', name: '로XXX', price: '3XXX', high: '3XXX', rate: '+10.09%' },
  { label: '스윙', name: '현XXX', price: '31,800', high: '35,000', rate: '관찰' },
  { label: '주도주', name: '한XXX', price: '980억', high: '+8.7%', rate: '중' },
]

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
    <main className="bm-landing with-public-contact">
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
          <button type="button" onClick={() => navigate('/register')}>시작하기</button>
        </div>
      </nav>

      <section className="bm-hero">
        <div className="bm-hero-media" aria-hidden="true">
          <div className="bm-phone-preview">
            <header>
              <span className="bitman-logo-mark bm-phone-logo">B<i /><em /></span>
              <strong>오늘 뭐사?</strong>
              <small>2026.05.16</small>
            </header>
            <div className="bm-phone-banner">실시간이슈 바로확인</div>
            {previewRows.map(row => (
              <div className="bm-preview-card" key={row.label}>
                <div>
                  <span className="bm-preview-icon" />
                  <strong>{row.label}</strong>
                  <b>TOP3</b>
                </div>
                <p><span>종목명</span><span>포착가</span><span>최고가</span><span>상태</span></p>
                <p><span>{row.name}</span><span>{row.price}</span><span>{row.high}</span><span>{row.rate}</span></p>
              </div>
            ))}
          </div>
        </div>

        <div className="bm-hero-copy">
          <p className="bm-eyebrow">월 구독형 AI 조건검색식 서비스</p>
          <h1>BitMan 오늘 뭐사? AI 조건검색기</h1>
          <p className="bm-hero-desc">
            단타, 스윙, 주도주, 테마, 종목 알림이를 한 화면에서 확인하는 모바일 전용 조건검색 앱입니다.
            KIS 조건검색과 DART 공시, AI 요약을 연결해 오늘 볼 종목을 빠르게 정리합니다.
          </p>
          <div className="bm-hero-actions">
            <button type="button" onClick={() => navigate('/register')}>무료 가입 후 구독 시작</button>
            <PWAInstallPrompt
              variant="hero-button"
              className="bm-install-button"
              showWhenDismissed
              label="앱 설치하기"
            />
            <button type="button" onClick={() => document.getElementById('features')?.scrollIntoView({ behavior: 'smooth' })}>기능 보기</button>
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
          <h2>가입하고, 구독하고, 오늘 포착된 TOP3를 확인합니다</h2>
        </div>
        <div className="bm-flow">
          <div><strong>1</strong><span>간편 회원가입</span></div>
          <div><strong>2</strong><span>월간 이용권 구독</span></div>
          <div><strong>3</strong><span>전체 종목 공개</span></div>
          <div><strong>4</strong><span>알림 등록</span></div>
        </div>
      </section>

      <section className="bm-pricing">
        <div>
          <p className="bm-eyebrow">Monthly Plan</p>
          <h2>월간 이용권</h2>
          <strong className="bm-price">월 구독료 30,000원</strong>
          <p>구독자는 종목명, 포착가, 최고가, 수익률, AI 요약, 종목 알림이를 모두 확인할 수 있습니다.</p>
        </div>
        <button type="button" onClick={() => navigate('/register')}>구독 시작하기</button>
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
