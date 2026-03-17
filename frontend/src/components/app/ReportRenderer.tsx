import type { ReactNode } from 'react'

/** 인라인 마크다운 렌더링 */
function renderInline(text: string): ReactNode {
  const parts: ReactNode[] = []
  let remaining = text
  let i = 0

  while (remaining.length > 0) {
    const stockMatch = remaining.match(/([가-힣A-Za-z][가-힣A-Za-z0-9·&]{1,15})\s*[\(（](\d{6})[\)）]/)
    const boldMatch = remaining.match(/\*\*(.+?)\*\*/)
    const stockIdx = stockMatch?.index ?? Infinity
    const boldIdx = boldMatch?.index ?? Infinity

    if (stockIdx === Infinity && boldIdx === Infinity) { parts.push(<span key={i++}>{remaining}</span>); break }

    if (stockIdx <= boldIdx && stockMatch) {
      if (stockMatch.index! > 0) parts.push(<span key={i++}>{remaining.slice(0, stockMatch.index!)}</span>)
      parts.push(<span key={i++} className="stock-inline">{stockMatch[1]}<span className="stock-code-inline">{stockMatch[2]}</span></span>)
      remaining = remaining.slice(stockMatch.index! + stockMatch[0].length)
    } else if (boldMatch) {
      if (boldMatch.index! > 0) parts.push(<span key={i++}>{remaining.slice(0, boldMatch.index!)}</span>)
      parts.push(<strong key={i++}>{boldMatch[1]}</strong>)
      remaining = remaining.slice(boldMatch.index! + boldMatch[0].length)
    }
  }
  return parts.length === 1 ? parts[0] : <>{parts}</>
}

/** 종합분석 본문에서 현재가/목표가/손절가 가격 정보를 제거 (stockPicks 카드에서 실시간 가격 표시) */
function stripPriceLines(text: string): string {
  return text
    .split('\n')
    .map(line => {
      const t = line.trim()
      // "현재가 약 XX,XXX원" 또는 "현재가 XX,XXX원" 패턴 제거
      if (/현재가\s*(?:약\s*)?[0-9,]+(?:~[0-9,]+)?\s*원/.test(t)) {
        // "📌 종목명 (코드) — 현재가 약 XX원" → "📌 종목명 (코드)"
        const cleaned = t.replace(/\s*[—\-–]\s*현재가\s*(?:약\s*)?[0-9,]+(?:~[0-9,]+)?\s*원/, '')
          .replace(/현재가\s*(?:약\s*)?[0-9,]+(?:~[0-9,]+)?\s*원/, '').trim()
        return cleaned || null
      }
      // "목표가: XX,XXX원" 또는 "손절가: XX,XXX원" 단독 라인 제거
      if (/^\s*[-*]?\s*(?:목표가|1차\s*목표|손절가|손절)[:\s]*(?:약\s*)?[0-9,]+/.test(t)) return null
      // "목표가: XX원 / 손절가: XX원" 복합 라인 제거
      if (/목표가.*손절/.test(t) && /[0-9,]+\s*원/.test(t)) return null
      return line
    })
    .filter(line => line !== null)
    .join('\n')
}

/** 마크다운 기반 리포트 렌더러 */
export function ReportRenderer({ content }: { content: string }) {
  if (!content) return null
  const lines = stripPriceLines(content).split('\n')
  const elements: ReactNode[] = []
  let listItems: string[] = []
  let key = 0

  const flushList = () => {
    if (listItems.length === 0) return
    elements.push(<ul key={key++}>{listItems.map((item, i) => <li key={i}>{renderInline(item)}</li>)}</ul>)
    listItems = []
  }

  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) { flushList(); continue }
    if (/^#{1}\s+/.test(trimmed)) { flushList(); elements.push(<h1 key={key++}>{renderInline(trimmed.replace(/^#{1}\s+/, ''))}</h1>); continue }
    if (/^#{2}\s+/.test(trimmed)) { flushList(); elements.push(<h2 key={key++}>{renderInline(trimmed.replace(/^#{2,3}\s+/, ''))}</h2>); continue }
    if (/^#{3}\s+/.test(trimmed)) { flushList(); elements.push(<h3 key={key++}>{renderInline(trimmed.replace(/^#{3}\s+/, ''))}</h3>); continue }
    if (/^[-*]\s+/.test(trimmed)) { listItems.push(trimmed.replace(/^[-*]\s+/, '')); continue }
    if (/^[🟢🟡🔴]/.test(trimmed)) {
      flushList()
      const cls = trimmed.startsWith('🟢') ? 'scenario-bull' : trimmed.startsWith('🔴') ? 'scenario-bear' : 'scenario-base'
      elements.push(<p key={key++} className={cls}>{renderInline(trimmed)}</p>)
      continue
    }
    flushList()
    elements.push(<p key={key++}>{renderInline(trimmed)}</p>)
  }
  flushList()
  return <div className="report-content">{elements}</div>
}
