import { API_BASE } from './config'
import { failWithApiError } from './subscriptionGate'

export interface KeywordSearchEvidence {
  condition: string
  observedValue: string
  unit: string
  source: string
  observedAt: string | null
  environment?: string | null
  fetchedAt?: string | null
}

export interface KeywordSearchProvider {
  source: string
  environment: string
  status: string
  checkedAt: string | null
  scope: string | null
  requestedCount?: number | null
  availableCount?: number | null
  pendingCount?: number | null
}

export interface KeywordSearchSourceObservation {
  source: string
  environment: string
  currentPrice: number | null
  changePct: number | null
  volume: number | null
  tradingValueKrw: number | null
  fetchedAt: string | null
  observedAt: string | null
}

export interface KeywordSearchMatch {
  code: string
  name: string
  market: string
  currentPrice: number
  changePct: number | null
  volume: number | null
  tradingValueKrw: number | null
  quoteSource: string
  quoteCheckedAt: string | null
  observedAt: string | null
  evidence: KeywordSearchEvidence[]
  quoteEnvironment?: string | null
  sourceObservations?: KeywordSearchSourceObservation[]
}

export interface FlowCoverage {
  requestedCount: number
  availableCount: number
  pendingCount: number
  unavailableCount: number
}

export interface KeywordSearchProgress {
  id: string
  expiresAt: string
  retryAfterMs: number
}

export interface KeywordSearchResponse {
  query: string
  outcome: 'COMPLETE' | 'PARTIAL' | 'EMPTY' | 'UNAVAILABLE' | 'UNSUPPORTED'
  appliedConditions: string[]
  unsupportedTerms: string[]
  scope: {
    label: string
    examinedCount: number
    eligibleCount: number
    matchedCount: number
    truncated: boolean
  }
  fetchedAt: string | null
  sourceStatus: string
  warnings: string[]
  matches: KeywordSearchMatch[]
  providers?: KeywordSearchProvider[]
  flowCoverage?: FlowCoverage | null
  progress?: KeywordSearchProgress | null
}

/** Only explicit, internally consistent flow counts may start automatic tracking. */
export function getFlowCoverage(result: KeywordSearchResponse | null | undefined): FlowCoverage | null {
  const value = result?.flowCoverage
  if (!value || typeof value !== 'object') return null
  const { requestedCount, availableCount, pendingCount, unavailableCount } = value
  if (![requestedCount, availableCount, pendingCount, unavailableCount].every(count =>
    typeof count === 'number' && Number.isSafeInteger(count) && count >= 0 && count <= 600)) return null
  if (availableCount + pendingCount + unavailableCount !== requestedCount) return null
  return { requestedCount, availableCount, pendingCount, unavailableCount }
}

export function getSearchProgress(result: KeywordSearchResponse | null | undefined): KeywordSearchProgress | null {
  const value = result?.progress
  if (!value || typeof value !== 'object') return null
  const { id, expiresAt, retryAfterMs } = value
  if (typeof id !== 'string' || !id.trim() || id !== id.trim() || id.length > 200
    || typeof expiresAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(expiresAt)
    || !Number.isFinite(Date.parse(expiresAt))
    || typeof retryAfterMs !== 'number' || !Number.isSafeInteger(retryAfterMs) || retryAfterMs < 0) return null
  return { id, expiresAt, retryAfterMs }
}

/** Always query the data endpoint; neither AI output nor local analysis cache is a search source. */
export async function searchKeywordStocks(query: string, token?: string, signal?: AbortSignal): Promise<KeywordSearchResponse> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json; charset=utf-8' }
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(`${API_BASE}/api/analysis/keyword-search`, {
    method: 'POST', headers, body: JSON.stringify({ query }), signal, cache: 'no-store',
  })
  if (!res.ok) await failWithApiError(res)
  return res.json()
}

/** Read progress for the same frozen candidate snapshot without submitting new source demand. */
export async function searchKeywordProgress(progressId: string, token?: string, signal?: AbortSignal): Promise<KeywordSearchResponse> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json; charset=utf-8' }
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(`${API_BASE}/api/analysis/keyword-search/progress`, {
    method: 'POST', headers, body: JSON.stringify({ progressId }), signal, cache: 'no-store',
  })
  if (!res.ok) await failWithApiError(res)
  return res.json()
}
