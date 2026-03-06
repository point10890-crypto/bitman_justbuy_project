/** 분석 히스토리 — localStorage 기반 */

export interface HistoryEntry {
  id: string
  query: string
  mode?: string
  timestamp: number
  /** 시나리오 판정: 강세/기준/약세 */
  verdict: string
  /** 응답 첫 줄 요약 */
  summary: string
}

const HISTORY_KEY = 'bitman_analysis_history'
const MAX_HISTORY = 20

function load(): HistoryEntry[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

function save(entries: HistoryEntry[]) {
  localStorage.setItem(HISTORY_KEY, JSON.stringify(entries))
}

/** 응답 텍스트에서 시나리오 판정 추출 */
function extractVerdict(content: string): string {
  // 가장 높은 확률의 시나리오 찾기
  const patterns = [
    { label: '강세', regex: /강세[:\s]*(\d+)%/ },
    { label: '기준', regex: /기준[:\s]*(\d+)%/ },
    { label: '약세', regex: /약세[:\s]*(\d+)%/ },
  ]

  let maxLabel = '기준'
  let maxPct = 0

  for (const { label, regex } of patterns) {
    const match = content.match(regex)
    if (match) {
      const pct = parseInt(match[1], 10)
      if (pct > maxPct) {
        maxPct = pct
        maxLabel = label
      }
    }
  }

  return maxLabel
}

/** 히스토리에 추가 */
export function addHistory(query: string, mode: string | undefined, content: string) {
  const entries = load()

  const entry: HistoryEntry = {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    query,
    mode,
    timestamp: Date.now(),
    verdict: extractVerdict(content),
    summary: content.slice(0, 100),
  }

  entries.unshift(entry)

  // 최대 개수 제한
  if (entries.length > MAX_HISTORY) {
    entries.length = MAX_HISTORY
  }

  save(entries)
  return entry
}

/** 샘플 히스토리 (데이터 없을 때 표시) */
const SAMPLE_HISTORY: HistoryEntry[] = [
  {
    id: 'sample-1',
    query: '오늘 매수 추천 종목 분석',
    mode: '오늘뭐사',
    timestamp: Date.now() - 2 * 60 * 60 * 1000,
    verdict: '강세',
    summary: '📊 5대 AI 합의 종합 리포트 | 오늘의 매수 추천 종목을 분석합니다. 시장 전반적으로 상승 모멘텀이...',
  },
  {
    id: 'sample-2',
    query: '종가매매 후보 종목 분석',
    mode: '종가매매',
    timestamp: Date.now() - 5 * 60 * 60 * 1000,
    verdict: '기준',
    summary: '💎 종가·시초가 단타 매매 후보를 분석합니다. 금일 거래량 상위 종목 중 기술적 지표 기반 매매...',
  },
  {
    id: 'sample-3',
    query: '스윙매매 후보 종목 분석',
    mode: '스윙매매',
    timestamp: Date.now() - 8 * 60 * 60 * 1000,
    verdict: '강세',
    summary: '⚡ 3일~3주 스윙 트레이딩 후보 종목을 선별합니다. 최근 수급 동향과 기술적 패턴을 종합 분석...',
  },
]

/** 최근 N개 조회 */
export function getRecentHistory(count = 3): HistoryEntry[] {
  const entries = load()
  if (entries.length === 0) return SAMPLE_HISTORY.slice(0, count)
  return entries.slice(0, count)
}

/** 경과 시간 포맷 */
export function formatTimeAgo(timestamp: number): string {
  const diff = Date.now() - timestamp
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return '방금 전'
  if (mins < 60) return `${mins}분 전`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}시간 전`
  const days = Math.floor(hours / 24)
  return `${days}일 전`
}
