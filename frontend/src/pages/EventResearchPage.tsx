import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchEventResearch, fetchEventResearchStatus, refreshEventResearch, type EventResearchCandidate, type EventResearchOutcome, type EventResearchSnapshot, type EventResearchStatus } from '../api/eventResearchApi'
import { ApiError } from '../api/subscriptionGate'
import { getStoredToken, useAuth } from '../contexts/AuthContext'
import './EventResearchPage.css'

type Evidence = Record<string, unknown>
const record = (value: unknown): Evidence => value && typeof value === 'object' && !Array.isArray(value) ? value as Evidence : {}
const scalar = (value: unknown): string | null => typeof value === 'string' && value.trim() ? value : typeof value === 'number' && Number.isFinite(value) ? String(value) : null
const text = (value: unknown, fallback = '자료 없음') => scalar(value) ?? fallback
const strings = (value: unknown): string[] => Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && Boolean(item.trim())) : []

function numberText(value: unknown): string | null {
  const raw = scalar(value)
  if (!raw || !/^[+-]?\d+(?:\.\d+)?$/.test(raw)) return null
  const [integer, decimal] = raw.split('.')
  return `${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${decimal ? `.${decimal}` : ''}`
}
const money = (value: unknown) => { const amount = numberText(value); return amount === null ? '자료 없음' : `${amount}원` }
const percent = (value: unknown) => { const amount = numberText(value); return amount === null ? '자료 없음' : `${amount}%` }
const percentPoints = (value: unknown) => { const amount = numberText(value); return amount === null ? '자료 없음' : `${amount}%p` }

/** Rounds decimal strings outward without converting monetary values to floating point. */
function outwardDecimal(value: unknown, places: number, bound: 'lower' | 'upper'): string | null {
  const raw = scalar(value)
  if (!raw || !/^[+-]?\d+(?:\.\d+)?$/.test(raw)) return null
  const negative = raw.startsWith('-')
  const [integer, fraction = ''] = raw.replace(/^[+-]/, '').split('.')
  let magnitude = BigInt(integer + fraction.slice(0, places).padEnd(places, '0'))
  const discarded = /[1-9]/.test(fraction.slice(places))
  if (discarded && (bound === 'lower' ? negative : !negative)) magnitude += 1n
  const digits = magnitude.toString().padStart(places + 1, '0')
  const decimals = places ? digits.slice(-places).replace(/0+$/, '') : ''
  const whole = places ? digits.slice(0, -places) : digits
  return `${negative && magnitude !== 0n ? '-' : ''}${whole}${decimals ? `.${decimals}` : ''}`
}

function fractionPercent(value: unknown, bound: 'lower' | 'upper'): string {
  const raw = scalar(value)
  if (!raw || !/^[+-]?\d+(?:\.\d+)?$/.test(raw)) return '자료 없음'
  const sign = raw.startsWith('-') ? '-' : ''
  const [integer, decimal = ''] = raw.replace(/^[+-]/, '').split('.')
  const padded = decimal.padEnd(2, '0')
  const shiftedInteger = `${integer}${padded.slice(0, 2)}`.replace(/^0+(?=\d)/, '')
  const shiftedDecimal = padded.slice(2).replace(/0+$/, '')
  return percent(outwardDecimal(`${sign}${shiftedInteger}${shiftedDecimal ? `.${shiftedDecimal}` : ''}`, 4, bound))
}

