/** 분석 API 클라이언트 — Express 백엔드 경유 Multi-Agent */
import { API_BASE } from './config'

export interface AgentInfo {
  agent: 'claude' | 'gemini' | 'chatgpt' | 'perplexity' | 'grok'
  status: 'success' | 'error' | 'skipped'
  model: string
  durationMs: number
  error?: string
}

export interface StockPick {
  name: string
  code: string
  currentPrice?: string
  targetPrice?: string
  stopLoss?: string
  action: '매수' | '매도' | '관망' | '주목'
  reason?: string
}

/** 에이전트 간 합의 결과 */
export interface ConsensusStock {
  name: string
  code: string
  consensusAction: string
  consensusScore: number
  mentionCount: number
  agentVotes: Record<string, { action: string; confidence: number; targetPrice?: number; stopLoss?: number }>
  averageConfidence: number
  scenarioConsensus?: {
    bull: { avgProbability: number; avgTarget?: number }
    base: { avgProbability: number; avgTarget?: number }
    bear: { avgProbability: number; avgTarget?: number }
  }
  avgTargetPrice?: number
  avgStopLoss?: number
}

export interface ConsensusResult {
  stocks: ConsensusStock[]
  overallSentiment: 'bullish' | 'neutral' | 'bearish'
  agreementScore: number
  divergences: Array<{
    stockCode: string
    stockName: string
    type: string
    agents: string[]
    details: string
  }>
  agentCount: number
}

export interface AnalysisResponse {
  content: string
  stockPicks: StockPick[]
  agents: AgentInfo[]
  hasSynthesis: boolean
  consensus?: ConsensusResult
  updatedAt: string
  isFresh: boolean
  mode: string
  metadata: {
    totalDurationMs: number
    agentsUsed: number
    agentsSucceeded: number
  }
}

/** 서버 응답을 프론트엔드 형식으로 변환 */
function transformResponse(data: any): AnalysisResponse {
  return {
    content: data.finalContent || '',
    stockPicks: data.stockPicks || [],
    agents: (data.round1 || []).map((r: any) => ({
      agent: r.agent,
      status: r.status,
      model: r.model,
      durationMs: r.durationMs,
      error: r.error,
    })),
    hasSynthesis: !!data.synthesis && data.synthesis.status === 'success',
    consensus: data.consensus || undefined,
    updatedAt: data.updatedAt || new Date().toISOString(),
    isFresh: data.isFresh ?? true,
    mode: data.mode || '',
    metadata: data.metadata || { totalDurationMs: 0, agentsUsed: 0, agentsSucceeded: 0 },
  }
}

/** 프리컴퓨트 결과 조회 (스케줄에 의해 미리 분석된 결과) */
export async function fetchPrecomputed(mode: string, token?: string): Promise<AnalysisResponse | null> {
  try {
    const headers: HeadersInit = {}
    if (token) headers['Authorization'] = `Bearer ${token}`
    const res = await fetch(`${API_BASE}/api/analysis/${encodeURIComponent(mode)}`, { headers })
    if (res.status === 404) return null
    if (!res.ok) throw new Error(`API error ${res.status}`)
    const data = await res.json()
    return transformResponse(data)
  } catch {
    return null
  }
}

/** 종목코드 리스트로 실시간 현재가 조회 (인증 불필요) */
export async function fetchStockPrices(codes: string[]): Promise<Record<string, string>> {
  if (codes.length === 0) return {}
  try {
    const res = await fetch(`${API_BASE}/api/market/prices?codes=${codes.join(',')}`)
    if (!res.ok) return {}
    return await res.json()
  } catch {
    return {}
  }
}

/** 실시간 멀티에이전트 분석 트리거 */
export async function fetchLiveAnalysis(query: string, mode: string, token?: string): Promise<AnalysisResponse> {
  const headers: HeadersInit = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${API_BASE}/api/analysis/live`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ query, mode }),
  })

  if (!res.ok) {
    const errText = await res.text()
    let parsed: string
    try {
      const errJson = JSON.parse(errText)
      parsed = errJson.error || errText
    } catch {
      parsed = errText
    }
    throw new Error(parsed)
  }

  const data = await res.json()
  return transformResponse(data)
}
