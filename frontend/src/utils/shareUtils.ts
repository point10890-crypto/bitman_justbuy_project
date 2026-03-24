import type { AnalysisResult } from '../hooks/useAnalysis'

const ACTION_ICONS: Record<string, string> = {
  '매수': '▲',
  '매도': '▼',
  '관망': '■',
  '주목': '★',
}

export function buildShareText(result: AnalysisResult, query: string): string {
  const mode = result.mode || query || '분석'
  const lines: string[] = []

  lines.push(`🤖 BitMan AI 분석 — ${mode}`)
  lines.push('')

  // Stock picks
  if (result.stockPicks && result.stockPicks.length > 0) {
    lines.push(`🎯 추천 종목 TOP ${result.stockPicks.length}`)
    for (let i = 0; i < result.stockPicks.length; i++) {
      const p = result.stockPicks[i]
      const icon = ACTION_ICONS[p.action] || '★'
      const price = p.currentPrice ? ` — ${p.currentPrice}원` : ''
      lines.push(`${i + 1}. ${icon} ${p.name} (${p.code}) ${p.action}${price}`)
    }
    lines.push('')
  }

  // Consensus
  if (result.consensus) {
    const total = result.metadata.agentsUsed || 5
    lines.push(`🤝 AI 합의: ${result.metadata.agentsSucceeded}/${total} 에이전트 분석 완료`)
    if (result.consensus.stocks?.length > 0) {
      lines.push(`   합의 종목 ${result.consensus.stocks.length}개`)
    }
  }

  // Timestamp
  if (result.updatedAt) {
    const dt = new Date(result.updatedAt)
    const timeStr = dt.toLocaleString('ko-KR', {
      month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
      hour12: false,
    })
    lines.push(`⏰ ${timeStr} 분석`)
  }

  lines.push('')
  lines.push('👉 bitman-justbuy.pages.dev')

  return lines.join('\n')
}

export async function shareAnalysis(text: string): Promise<'shared' | 'copied' | 'failed'> {
  // Web Share API (mobile: shows KakaoTalk, iMessage, etc.)
  if (navigator.share) {
    try {
      await navigator.share({
        title: 'BitMan AI 분석 결과',
        text,
      })
      return 'shared'
    } catch (e) {
      // User cancelled or share failed — fall through to clipboard
      if ((e as DOMException)?.name === 'AbortError') return 'failed'
    }
  }

  // Clipboard fallback
  try {
    await navigator.clipboard.writeText(text)
    return 'copied'
  } catch {
    // Last resort: textarea copy
    try {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
      return 'copied'
    } catch {
      return 'failed'
    }
  }
}
