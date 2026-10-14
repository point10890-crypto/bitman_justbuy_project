import { useEffect, useRef, type ReactNode } from 'react'
import type { KeywordSearchResponse } from '../../api/keywordSearchApi'

interface KeywordSearchPanelProps {
  query: string
  loading: boolean
  error: string | null
  result: KeywordSearchResponse | null
  onClose: () => void
  /** 실데이터 결과 아래에 붙는 추가 결과 (AI 조건검색 엔진). */
  children?: ReactNode
}

const outcomeLabel: Record<KeywordSearchResponse['outcome'], string> = {
  COMPLETE: '실데이터 검색 완료',
  PARTIAL: '일부 데이터로 검색',
  EMPTY: '조건 일치 0건',
  UNAVAILABLE: '데이터 조회 불가',
  UNSUPPORTED: '지원하지 않는 조건',
}
const outcomeMessage: Partial<Record<KeywordSearchResponse['outcome'], string>> = {
  EMPTY: '조회한 범위에서 조건에 맞는 종목이 없습니다.',
  UNAVAILABLE: '실데이터를 조회하지 못했습니다. 잠시 후 다시 검색해 주세요.',
  UNSUPPORTED: '지원하지 않는 조건이 있습니다. 아래 조건을 바꿔 다시 검색해 주세요.',
}

function formatNumber(value: number | null | undefined, unit: string, signed = false) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '미확인'
  const formatted = value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
  return `${signed && value > 0 ? '+' : ''}${formatted}${unit}`
}

function sourceLabel(source: string | null | undefined) {
  if (source === 'NAVER_FINANCE') return '네이버 금융'
  if (source === 'KIWOOM_REST' || source === 'KIWOOM_REST_MOCK') return '키움 REST'
  return source || '미확인'
}

function environmentLabel(environment: string | null | undefined) {
  if (environment === 'MOCK') return '모의투자 서버 시장조회'
  if (environment === 'REAL') return '실전 서버 시장조회'
  return '환경 미확인'
}

function providerStatusLabel(status: string) {
  if (status === 'COMPLETE' || status === 'AVAILABLE') return '조건검색에 사용'
  if (status === 'PARTIAL') return '일부 데이터 사용'
  if (status === 'PENDING' || status === 'QUEUED') return '추가 조회 대기'
  if (status === 'UNAVAILABLE' || status === 'FAILED') return '조회 미확인'
  if (status === 'DISABLED') return '연결 꺼짐'
  if (status === 'NOT_REQUESTED' || status === 'NOT_REQUIRED') return '미조회'
  return '조회 상태 미확인'
}

function validCount(value: number | null | undefined) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 600
}

function providerCoverage(provider: NonNullable<KeywordSearchResponse['providers']>[number]) {
  const { requestedCount, availableCount, pendingCount } = provider
  if (!validCount(requestedCount) || !validCount(availableCount) || !validCount(pendingCount)
    || availableCount! > requestedCount! || pendingCount! > requestedCount!) return null
  return `근거 확인 ${availableCount} / 요청 ${requestedCount}종목 · 추가 확인 대기 ${pendingCount}종목`
}

function observationSourceLabel(source: string | null | undefined, environment: string | null | undefined) {
  return `${sourceLabel(source)}${environment ? ` · ${environmentLabel(environment)}` : ''}`
}

