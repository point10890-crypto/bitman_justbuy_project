import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { getStoredToken, useAuth } from '../contexts/AuthContext'
import { fetchClosingBetPerformance, type JonggaPerformanceResponse, type JonggaPerformanceRow } from '../api/conditionApi'

const RANGE_PRESETS = [
  { days: 7, label: '7일' },
  { days: 30, label: '30일' },
  { days: 90, label: '90일' },
] as const

function toIsoDate(date: Date) {
  return date.toISOString().slice(0, 10)
}

function rangeFor(days: number) {
  const to = new Date()
  const from = new Date(to)
  from.setDate(from.getDate() - days)
  return { from: toIsoDate(from), to: toIsoDate(to) }
}

function maskValue(value: string) {
  if (/^[+-]/.test(value)) return value
  if (/^[0-9,]+/.test(value)) return value.replace(/[0-9]/g, 'X')
  if (value.length <= 2) return value
  return `${value[0]}${value.slice(1).replace(/[가-힣A-Za-z0-9]/g, 'X')}`
}

function returnClass(value: string) {
  if (value.startsWith('+')) return 'profit-text'
  if (value.startsWith('-')) return 'loss-text'
  return ''
}

function resultClass(result: string) {
  if (result === '승') return 'history-result-win'
  if (result === '패') return 'history-result-loss'
  if (result === '보합') return 'history-result-flat'
  return 'history-result-pending'
}

function formatDateLabel(date: string) {
  const parsed = new Date(`${date}T00:00:00+09:00`)
  if (Number.isNaN(parsed.getTime())) return date
  return parsed.toLocaleDateString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
  })
}

export default function ClosingBetHistoryPage() {
  const navigate = useNavigate()
  const { user } = useAuth()
  const [rangeDays, setRangeDays] = useState<number>(30)
  const [data, setData] = useState<JonggaPerformanceResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const locked = user?.role !== 'ADMIN' && user?.subscription !== 'pro'
  const range = useMemo(() => rangeFor(rangeDays), [rangeDays])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setData(await fetchClosingBetPerformance(range.from, range.to, getStoredToken() || undefined))
    } catch (err) {
      setError(err instanceof Error ? err.message : '히스토리를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }, [range.from, range.to])

  useEffect(() => {
    load()
  }, [load])

  const show = (value: string) => (locked ? maskValue(value) : value)

  return (
    <main className="search-home-page">
      <div className="history-page-head">
        <button type="button" className="history-back-button" onClick={() => navigate('/')}>←</button>
        <div>
          <h1>종가매매 히스토리</h1>
          <span>추천 다음 영업일 종가 기준으로 검증한 결과입니다.</span>
        </div>
      </div>

      <div className="history-range-tabs" role="group" aria-label="조회 기간">
        {RANGE_PRESETS.map(preset => (
          <button
            key={preset.days}
            type="button"
            className={preset.days === rangeDays ? 'is-active' : ''}
            onClick={() => setRangeDays(preset.days)}
          >
            최근 {preset.label}
          </button>
        ))}
      </div>

      {error && <div className="condition-feed-status">{error}</div>}

      {data && (
        <div className="condition-track-summary" aria-label="기간 성과 요약">
          <span>추천 {data.totalSignals}건</span>
          <span>검증 {data.verifiedCount}건</span>
          <span>승률 {data.winRate}</span>
          <span>평균 종가 {data.avgCloseReturnPct}</span>
          <span>평균 최대 {data.avgMaxReturnPct}</span>
          <span>목표가 도달 {data.targetHitRate}</span>
        </div>
      )}

      {data && data.avgExcessReturnPct !== '-' && (
        <div className="condition-track-summary" aria-label="시장 대비 성과">
          <span>시장 평균 {data.avgBenchmarkReturnPct}</span>
          <span className={returnClass(data.avgExcessReturnPct)}>
            시장 대비 {data.avgExcessReturnPct}
          </span>
          <span>시장 상회 {data.marketBeatRate}</span>
        </div>
      )}

      {data?.note && <div className="condition-warning-strip history-note">{data.note}</div>}

      {loading && !data && <div className="rank-table-empty">히스토리를 불러오는 중입니다.</div>}

      {data?.days.map(day => (
        <section className="search-rank-card history-day-card" key={day.date}>
          <div className="rank-card-title-row history-day-title-row">
            <div className="rank-title-copy">
              <h2>{formatDateLabel(day.date)}</h2>
              <span>
                {day.verified ? `검증 완료 · 평균 ${day.avgCloseReturnPct}` : '익일 성과 미검증'}
              </span>
            </div>
          </div>

          <div className="rank-table" style={{ ['--rank-cols' as string]: '1.05fr .65fr .65fr .6fr .65fr .55fr' }}>
            <div className="rank-table-head">
              <span>종목명</span>
              <span>진입가</span>
              <span>익일 종가</span>
              <span>최대 수익률</span>
              <span>시장 대비</span>
              <span>결과</span>
            </div>
            {day.rows.map((row: JonggaPerformanceRow) => (
              <div className="rank-table-row" key={`${day.date}-${row.stockCode}`}>
                <span className="rank-name">
                  <strong>{row.rank}</strong>
                  <span className="rank-name-copy">
                    <span>{show(row.stockName)}</span>
                    <em>
                      {row.grade}등급 · 목표 {show(row.targetPrice)} · 손절 {show(row.stopLoss)}
                    </em>
                  </span>
                </span>
                <span>{show(row.entryPrice)}</span>
                <span className={returnClass(row.closeReturnPct)}>
                  {show(row.closePrice)}
                  {row.closeReturnPct !== '-' && <em className="history-return">{row.closeReturnPct}</em>}
                </span>
                <span className={returnClass(row.maxReturnPct)}>{row.maxReturnPct}</span>
                <span className={returnClass(row.excessReturnPct)}>
                  {row.excessReturnPct}
                  {row.benchmarkReturnPct !== '-' && (
                    <em className="history-return">시장 {row.benchmarkReturnPct}</em>
                  )}
                </span>
                <span className={`history-result ${resultClass(row.result)}`}>
                  {row.result}
                  {row.hitTarget && <em>목표달성</em>}
                  {row.hitStop && !row.hitTarget && <em>손절터치</em>}
                </span>
              </div>
            ))}
          </div>
        </section>
      ))}

      {data && data.days.length === 0 && !loading && (
        <div className="rank-table-empty">해당 기간에 종가매매 추천 기록이 없습니다.</div>
      )}

      {locked && (
        <button className="subscribe-sticky-cta" type="button" onClick={() => navigate('/subscribe')}>
          월간 이용권 구독하고 전체 기록 보기
        </button>
      )}

      <p className="investment-notice">
        투자 유의: 과거 성과는 미래 수익을 보장하지 않습니다. 본 기록은 투자 참고용 정보이며,
        특정 종목의 매수/매도 추천이나 수익 보장을 의미하지 않습니다.
      </p>
    </main>
  )
}
