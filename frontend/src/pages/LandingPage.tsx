import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'

/* ─── Types ─── */
type DemoPhase = 'idle' | 'analyzing' | 'agent-0' | 'agent-1' | 'agent-2' | 'agent-3' | 'agent-4' | 'consensus' | 'result'

/* ─── Mock Data ─── */
const MOCK_AGENTS = [
  { name: 'Claude',     role: '리스크·베이즈 추론', color: '#FF6B35', rgb: '255,107,53',   weight: '1.2x', verdict: '매수', confidence: 87 },
  { name: 'Gemini',     role: '차트·기술 분석',     color: '#4285F4', rgb: '66,133,244',    weight: '1.0x', verdict: '매수', confidence: 82 },
  { name: 'ChatGPT',    role: '펀더멘털·글로벌',    color: '#10A37F', rgb: '16,163,127',    weight: '1.1x', verdict: '매수', confidence: 79 },
  { name: 'Perplexity', role: '실시간 뉴스',        color: '#20B2AA', rgb: '32,178,170',    weight: '1.15x', verdict: '주목', confidence: 73 },
  { name: 'Grok',       role: '소셜·감성',          color: '#FF4500', rgb: '255,69,0',      weight: '0.9x', verdict: '매수', confidence: 68 },
]

const MOCK_RESULT = {
  consensusScore: 87,
  sentiment: 'bullish',
  sentimentLabel: '강세',
  scenarios: { bull: 35, base: 45, bear: 20 },
}

const QUICK_STOCKS = ['삼성전자', 'SK하이닉스', 'NAVER', '카카오', 'LG에너지솔루션']

const MOCK_PICKS = [
  { rank: 1, name: 'LG에너지솔루션', code: '373220', price: '384,000', target: '396,000', stopLoss: '375,000', action: '매수' as const },
  { rank: 2, name: '기아', code: '000270', price: '167,000', target: '172,000', stopLoss: '163,000', action: '매수' as const },
  { rank: 3, name: '한화에어로스페이스', code: '012450', price: '1,465,000', target: '1,517,000', stopLoss: '1,423,000', action: '매수' as const },
  { rank: 4, name: 'HD현대중공업', code: '329180', price: '604,000', target: '622,000', stopLoss: '590,000', action: '매수' as const },
  { rank: 5, name: 'POSCO홀딩스', code: '005490', price: '347,500', target: '360,000', stopLoss: '340,000', action: '매수' as const },
]

const DEMO_TIMINGS: Record<string, [DemoPhase, number]> = {
  'analyzing': ['agent-0', 400],
  'agent-0': ['agent-1', 600],
  'agent-1': ['agent-2', 500],
  'agent-2': ['agent-3', 500],
  'agent-3': ['agent-4', 450],
  'agent-4': ['consensus', 400],
  'consensus': ['result', 800],
}

