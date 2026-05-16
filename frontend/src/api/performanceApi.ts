import { API_BASE } from './config'

export interface DailyCloseRow {
  rank: number
  stockName: string
  stockCode: string
  action: string
  entryPrice: string
  closePrice: string
  returnPct: string
  result: string
  targetPrice: string
  stopLoss: string
  hitTarget: boolean
  hitStop: boolean
  capturedAt: string
  verifiedAt: string
}

export interface DailyClosePerformanceResponse {
  date: string
  mode: string
  title: string
  marketClosed: boolean
  verified: boolean
  asOf: string
  totalSignals: number
  winCount: number
  lossCount: number
  flatCount: number
  avgReturnPct: string
  bestReturnPct: string
  worstReturnPct: string
  rows: DailyCloseRow[]
  note: string
}

export interface SwingPerformanceRow {
  rank: number
  stockName: string
  stockCode: string
  action: string
  capturedDate: string
  entryPrice: string
  currentStatus: string
  return1d: string
  return3d: string
  return5d: string
  targetPrice: string
  stopLoss: string
  hitTarget: boolean
  hitStop: boolean
  capturedAt: string
}

export interface SwingCumulativePerformanceResponse {
  from: string
  to: string
  mode: string
  title: string
  totalSignals: number
  trackingCount: number
  completedCount: number
  avgReturn1d: string
  avgReturn3d: string
  avgReturn5d: string
  winRate1d: string
  winRate3d: string
  winRate5d: string
  targetHitRate: string
  stopHitRate: string
  rows: SwingPerformanceRow[]
  note: string
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

function withParams(path: string, params: Record<string, string | undefined>) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value) query.set(key, value)
  })
  const suffix = query.toString()
  return suffix ? `${path}?${suffix}` : path
}

export async function fetchShortTermDailyClose(date?: string, token?: string) {
  const path = withParams('/api/performance/short-term/daily-close', { date })
  const res = await fetch(`${API_BASE}${path}`, { headers: authHeaders(token) })
  return readJson<DailyClosePerformanceResponse>(res)
}

export async function refreshShortTermDailyClose(date?: string, token?: string) {
  const path = withParams('/api/performance/short-term/daily-close/refresh', { date })
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  return readJson<DailyClosePerformanceResponse>(res)
}

export async function fetchSwingCumulativePerformance(from?: string, to?: string, token?: string) {
  const path = withParams('/api/performance/swing/cumulative', { from, to })
  const res = await fetch(`${API_BASE}${path}`, { headers: authHeaders(token) })
  return readJson<SwingCumulativePerformanceResponse>(res)
}
