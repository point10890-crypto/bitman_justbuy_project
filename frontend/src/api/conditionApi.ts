import { API_BASE } from './config'
import { failWithApiError } from './subscriptionGate'

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

// 구독 403 판정·신호·에러 타입은 subscriptionGate 한 곳에서만 정의한다.
// 이 파일에만 있던 탓에 analysis/performance/auth 경로는 만료 회원을 그냥 통과시켰다.
export { SUBSCRIPTION_CODES, ApiError } from './subscriptionGate'
export type { SubscriptionCode } from './subscriptionGate'

async function readJson<T>(res: Response): Promise<T> {
  if (!res.ok) await failWithApiError(res)
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

// ─── 회원 트랙레코드 (모드별 성적표) ───

export interface TrackRecordMode {
  mode: string
  title: string
  totalSignals: number
  verifiedCount: number
  wins: number
  losses: number
  winRate: string
  avgReturnPct: string
  avgMaxReturnPct: string
  targetHitRate: string
  stopHitRate: string
  avgBenchmarkReturnPct: string
  avgExcessReturnPct: string
  marketBeatRate: string
}

export interface MemberTrackRecordResponse {
  from: string
  to: string
  days: number
  modes: TrackRecordMode[]
  overall: TrackRecordMode
  benchmarkLabel: string | null
  note: string
}

/** 모드별 승률·평균수익·시장 대비 초과수익. days 기본 30. */
export async function fetchMemberTrackRecord(days: number, token?: string): Promise<MemberTrackRecordResponse> {
  const res = await fetch(`${API_BASE}/api/performance/track-record?days=${days}`, {
    headers: authHeaders(token),
  })
  return readJson<MemberTrackRecordResponse>(res)
}

/** 회원 텔레그램 연결. chatId 는 숫자 문자열. */
export async function linkTelegram(chatId: string, token?: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/subscription/telegram`, {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify({ chatId }),
  })
  await readJson<unknown>(res)
}

/** 회원 텔레그램 연결 해제. */
export async function unlinkTelegram(token?: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/subscription/telegram`, {
    method: 'DELETE',
    headers: authHeaders(token),
  })
  await readJson<unknown>(res)
}