function isCalendarDate(value: string) {
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function formatKstTimestamp(value: string | null | undefined) {
  const isoTimestamp = /^(\d{4}-\d{2}-\d{2})T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/
  const match = value?.match(isoTimestamp)
  if (!value || !match || !isCalendarDate(match[1]) || !Number.isFinite(Date.parse(value))) return '미확인'
  return `${new Date(value).toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  })} KST`
}

function formatEvidenceObservation(value: string) {
  const interval = value.match(/^(\d{4}-\d{2}-\d{2}) ~ (\d{4}-\d{2}-\d{2})$/)
  if (interval && isCalendarDate(interval[1]) && isCalendarDate(interval[2]) && interval[1] <= interval[2]) {
    return `관측 기간 ${interval[1]} ~ ${interval[2]}`
  }
  return `관측 ${formatKstTimestamp(value)}`
}

export function KeywordSearchPanel({ query, loading, error, result, onClose, children }: KeywordSearchPanelProps) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    closeRef.current?.focus()
    return () => { previousFocus?.focus() }
  }, [])
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
      // Keep keyboard focus inside the modal: Tab cycles through its own controls only.
      if (event.key === 'Tab') {
        const focusables = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(
          'button:not([disabled]), a[href], input:not([disabled]), [tabindex]:not([tabindex="-1"])') ?? [])
        if (!focusables.length) return
        const i = focusables.indexOf(document.activeElement as HTMLElement)
        const next = event.shiftKey ? (i <= 0 ? focusables.length - 1 : i - 1) : (i === -1 || i === focusables.length - 1 ? 0 : i + 1)
        event.preventDefault()
        focusables[next].focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  return (
    <div ref={dialogRef} className="maker-analysis-panel keyword-search-panel" role="dialog" aria-modal="true" aria-label="실데이터 조건검색">
      <div className="maker-analysis-sheet">
        <header>
          <div>
            <strong>실데이터 조건검색</strong>
            <span>{query}</span>
          </div>
          <button ref={closeRef} type="button" onClick={onClose}>닫기</button>
        </header>
        <div className="maker-analysis-body keyword-search-body">
          {loading && <div className="analysis-loading" role="status">실데이터를 조회하고 조건을 확인하고 있습니다.</div>}
          {error && <div className="auth-error" role="alert">{error}</div>}
          {result && !loading && (
            <>
              <div className="keyword-search-status" role="status">
                <strong>{outcomeLabel[result.outcome]}</strong>
                <span>조회 시각 {formatKstTimestamp(result.fetchedAt)}</span>
              </div>
              {outcomeMessage[result.outcome] && <p className="keyword-search-empty">{outcomeMessage[result.outcome]}</p>}
              {result.providers && result.providers.length > 0 && (
                <section className="keyword-search-scope" aria-label="조회 출처와 상태">
                  <h3>조건검색 데이터 출처</h3>
                  {result.providers.map((provider, index) => (
                    <div key={`${provider.source}-${index}`}>
                      <p><strong>{sourceLabel(provider.source)} · {environmentLabel(provider.environment)} · {providerStatusLabel(provider.status)}</strong></p>
                      {provider.scope && <p>{provider.scope}</p>}
                      {providerCoverage(provider) && <p>{providerCoverage(provider)}</p>}
                      <p>조회 결과 확인 {formatKstTimestamp(provider.checkedAt)}</p>
                    </div>
                  ))}
                  {result.providers.some(provider => provider.status === 'PENDING' || provider.status === 'QUEUED'
                    || validCount(provider.pendingCount) && provider.pendingCount! > 0) && (
                    <p>확인된 종목은 먼저 표시합니다. 추가 조회가 끝난 뒤 같은 조건으로 다시 검색해 주세요.</p>
                  )}
                </section>
              )}
              <section className="keyword-search-scope" aria-label="검색 조건과 범위">
                <h3>적용한 조건</h3>
                {result.appliedConditions.length > 0
                  ? <ul>{result.appliedConditions.map((condition, index) => <li key={index}>{condition}</li>)}</ul>
                  : <p>적용한 조건이 없습니다.</p>}
                <p>검색 범위: {result.scope.label}</p>
                <p>시세 확인 {result.scope.examinedCount.toLocaleString('ko-KR')}개 · 시세 조건 통과 {result.scope.eligibleCount.toLocaleString('ko-KR')}개 · 조건 일치 {result.scope.matchedCount.toLocaleString('ko-KR')}개</p>
                {result.scope.truncated && <p className="keyword-search-caution">검색 범위 제한</p>}
              </section>
              {(result.unsupportedTerms.length > 0 || result.warnings.length > 0) && (
                <section className="keyword-search-warnings" aria-label="검색 제한과 확인 필요 사항">
                  {result.unsupportedTerms.length > 0 && <p>적용하지 못한 조건: {result.unsupportedTerms.join(', ')}</p>}
                  {result.warnings.map((warning, index) => <p key={index}>{warning}</p>)}
                </section>
              )}
              {result.matches.length > 0 && (
                <section className="keyword-search-matches" aria-label="조건 일치 종목">
                  <h3>조건 일치 종목</h3>
                  {result.matches.map(match => (
                    <article className="keyword-match" key={match.code}>
                      <div className="keyword-match-heading"><strong>{match.name}</strong><span>{match.code} · {match.market || '시장 미확인'}</span></div>
                      <div className="keyword-match-values">
                        <span>현재가 {formatNumber(match.currentPrice, '원')}</span>
                        <span>등락률 {formatNumber(match.changePct, '%', true)}</span>
                        <span>거래량 {formatNumber(match.volume, '주')}</span>
                        <span>거래대금 {formatNumber(match.tradingValueKrw, '원')}</span>
                      </div>
                      <p className="keyword-match-source">시세 출처 {observationSourceLabel(match.quoteSource, match.quoteEnvironment)} · 확인 시각 {formatKstTimestamp(match.quoteCheckedAt)}</p>
                      <p className="keyword-match-source">시세 관측 시각 {formatKstTimestamp(match.observedAt)}</p>
                      {match.sourceObservations && match.sourceObservations.length > 0 && (
                        <ul className="keyword-match-evidence" aria-label={`${match.name} 공급자별 조회값`}>
                          {match.sourceObservations.map((observation, index) => (
                            <li key={`${observation.source}-${index}`}>
                              <strong>{sourceLabel(observation.source)} · {environmentLabel(observation.environment)}</strong>
                              <span>현재가 {formatNumber(observation.currentPrice, '원')} · 등락률 {formatNumber(observation.changePct, '%', true)} · 거래량 {formatNumber(observation.volume, '주')} · 거래대금 {formatNumber(observation.tradingValueKrw, '원')}</span>
                              <span>조회 {formatKstTimestamp(observation.fetchedAt)} · 관측 {formatKstTimestamp(observation.observedAt)}</span>
                            </li>
                          ))}
                        </ul>
                      )}
                      <ul className="keyword-match-evidence">
                        {match.evidence.map((evidence, index) => (
                          <li key={index}>
                            <strong>{evidence.condition}</strong>
                            <span>실측 {evidence.observedValue || '미확인'}{evidence.unit} · 출처 {observationSourceLabel(evidence.source, evidence.environment)}</span>
                            {evidence.observedAt && <span>{formatEvidenceObservation(evidence.observedAt)}</span>}
                            {evidence.fetchedAt && <span>근거 조회 시각 {formatKstTimestamp(evidence.fetchedAt)}</span>}
                          </li>
                        ))}
                      </ul>
                    </article>
                  ))}
                </section>
              )}
              <p className="keyword-search-note">조건에 맞는 조회 결과입니다. 조회 시각과 시세 관측 시각은 다를 수 있습니다.</p>
            </>
          )}
          {children}
        </div>
      </div>
    </div>
  )
}
