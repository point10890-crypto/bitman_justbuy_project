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
  sourceStatus: 'REALTIME_SCAN' | 'REALTIME_EMPTY' | 'REALTIME_WAITING' | 'PRECOMPUTED' | 'STALE_CACHE' | 'SERVICE_FALLBACK' | 'READY' | string
  signals: ConditionSignal[]
}

export interface ConditionCaptureTime {
  section: string
  slug: string
  title: string
  mode: string
  rank: number
  stockName: string
  stockCode: string
  capturedAt: string
  capturedTime: string
  sourceStatus: string
}

export interface ConditionCaptureTimesResponse {
  asOf: string
  endpoint: string
  sourceStatus: string
  totalCount: number
  captures: ConditionCaptureTime[]
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

export async function fetchConditionCaptureTimes(sectionSlug?: string, token?: string): Promise<ConditionCaptureTimesResponse> {
  const path = sectionSlug
    ? `/api/conditions/${encodeURIComponent(sectionSlug)}/capture-times`
    : '/api/conditions/capture-times'
  const res = await fetch(`${API_BASE}${path}`, {
    headers: authHeaders(token),
  })
  return readJson<ConditionCaptureTimesResponse>(res)
}