const issueLabels: Record<string, string> = {
  HISTORY_WINDOW_MAX_THREE_REPORT_DAYS: '최근 최대 3개 공시일의 이행률 자료만 조회',
  BUYBACK_ROW_LIMIT: '자사주 표시 상한 80건 · 시장별 최대 40건',
  FINANCIAL_FILING_DATE_UNKNOWN_CAPTURE_TIME_USED: '재무 공시일 미확인 · 자료 조회 시각을 기준으로 사용',
  STRAIGHT_LINE_TIME_SHARE_ASSUMPTION: '계약 기간에 매출이 고르게 발생한다고 가정',
  ISSUER_CONTRACT_SHARE_ASSUMPTION: '공시 계약액이 해당 공급사에 귀속된다고 가정',
  DISCLOSURE_PAGE_LIMIT: '공시 목록은 회차별 최대 3페이지까지만 확인했습니다.',
  DISCLOSURE_ROW_LIMIT: '공시 목록은 페이지당 최대 100건까지만 확인했습니다.',
  ORIGINAL_DOCUMENT_LIMIT: '공시 원문은 회차별 최대 12건까지만 확인했습니다.',
  AMENDMENT_PRIOR_AMOUNT_UNKNOWN: '정정 전 계약액 미확인',
  CUMULATIVE_CHANGED_CONTRACT: '이전 계약의 누적 변경 공시',
  WITHDRAWN_CONTRACT: '철회된 계약',
  AMOUNT_UNKNOWN: '계약 금액 미확인',
  AMBIGUOUS_AMOUNT: '공시 안의 계약 금액이 서로 달라 확정할 수 없음',
  CUSTOMER_UNKNOWN: '계약 상대방 미공개 또는 미확인',
  PRODUCT_UNKNOWN: '계약 제품·내용 미확인',
  INVALID_CONTRACT_PERIOD: '계약 기간을 확인하지 못함',
  AMBIGUOUS_CONTRACT_PERIOD: '공시 안의 계약 기간이 서로 다름',
  SUBSIDIARY_SUPPLIER_IDENTITY_UNVERIFIED: '자회사 계약의 실제 공급사를 확인하지 못해 제외',
  INVALID_DISCLOSURE_IDENTITY_OR_DATE: '공시 회사·접수 번호·날짜를 확인하지 못해 제외',
  CONFLICTING_DISCLOSURE_METADATA: '동일 공시의 회사·날짜 정보가 서로 달라 제외',
  CONFLICTING_CACHED_DISCLOSURE_METADATA: '저장된 원문과 공시 정보가 서로 달라 확인 보류',
  BUDGET_EXHAUSTED: '정해진 조회 시간 안에 확인하지 못한 자료가 있습니다.',
  RESPONSE_SIZE_LIMIT: '원문 크기가 조회 상한을 넘어 확인하지 못함',
  ARCHIVE_SIZE_LIMIT: '압축 원문 크기가 조회 상한을 넘어 확인하지 못함',
  PROVIDER_RATE_LIMIT: '공식 공시 제공자의 조회 제한으로 일부 자료 미확인',
  PROVIDER_KEY_REJECTED: '공식 공시 연결 설정을 확인하지 못함',
  MISSING_KEY: '공식 공시 연결 설정 미확인',
  INVALID_JSON_SCHEMA: '공식 자료의 응답 형식을 확인하지 못함',
  INVALID_ORIGINAL_DOCUMENT: '공식 원문 내용을 확인하지 못함',
  TRANSPORT_OR_PARSE_ERROR: '공식 자료 연결 또는 원문 해석 실패',
  EMPTY_HTTP_BODY: '공식 자료 응답이 비어 있음',
  HTTP_STATUS_ERROR: '공식 자료 제공자가 정상 응답을 반환하지 않음',
  PROVIDER_STATUS_ERROR: '공식 공시 제공자가 자료를 반환하지 않음',
  INTERRUPTED: '자료 확인이 중단되어 일부 근거 미확인',
  CASH_INPUTS_UNKNOWN: '세금·현금 전환·운전자본·설비투자 입력 미확인',
  CASH_INPUTS_INVALID: '현금 계산 입력을 검증하지 못함',
  HISTORICAL_ISSUER_MARGIN_PROXY: '과거 전사 영업이익률을 대용치로 사용',
  SUPPLIER_ALLOCATION_ASSUMPTION: '공급사 귀속 비율은 계산 가정',
  PERIOD_RECOGNITION_ASSUMPTION: '기간별 매출 인식 비율은 계산 가정',
  FINANCIAL_EVIDENCE_UNKNOWN: '재무 근거 미확인',
  PREVIOUS_AMOUNT_UNKNOWN: '정정 전 계약액 미확인',
  CONTRACT_PERIOD_UNKNOWN: '계약 기간 미확인',
  NEGATIVE_CONTRACT_DELTA: '이전 계약보다 계약액 감소',
  NO_INCREMENTAL_CONTRACT_AMOUNT: '추가 계약액 없음',
  AMENDMENT: '정정 공시', WITHDRAWAL: '계약 철회',
  PERIOD_INVALID: '예측 기간과 계약 기간이 맞지 않음',
  CAPACITY_EXCEEDED: '가정한 매출이 생산 능력 범위를 초과',
  INVALID_ASSUMPTIONS: '계산 가정을 검증하지 못함',
  FUTURE_FINANCIAL_EVIDENCE: '관측 시점 이후의 재무 자료로 계산할 수 없음',
  COUNTERPARTY_UNKNOWN: '계약 상대방 미확인',
  CASH_FLOW_UNKNOWN: '현금흐름 영향 미확인',
  HISTORICAL_MARGIN_PROXY: '과거 전사 이익률을 참고한 가정',
  MARGIN_ASSUMPTION: '영업이익률은 계산 가정',
  RECOGNITION_ASSUMPTION: '매출 인식 시점은 계산 가정',
  CUSTOMER_RELIANCE: '고객사 의존도 확인 필요',
  CAPACITY_RISK: '생산 능력 확인 필요', EVIDENCE_UNKNOWN: '원문 근거 미확인',
  CONTRACT_WITHDRAWAL_OR_CHANGE: '계약 철회 또는 계약 내용 변경',
  RECOGNITION_OR_MARGIN_ASSUMPTION_FAILED: '매출 인식 또는 이익률 가정이 성립하지 않음',
  MISSING_SOURCE_EVIDENCE: '공식 원문 근거가 확인되지 않음',
  AI_UNAVAILABLE: 'AI 검증을 사용할 수 없어 규칙으로만 검증',
  AI_TIMEOUT: 'AI 검증 시간 초과 · 규칙 검증만 유지',
  AI_RESULT_INVALID: 'AI 검증 결과를 확인하지 못함 · 규칙 검증만 유지',
  WAITING_FOR_AFTER_CLOSE_OBSERVATION: '장마감 후 실제 연구 발행 시각 확정 대기',
  HORIZON_NOT_MATURE: '평가 대상 거래일이 아직 완료되지 않음',
  OFFICIAL_CALENDAR_COVERAGE_UNCONFIRMED: '공식 거래일 달력의 해당 기간 미확인',
  FINANCIAL_SOURCE_UNAVAILABLE: '재무 제공 자료를 확인하지 못함',
  KIS_SOURCE_UNAVAILABLE: 'KIS 조회 자료를 확인하지 못함',
  KIWOOM_SOURCE_UNAVAILABLE: '키움 조회 자료를 확인하지 못함',
  CUSTOMER_EVENT_ALLOCATION_UNKNOWN: '고객사 사건의 공급사 배정 물량 미확인',
  FACT_NUMBERS_UNQUANTIFIED: '공시 수치의 공급사 이익 영향 미확인',
  CUSTOMER_EVENT_IMPACT_UNQUANTIFIED: '고객사 사건의 공급사 매출·이익 영향 미확인',
  COSTS_UNKNOWN_OR_UNVERIFIED: '비용 입력 미확인 · 순수익률 비교 제외',
  COSTS_UNKNOWN: '거래 비용 미확인',
  BENCHMARK_MISSING: '벤치마크 미확인',
  BENCHMARK_NOT_ALIGNED: '벤치마크 관측 기간이 맞지 않음',
  FROZEN_PORTFOLIO_UNION: '같은 시점에 고정한 정책별 종목과 비중으로 비교',
  REFERENCE_SIMULATION_NOT_REALIZED_PNL: '참조 가격 모의 성과 · 실제 체결 손익 아님',
  NO_AUTOMATIC_PROMOTION: '운영 순위 자동 반영 없음',
  CASH_REMAINDER_ZERO_RETURN_ASSUMPTION: '잔여 현금은 수익이 없는 것으로 가정',
  COST_CHARGED_ON_TRADED_WEIGHTS_ONLY: '거래한 비중에만 비용을 적용',
  RETURNS_AS_FRACTIONS_FOR_LEARNING: '집계 시 같은 수익률 단위 사용',
  RETURN_PENDING: '성과 완료 대기',
  MISSING_RETURN: '종목 성과 자료 누락',
  DUPLICATE_RETURN: '종목 성과 중복으로 측정 제외',
  NON_REAL_RETURN: '실제 시장 자료가 아니므로 측정 제외',
  RETURN_NOT_COMPLETE: '성과가 완료되지 않아 측정 제외',
  RETURN_VALUE_UNKNOWN_OR_INVALID: '수익률을 확인하지 못해 측정 제외',
  INVALID_HORIZON: '평가 기간 미확인',
  INVALID_SELECTION: '고정한 정책별 종목·비중을 확인하지 못함',
  SAME_POLICY_ID: '동일한 정책끼리는 비교할 수 없음',
  INVALID_COST: '비용 입력을 확인하지 못함',
  MALFORMED_RETURN: '성과 응답을 확인하지 못함',
  INVALID_OUTCOME_WINDOW: '성과 관측 기간을 확인하지 못함',
  INVALID_COMPARISON: '비교 계산을 확인하지 못함',
}

