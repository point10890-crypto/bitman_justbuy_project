import { API_BASE } from './config'

export interface ConditionSignal {
  section: string
  mode: string
  rank: number
  stockName: string
  stockCode: string
  capturePrice: string
  currentPrice: string
  highPrice: string
  maxReturnPct: string
  status: string
  ruleScore: number
  aiScore: number
  finalScore: number
  summary: string
  evidence: string[]
  riskFlags: string[]
  invalidation: string
  capturedAt: string
}

export interface ConditionSectionResponse {
  key: string
  slug: string
  title: string
  mode: string
  endpoint: string
  asOf: string
  sourceStatus: 'PRECOMPUTED' | 'SERVICE_FALLBACK' | 'READY' | string
  signals: ConditionSignal[]
}

export interface TrackRecordSummary {
  totalSignals: number
  avgMaxReturnPct: string
  winRate5d: string
}

export interface MainConditionResponse {
  asOf: string
  sections: {
    shortTerm: ConditionSectionResponse
    swing: ConditionSectionResponse
    leaders: ConditionSectionResponse
    themes: ConditionSectionResponse
    alerts: ConditionSectionResponse
  }
  trackRecord: TrackRecordSummary
  notice: string
}

function authHeaders(token?: string): HeadersInit {
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function readJson<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `API error ${res.status}`)
  }
  return res.json()
}

export async function fetchMainConditions(token?: string): Promise<MainConditionResponse> {
  const res = await fetch(`${API_BASE}/api/main`, {
    headers: authHeaders(token),
  })
  return readJson<MainConditionResponse>(res)
}

export async function fetchConditionSection(sectionSlug: string, token?: string): Promise<ConditionSectionResponse> {
  const res = await fetch(`${API_BASE}/api/conditions/${encodeURIComponent(sectionSlug)}`, {
    headers: authHeaders(token),
  })
  return readJson<ConditionSectionResponse>(res)
}
