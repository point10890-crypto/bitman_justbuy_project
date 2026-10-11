/** 분석 결과 로컬 캐시 — localStorage 기반, TTL 지원 */

import type { AnalysisResponse } from '../api/analysisApi'

interface CacheEntry {
  data: AnalysisResponse
  timestamp: number
  query: string
  mode?: string
  source?: 'live' | 'precomputed'
}

const CACHE_KEY = 'bitman_analysis_cache_v2'
const DEFAULT_TTL = 24 * 60 * 60 * 1000 // 24시간
const MAX_ENTRIES = 50
const PRECOMPUTED_MODES = new Set(['BREAKOUT', 'FLOW_LEADER', 'CATALYST_BURST', 'REVERSAL_EDGE'])

/** Mirror the server's observation freshness policy; storage TTL is only retention. */
function isFresh(data: AnalysisResponse, mode?: string, source?: CacheEntry['source']): boolean {
  const observedAt = Date.parse(data.updatedAt)
  const now = Date.now()
  if (!data.isFresh || !Number.isFinite(observedAt) || observedAt > now) return false
  // Entries from the older cache format have no source; conservatively use live TTL.
  const precomputed = source === 'precomputed' && PRECOMPUTED_MODES.has(mode || data.mode)
  const ttl = (precomputed ? 175 : 30) * 60 * 1000
  if (now - observedAt > ttl) return false
  if (precomputed) {
    const kstNow = new Date(now + 9 * 60 * 60 * 1000)
    const kstObserved = new Date(observedAt + 9 * 60 * 60 * 1000)
    const sameDay = kstNow.toISOString().slice(0, 10) === kstObserved.toISOString().slice(0, 10)
    if (sameDay && (kstNow.getUTCHours() * 60 + kstNow.getUTCMinutes()) >= 15 * 60 + 30) return false
  }
  return true
}

/** 쿼리 + 모드 → 캐시 키 생성 (정규화) */
function generateKey(query: string, mode?: string): string {
  const normalized = query
    .trim()
    .toLowerCase()
    .replace(/\s+/g, ' ')
  return mode ? `${mode}::${normalized}` : `default::${normalized}`
}

/** 전체 캐시 로드 */
function loadCache(): Record<string, CacheEntry> {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

/** 전체 캐시 저장 */
function saveCache(cache: Record<string, CacheEntry>) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(cache))
  } catch {
    // 용량 초과 시 가장 오래된 절반 삭제
    const entries = Object.entries(cache)
    entries.sort((a, b) => a[1].timestamp - b[1].timestamp)
    const trimmed = Object.fromEntries(entries.slice(Math.floor(entries.length / 2)))
    localStorage.setItem(CACHE_KEY, JSON.stringify(trimmed))
  }
}

/** 캐시에서 조회 (TTL 만료 시 null 반환) */
export function getCached(query: string, mode?: string, ttl = DEFAULT_TTL): AnalysisResponse | null {
  const cache = loadCache()
  const key = generateKey(query, mode)
  const entry = cache[key]

  if (!entry) return null
  if (Date.now() - entry.timestamp > ttl) {
    // TTL 만료 → 삭제
    delete cache[key]
    saveCache(cache)
    return null
  }

  return { ...entry.data, isFresh: isFresh(entry.data, mode, entry.source) }
}

/** 캐시에 저장 */
export function setCache(query: string, mode: string | undefined, data: AnalysisResponse, source: CacheEntry['source'] = 'live') {
  const cache = loadCache()
  const key = generateKey(query, mode)

  cache[key] = { data, timestamp: Date.now(), query, mode, source }

  // MAX_ENTRIES 초과 시 가장 오래된 항목 제거
  const keys = Object.keys(cache)
  if (keys.length > MAX_ENTRIES) {
    const entries = Object.entries(cache)
    entries.sort((a, b) => a[1].timestamp - b[1].timestamp)
    const removeCount = keys.length - MAX_ENTRIES
    for (let i = 0; i < removeCount; i++) {
      delete cache[entries[i][0]]
    }
  }

  saveCache(cache)
}

/** 캐시 전체 삭제 */
export function clearAllCache() {
  localStorage.removeItem(CACHE_KEY)
}