function time(value: unknown): string {
  const input = scalar(value)
  if (!input) return '시점 미확인'
  if (/^\d{4}-\d{2}-\d{2}$/.test(input)) return input
  const date = new Date(input)
  return Number.isNaN(date.getTime()) ? '시점 미확인' : date.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}

function officialUrl(value: unknown): string | null {
  try {
    const url = new URL(text(value, ''))
    return url.protocol === 'https:' && !url.port && !url.username && !url.password && ['dart.fss.or.kr', 'opendart.fss.or.kr', 'kind.krx.co.kr'].includes(url.hostname) ? url.href : null
  } catch { return null }
}

function SourceLink({ url, label }: { url: unknown; label: string }) {
  const href = officialUrl(url)
  return href ? <a href={href} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{label} <span aria-hidden="true">↗</span></a> : <span className="event-muted">원문 링크 확인 필요</span>
}

function EvidenceList({ values, empty }: { values: unknown; empty?: string }) {
  const items = strings(values)
  const label = (item: string) => {
    if (issueLabels[item]) return issueLabels[item]
    const [reason, stockCode] = item.split(':')
    if (/^\d{14}$/.test(reason) && issueLabels[stockCode]) return `${issueLabels[stockCode]} (공시 ${reason})`
    return issueLabels[reason] && /^\d{6}$/.test(stockCode ?? '') ? `${issueLabels[reason]} (${stockCode})` : item
  }
  return items.length ? <ul className="event-evidence-list">{items.map((item, index) => <li key={`${index}-${item}`}>{label(item)}</li>)}</ul> : empty ? <p className="event-muted">{empty}</p> : null
}

function Range({ value }: { value: unknown }) {
  const bounds = record(value)
  if (scalar(bounds.lower) === null || scalar(bounds.upper) === null) return <>자료 없음</>
  return <>{money(outwardDecimal(bounds.lower, 0, 'lower'))} ~ {money(outwardDecimal(bounds.upper, 0, 'upper'))}</>
}

function FinancialReference({ value }: { value: Evidence | null }) {
  const facts = record(value?.facts)
  if (!value || Object.keys(facts).length === 0) return <p className="event-muted">재무 근거 자료 없음</p>
  const amount = (value: unknown) => facts.currency === 'KRW' ? money(value) : `${text(value)} ${text(facts.currency, '(단위 미확인)')}`
  return <>
    <p className="event-note">과거 전사 실적 참고 · 제품별 미래 이익률을 뜻하지 않습니다.</p>
    <dl className="event-facts">
      <div><dt>기준 기간</dt><dd>{text(facts.period)}</dd></div>
      <div><dt>매출액</dt><dd>{amount(facts.revenue)}</dd></div>
      <div><dt>영업이익</dt><dd>{amount(facts.operatingProfit)}</dd></div>
      <div><dt>영업이익률</dt><dd>{percent(facts.operatingMarginPct)}</dd></div>
      <div><dt>영업 현금흐름</dt><dd>{amount(facts.operatingCashFlow)}</dd></div>
      <div><dt>현금흐름 기준 기간</dt><dd>{text(facts.cashFlowPeriod)}</dd></div>
      <div><dt>재무 공시일</dt><dd>{time(facts.filingDate)}</dd></div>
    </dl>
    <SourceLink url={facts.sourceUrl} label="재무 공시 원문" />
    <EvidenceList values={value.issues} />
  </>
}

