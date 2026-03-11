import type { ConsensusResult, ConsensusStock } from '../../api/analysisApi'

interface ConsensusDisplayProps {
  consensus: ConsensusResult
}

/** AI 합의도 원형 게이지 */
function AgreementGauge({ score }: { score: number }) {
  const radius = 36
  const circumference = 2 * Math.PI * radius
  const offset = circumference - (score / 100) * circumference
  const color = score >= 70 ? '#22c55e' : score >= 40 ? '#eab308' : '#ef4444'

  return (
    <div style={{ position: 'relative', width: 88, height: 88, margin: '0 auto' }}>
      <svg width={88} height={88} viewBox="0 0 88 88">
        <circle cx={44} cy={44} r={radius} fill="none" stroke="#e5e7eb" strokeWidth={6} />
        <circle
          cx={44} cy={44} r={radius}
          fill="none" stroke={color} strokeWidth={6}
          strokeDasharray={circumference} strokeDashoffset={offset}
          strokeLinecap="round"
          transform="rotate(-90 44 44)"
          style={{ transition: 'stroke-dashoffset 0.6s ease' }}
        />
      </svg>
      <div style={{
        position: 'absolute', inset: 0,
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center'
      }}>
        <span style={{ fontSize: 20, fontWeight: 700, color }}>{score}%</span>
        <span style={{ fontSize: 10, color: '#6b7280' }}>합의도</span>
      </div>
    </div>
  )
}

/** 센티먼트 배지 */
function SentimentBadge({ sentiment }: { sentiment: string }) {
  const config: Record<string, { label: string; color: string; bg: string }> = {
    bullish: { label: '강세', color: '#dc2626', bg: '#fef2f2' },
    bearish: { label: '약세', color: '#2563eb', bg: '#eff6ff' },
    neutral: { label: '중립', color: '#6b7280', bg: '#f3f4f6' },
  }
  const c = config[sentiment] || config.neutral
  return (
    <span style={{
      display: 'inline-block', padding: '2px 10px', borderRadius: 12,
      fontSize: 12, fontWeight: 600, color: c.color, backgroundColor: c.bg,
    }}>
      {c.label}
    </span>
  )
}

/** 에이전트 투표 칩 */
function AgentVoteChip({ agent, vote }: { agent: string; vote: { action: string; confidence: number } }) {
  const actionColors: Record<string, string> = {
    '매수': '#dc2626', '매도': '#2563eb', '관망': '#6b7280', '주목': '#d97706',
  }
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 3,
      padding: '1px 8px', borderRadius: 10,
      fontSize: 11, fontWeight: 500,
      backgroundColor: '#f3f4f6', color: '#374151',
    }}>
      <span style={{ fontWeight: 600, textTransform: 'capitalize' }}>{agent}</span>
      <span style={{ color: actionColors[vote.action] || '#374151', fontWeight: 700 }}>
        {vote.action}
      </span>
      <span style={{ color: '#9ca3af', fontSize: 10 }}>
        {Math.round(vote.confidence * 100)}%
      </span>
    </span>
  )
}