export default function LandingPage() {
  const navigate = useNavigate()
  const [demoPhase, setDemoPhase] = useState<DemoPhase>('idle')
  const [demoStock, setDemoStock] = useState('')
  const [showMockScreen, setShowMockScreen] = useState(false)
  const [mockBlurVisible, setMockBlurVisible] = useState(false)
  const navRef = useRef<HTMLElement>(null)
  const demoAgentsRef = useRef<HTMLDivElement>(null)
  const demoResultRef = useRef<HTMLDivElement>(null)

  // ─── Navbar scroll effect ───
  useEffect(() => {
    const onScroll = () => {
      if (navRef.current) {
        navRef.current.classList.toggle('scrolled', window.scrollY > 50)
      }
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  // ─── IntersectionObserver for scroll animations ───
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach(e => {
          if (e.isIntersecting) e.target.classList.add('l-visible')
        })
      },
      { threshold: 0.1 }
    )
    document.querySelectorAll('.l-animate-on-scroll').forEach(el => observer.observe(el))
    return () => observer.disconnect()
  }, [])

  // ─── Mock screen blur timer ───
  useEffect(() => {
    if (!showMockScreen) { setMockBlurVisible(false); return }
    const timer = setTimeout(() => setMockBlurVisible(true), 3500)
    return () => clearTimeout(timer)
  }, [showMockScreen])

  // ─── Auto-scroll to result when demo completes ───
  useEffect(() => {
    if (demoPhase === 'result') {
      setTimeout(() => {
        demoResultRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
      }, 200)
    }
  }, [demoPhase])

  // ─── Demo state machine ───
  useEffect(() => {
    if (demoPhase === 'idle' || demoPhase === 'result') return
    const entry = DEMO_TIMINGS[demoPhase]
    if (!entry) return
    const [next, delay] = entry
    const timer = setTimeout(() => setDemoPhase(next), delay)
    return () => clearTimeout(timer)
  }, [demoPhase])

  const startDemo = () => {
    if (!demoStock) return
    setDemoPhase('analyzing')
    setTimeout(() => {
      demoAgentsRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 150)
  }

  const smoothScroll = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const agentIndex = (phase: DemoPhase): number => {
    const m = phase.match(/^agent-(\d)$/)
    return m ? parseInt(m[1]) : -1
  }
  const currentAgentIdx = agentIndex(demoPhase)
  const isPhaseAfter = (phase: DemoPhase, target: number) => {
    const idx = agentIndex(phase)
    if (idx > target) return true
    if (phase === 'consensus' || phase === 'result') return true
    return false
  }

  // Gauge SVG
  const gaugeR = 50
  const gaugeC = 2 * Math.PI * gaugeR
  const gaugeTarget = gaugeC * (1 - MOCK_RESULT.consensusScore / 100)

  return (
    <div className="landing-page">
      {/* ─── Navigation ─── */}
      <nav ref={navRef} className="landing-nav">
        <div className="l-container">
          <a href="#" className="l-logo" onClick={e => { e.preventDefault(); window.scrollTo({ top: 0, behavior: 'smooth' }) }}>BITMAN AI</a>
          <ul className="l-nav-links">
            <li><a href="#why" onClick={e => { e.preventDefault(); smoothScroll('why') }}>엔진소개</a></li>
            <li><a href="#engine" onClick={e => { e.preventDefault(); smoothScroll('engine') }}>아키텍처</a></li>
            <li><a href="#modes" onClick={e => { e.preventDefault(); smoothScroll('modes') }}>분석모드</a></li>
            <li><a href="#demo" onClick={e => { e.preventDefault(); smoothScroll('demo') }}>체험하기</a></li>
            <li><a href="#" onClick={e => { e.preventDefault(); navigate('/register') }}>구독</a></li>
          </ul>
        </div>
      </nav>

      {/* ─── Hero ─── */}
      <section className="landing-hero">
        <div className="l-hero-grid" />
        <div className="l-container">
          <div className="l-hero-content">
            <div className="l-hero-badge">★ 5개 AI 합의 엔진 V4.0</div>
            <h1>
              <span>Multi-AI</span>
              <span className="l-text-gradient">Consensus Engine</span>
            </h1>
            <p className="l-hero-subtitle">
              5개 AI가 동시에 분석하고, 가중 투표로 합의합니다<br />
              단일 AI의 편향을 넘어선 차세대 투자 분석 시스템
            </p>
            <div className="l-hero-stats">
              <div className="l-stat-item">
                <div className="l-stat-value">87%</div>
                <div className="l-stat-label">합의 정확도</div>
              </div>
              <div className="l-stat-item">
                <div className="l-stat-value">5 AI</div>
                <div className="l-stat-label">병렬 엔진</div>
              </div>
              <div className="l-stat-item">
                <div className="l-stat-value">3</div>
                <div className="l-stat-label">시나리오 분석</div>
              </div>
            </div>
            <button className="l-hero-cta" onClick={() => smoothScroll('demo')}>
              엔진 체험하기 →
            </button>
          </div>
        </div>
        <div className="l-scroll-indicator">
          <span>Scroll</span>
        </div>
      </section>

      {/* ─── Why BitMan ─── */}
      <section className="landing-why" id="why">
        <div className="l-container">
          <div className="l-animate-on-scroll">
            <span className="l-section-label">The Problem</span>
            <h2 className="l-section-title">왜 BitMan 뭐사 인가</h2>
            <p className="l-section-desc">
              단일 AI에 의존하는 투자 분석의 한계를 넘어,<br />
              5개 전문 AI의 교차 검증으로 편향을 제거합니다.
            </p>
          </div>

          <div className="l-about-grid">
            <div className="l-animate-on-scroll">
              <blockquote className="l-quote-block">
                하나의 AI는 학습 데이터에 따라 강세 또는 약세 편향이 존재합니다.
                차트만, 뉴스만, 감성만 보는 AI는 전체 그림을 놓칩니다.
                검증 없이 제공되는 추천은 동전 던지기와 다르지 않습니다.<br /><br />
                <strong>BitMan은 이 문제를 5개 AI의 합의로 해결합니다.</strong>
              </blockquote>
            </div>
            <div className="l-animate-on-scroll">
              <ul className="l-achievement-list">
                <li>
                  <div>
                    <strong>단일 AI 편향 → 5 AI 교차 검증</strong>
                    <p>각 AI가 서로 다른 관점에서 분석, 가중 투표로 편향 제거</p>
                  </div>
                </li>
                <li>
                  <div>
                    <strong>정보 사각지대 → 5가지 전문 영역</strong>
                    <p>차트·펀더멘털·뉴스·감성·리스크를 동시에 커버</p>
                  </div>
                </li>
                <li>
                  <div>
                    <strong>검증 없는 추천 → 매일 자동 백테스팅</strong>
                    <p>추천 정확도를 매일 추적하고 가중치를 자동 조정</p>
                  </div>
                </li>
                <li>
                  <div>
                    <strong>느린 업데이트 → 실시간 5회/일 자동 분석</strong>
                    <p>시장 상황에 맞춰 하루 5회 자동 업데이트</p>
                  </div>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </section>

      {/* ─── Engine Architecture ─── */}
      <section className="landing-engine" id="engine">
        <div className="l-container">
          <div className="l-animate-on-scroll" style={{ textAlign: 'center' }}>
            <span className="l-section-label">Architecture</span>
            <h2 className="l-section-title">5 AI 에이전트 아키텍처</h2>
            <p className="l-section-desc" style={{ margin: '0 auto' }}>
              5개 AI가 병렬 분석 후 가중 합의를 거쳐<br />
              Bull/Base/Bear 3-시나리오 리포트를 생성합니다.
            </p>
          </div>

          <div className="l-pipeline">
            <div className="l-pipeline-stage l-animate-on-scroll">
              <div className="l-stage-number">1</div>
              <h4>5 AI 병렬 분석</h4>
              <p>
                Claude(리스크), Gemini(차트), ChatGPT(펀더멘털),
                Perplexity(뉴스), Grok(감성) — 각 전문 영역에서 동시 분석을 수행합니다.
              </p>
            </div>
            <div className="l-pipeline-stage l-animate-on-scroll">
              <div className="l-stage-number">2</div>
              <h4>가중 합의 투표</h4>
              <p>
                성과 기반 동적 가중치로 각 AI의 투표를 합산합니다.
                베이지안 추론 프레임워크로 불확실성을 정량화합니다.
              </p>
            </div>
            <div className="l-pipeline-stage l-animate-on-scroll">
              <div className="l-stage-number">3</div>
              <h4>3-시나리오 리포트</h4>
              <p>
                Bull/Base/Bear 시나리오별 목표가·손절가를 산출하고,
                S~D 출처 등급으로 분석의 신뢰도를 투명하게 공개합니다.
              </p>
            </div>
          </div>

          <div className="l-tech-stack l-animate-on-scroll">
            {['베이지안 추론', '동적 가중치', '시나리오 분석', '자동 백테스팅', '실시간 뉴스', '감성 분석'].map(tag => (
              <span key={tag} className="l-tech-tag">{tag}</span>
            ))}
          </div>
        </div>
      </section>

      {/* ─── Analysis Modes ─── */}
      <section className="landing-modes" id="modes">
        <div className="l-container">
          <div className="l-animate-on-scroll" style={{ textAlign: 'center' }}>
            <span className="l-section-label">5 Modes</span>
            <h2 className="l-section-title">5가지 분석 모드</h2>
            <p className="l-section-desc" style={{ margin: '0 auto' }}>
              투자 스타일에 맞는 분석 모드를 선택하세요.<br />
              자동 스케줄 분석부터 온디맨드 심층 분석까지.
            </p>
          </div>

          <div className="l-template-grid">
            {[
              { num: '01', title: '오늘뭐사', schedule: '매일 08:00', desc: '당일 매매에 최적화된 추천 종목을 제공합니다' },
              { num: '02', title: '스윙매매', schedule: '3일마다 07:00', desc: '3~10일 보유 전략의 스윙 후보를 선별합니다' },
              { num: '03', title: '종가매매', schedule: '매일 15:10', desc: '종가 매수 → 시초가 매도 전략을 분석합니다' },
              { num: '04', title: '수급분석', schedule: '매일 10:00 · 14:00', desc: '외인·기관 수급 교차 분석으로 주도주를 포착합니다' },
            ].map(mode => (
              <div key={mode.num} className="l-mode-card l-animate-on-scroll">
                <div className="l-mode-number">{mode.num}</div>
                <h4>{mode.title}</h4>
                <div className="l-mode-schedule">{mode.schedule}</div>
                <p>{mode.desc}</p>
              </div>
            ))}
            <div className="l-mode-card l-mode-special l-animate-on-scroll">
              <div className="l-mode-number">05</div>
              <h4>분석해줘</h4>
              <div className="l-mode-schedule">On-demand</div>
              <p>원하는 종목명을 입력하면 5개 AI가 즉시 심층 분석을 수행합니다. 아래에서 직접 체험해 보세요.</p>
            </div>
          </div>
        </div>
      </section>

      {/* ─── Interactive Demo ─── */}
      <section className="landing-demo" id="demo">
        <div className="l-container">
          <div className="l-animate-on-scroll" style={{ textAlign: 'center' }}>
            <span className="l-section-label">Try It</span>
            <h2 className="l-section-title">직접 체험해 보세요</h2>
            <p className="l-section-desc" style={{ margin: '0 auto 32px' }}>
              5개 AI 합의 엔진의 분석 과정을 직접 확인하세요.
            </p>
            <div className="l-demo-warning">
              ⚠️ 시연용 가상 데이터입니다. 실제 서비스에서는 실시간 시장 데이터로 분석됩니다.
            </div>
          </div>

          {/* Input Area */}
          {demoPhase === 'idle' && (
            <div className="l-demo-input-area l-animate-on-scroll">
              <input
                className="l-demo-input"
                type="text"
                placeholder="종목명을 입력하세요 (예: 삼성전자)"
                value={demoStock}
                onChange={e => setDemoStock(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && startDemo()}
              />
              <div className="l-demo-chips">
                {QUICK_STOCKS.map(s => (
                  <button key={s} className="l-demo-chip" onClick={() => setDemoStock(s)}>{s}</button>
                ))}
              </div>
              <button className="l-btn-primary" onClick={startDemo} disabled={!demoStock}>
                분석 시작 →
              </button>
            </div>
          )}

          {/* Agent Cards */}
          {demoPhase !== 'idle' && (
            <>
              <div className="l-demo-agents" ref={demoAgentsRef}>
                {MOCK_AGENTS.map((agent, i) => {
                  const isActive = currentAgentIdx === i
                  const isDone = isPhaseAfter(demoPhase, i)
                  return (
                    <div
                      key={agent.name}
                      className={`l-demo-agent-card ${isActive ? 'l-agent-active' : ''} ${isDone ? 'l-agent-done' : ''}`}
                      style={{ '--l-agent-color': agent.color, '--l-agent-rgb': agent.rgb } as React.CSSProperties}
                    >
                      <div className="l-agent-dot" style={{ background: agent.color }} />
                      <h5>{agent.name}</h5>
                      <div className="l-agent-role">{agent.role}</div>
                      <div className="l-agent-status">
                        {isActive ? '분석 중...' : isDone ? `${agent.verdict} (${agent.confidence}%)` : agent.weight}
                      </div>
                    </div>
                  )
                })}
              </div>

              {/* Progress Bar */}
              {demoPhase !== 'result' && (
                <div className="l-demo-progress">
                  <div className="l-demo-progress-bar" key={demoPhase === 'analyzing' ? 'start' : undefined} />
                </div>
              )}

              {/* Consensus animation */}
              {demoPhase === 'consensus' && (
                <p style={{ textAlign: 'center', color: '#D4AF37', fontSize: '1.1rem', marginTop: 16 }}>
                  합의 도출 중...
                </p>
              )}

              {/* Result */}
              {demoPhase === 'result' && (
                <div className="l-demo-result" ref={demoResultRef}>
                  <div className="l-demo-result-visible">
                    <div className="l-result-header">
                      <div className="l-result-stock">{demoStock}</div>
                      <div className="l-result-sentiment">{MOCK_RESULT.sentimentLabel}</div>
                    </div>

                    <div className="l-result-gauge">
                      <svg className="l-gauge-svg" viewBox="0 0 120 120">
                        <circle cx="60" cy="60" r={gaugeR} fill="none" stroke="rgba(212,175,55,0.1)" strokeWidth="8" />
                        <circle
                          cx="60" cy="60" r={gaugeR}
                          fill="none" stroke="#D4AF37" strokeWidth="8"
                          strokeLinecap="round"
                          strokeDasharray={gaugeC}
                          strokeDashoffset={gaugeC}
                          className="l-gauge-animate"
                          style={{ '--l-gauge-circumference': gaugeC, '--l-gauge-target': gaugeTarget, transform: 'rotate(-90deg)', transformOrigin: 'center' } as React.CSSProperties}
                        />
                        <text x="60" y="55" textAnchor="middle" fill="#D4AF37" fontSize="28" fontFamily="Cormorant Garamond" fontWeight="700">
                          {MOCK_RESULT.consensusScore}%
                        </text>
                        <text x="60" y="75" textAnchor="middle" fill="#DEE2E6" fontSize="10">합의 점수</text>
                      </svg>
                    </div>

                    <div className="l-result-votes">
                      {MOCK_AGENTS.map(a => (
                        <span key={a.name} className="l-vote-chip" style={{ borderColor: a.color, color: a.color }}>
                          {a.name}: {a.verdict}
                        </span>
                      ))}
                    </div>

                    <div className="l-scenario-bar">
                      <div style={{ width: `${MOCK_RESULT.scenarios.bull}%`, background: '#00c853' }} />
                      <div style={{ width: `${MOCK_RESULT.scenarios.base}%`, background: '#D4AF37' }} />
                      <div style={{ width: `${MOCK_RESULT.scenarios.bear}%`, background: '#ff1744' }} />
                    </div>
                    <div className="l-scenario-labels">
                      <span>Bull {MOCK_RESULT.scenarios.bull}%</span>
                      <span>Base {MOCK_RESULT.scenarios.base}%</span>
                      <span>Bear {MOCK_RESULT.scenarios.bear}%</span>
                    </div>
                  </div>

                  {/* Blur overlay */}
                  <div className="l-demo-blur">
                    <div className="l-demo-blur-content">
                      <p style={{ color: '#aaa' }}>목표가: ₩87,000 | 손절가: ₩72,500 | 현재가: ₩79,800</p>
                      <p style={{ color: '#aaa' }}>출처 등급: S | 상세 시나리오 분석 | 베이지안 신뢰구간</p>
                      <p style={{ color: '#aaa' }}>개별 에이전트 상세 리포트 | 백테스팅 결과 | 리스크 매트릭스</p>
                    </div>
                    <div className="l-demo-blur-overlay">
                      <div className="l-lock-icon">🔒</div>
                      <h4>전체 분석 리포트 보기</h4>
                      <p>목표가, 손절가, 상세 시나리오, 출처 등급 등<br />전체 리포트는 PRO 구독 후 확인하실 수 있습니다.</p>
                      <div className="l-demo-cta-buttons">
                        <button className="l-btn-primary" onClick={() => setShowMockScreen(true)}>
                          PRO 구독하고 전체 보기 →
                        </button>
                        <button className="l-btn-secondary" onClick={() => navigate('/register')}>
                          무료로 가입 먼저
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </section>

      {/* ─── CTA ─── */}
      <section className="landing-cta">
        <div className="l-container">
          <div className="l-cta-content l-animate-on-scroll">
            <h2 className="l-section-title" style={{ textAlign: 'center', marginBottom: 32 }}>
              5개 AI의 합의를<br /><span className="l-text-gold">지금 받아보세요</span>
            </h2>
            <p className="l-cta-quote">
              "데이터가 아닌, 합의를 믿으세요.<br />
              5개 AI가 동의할 때, 그것이 진짜 신호입니다."
              <span className="l-cta-author">— BitMan AI Engine</span>
            </p>
            <div className="l-cta-buttons">
              <button className="l-btn-primary" onClick={() => navigate('/register')}>
                지금 시작하기 →
              </button>
              <button className="l-btn-secondary" onClick={() => smoothScroll('demo')}>
                무료 체험 보기
              </button>
            </div>
          </div>
        </div>
      </section>

      {/* ─── Footer ─── */}
      <footer className="landing-footer">
        <div className="l-container">
          <div className="l-footer-content">
            <div className="l-footer-logo">BITMAN AI</div>
            <div className="l-footer-text">
              © 2026 BitMan AI. 투자의 결정은 투자자 본인의 판단과 책임하에 이루어져야 합니다.
              본 서비스는 투자자문이 아닙니다.
            </div>
          </div>
        </div>
      </footer>

      {/* ─── KakaoTalk Floating Button ─── */}
      <a
        href="https://open.kakao.com/o/sJVLbWUe"
        target="_blank"
        rel="noopener noreferrer"
        className="l-kakao-float"
        title="AI 단톡방 입장문의"
      >
        <svg className="l-kakao-icon" viewBox="0 0 24 24" fill="#3C1E1E">
          <path d="M12 3C6.48 3 2 6.58 2 10.9c0 2.78 1.8 5.22 4.5 6.6-.2.73-.72 2.65-.82 3.06-.13.5.18.49.38.36.16-.1 2.46-1.67 3.46-2.35.48.07.97.1 1.48.1 5.52 0 10-3.58 10-7.77C22 6.58 17.52 3 12 3z"/>
        </svg>
        <span className="l-kakao-text">AI 단톡방<br/>입장문의</span>
      </a>

      {/* ─── Mock Full Result Screen ─── */}
      {showMockScreen && (
        <div className="l-mock-screen">
          <div className="l-mock-inner">
            {/* Mock Header */}
            <div className="l-mock-header">
              <span className="l-mock-back">← AI 분석</span>
              <span className="l-mock-title"><b>BitMan</b> 오늘 <span style={{ color: '#00C853' }}>뭐사?</span></span>
              <span className="l-mock-home">🏠</span>
            </div>

            {/* Mock Sub-header */}
            <div className="l-mock-subheader">
              <div className="l-mock-query">📋 오늘 뭐 살까? 당일 매매 추천</div>
              <div className="l-mock-meta">
                <span className="l-mock-meta-badge l-mock-badge-agree">🏆 3 AI 동의</span>
                <span className="l-mock-meta-badge">chatgpt 11s</span>
                <span className="l-mock-meta-badge">claude 41s</span>
                <span className="l-mock-meta-badge">gemini 26s</span>
              </div>
            </div>

            {/* Mock Stock Cards */}
            <div className="l-mock-section-title">🎯 추천 종목 TOP 5</div>
            <div className="l-mock-cards">
              {MOCK_PICKS.map((pick, i) => (
                <div key={pick.code} className="l-mock-card" style={{ animationDelay: `${i * 0.15}s` }}>
                  <div className="l-mock-card-border" />
                  <div className="l-mock-card-body">
                    <div className="l-mock-card-rank">{pick.rank}</div>
                    <div className="l-mock-card-info">
                      <div className="l-mock-card-name">
                        {pick.name} <span className="l-mock-card-code">{pick.code}</span>
                      </div>
                      <div className="l-mock-card-prices">
                        <span className="l-mock-price">{pick.price}원</span>
                        <span className="l-mock-live">LIVE</span>
                        <span className="l-mock-target">목표 {pick.target}원</span>
                        <span className="l-mock-stoploss">손절 {pick.stopLoss}원</span>
                      </div>
                      <div className="l-mock-card-reason">3개 AI 전원 동의</div>
                    </div>
                    <div className="l-mock-card-action">
                      <div className="l-mock-action-icon">▲</div>
                      <div className="l-mock-action-label">BUY</div>
                    </div>
                  </div>
                </div>
              ))}
            </div>

            {/* Mock Detail Section */}
            <div className="l-mock-detail-divider">상세 분석 리포트</div>
            <div className="l-mock-details">
              <div className="l-mock-detail-title">● 당일 매매 추천 종목 TOP</div>
              {MOCK_PICKS.slice(0, 3).map(pick => (
                <div key={pick.code} className="l-mock-detail-item">
                  <div className="l-mock-detail-name">🚀 {pick.name} ({pick.code}) — <span style={{ color: '#FF6B35' }}>현재가 약 {pick.price}원</span></div>
                  <ul className="l-mock-detail-list">
                    <li>투자판단: {pick.action}</li>
                    <li>AI 합의: 3개 AI 전원 동의</li>
                    <li>목표가: {pick.target}원 / 손절가: {pick.stopLoss}원</li>
                  </ul>
                </div>
              ))}
            </div>

            <div className="l-mock-disclaimer">
              ⚠ 본 정보는 투자자문이 아니며, 투자 판단의 책임은 본인에게 있습니다.
            </div>
          </div>

          {/* Blur Overlay (appears after 3.5s) */}
          <div className={`l-mock-blur-overlay ${mockBlurVisible ? 'l-mock-blur-visible' : ''}`}>
            <div className="l-mock-blur-content">
              <div className="l-mock-blur-icon">📊</div>
              <h3>이런 분석을 매일 받아보세요</h3>
              <p>
                매일 아침 8시, 5개 AI가<br />
                오늘의 추천 종목을 분석합니다
              </p>
              <div className="l-mock-blur-buttons">
                <button className="l-btn-primary" onClick={() => navigate('/register')}>
                  PRO 구독하기 →
                </button>
                <button className="l-btn-secondary" onClick={() => navigate('/register')}>
                  무료 가입
                </button>
              </div>
              <button className="l-mock-close" onClick={() => setShowMockScreen(false)}>
                ✕ 닫기
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