function Scenario({ value }: { value: Evidence | null }) {
  if (!value) return <p className="event-muted">이익 시나리오 자료 없음</p>
  const assumptions = record(value.assumptions)
  const fractionRange = (value: unknown) => {
    const range = record(value)
    return scalar(range.lower) === null || scalar(range.upper) === null ? '자료 없음' : `${fractionPercent(range.lower, 'lower')} ~ ${fractionPercent(range.upper, 'upper')}`
  }
  return <>
    <p className="event-note">추정 범위는 입력 가정에 따른 계산입니다. 실제 이익이나 현금 유입이 확정된 값은 아닙니다.</p>
    <p className="event-note">금액은 원 단위, 비율은 소수점 4자리까지 범위를 넓혀 표시합니다.</p>
    {value.status === 'INVALIDATED' && <p className="event-stale">시나리오 무효 · 계약·기간·생산 능력 조건 확인 필요</p>}
    {value.status === 'UNKNOWN' && <p className="event-muted">계산에 필요한 근거 부족</p>}
    <dl className="event-facts">
      <div><dt>증분 계약액</dt><dd>{money(value.incrementalContractAmountWon)}</dd></div>
      <div><dt>인식 매출 범위</dt><dd><Range value={value.recognizedRevenueWon} /></dd></div>
      <div><dt>영업이익 범위</dt><dd><Range value={value.operatingProfitWon} /></dd></div>
      <div><dt>현금 영향</dt><dd>{value.cashFlowWon ? <Range value={value.cashFlowWon} /> : '현금 영향 자료 없음'}</dd></div>
    </dl>
    <h4>계산 가정</h4>
    {Object.keys(assumptions).length > 0 ? <dl className="event-facts">
      <div><dt>공급사 귀속 비율</dt><dd>{fractionRange(assumptions.supplierAllocation)}</dd></div>
      <div><dt>기간 인식 비율</dt><dd>{fractionRange(assumptions.periodRecognition)}</dd></div>
      <div><dt>영업이익률 범위</dt><dd>{fractionRange(assumptions.operatingMargin)}</dd></div>
      <div><dt>이익률 근거</dt><dd>{assumptions.marginBasis === 'HISTORICAL_ISSUER_MARGIN_PROXY' ? '과거 전사 영업이익률 대용치' : text(assumptions.marginBasis)}</dd></div>
      <div><dt>예측 시작일</dt><dd>{time(assumptions.forecastStart)}</dd></div>
      <div><dt>예측 종료일</dt><dd>{time(assumptions.forecastEnd)}</dd></div>
      <div><dt>세율 가정</dt><dd>{fractionRange(assumptions.cashTaxRate)}</dd></div>
      <div><dt>현금 전환 비율</dt><dd>{fractionRange(assumptions.cashConversion)}</dd></div>
      <div><dt>운전자본 증감</dt><dd><Range value={assumptions.workingCapitalWon} /></dd></div>
      <div><dt>설비투자</dt><dd><Range value={assumptions.capexWon} /></dd></div>
      <div><dt>생산 능력 매출 한도</dt><dd>{money(assumptions.capacityRevenueWon)}</dd></div>
    </dl> : <EvidenceList values={value.assumptions} empty="계산 가정 자료 없음" />}
    <EvidenceList values={value.issues} />
  </>
}

function MarketObservation({ source, value }: { source: string; value: Evidence | null }) {
  if (!value || Object.keys(value).length === 0) return <div className="event-market-source"><h4>{source}</h4><p className="event-muted">시세 근거 자료 없음</p></div>
  const quoteRecord = record(value.quote)
  const quote = Object.keys(quoteRecord).length ? quoteRecord : record(value.valuation)
  const environment = text(quote.environment ?? value.environment, '환경 정보 미제공')
  const sourceName = text(quote.source ?? value.source, '제공자 미확인')
  const mock = sourceName === 'KIWOOM_REST_MOCK' || environment === 'MOCK'
  const currentPrice = quote.currentPrice ?? quote.price
  const observedAt = quote.observedAt
  const fetchedAt = quote.fetchedAt ?? value.fetchedAt
  const knownTimestamp = (value: unknown) => scalar(value) !== null && !/^\d{4}-\d{2}-\d{2}$/.test(text(value)) && time(value) !== '시점 미확인'
  const trustedProvider = ['KIS OpenAPI', 'KIS_OPENAPI', 'KIWOOM_REST', 'KIWOOM_REST_REAL', 'KIWOOM_REST_MOCK'].includes(sourceName)
  const price = scalar(currentPrice)
  const priceVerified = trustedProvider && quote.status === 'AVAILABLE' && (knownTimestamp(observedAt) || knownTimestamp(fetchedAt)) && price !== null && /^\+?\d+(?:\.\d+)?$/.test(price) && /[1-9]/.test(price)
  return <div className="event-market-source">
    <h4>{source}</h4>
    <p>{mock ? '모의서버 조회 자료' : sourceName}</p>
    {mock && <p className="event-note">시장 조회 자료를 함께 표시합니다. 실제 주문·체결 성과로 집계하지 않습니다.</p>}
    <dl className="event-facts">
      <div><dt>조회 환경</dt><dd>{environment === 'MOCK' ? '모의서버' : environment === 'REAL' ? '실서버' : environment}</dd></div>
      <div><dt>조회 가격</dt><dd>{priceVerified ? money(currentPrice) : '시세 검증 자료 없음'}</dd></div>
      <div><dt>조회 시각</dt><dd>{time(fetchedAt)}</dd></div>
      <div><dt>거래소 체결 시각</dt><dd>{knownTimestamp(observedAt) ? time(observedAt) : '거래소 체결 시각 미제공'}</dd></div>
    </dl>
  </div>
}

