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
  stopLoss: string
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
    closingBet: ConditionSectionResponse
    alerts: ConditionSectionResponse
  }
  trackRecord: TrackRecordSummary
  notice: string
  /** 미구독자에게 내려간 마스킹 응답인지. 종목명·가격은 서버에서 이미 가려져 있다. */
  preview?: boolean
  /** ACTIVE | PENDING | EXPIRED | NONE — 구독 유도 문구 분기용 */
  tier?: 'ACTIVE' | 'PENDING' | 'EXPIRED' | 'NONE' | string
}

export interface JonggaPerformanceRow {
  rank: number
  stockName: string
  stockCode: string
  grade: string
  score: number
  entryPrice: string
  targetPrice: string
  stopLoss: string
  closePrice: string
  closeReturnPct: string
  maxReturnPct: string
  /** 같은 구간 시장(지수 추종 ETF) 수익률. 조회 불가 시 '-' */
  benchmarkReturnPct: string
  /** 종목 수익률 − 시장 수익률 */
  excessReturnPct: string
  hitTarget: boolean
  hitStop: boolean
  result: '승' | '패' | '보합' | '미검증' | '검증불가' | string
}

export interface JonggaPerformanceDay {
  date: string
  verified: boolean
  avgCloseReturnPct: string
  rows: JonggaPerformanceRow[]
}

export interface JonggaPerformanceResponse {
  from: string
  to: string
  mode: string
  title: string
  totalSignals: number
  verifiedCount: number
  wins: number
  losses: number
  flats: number
  avgCloseReturnPct: string
  avgMaxReturnPct: string
  winRate: string
  targetHitRate: string
  stopHitRate: string
  /** 같은 구간 시장 평균 수익률 */
  avgBenchmarkReturnPct: string
  /** 시장 대비 초과수익 평균 — 전략이 좋았는지 장이 좋았는지 구분 */
  avgExcessReturnPct: string
  /** 시장을 이긴 비율 */
  marketBeatRate: string
  days: JonggaPerformanceDay[]
  note: string
}

function authHeaders(token?: string): HeadersInit {
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** 구독 때문에 막혔음을 나타내는 서버 코드. SubscriptionAccessGuard 와 1:1. */
export const SUBSCRIPTION_CODES = ['SUBSCRIPTION_EXPIRED', 'SUBSCRIPTION_PENDING', 'SUBSCRIPTION_REQUIRED'] as const
export type SubscriptionCode = (typeof SUBSCRIPTION_CODES)[number]

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  constructor(status: number, message: string, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

/**
 * 구독 문제로 403 이 오면 전역 신호를 보낸다.
 * 라우터 밖(api 모듈)에서 직접 이동할 수 없으므로 이벤트로 넘기고,
 * SubscriptionGateWatcher 가 받아서 /subscribe 로 보낸다. (authApi 의 auth:unauthorized 와 같은 방식)
 */
function emitSubscriptionRequired(code: SubscriptionCode, message: string) {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent('subscription:required', { detail: { code, message } }))
}

async function readJson<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    let message = ''
    let code: string | undefined

    // 서버는 {"error": "...", "code": "..."} 형태로 내려준다.
    // 파싱에 실패하면(프록시 HTML 오류 등) 원문 대신 상태코드를 쓴다.
    try {
      const parsed = JSON.parse(text) as { error?: string; message?: string; code?: string }
      message = parsed.error || parsed.message || ''
      code = parsed.code
    } catch {
      message = /^\s*[<{]/.test(text) ? '' : text
    }
    if (!message) message = `요청을 처리하지 못했습니다. (${res.status})`

    if (res.status === 403 && code && (SUBSCRIPTION_CODES as readonly string[]).includes(code)) {
      emitSubscriptionRequired(code as SubscriptionCode, message)
    }
    throw new ApiError(res.status, message, code)
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

/** 종가매매 추천종목 히스토리 + 익일 성과. from/to 는 YYYY-MM-DD. */
export async function fetchClosingBetPerformance(from: string, to: string, token?: string): Promise<JonggaPerformanceResponse> {
  const params = new URLSearchParams({ from, to })
  const res = await fetch(`${API_BASE}/api/kr/jongga-v2/performance?${params}`, {
    headers: authHeaders(token),
  })
  return readJson<JonggaPerformanceResponse>(res)
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
