import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { getStoredToken } from '../contexts/AuthContext'
import { fetchMemberTrackRecord, type MemberTrackRecordResponse, type TrackRecordMode } from '../api/conditionApi'

const RANGE_PRESETS = [
  { days: 30, label: '30일' },
  { days: 90, label: '90일' },
] as const

function returnClass(value: string) {
  // '-' 는 "측정 불가"지 손실이 아니다. 그대로 두면 빈 값이 빨갛게 칠해진다.
  if (value === '-' || !value) return ''
  if (value.startsWith('+')) return 'profit-text'
  if (value.startsWith('-')) return 'loss-text'
  return ''
}

function ModeCard({ record }: { record: TrackRecordMode }) {
  const measured = record.verifiedCount > 0
  return (
    <section className="search-rank-card history-day-card">
      <div className="rank-card-title-row history-day-title-row">
        <div className="rank-title-copy">
          <h2>{record.title}</h2>
          <span>
            {measured
              ? `검증 ${record.verifiedCount}건 · ${record.wins}승 ${record.losses}패 · ${record.returnBasis}`
              : '검증된 포착이 아직 없습니다'}
          </span>
        </div>
      </div>

      {measured ? (
        <>
          <div className="condition-track-summary">
            <span>승률 {record.winRate}</span>
            <span className={returnClass(record.avgReturnPct)}>평균 {record.avgReturnPct}</span>
            <span className={returnClass(record.avgMaxReturnPct)}>평균 최대 {record.avgMaxReturnPct}</span>
            {record.targetHitRate !== '-' && <span>목표가 도달 {record.targetHitRate}</span>}
            {record.stopHitRate !== '-' && <span>손절 터치 {record.stopHitRate}</span>}
          </div>
          {record.targetHitRate !== '-' && (
            <div className="history-note">도달률 기준: {record.hitRateBasis}</div>
          )}
          {record.avgExcessReturnPct !== '-' && (
            <div className="condition-track-summary">
              <span>시장 평균 {record.avgBenchmarkReturnPct}</span>
              <span className={returnClass(record.avgExcessReturnPct)}>
                시장 대비 {record.avgExcessReturnPct}
              </span>
              <span>시장 상회 {record.marketBeatRate}</span>
            </div>
          )}
        </>
      ) : (
        <div className="rank-table-empty">검증이 채워지면 성과가 표시됩니다.</div>
      )}
    </section>
  )
}

export default function TrackRecordPage() {
  const navigate = useNavigate()
  const [days, setDays] = useState<number>(30)
  const [data, setData] = useState<MemberTrackRecordResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    // 기간 전환 시 이전 수치가 새 탭 라벨 아래 남지 않도록 즉시 비우고,
    // 응답 역전(늦게 온 옛 요청이 덮어쓰는 것)을 막기 위해 취소 플래그를 둔다.
    let cancelled = false
    setLoading(true)
    setError(null)
    setData(null)
    fetchMemberTrackRecord(days, getStoredToken() || undefined)
      .then(res => { if (!cancelled) setData(res) })
      .catch(err => {
        if (!cancelled) setError(err instanceof Error ? err.message : '성적표를 불러오지 못했습니다.')
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [days])

  return (
    <main className="search-home-page">
      <div className="history-page-head">
        <button type="button" className="history-back-button" onClick={() => navigate('/')}>←</button>
        <div>
          <h1>성적표</h1>
          <span>추천일 종가 진입 기준, 다음 거래일 결과입니다.</span>
        </div>
      </div>

      <div className="history-range-tabs" role="group" aria-label="조회 기간">
        {RANGE_PRESETS.map(preset => (
          <button
            key={preset.days}
            type="button"
            className={preset.days === days ? 'is-active' : ''}
            onClick={() => setDays(preset.days)}
          >
            최근 {preset.label}
          </button>
        ))}
      </div>

      {error && <div className="condition-feed-status">{error}</div>}
      {loading && <div className="rank-table-empty">성적표를 불러오는 중입니다.</div>}

      {data && (
        <>
          <section className="search-rank-card history-day-card">
            <div className="rank-card-title-row history-day-title-row">
              <div className="rank-title-copy">
                <h2>전체</h2>
                <span>{data.from} ~ {data.to}</span>
              </div>
            </div>
            {data.overall.verifiedCount > 0 ? (
              <>
                <div className="condition-track-summary">
                  <span>검증 {data.overall.verifiedCount}건</span>
                  <span>승률 {data.overall.winRate}</span>
                  <span className={returnClass(data.overall.avgReturnPct)}>평균 {data.overall.avgReturnPct}</span>
                </div>
                <div className="history-note">
                  전체는 기준이 다른 모드를 합친 값입니다. 단일 지표로 해석하지 마세요.
                </div>
              </>
            ) : (
              <div className="rank-table-empty">검증된 포착이 아직 없습니다.</div>
            )}
            {data.benchmarkLabel && <div className="history-note">{data.benchmarkLabel}</div>}
          </section>

          {data.note && <div className="condition-warning-strip history-note">{data.note}</div>}

          {data.modes.map(mode => <ModeCard key={mode.mode} record={mode} />)}
        </>
      )}

      <p className="investment-notice">
        투자 유의: 과거 성과는 미래 수익을 보장하지 않습니다. 본 기록은 투자 참고용 정보이며,
        특정 종목의 매수/매도 추천이나 수익 보장을 의미하지 않습니다.
      </p>
    </main>
  )
}