function Outcome({ value }: { value: EventResearchOutcome }) {
  const completed = ['COMPLETE', 'MEASURED'].includes(value.state)
  const unavailable = ['UNMEASURABLE', 'UNFILLABLE', 'AMBIGUOUS_PATH', 'INVALID', 'UNAVAILABLE'].includes(value.state)
  return <div className="event-outcome">
    <h4>{value.horizonSessions}거래일 {completed ? '측정 완료' : unavailable ? '측정 불가' : '검증 대기'}</h4>
    {completed && <dl className="event-facts event-return-facts">
      <div><dt>총수익률</dt><dd>{percent(value.grossReturnPct)}</dd></div>
      <div><dt>비용 차감 수익률</dt><dd>{percent(value.netReturnPct)}</dd></div>
      <div><dt>시장 대비 수익률</dt><dd>{percent(value.excessReturnPct)}</dd></div>
    </dl>}
    <EvidenceList values={value.reasons} />
  </div>
}

function Candidate({ value }: { value: EventResearchCandidate }) {
  const verification = value.verification
  const propagation = value.origin === 'CUSTOMER_EVENT_PROPAGATION'
  const customerEvent = value.relatedCustomerEvent
  const displayedMarket = value.currentMarket ?? value.market
  const amendments: Record<string, string> = { ORIGINAL: '최초 공시', AMENDED: '정정 공시', CORRECTED: '정정 공시', CANCELLED: '철회 공시', WITHDRAWN: '철회 공시' }
  const amendment = amendments[value.amendmentStatus] ?? '정정 여부 확인 필요'
  return <article className="event-case" aria-label={`${value.stockName} 사건 연구`}>
    <div className="event-case-heading"><div><h2>{value.stockName}</h2><span className="event-muted">{value.stockCode} · {propagation ? '고객사 사건 연결' : amendment}</span></div>{!propagation && <SourceLink url={value.sourceUrl} label="DART 계약 원문" />}</div>
    {propagation && <section className="event-original" aria-label="고객사 신규 공시">
      <h3>고객사 신규 공시</h3>
      <p className="event-verification">고객사 사건 연결 · 공급 물량·이익 영향은 추가 확인 필요</p>
      {customerEvent ? <>
        <h4>{customerEvent.title}</h4>
        <dl className="event-facts event-original-facts">
          <div><dt>고객사</dt><dd>{text(value.customerName, '상대방 미공개')}</dd></div>
          <div><dt>고객사 공시일</dt><dd>{time(customerEvent.filingDate)}</dd></div>
          <div><dt>고객사 사건 최초 관측</dt><dd>{time(customerEvent.firstSeenAt)}</dd></div>
          <div><dt>고객사 접수 번호</dt><dd>{text(customerEvent.receiptNo)}</dd></div>
        </dl>
        <SourceLink url={customerEvent.sourceUrl} label="고객사 DART 공시 원문" />
        <EvidenceList values={customerEvent.issues} />
      </> : <p className="event-muted">고객사 공시 연결 근거 자료 없음</p>}
    </section>}
    <section className="event-original" aria-label={propagation ? '사전에 확인된 공급 관계' : '공시에서 확인한 사실'}>
      <h3>{propagation ? '사전에 확인된 공급 관계' : '공시에서 확인한 사실'}</h3>
      {propagation && <p className="event-note">이전 공급 계약은 거래 관계의 근거입니다. 고객사 새 공시의 추가 수주액이나 이익 증가로 계산하지 않습니다.</p>}
      <dl className="event-facts event-original-facts">
        <div><dt>고객사</dt><dd>{text(value.customerName, '상대방 미공개')}</dd></div>
        <div><dt>제품·계약 내용</dt><dd>{text(value.product)}</dd></div>
        <div><dt>{propagation ? '이전 공급 계약 금액' : '계약 총액'}</dt><dd>{money(value.contractAmountWon)}</dd></div>
        <div><dt>{propagation ? '고객사 사건의 증분 수주' : '이전 계약 대비 증감'}</dt><dd>{propagation ? '자료 없음' : money(value.contractDeltaWon)}</dd></div>
        {!propagation && <div><dt>공시일</dt><dd>{time(value.filingDate)}</dd></div>}
        <div><dt>{propagation ? '연구 최초 관측' : '최초 관측'}</dt><dd>{time(value.firstSeenAt)}</dd></div>
        {propagation && value.relationshipKnownAt && <div><dt>관계 근거 확보</dt><dd>{time(value.relationshipKnownAt)}</dd></div>}
        {value.publishedAt && <div><dt>연구 발행</dt><dd>{time(value.publishedAt)}</dd></div>}
        <div><dt>{propagation ? '저장 근거 접수 번호' : '접수 번호'}</dt><dd>{text(value.receiptNo)}</dd></div>
      </dl>
      {propagation && <SourceLink url={value.sourceUrl} label="공급 관계 확인 원문" />}
      {value.evidenceText && <blockquote>{value.evidenceText}</blockquote>}
    </section>
    <div className="event-case-grid">
      <section><h3>재무 근거</h3><FinancialReference value={value.financial} /></section>
      <section className="event-scenario"><h3>{propagation ? '고객사 사건의 공급사 영향' : '가정에 따른 이익 시나리오'}</h3>{propagation ? <>
        <p className="event-note">배정 물량·추가 계약액·원가가 확인되지 않아 이익 범위를 계산하지 않습니다.</p>
        <dl className="event-facts"><div><dt>증분 계약액</dt><dd>자료 없음</dd></div><div><dt>영업이익 영향</dt><dd>자료 없음</dd></div><div><dt>현금 영향</dt><dd>현금 영향 자료 없음</dd></div></dl>
        <EvidenceList values={value.scenario?.issues} />
      </> : <Scenario value={value.scenario} />}</section>
    </div>
    <section className="event-challenge"><h3>근거 검증과 반론</h3>
      <p className="event-verification">{verification?.status === 'RULE_ONLY' ? '규칙 검증만 완료 · AI 검증 없음' : ['AI_CHECKED', 'AI_VERIFIED'].includes(verification?.status) ? 'AI 근거 대조 완료' : `검증 상태: ${text(verification?.status, '검증 자료 없음')}`}</p>
      <div className="event-challenge-grid">
        <div><h4>위험 요인</h4><EvidenceList values={verification?.risks} empty="확인된 위험 자료 없음" /></div>
        <div><h4>반론</h4><EvidenceList values={verification?.counterArguments} empty="반론 자료 없음" /></div>
        <div><h4>가정이 무효가 되는 조건</h4><EvidenceList values={verification?.invalidationConditions} empty="무효 조건 자료 없음" /></div>
      </div>
    </section>
    <div className="event-case-grid">
      <section><h3>{value.currentMarket ? '최근 조회 근거' : '발행 시점 가격 근거'}</h3><p className="event-note">제공자가 조회한 가격과 조회 시각을 표시합니다. 거래소 체결 시각이 제공되지 않은 경우 별도로 표시합니다. 연구 발행 시점의 근거는 성과 평가 기준으로 보존합니다.</p><MarketObservation source="KIS" value={displayedMarket?.kis} /><MarketObservation source="키움" value={displayedMarket?.kiwoom} /></section>
      <section><h3>발행 후 성과 관측</h3><p className="event-note">1·3·5·20거래일 중 완료된 관측만 표시합니다. 비용·벤치마크가 없으면 순수익률·초과수익률은 자료 없음입니다.</p>
        <dl className="event-facts"><div><dt>장마감 후 평가 기준시각</dt><dd>{value.evaluationPublishedAt ? time(value.evaluationPublishedAt) : '실제 발행 시각 확정 대기'}</dd></div></dl>
        {value.evaluationPolicy === 'AFTER_CLOSE_NEXT_SESSION_OPEN_V1' && <p className="event-note">다음 거래일 시가 진입 가정의 모의 성과입니다. 실제 주문·체결 수익률이 아닙니다.</p>}
        {value.outcomes?.length ? value.outcomes.map((outcome, index) => <Outcome key={`${outcome.horizonSessions}-${index}`} value={outcome} />) : <p className="event-muted">성과 관측 대기</p>}
      </section>
    </div>
  </article>
}