/** 종목 합의 카드 */
function StockConsensusCard({ stock, agentCount }: { stock: ConsensusStock; agentCount: number }) {
  const scoreColor = stock.consensusScore >= 70 ? '#22c55e' : stock.consensusScore >= 40 ? '#eab308' : '#ef4444'

  return (
    <div style={{
      padding: '12px 14px', borderRadius: 10,
      border: '1px solid #e5e7eb', backgroundColor: '#fff',
      marginBottom: 8,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
        <div>
          <span style={{ fontWeight: 700, fontSize: 14 }}>{stock.name}</span>
          <span style={{ color: '#9ca3af', fontSize: 12, marginLeft: 4 }}>({stock.code})</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{
            fontWeight: 700, fontSize: 13, color: scoreColor,
            border: `1.5px solid ${scoreColor}`, borderRadius: 8, padding: '1px 8px',
          }}>
            {stock.consensusScore}점
          </span>
          <span style={{ fontSize: 11, color: '#6b7280' }}>
            {stock.mentionCount}/{agentCount}
          </span>
        </div>
      </div>

      {/* 에이전트 투표 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 6 }}>
        {Object.entries(stock.agentVotes).map(([agent, vote]) => (
          <AgentVoteChip key={agent} agent={agent} vote={vote} />
        ))}
      </div>

      {/* 시나리오 바 */}
      {stock.scenarioConsensus && (
        <div style={{ marginTop: 6 }}>
          <div style={{ display: 'flex', height: 6, borderRadius: 3, overflow: 'hidden' }}>
            <div style={{
              width: `${Math.round(stock.scenarioConsensus.bull.avgProbability * 100)}%`,
              backgroundColor: '#22c55e',
            }} />
            <div style={{
              width: `${Math.round(stock.scenarioConsensus.base.avgProbability * 100)}%`,
              backgroundColor: '#eab308',
            }} />
            <div style={{
              width: `${Math.round(stock.scenarioConsensus.bear.avgProbability * 100)}%`,
              backgroundColor: '#ef4444',
            }} />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10, color: '#9ca3af', marginTop: 2 }}>
            <span>강세 {Math.round(stock.scenarioConsensus.bull.avgProbability * 100)}%</span>
            <span>기준 {Math.round(stock.scenarioConsensus.base.avgProbability * 100)}%</span>
            <span>약세 {Math.round(stock.scenarioConsensus.bear.avgProbability * 100)}%</span>
          </div>
        </div>
      )}

      {/* 목표가/손절가 */}
      {(stock.avgTargetPrice || stock.avgStopLoss) && (
        <div style={{ display: 'flex', gap: 12, marginTop: 6, fontSize: 11, color: '#6b7280' }}>
          {stock.avgTargetPrice && <span>목표 <b style={{ color: '#dc2626' }}>{stock.avgTargetPrice.toLocaleString()}원</b></span>}
          {stock.avgStopLoss && <span>손절 <b style={{ color: '#2563eb' }}>{stock.avgStopLoss.toLocaleString()}원</b></span>}
        </div>
      )}
    </div>
  )
}

/** 메인 합의 표시 컴포넌트 */
export default function ConsensusDisplay({ consensus }: ConsensusDisplayProps) {
  if (!consensus || consensus.stocks.length === 0) return null

  return (
    <div style={{
      marginTop: 16, marginBottom: 16, padding: 16,
      borderRadius: 12, border: '1px solid #e0e7ff',
      backgroundColor: '#f8faff',
    }}>
      {/* 헤더 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
        <AgreementGauge score={consensus.agreementScore} />
        <div>
          <div style={{ fontWeight: 700, fontSize: 15, marginBottom: 4 }}>
            AI 합의 분석
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <SentimentBadge sentiment={consensus.overallSentiment} />
            <span style={{ fontSize: 12, color: '#6b7280' }}>
              {consensus.agentCount}개 AI 엔진 참여
            </span>
          </div>
        </div>
      </div>

      {/* 종목 카드 */}
      {consensus.stocks.slice(0, 5).map((stock) => (
        <StockConsensusCard
          key={stock.code}
          stock={stock}
          agentCount={consensus.agentCount}
        />
      ))}

      {/* 이견 표시 */}
      {consensus.divergences.length > 0 && (
        <div style={{ marginTop: 10, padding: '8px 12px', borderRadius: 8, backgroundColor: '#fef3c7' }}>
          <div style={{ fontWeight: 600, fontSize: 12, color: '#92400e', marginBottom: 4 }}>
            에이전트 간 이견
          </div>
          {consensus.divergences.map((div, i) => (
            <div key={i} style={{ fontSize: 11, color: '#78350f', marginBottom: 2 }}>
              {div.details}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
