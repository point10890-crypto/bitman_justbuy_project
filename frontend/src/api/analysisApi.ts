/** 분석 API 클라이언트 — Express 백엔드 경유 Multi-Agent */
import { API_BASE } from './config'

export interface AgentInfo {
  agent: 'chatgpt' | 'grok'
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
  /** DART 기반 재무 점수 0-100 (없으면 0) */
  financialScore?: number
  /** 재무 요약 (60자 이내) */
  financialSummary?: string
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

/** 비동기 작업 상태 폴링 */
async function pollJob(jobId: string, token?: string, maxWaitMs = 180_000): Promise<AnalysisResponse> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`

  const start = Date.now()
  const interval = 3000 // 3초마다 폴링

  while (Date.now() - start < maxWaitMs) {
    await new Promise(r => setTimeout(r, interval))

    const res = await fetch(`${API_BASE}/api/analysis/job/${jobId}`, { headers })
    if (!res.ok) {
      if (res.status === 404) throw new Error('분석 작업을 찾을 수 없습니다.')
      const errData = await res.json().catch(() => ({ error: `Server error ${res.status}` }))
      if (errData.status === 'error') throw new Error(errData.error || '분석 중 오류 발생')
      throw new Error(errData.error || `API error ${res.status}`)
    }

    const data = await res.json()

    // status가 pending/running이면 계속 폴링
    if (data.status === 'pending' || data.status === 'running') continue

    // 완료된 경우: finalContent가 직접 있거나 result 안에 있는 경우 모두 처리
    if (data.finalContent !== undefined) {
      return transformResponse(data)
    }

    // 래핑된 응답 처리: { status: "complete", result: AnalysisResponse }
    if (data.status === 'complete' && data.result?.finalContent !== undefined) {
      return transformResponse(data.result)
    }

    // mode 필드가 있으면 AnalysisResponse 직접 반환된 것 (status 필드 없이)
    if (data.mode !== undefined && data.stockPicks !== undefined) {
      return transformResponse(data)
    }

    // status가 error면 에러 throw
    if (data.status === 'error') {
      throw new Error(data.error || '분석 중 오류 발생')
    }
  }

  throw new Error('분석 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.')
}

/** 실시간 멀티에이전트 분석 트리거 (비동기 폴링) */
export async function fetchLiveAnalysis(query: string, mode: string, token?: string): Promise<AnalysisResponse> {
  const headers: HeadersInit = { 'Content-Type': 'application/json; charset=utf-8' }
  if (token) headers['Authorization'] = `Bearer ${token}`

  // 1단계: 작업 시작 → jobId 받기
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

  // 비동기 패턴: jobId가 있으면 폴링
  if (data.jobId) {
    return pollJob(data.jobId, token)
  }

  // 동기 패턴 폴백: 서버가 직접 결과를 반환한 경우 (구 버전 호환)
  if (data.finalContent !== undefined || data.mode !== undefined) {
    return transformResponse(data)
  }

  // status가 있는 래핑 응답
  if (data.status === 'complete' && data.result) {
    return transformResponse(data.result)
  }

  throw new Error('서버에서 작업 ID를 받지 못했습니다. 서버 상태를 확인해 주세요.')
}