function Buybacks({ value }: { value: EventResearchSnapshot['buybacks'] }) {
  return <section className="event-buybacks" aria-labelledby="event-buybacks-title">
    <div className="event-section-heading"><h2 id="event-buybacks-title">자사주 신청과 실제 체결</h2><span>{time(value.asOf)} 기준</span></div>
    <p className="event-note">다음 거래일 신청은 실제 매수가 아닙니다. 과거 이행률과 예상 참여량은 보장된 수량이나 지지 가격을 뜻하지 않습니다.</p>
    <EvidenceList values={value.issues} />
    {value.rows.length === 0 ? <p className="event-empty">저장된 공식 자사주 자료가 없습니다.</p> : <div className="event-buyback-grid">{value.rows.map((row, index) => <div className="event-buyback-row" key={`${text(row.stockCode)}-${index}`}>
      <h3>{text(row.stockName, '회사명 미확인')} <span>{text(row.market, '')} {text(row.stockCode, '')}{scalar(row.category) !== null ? ` · ${text(row.category)}` : ''}</span></h3>
      <p>신청일 {time(row.applicationDate)} · {numberText(row.applicationQuantity) === null ? '신청 수량 자료 없음' : `신청 수량 ${numberText(row.applicationQuantity)}주`}</p>
      <p>{numberText(row.executionQuantity) === null ? '실제 체결 자료 없음' : `실제 체결 ${numberText(row.executionQuantity)}주 · ${time(row.executionDate)}`}</p>
      <dl className="event-facts">
        <div><dt>실제 체결 금액</dt><dd>{money(row.executionAmountWon)}</dd></div>
        <div><dt>과거 신청 대비 이행률</dt><dd>{percent(row.fulfilmentRatePct)}</dd></div>
        <div><dt>예상 참여량 (추정)</dt><dd>{numberText(row.expectedQuantity) === null ? '자료 없음' : `${numberText(row.expectedQuantity)}주`}</dd></div>
        <div><dt>이행률 관측 기간</dt><dd>{text(row.historyDays, '0')}거래일</dd></div>
      </dl>
      <SourceLink url={row.sourceUrl} label="KIND 신청 원문" />
      {officialUrl(row.executionSourceUrl) && <> · <SourceLink url={row.executionSourceUrl} label="KIND 체결 원문" /></>}
    </div>)}</div>}
  </section>
}

