import { API_BASE } from './config'
import { failWithApiError } from './subscriptionGate'

export interface EventResearchOutcome {
  horizonSessions: number
  state: string
  grossReturnPct: string | null
  netReturnPct: string | null
  excessReturnPct: string | null
  reasons: string[]
}

export interface EventResearchCandidate {
  caseId: string
  origin?: 'SUPPLIER_CONTRACT' | 'CUSTOMER_EVENT_PROPAGATION'
  relationshipKnownAt?: string | null
  relatedCustomerEvent?: {
    receiptNo: string
    title: string
    sourceUrl: string | null
    firstSeenAt: string | null
    filingDate: string | null
    status: string
    issues: string[]
  } | null
  stockCode: string
  stockName: string
  customerName: string | null
  product: string | null
  contractAmountWon: string | null
  contractDeltaWon: string | null
  sourceUrl: string | null
  receiptNo: string
  firstSeenAt: string | null
  publishedAt?: string | null
  evaluationPublishedAt?: string | null
  evaluationPolicy?: string | null
  filingDate: string | null
  amendmentStatus: string
  evidenceText: string | null
  financial: Record<string, unknown> | null
  scenario: Record<string, unknown> | null
  verification: {
    status: string
    risks: string[]
    counterArguments: string[]
    invalidationConditions: string[]
  }
  market: { kis: Record<string, unknown> | null; kiwoom: Record<string, unknown> | null }
  currentMarket?: { kis: Record<string, unknown> | null; kiwoom: Record<string, unknown> | null } | null
  outcomes: EventResearchOutcome[]
}

export interface EventResearchSnapshot {
  status: string
  asOf: string | null
  runId: string | null
  stale: boolean
  issues: string[]
  coverage: Record<string, unknown>
  candidates: EventResearchCandidate[]
  buybacks: { status: string; asOf: string | null; rows: Record<string, unknown>[]; issues: string[] }
  comparison?: {
    status: string
    captureStatus: string
    capturedDates: number
    measuredDates: number
    independentDates: number
    productionActivationAllowed: boolean
    baselinePolicyId: string
    challengerPolicyId: string
    costsVerified: boolean
    meanPairedDeltaPct: string | null
    latestOutcomes: Array<{
      state: string
      horizonSessions: number
      baselineGross: string | null
      challengerGross: string | null
      baselineNet: string | null
      challengerNet: string | null
      netDelta: string | null
      reasons: string[]
    }>
  } | null
}

export interface EventResearchRefresh {
  accepted: boolean
  runId: string | null
  status: string
}

export interface EventResearchStatus {
  status: string
  runId: string | null
  running: boolean
  lastCompletedAt: string | null
  lastError: string | null
  asOf: string | null
  candidateCount: number
  stale: boolean
  issues: string[]
  coverage: Record<string, unknown>
}

async function request<T>(path: string, method: 'GET' | 'POST', token?: string, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json; charset=utf-8' }
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`${API_BASE}${path}`, { method, headers, signal, cache: 'no-store' })
  if (!response.ok) {
    if (response.status === 401 && token && typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('auth:unauthorized', { detail: { token } }))
    }
    await failWithApiError(response)
  }
  return response.json()
}

/** Reads persisted evidence only. Source collection requires a separate, explicit admin action. */
export async function fetchEventResearch(token?: string, signal?: AbortSignal): Promise<EventResearchSnapshot> {
  const result = await request<EventResearchSnapshot>('/api/research/events', 'GET', token, signal)
  if (!result || !Array.isArray(result.candidates) || !result.buybacks || !Array.isArray(result.buybacks.rows)) {
    throw new Error('저장된 연구의 응답 형식을 확인하지 못했습니다.')
  }
  return result
}

export function refreshEventResearch(token?: string, signal?: AbortSignal): Promise<EventResearchRefresh> {
  return request('/api/admin/research/events/refresh', 'POST', token, signal)
}

export function fetchEventResearchStatus(token?: string, signal?: AbortSignal): Promise<EventResearchStatus> {
  return request('/api/admin/research/events/status', 'GET', token, signal)
}
