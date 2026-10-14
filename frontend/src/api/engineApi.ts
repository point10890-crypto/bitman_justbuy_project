// 조건검색 엔진(condition_engine) 클라이언트.
// 운영: Spring Boot `/api/engine/*` 가 로그인·PRO 구독을 확인한 뒤 같은 호스트의 엔진(127.0.0.1:8090)으로 전달한다.
// 개발: Vite 가 `/api/engine/*` 를 로컬 엔진 `/engine/*` 로 바로 프록시한다.
import { API_BASE } from './config'
import { errorMessageOf, notifySubscriptionGate } from './subscriptionGate'
import { getStoredToken } from '../contexts/AuthContext'

export type Leaf =
  | { f: string; op: string; v: number }
  | { between: [string, number, number] }
  | { sector_any: string[] }
  | { market_in: string[] }
export type Condition = Leaf | { all: Condition[] } | { any: Condition[] } | { not: Condition }

export interface Strategy {
  id?: string
  title: string
  when: Condition
  rank_by: { f: string; desc?: boolean }
  top_n: number
  guard?: { exclude_risk?: string[]; regime_in?: string[] }
}

export interface LibraryStrategy extends Strategy {
  id: string
  status: 'candidate' | 'regime_gated' | 'monitoring' | 'retired'
  status_label: string
  summary: string
  evidence: string
}

export interface Translation {
  strategy: Strategy | null
  source: string
  conditions: string[]
  assumptions: string[]
  warnings: string[]
  unsupported: string[]
  unparsed: string[]
  error: string | null
  fallback_reason: string | null
}

export interface RiskBand {
  atr_pct: number | null
  typical_pct: number | null
  wide_low_pct: number | null
  wide_high_pct: number | null
  note: string
}

export interface RiskView {
  flags: Array<{ id: string; label: string; rule: string; evidence: string }>
  score: number
  grade: string
  band?: RiskBand
}

export interface ScreenRow {
  code: string
  name: string | null
  market: string | null
  sector: string | null
  stage: string
  excluded_by: string[]
  rank: number | null
  snapshot: Record<string, number | null>
  explanation: string[]
  risk: RiskView
}

export interface ScreenResult {
  strategy: Strategy
  conditions: string[]
  as_of: string
  regime: { code: string; label: string }
  regime_blocked: boolean
  counts: { universe: number; passing: number; guard_excluded: number; eligible: number; published: number }
  results: ScreenRow[]
  excluded: ScreenRow[]
  below_top_n: string[]
  data_version: string
  run_id?: string
}

export interface WindowStats {
  signal_days: number
  picks?: number
  avg_picks_per_day?: number | null
  mean_return_pct?: number | null
  net_return_pct?: number | null
  excess_vs_universe_pct?: number | null
  excess_vs_index_pct?: number | null
  hit_rate_pct?: number | null
  t_stat?: number | null
}

export interface HorizonStats {
  all: WindowStats
  discovery: WindowStats
  confirm: WindowStats
  verdict: { code: string; label: string; detail: string }
}

export interface BacktestResult {
  conditions: string[]
  entry: string
  period: Record<string, [string, string, number]>
  horizons: Record<string, HorizonStats>
  by_regime: Record<string, { signal_days: number; excess_vs_universe_pct?: number | null }>
  by_year: Record<string, { signal_days: number; excess_vs_universe_pct?: number | null }>
  curve: Array<{ date: string; excess_pct: number; strategy_pct: number; index_pct: number }>
  guard_effect?: { excluded_flags: string[]; with_guard_excess_pct: number | null; without_guard_excess_pct: number | null }
  method: string
  data_version: string
}

export interface RiskCheckItem {
  code: string
  found: boolean
  name?: string | null
  in_universe?: boolean
  explanation?: string[]
  risk?: RiskView
}

export interface EngineHealth {
  state: 'idle' | 'loading' | 'ready' | 'error'
  error: string | null
  data_version: string | null
  last_session: string | null
  codes: number
  provisional_dates: string[]
  unrepairable_provisional?: { rows: number; codes: number } | null
  last_final_date: string | null
  translator: string
}

export interface RegimeNow {
  as_of: string
  code: string
  label: string
  history: Array<{ date: string; code: string }>
}

export class EngineError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
  }
}

function detailText(body: unknown, status: number): string {
  const detail = body && typeof body === 'object' ? (body as { detail?: unknown }).detail : undefined
  if (typeof detail === 'string') return detail
  if (detail && typeof detail === 'object' && typeof (detail as { message?: unknown }).message === 'string') {
    return (detail as { message: string }).message
  }
  if (status === 502 || status === 504) return UNREACHABLE
  return errorMessageOf(JSON.stringify(body ?? {}), `조건검색 요청을 처리하지 못했습니다. (HTTP ${status})`)
}

const UNREACHABLE = '조건검색 엔진에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    const token = getStoredToken()
    response = await fetch(`${API_BASE}/api/engine${path}`, {
      cache: 'no-store',
      ...init,
      headers: {
        Accept: 'application/json',
        ...(init?.body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new EngineError(UNREACHABLE, 0)
  }
  const text = await response.text()
  let body: unknown = null
  try { body = text ? JSON.parse(text) : null } catch { body = null }
  if (!response.ok) {
    notifySubscriptionGate(response.status, text)       // 만료·미구독이면 재구독 화면으로 보낸다
    throw new EngineError(detailText(body, response.status), response.status)
  }
  return body as T
}

const post = <T,>(path: string, payload: unknown, signal?: AbortSignal) =>
  call<T>(path, { method: 'POST', body: JSON.stringify(payload), signal })

export const engineApi = {
  health: (signal?: AbortSignal) => call<EngineHealth>('/health', { signal }),
  regime: (signal?: AbortSignal) => call<RegimeNow>('/regime', { signal }),
  strategies: (signal?: AbortSignal) =>
    call<{ strategies: LibraryStrategy[]; evidence_basis: string }>('/strategies', { signal }),
  translate: (text: string, signal?: AbortSignal) => post<Translation>('/translate', { text }, signal),
  screen: (req: { strategy?: Strategy; strategy_id?: string }, signal?: AbortSignal) => post<ScreenResult>('/screen', req, signal),
  backtest: (req: { strategy?: Strategy; strategy_id?: string }) => post<BacktestResult>('/backtest', req),
  risk: (codes: string[]) => post<{ as_of: string; items: RiskCheckItem[] }>('/risk', { codes }),
}