function ComparisonPanel({ value }: { value: EventResearchSnapshot['comparison'] }) {
  const statuses: Record<string, string> = { INSUFFICIENT_EVIDENCE: '비교 근거 수집 중', REVIEW_CANDIDATE: '추가 검토 기준 충족', REJECTED: '비교 기준 미충족' }
  const captures: Record<string, string> = { CAPTURED: '정책별 종목·비중 저장됨', WAITING_FOR_AFTER_CLOSE_CAPTURE: '장마감 후 정책 포착 대기', SELECTION_EVIDENCE_INSUFFICIENT: '정책 포착 근거 부족', CURRENT_BASELINE_UNAVAILABLE: '기준 정책 자료 미확인' }
  const states: Record<string, string> = { PENDING: '검증 대기', GROSS_ONLY: '총수익률만 측정', COMPLETE: '측정 완료', UNMEASURABLE: '측정 불가' }
  const outcomes = value?.latestOutcomes ?? []
  const reasons = [...new Set(outcomes.flatMap(row => strings(row.reasons)))]
  const count = (number: unknown) => typeof number === 'number' && Number.isInteger(number) && number >= 0 ? `${number}일` : '자료 없음'
  return <section className="event-comparison" aria-labelledby="event-comparison-title">
    <div className="event-section-heading"><h2 id="event-comparison-title">고정 정책 비교 연구</h2><span>{value ? statuses[value.status] ?? '비교 상태 확인 필요' : '비교 자료 대기'}</span></div>
    <p className="event-verification">{value?.productionActivationAllowed === true ? '운영 반영 검토 가능 · 수동 검토 필요' : '연구 검증 중 · 기존 순위 유지'}</p>
    {!value ? <p className="event-muted">저장된 정책 비교 자료가 없습니다.</p> : <>
      <p className="event-note">발행 시점에 고정한 종목과 비중을 다음 거래일 시가 진입 가정으로 비교합니다. 실제 주문·체결 손익이나 투자 우위의 증명이 아닙니다.</p>
      <dl className="event-facts event-original-facts">
        <div><dt>기준 정책</dt><dd>{text(value.baselinePolicyId)}</dd></div>
        <div><dt>비교 정책</dt><dd>{text(value.challengerPolicyId)}</dd></div>
        <div><dt>포착 상태</dt><dd>{captures[value.captureStatus] ?? '포착 상태 확인 필요'}</dd></div>
        <div><dt>평균 순수익률 차이</dt><dd>{value.costsVerified && value.measuredDates > 0 ? percentPoints(value.meanPairedDeltaPct) : '자료 없음'}</dd></div>
      </dl>
      <div className="event-comparison-counts"><span>포착 {count(value.capturedDates)}</span><span>측정 완료 {count(value.measuredDates)}</span><span>독립 관측 {count(value.independentDates)}</span></div>
      <p className="event-note">{value.costsVerified ? '비용 검증 완료 · 확인된 비용을 적용한 결과만 비교' : '비용 미확인 · 순수익률 비교 제외'}</p>
      {outcomes.length === 0 ? <p className="event-muted">완료된 비교 성과 자료가 없습니다.</p> : <div className="event-comparison-table-wrap" tabIndex={0} role="region" aria-label="정책 비교 성과 표"><table className="event-comparison-table">
        <thead><tr><th scope="col">기간</th><th scope="col">상태</th><th scope="col">기준 총수익률</th><th scope="col">비교 총수익률</th><th scope="col">기준 순수익률</th><th scope="col">비교 순수익률</th><th scope="col">순수익률 차이</th></tr></thead>
        <tbody>{outcomes.map((row, index) => {
          const grossMeasured = ['COMPLETE', 'GROSS_ONLY'].includes(row.state)
          const netMeasured = value.costsVerified && row.state === 'COMPLETE'
          return <tr key={`${row.horizonSessions}-${index}`}><th scope="row">{row.horizonSessions}거래일</th><td>{states[row.state] ?? '측정 상태 미확인'}</td><td>{grossMeasured ? percent(row.baselineGross) : '자료 없음'}</td><td>{grossMeasured ? percent(row.challengerGross) : '자료 없음'}</td><td>{netMeasured ? percent(row.baselineNet) : '자료 없음'}</td><td>{netMeasured ? percent(row.challengerNet) : '자료 없음'}</td><td>{netMeasured ? percentPoints(row.netDelta) : '자료 없음'}</td></tr>
        })}</tbody>
      </table></div>}
      {reasons.length > 0 && <details className="event-comparison-reasons"><summary>비교 해석 조건</summary><EvidenceList values={reasons} /></details>}
    </>}
  </section>
}

