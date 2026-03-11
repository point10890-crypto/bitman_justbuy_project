import { useState } from 'react'
import { API_BASE } from '../../api/config'

interface FeedbackWidgetProps {
  mode: string
  analysisId: string    // updatedAt as ID
  stockPicks?: Array<{ name: string; code: string }>
}

/** 분석 결과 피드백 위젯 — 사용자가 분석 품질을 평가 */
export default function FeedbackWidget({ mode, analysisId, stockPicks }: FeedbackWidgetProps) {
  const [submitted, setSubmitted] = useState(false)
  const [rating, setRating] = useState<number | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [comment, setComment] = useState('')
  const [selectedStocks, setSelectedStocks] = useState<Set<string>>(new Set())

  if (submitted) {
    return (
      <div className="glass-card flex items-center gap-2" style={{ padding: '10px 14px' }}>
        <span className="text-base">✅</span>
        <span className="text-[11px] font-bold" style={{ color: 'var(--color-bull)' }}>피드백 감사합니다! AI 분석 개선에 반영됩니다.</span>
      </div>
    )
  }

  const handleSubmit = async () => {
    if (rating === null) return

    const payload = {
      mode,
      analysisId,
      rating,
      comment: comment.trim() || undefined,
      helpfulStocks: selectedStocks.size > 0 ? Array.from(selectedStocks) : undefined,
      timestamp: new Date().toISOString(),
    }

    try {
      const token = localStorage.getItem('auth_token')
      await fetch(`${API_BASE}/api/feedback`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(payload),
      })
    } catch {
      // 피드백 전송 실패해도 UX 유지
    }

    setSubmitted(true)
  }

  const stars = [1, 2, 3, 4, 5]

  return (
    <div className="glass-card" style={{ padding: '12px 14px' }}>
      {/* 첫 번째 줄: 별점 */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-[11px] font-bold" style={{ color: 'var(--text-secondary)' }}>분석 만족도</span>
          <div className="flex items-center gap-0.5">
            {stars.map(s => (
              <button
                key={s}
                type="button"
                onClick={() => { setRating(s); if (!expanded) setExpanded(true) }}
                className="w-7 h-7 flex items-center justify-center rounded-lg transition-all active:scale-90"
                style={{
                  backgroundColor: rating !== null && s <= rating ? 'rgba(255,215,0,0.15)' : 'transparent',
                  WebkitTapHighlightColor: 'transparent',
                }}
              >
                <span style={{
                  fontSize: '16px',
                  filter: rating !== null && s <= rating ? 'drop-shadow(0 0 4px rgba(255,215,0,0.5))' : 'none',
                  opacity: rating !== null && s <= rating ? 1 : 0.3,
                }}>
                  ★
                </span>
              </button>
            ))}
          </div>
        </div>
        {rating !== null && !expanded && (
          <button
            type="button"
            onClick={() => setExpanded(true)}
            className="text-[10px] font-bold px-2 py-1 rounded-lg"
            style={{ backgroundColor: 'var(--bg-elevated)', color: 'var(--text-secondary)' }}
          >
            상세 피드백
          </button>
        )}
      </div>

      {/* 확장 영역 */}
      {expanded && rating !== null && (
        <div className="mt-3 pt-3" style={{ borderTop: '1px solid var(--border-subtle)' }}>
          {/* 유용했던 종목 선택 */}
          {stockPicks && stockPicks.length > 0 && (
            <div className="mb-2.5">
              <span className="text-[10px] font-bold" style={{ color: 'var(--text-muted)' }}>유용했던 종목 (선택)</span>
              <div className="flex flex-wrap gap-1.5 mt-1.5">
                {stockPicks.map(stock => {
                  const selected = selectedStocks.has(stock.code)
                  return (
                    <button
                      key={stock.code}
                      type="button"
                      onClick={() => {
                        const next = new Set(selectedStocks)
                        selected ? next.delete(stock.code) : next.add(stock.code)
                        setSelectedStocks(next)
                      }}
                      className="px-2 py-1 rounded-lg text-[10px] font-bold transition-all active:scale-95"
                      style={{
                        backgroundColor: selected ? 'rgba(0,200,83,0.15)' : 'var(--bg-elevated)',
                        border: `1px solid ${selected ? 'rgba(0,200,83,0.3)' : 'var(--border-subtle)'}`,
                        color: selected ? '#00C853' : 'var(--text-secondary)',
                        WebkitTapHighlightColor: 'transparent',
                      }}
                    >
                      {stock.name}
                    </button>
                  )
                })}
              </div>
            </div>
          )}

          {/* 코멘트 */}
          <textarea
            value={comment}
            onChange={e => setComment(e.target.value)}
            placeholder="개선 의견이 있으시면 남겨주세요 (선택)"
            maxLength={200}
            rows={2}
            className="w-full text-[11px] rounded-lg resize-none"
            style={{
              padding: '8px 10px',
              backgroundColor: 'var(--bg-elevated)',
              border: '1px solid var(--border-subtle)',
              color: 'var(--text-primary)',
              outline: 'none',
            }}
          />

          {/* 제출 */}
          <button
            type="button"
            onClick={handleSubmit}
            className="mt-2 w-full py-2 rounded-xl text-[12px] font-black transition-all active:scale-[0.98]"
            style={{
              backgroundImage: 'var(--gradient-brand)',
              color: '#0D1117',
              WebkitTapHighlightColor: 'transparent',
            }}
          >
            피드백 전송
          </button>
        </div>
      )}
    </div>
  )
}
