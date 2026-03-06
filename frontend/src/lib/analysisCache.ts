/** 분석 결과 로컬 캐시 — localStorage 기반, TTL 지원 */

import type { AnalysisResponse } from '../api/analysisApi'

interface CacheEntry {
  data: AnalysisResponse
  timestamp: number
  query: string
  mode?: string
}

const CACHE_KEY = 'bitman_analysis_cache'
const DEFAULT_TTL = 24 * 60 * 60 * 1000 // 24시간
const MAX_ENTRIES = 50

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

  return entry.data
}

/** 캐시에 저장 */
export function setCache(query: string, mode: string | undefined, data: AnalysisResponse) {
  const cache = loadCache()
  const key = generateKey(query, mode)

  cache[key] = { data, timestamp: Date.now(), query, mode }

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

/** 캐시 통계 */
export function getCacheStats(): { count: number; oldestAge: string } {
  const cache = loadCache()
  const entries = Object.values(cache)
  if (entries.length === 0) return { count: 0, oldestAge: '-' }

  const oldest = Math.min(...entries.map(e => e.timestamp))
  const ageMs = Date.now() - oldest
  const hours = Math.floor(ageMs / (60 * 60 * 1000))
  const mins = Math.floor((ageMs % (60 * 60 * 1000)) / (60 * 1000))

  return {
    count: entries.length,
    oldestAge: hours > 0 ? `${hours}시간 ${mins}분` : `${mins}분`,
  }
}