export default function EventResearchPage() {
  const { user } = useAuth()
  const [data, setData] = useState<EventResearchSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [reload, setReload] = useState(0)
  const [requesting, setRequesting] = useState(false)
  const [adminStatus, setAdminStatus] = useState<Partial<EventResearchStatus> | null>(null)
  const [adminError, setAdminError] = useState<string | null>(null)
  const adminController = useRef<AbortController | null>(null)
  const isAdmin = user?.role === 'ADMIN'

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError(null)
    void fetchEventResearch(getStoredToken() || undefined, controller.signal)
      .then(result => { if (!controller.signal.aborted) setData(result) })
      .catch(failure => {
        if (controller.signal.aborted) return
        const nextError = failure instanceof Error ? failure : new Error('저장된 연구를 불러오지 못했습니다.')
        setError(nextError)
        if (nextError instanceof ApiError && [401, 403].includes(nextError.status)) setData(null)
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [reload])

  useEffect(() => () => adminController.current?.abort(), [])

  async function adminAction(collect: boolean) {
    if (!isAdmin || requesting) return
    adminController.current?.abort()
    const controller = new AbortController()
    adminController.current = controller
    setRequesting(true)
    setAdminError(null)
    try {
      if (collect) {
        const result = await refreshEventResearch(getStoredToken() || undefined, controller.signal)
        if (!controller.signal.aborted) setAdminStatus({ ...result, running: result.status === 'RUNNING' || result.accepted })
      } else {
        const result = await fetchEventResearchStatus(getStoredToken() || undefined, controller.signal)
        if (!controller.signal.aborted) {
          setAdminStatus(result)
          if (!result.running) setReload(current => current + 1)
        }
      }
    } catch (failure) {
      if (!controller.signal.aborted) setAdminError(failure instanceof Error ? failure.message : '수집 요청을 처리하지 못했습니다.')
    } finally { if (!controller.signal.aborted) setRequesting(false) }
  }

  const authError = error instanceof ApiError && error.status === 401
  const subscriptionError = error instanceof ApiError && Boolean(error.code)
  const originals = data?.coverage?.originalsFetched ?? data?.coverage?.documentsFetched

  return <main className="event-research-page">
    <header className="event-page-header">
      <Link to="/" className="event-back">← 조건검색 홈</Link>
      <h1>공개 사건 연구</h1>
      <p>공식 공시에서 확인한 공급 계약과 자사주 자료를 연구합니다. 사실·계산 가정·실측 성과를 구분해 확인하세요.</p>
      <div className="event-page-actions">
        <button type="button" disabled={loading} onClick={() => setReload(current => current + 1)}>저장 결과 다시 조회</button>
        {isAdmin && <>
          <button type="button" className="event-primary-action" disabled={requesting || adminStatus?.running === true} onClick={() => void adminAction(true)}>공식 자료 수집 요청</button>
          <button type="button" disabled={requesting} onClick={() => void adminAction(false)}>수집 상태 확인</button>
        </>}
      </div>
      {isAdmin && adminStatus && <p role="status">{adminStatus.running ? '공식 자료 수집 중 · 완료된 저장 결과는 다시 조회할 수 있습니다.' : '수집 종료 · 저장 결과를 조회했습니다.'}{adminStatus.lastError && ` ${adminStatus.lastError}`}</p>}
      {adminError && <p className="event-error" role="alert">{adminError}</p>}
    </header>
    {loading && <p className="event-loading" role="status">저장된 연구 불러오는 중</p>}
    {error && <div className="event-error" role="alert"><p>{error.message}</p>{authError ? <Link to="/login">다시 로그인</Link> : subscriptionError ? <Link to="/subscribe">이용권 확인</Link> : data && <p>마지막으로 읽은 저장 결과를 표시합니다.</p>}</div>}
    {data && <>
      <section className="event-snapshot" aria-label="연구 수집 범위와 기준 시점">
        <div><strong>{data.status === 'PARTIAL' ? '일부 자료만 확인됨' : data.status === 'COMPLETE' ? '저장 자료 확인 완료' : data.status === 'EMPTY' ? '저장된 연구 없음' : `수집 상태: ${data.status}`}</strong><span>{time(data.asOf)} 기준</span></div>
        {data.stale && <p className="event-stale">이전 저장 결과 · 최신 수집 확인 필요</p>}
        <p>공급 계약 연구 {data.candidates.length}건{scalar(originals) !== null ? ` · 원문 확인 ${text(originals)}건` : ''}{scalar(data.coverage?.originalLimit) !== null ? ` · 회차별 원문 확인 상한 ${text(data.coverage.originalLimit)}건` : ''}</p>
        {scalar(data.coverage?.contracts) !== null && <p>계약 수집 {text(data.coverage.contracts)}건 · 회차 상한 {text(data.coverage.maxContracts)}건</p>}
        {scalar(data.coverage?.financialReferences) !== null && <p>재무 근거 {text(data.coverage.financialReferences)}건 · KIS 가격 {text(data.coverage.kisCandidates)}건 · 키움 조회 {text(data.coverage.kiwoomCandidates)}건</p>}
        {strings(data.issues).length > 0 && <details className="event-comparison-reasons"><summary>자료 확인 제약 {strings(data.issues).length}건</summary><EvidenceList values={data.issues} /></details>}
      </section>
      {data.candidates.length === 0 ? <div className="event-empty"><h2>저장된 공급 계약 연구가 없습니다.</h2><p>공식 자료가 수집되고 근거가 확인되면 이곳에 표시됩니다.</p></div> : <div className="event-cases">{data.candidates.map(candidate => <Candidate key={candidate.caseId} value={candidate} />)}</div>}
      <Buybacks value={data.buybacks} />
      <ComparisonPanel value={data.comparison} />
      <p className="event-footer-note">공시 관계는 관측 시점에 확인한 근거입니다. 이전 사건으로 소급하거나 기존 종목 순위에 자동 반영하지 않습니다. 연구 결과가 투자 우위를 입증한 것은 아닙니다.</p>
    </>}
  </main>
}
