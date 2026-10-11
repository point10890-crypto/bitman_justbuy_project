import { API_BASE } from './config'
import { failWithApiError } from './subscriptionGate'

export interface KeywordSearchEvidence {
  condition: string
  observedValue: string
  unit: string
  source: string
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
