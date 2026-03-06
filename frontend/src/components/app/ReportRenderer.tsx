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

/** 마크다운 기반 리포트 렌더러 */
export function ReportRenderer({ content }: { content: string }) {
  if (!content) return null
  const lines = content.split('\n')
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
