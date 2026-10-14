// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import EventResearchPage from './EventResearchPage'
import { fetchEventResearch, refreshEventResearch } from '../api/eventResearchApi'

let role = 'USER'
vi.mock('../contexts/AuthContext', () => ({
  getStoredToken: () => 'event-research-fixture-token',
  useAuth: () => ({ user: { role, subscription: 'PRO' } }),
}))

const candidate = {
  caseId: '20261014000123', stockCode: '012345', stockName: '공시 공급사', customerName: '공시 고객사',
  product: '공급 장비', contractAmountWon: '12000000000', contractDeltaWon: null,
  sourceUrl: 'https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261014000123', receiptNo: '20261014000123',
  firstSeenAt: '2026-10-14T16:00:00+09:00', filingDate: '2026-10-14', amendmentStatus: 'ORIGINAL',
  evidenceText: '공급사와 고객사 사이의 장비 공급 계약',
  financial: { status: 'AVAILABLE', facts: { revenue: '70000000000', operatingProfit: '7000000000', operatingMarginPct: '10', period: '2025', currency: 'KRW', sourceUrl: 'https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260317000001' }, issues: [] },
  scenario: { status: 'PARTIAL', incrementalContractAmountWon: null, recognizedRevenueWon: { lower: '6000000000', upper: '9000000000' }, operatingProfitWon: { lower: '300000000', upper: '900000000' }, cashFlowWon: null, scenarios: [], assumptions: ['기간 인식 비율 50~75%는 가정'], observed: { historicalOperatingMarginPct: '10' }, issues: ['현금 전환 입력 없음'] },
  verification: { status: 'RULE_ONLY', risks: ['설비 생산 여력 미확인'], counterArguments: ['계약과 수익 인식 시점이 다를 수 있음'], invalidationConditions: ['계약 철회'] },
  market: { kis: null, kiwoom: { source: 'KIWOOM_REST_MOCK', quote: { currentPrice: 40000 } } },
  outcomes: [{ horizonSessions: 1, state: 'PENDING', grossReturnPct: '7.00', netReturnPct: null, excessReturnPct: null, reasons: ['1거래일이 아직 완료되지 않음'] }],
}
const snapshot = () => ({
  status: 'PARTIAL', asOf: '2026-10-14T16:00:00+09:00', runId: 'run-fixture', stale: false, issues: [],
  coverage: { originalsFetched: 1, originalLimit: 12 }, candidates: [candidate],
  buybacks: { status: 'EMPTY', asOf: null, rows: [], issues: [] },
})
const comparison = () => ({
  status: 'INSUFFICIENT_EVIDENCE', captureStatus: 'CAPTURED', capturedDates: 2, measuredDates: 0, independentDates: 0,
  productionActivationAllowed: false, baselinePolicyId: 'BASELINE_FROZEN_V1', challengerPolicyId: 'EVENT_RESEARCH_V1',
  costsVerified: false, meanPairedDeltaPct: null as string | null, latestOutcomes: [] as Array<{ state: string; horizonSessions: number; baselineGross: string | null; challengerGross: string | null; baselineNet: string | null; challengerNet: string | null; netDelta: string | null; reasons: string[] }>,
})
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const renderPage = () => render(<MemoryRouter><EventResearchPage /></MemoryRouter>)

beforeEach(() => { role = 'USER'; vi.stubGlobal('fetch', vi.fn()) })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('stored public event research', () => {
  it('shows loading and aborts the stored-result request when the page closes', () => {
    vi.mocked(fetch).mockReturnValue(new Promise(() => {}))
    const view = renderPage()
    expect(screen.getByRole('status').textContent).toContain('저장된 연구 불러오는 중')
    const [url, options] = vi.mocked(fetch).mock.calls[0]
    expect(url).toContain('/api/research/events')
    expect(options?.method).toBe('GET')
    expect(options?.cache).toBe('no-store')
    view.unmount()
    expect(options?.signal?.aborted).toBe(true)
  })

  it('separates originals, assumptions and unknown cash from an uncompleted return', async () => {
    vi.mocked(fetch).mockResolvedValue(json(snapshot()))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    const details = within(card)
    expect(details.getByText('공시 고객사')).toBeTruthy()
    expect(details.getByText('공급 장비')).toBeTruthy()
    expect(details.getByRole('link', { name: 'DART 계약 원문' }).getAttribute('href')).toBe(candidate.sourceUrl)
    expect(details.getByText('최초 관측')).toBeTruthy()
    expect(details.getByText('기간 인식 비율 50~75%는 가정')).toBeTruthy()
    expect(details.getByText('현금 영향 자료 없음')).toBeTruthy()
    expect(details.getByText('규칙 검증만 완료 · AI 검증 없음')).toBeTruthy()
    expect(details.getByText('계약 철회')).toBeTruthy()
    expect(details.getByText('모의서버 조회 자료')).toBeTruthy()
    expect(details.getByText('1거래일 검증 대기')).toBeTruthy()
    expect(details.queryByText(/7\.00%|40,000원/)).toBeNull()
    expect(screen.queryByRole('button', { name: '공식 자료 수집 요청' })).toBeNull()
  })

  it('renders missing evidence as unknown and blocks hostile source links', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{ ...candidate, sourceUrl: 'javascript:alert(1)', financial: null, scenario: null, outcomes: [], market: { kis: null, kiwoom: null } }] }))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    expect(within(card).queryByRole('link', { name: 'DART 계약 원문' })).toBeNull()
    expect(within(card).getByText('원문 링크 확인 필요')).toBeTruthy()
    expect(within(card).getByText('재무 근거 자료 없음')).toBeTruthy()
    expect(within(card).getByText('이익 시나리오 자료 없음')).toBeTruthy()
    expect(within(card).getByText('성과 관측 대기')).toBeTruthy()
  })

  it('rejects an official-looking link using an unapproved origin port', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{ ...candidate, sourceUrl: 'https://dart.fss.or.kr:8443/dsaf001/main.do?rcpNo=20261014000123' }] }))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    expect(within(card).queryByRole('link', { name: 'DART 계약 원문' })).toBeNull()
  })

  it('discloses structured allocation, period and margin assumptions from the calculator contract', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{ ...candidate, scenario: { ...candidate.scenario, assumptions: { supplierAllocation: { lower: '1', upper: '1' }, periodRecognition: { lower: '0.5', upper: '0.75' }, operatingMargin: { lower: '0.05', upper: '0.1' }, cashTaxRate: null, cashConversion: null, workingCapitalWon: null, capexWon: null, forecastStart: '2026-10-14', forecastEnd: '2027-10-13', capacityRevenueWon: null, marginBasis: 'HISTORICAL_ISSUER_MARGIN_PROXY' } } }] }))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    expect(within(card).getByText('50% ~ 75%')).toBeTruthy()
    expect(within(card).getByText('5% ~ 10%')).toBeTruthy()
    expect(within(card).getByText('과거 전사 영업이익률 대용치')).toBeTruthy()
    expect(within(card).getByText('2027-10-13')).toBeTruthy()
  })

  it('withholds quotes without provider provenance while displaying timestamped official data', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{ ...candidate, market: { kis: { source: 'KIS', quote: { currentPrice: '25000' } }, kiwoom: { source: 'KIWOOM_REST', environment: 'REAL', quote: { currentPrice: '30000', observedAt: '2026-10-14T15:30:00+09:00', fetchedAt: '2026-10-14T16:00:00+09:00', status: 'AVAILABLE' } } } }] }))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    expect(within(card).queryByText('25,000원')).toBeNull()
    expect(within(card).getByText('30,000원')).toBeTruthy()
  })

  it('displays actual KIS valuation quotes without inventing an exchange trade timestamp', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{ ...candidate, market: { kis: { stockCode: '012345', technical: { status: 'PARTIAL_COVERAGE' }, valuation: { status: 'AVAILABLE', source: 'KIS OpenAPI', currentPrice: '48500', priceUnit: 'KRW', fetchedAt: '2026-10-14T16:04:00+09:00', observationTimeBasis: 'NOT_PROVIDED' }, financial: { status: 'UNAVAILABLE' }, investorEstimate: { status: 'UNAVAILABLE' }, shortSale: { status: 'UNAVAILABLE' }, loan: { status: 'UNAVAILABLE' } }, kiwoom: null } }] }))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    expect(within(card).getByText('48,500원')).toBeTruthy()
    expect(within(card).getByText('KIS OpenAPI')).toBeTruthy()
    expect(within(card).getByText('거래소 체결 시각 미제공')).toBeTruthy()
    expect(within(card).getByText(/16:04/)).toBeTruthy()
  })

  it('shows timestamped Kiwoom mock-server provider data beside KIS without treating it as a completed return', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{ ...candidate, market: { kis: null, kiwoom: { status: 'PARTIAL', source: 'KIWOOM_REST_MOCK', environment: 'MOCK', fetchedAt: '2026-10-14T16:05:00+09:00', quote: { status: 'AVAILABLE', source: 'KIWOOM_REST_MOCK', environment: 'MOCK', stockCode: '012345', stockName: '공시 공급사', currentPrice: '31500', priceUnit: 'KRW', fetchedAt: '2026-10-14T16:03:00+09:00', observationTimeBasis: 'NOT_PROVIDED' }, valuation: { status: 'UNAVAILABLE' }, technical: { status: 'UNAVAILABLE' }, investorDaily: { status: 'UNAVAILABLE' } } } }] }))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    expect(within(card).getByText('31,500원')).toBeTruthy()
    expect(within(card).getByText('모의서버 조회 자료')).toBeTruthy()
    expect(within(card).getByText(/16:03/)).toBeTruthy()
    expect(within(card).queryByText(/16:05/)).toBeNull()
    expect(within(card).queryByText('7.00%')).toBeNull()
  })

  it('accepts a timestamped provider quote price field and keeps invalid provider status unknown', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{ ...candidate, market: { kis: { valuation: { status: 'INVALID_RESPONSE', source: 'KIS_OPENAPI', currentPrice: '50000', fetchedAt: '2026-10-14T16:04:00+09:00' } }, kiwoom: { source: 'KIWOOM_REST', environment: 'REAL', quote: { status: 'AVAILABLE', source: 'KIWOOM_REST', price: '32000', fetchedAt: '2026-10-14T16:03:00+09:00', observedAt: '2026-10-14T15:30:00+09:00' } } } }] }))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    expect(within(card).getByText('32,000원')).toBeTruthy()
    expect(within(card).queryByText('50,000원')).toBeNull()
    expect(within(card).getByText(/15:30/)).toBeTruthy()
  })

  it('shows recovered current market evidence while keeping the original research publication timestamp', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{
      ...candidate, publishedAt: '2026-10-14T16:01:00+09:00',
      market: { kis: { valuation: { status: 'UNAVAILABLE', source: 'KIS OpenAPI', fetchedAt: '2026-10-14T16:04:00+09:00' } }, kiwoom: null },
      currentMarket: { kis: { valuation: { status: 'AVAILABLE', source: 'KIS OpenAPI', currentPrice: '50500', priceUnit: 'KRW', fetchedAt: '2026-10-15T10:05:00+09:00', observationTimeBasis: 'NOT_PROVIDED' } }, kiwoom: null },
    }] }))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    expect(within(card).getByText('최근 조회 근거')).toBeTruthy()
    expect(within(card).getByText('50,500원')).toBeTruthy()
    expect(within(card).getByText(/10:05/)).toBeTruthy()
    expect(within(card).getByText(/16:01/)).toBeTruthy()
    expect(within(card).getByText(/16:00/)).toBeTruthy()
    expect(within(card).queryByText(/16:04/)).toBeNull()
  })

  it('distinguishes the after-close research anchor and next-session simulation from disclosure and first-seen dates', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{ ...candidate, evaluationPublishedAt: '2026-10-14T16:30:00+09:00', evaluationPolicy: 'AFTER_CLOSE_NEXT_SESSION_OPEN_V1', publishedAt: '2026-10-14T16:01:00+09:00' }] }))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    expect(within(card).getByText('장마감 후 평가 기준시각')).toBeTruthy()
    expect(within(card).getByText(/16:30/)).toBeTruthy()
    expect(within(card).getByText(/다음 거래일 시가 진입 가정의 모의 성과/)).toBeTruthy()
  })

  it('presents the new customer filing before prior relationship evidence without reusing the old amount as new earnings', async () => {
    const customerUrl = 'https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261015000321'
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{
      ...candidate, origin: 'CUSTOMER_EVENT_PROPAGATION', contractAmountWon: null, contractDeltaWon: null,
      evidenceText: '이전 공시의 장비 공급 계약 120억 원으로 공급 관계를 확인함',
      relatedCustomerEvent: { receiptNo: '20261015000321', title: '고객사의 신규 시설 투자', sourceUrl: customerUrl, firstSeenAt: '2026-10-15T16:10:00+09:00', filingDate: '2026-10-15', status: 'TRANSFER_UNQUANTIFIED', issues: ['CUSTOMER_EVENT_ALLOCATION_UNKNOWN', 'FACT_NUMBERS_UNQUANTIFIED'] },
      // Even if a stale calculation carries old values, the relation alone is not a new contract.
      scenario: { ...candidate.scenario, status: 'UNKNOWN', incrementalContractAmountWon: '12000000000', recognizedRevenueWon: { lower: '6000000000', upper: '9000000000' }, operatingProfitWon: { lower: '300000000', upper: '900000000' } },
    }] }))
    renderPage()
    const card = await screen.findByRole('article', { name: '공시 공급사 사건 연구' })
    const details = within(card)
    expect(details.getByText('고객사 사건 연결 · 공급 물량·이익 영향은 추가 확인 필요')).toBeTruthy()
    expect(details.getByText('고객사의 신규 시설 투자')).toBeTruthy()
    const customerLink = details.getByRole('link', { name: '고객사 DART 공시 원문' })
    const contractLink = details.getByRole('link', { name: '공급 관계 확인 원문' })
    expect(customerLink.getAttribute('href')).toBe(customerUrl)
    expect(customerLink.compareDocumentPosition(contractLink) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(details.getByText('이전 공시의 장비 공급 계약 120억 원으로 공급 관계를 확인함')).toBeTruthy()
    expect(details.getByText('고객사 사건의 공급사 배정 물량 미확인')).toBeTruthy()
    expect(details.getByText('공시 수치의 공급사 이익 영향 미확인')).toBeTruthy()
    expect(details.queryByText('12,000,000,000원')).toBeNull()
    expect(details.queryByText('6,000,000,000원 ~ 9,000,000,000원')).toBeNull()
    expect(details.queryByText('300,000,000원 ~ 900,000,000원')).toBeNull()
  })

  it('shows missing policy comparison as waiting without creating policy names or measured returns', async () => {
    vi.mocked(fetch).mockResolvedValue(json(snapshot()))
    renderPage()
    const panel = await screen.findByRole('region', { name: '고정 정책 비교 연구' })
    expect(within(panel).getByText('저장된 정책 비교 자료가 없습니다.')).toBeTruthy()
    expect(within(panel).queryByRole('table')).toBeNull()
    expect(within(panel).queryByText(/0\.00%|BASELINE_FROZEN_V1/)).toBeNull()
  })

  it('displays captured policies and real counts while withholding uncompleted comparison returns', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), comparison: { ...comparison(), latestOutcomes: [{ state: 'PENDING', horizonSessions: 5, baselineGross: '8.25', challengerGross: '9.50', baselineNet: '7.00', challengerNet: '8.00', netDelta: '1.00', reasons: ['RETURN_PENDING:012345', 'REFERENCE_SIMULATION_NOT_REALIZED_PNL'] }] } }))
    renderPage()
    const panel = await screen.findByRole('region', { name: '고정 정책 비교 연구' })
    expect(within(panel).getByText('BASELINE_FROZEN_V1')).toBeTruthy()
    expect(within(panel).getByText('EVENT_RESEARCH_V1')).toBeTruthy()
    expect(within(panel).getByText('포착 2일')).toBeTruthy()
    expect(within(panel).getByText('측정 완료 0일')).toBeTruthy()
    expect(within(panel).getByText('독립 관측 0일')).toBeTruthy()
    expect(within(panel).getByText('연구 검증 중 · 기존 순위 유지')).toBeTruthy()
    expect(within(panel).getByText('검증 대기')).toBeTruthy()
    expect(within(panel).getByText('성과 완료 대기 (012345)')).toBeTruthy()
    expect(within(panel).queryByText(/8\.25%|9\.50%|7\.00%|8\.00%|1\.00%/)).toBeNull()
  })

  it('renders gross-only comparison with cost uncertainty instead of net results or mean delta', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), comparison: { ...comparison(), measuredDates: 1, meanPairedDeltaPct: '99.99', latestOutcomes: [{ state: 'GROSS_ONLY', horizonSessions: 3, baselineGross: '1.50', challengerGross: '2.25', baselineNet: '66.66', challengerNet: '77.77', netDelta: '11.11', reasons: ['COSTS_UNKNOWN_OR_UNVERIFIED'] }] } }))
    renderPage()
    const panel = await screen.findByRole('region', { name: '고정 정책 비교 연구' })
    expect(within(panel).getByText('비용 미확인 · 순수익률 비교 제외')).toBeTruthy()
    expect(within(panel).getByText('1.50%')).toBeTruthy()
    expect(within(panel).getByText('2.25%')).toBeTruthy()
    expect(within(panel).getByText('총수익률만 측정')).toBeTruthy()
    expect(within(panel).queryByText(/66\.66%|77\.77%|11\.11%|99\.99%/)).toBeNull()
    expect(within(panel).queryByRole('button')).toBeNull()
  })

  it('uses the stored percent units for completed cost-verified policy comparisons', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), comparison: { ...comparison(), status: 'REVIEW_CANDIDATE', costsVerified: true, measuredDates: 5, independentDates: 5, meanPairedDeltaPct: '0.75', latestOutcomes: [{ state: 'COMPLETE', horizonSessions: 5, baselineGross: '1.60', challengerGross: '2.70', baselineNet: '1.40', challengerNet: '2.50', netDelta: '1.10', reasons: ['NO_AUTOMATIC_PROMOTION'] }] } }))
    renderPage()
    const panel = await screen.findByRole('region', { name: '고정 정책 비교 연구' })
    expect(within(panel).getByText('1.40%')).toBeTruthy()
    expect(within(panel).getByText('2.50%')).toBeTruthy()
    expect(within(panel).getByText('1.10%p')).toBeTruthy()
    expect(within(panel).getByText('0.75%p')).toBeTruthy()
    expect(within(panel).getByText('연구 검증 중 · 기존 순위 유지')).toBeTruthy()
  })

  it('labels an unfillable outcome as unmeasurable rather than waiting for future data', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{ ...candidate, outcomes: [{ horizonSessions: 5, state: 'UNFILLABLE', grossReturnPct: null, netReturnPct: null, excessReturnPct: null, reasons: ['첫 거래일 진입 체결 불가'] }] }] }))
    renderPage()
    expect(await screen.findByText('5거래일 측정 불가')).toBeTruthy()
    expect(screen.queryByText('5거래일 검증 대기')).toBeNull()
  })

  it('reports an empty snapshot without inserting demonstration stocks', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), status: 'EMPTY', candidates: [] }))
    renderPage()
    expect(await screen.findByText('저장된 공급 계약 연구가 없습니다.')).toBeTruthy()
    expect(screen.queryByRole('article')).toBeNull()
  })

  it('preserves prior evidence when a refresh is stale or only partially covered', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), stale: true, issues: ['공시 원문 일부 확인 실패'] }))
    renderPage()
    expect(await screen.findByText('이전 저장 결과 · 최신 수집 확인 필요')).toBeTruthy()
    expect(screen.getByText('일부 자료만 확인됨')).toBeTruthy()
    expect(screen.getByText('공시 원문 일부 확인 실패')).toBeTruthy()
    expect(screen.getByText('공시 공급사')).toBeTruthy()
    expect(screen.getByText(/원문 확인 1건/)).toBeTruthy()
  })

  it('renders only completed measured returns and keeps unavailable costs or benchmarks unknown', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [{ ...candidate, outcomes: [{ horizonSessions: 3, state: 'COMPLETE', grossReturnPct: '2.50', netReturnPct: null, excessReturnPct: null, reasons: ['거래 비용 미확인', '벤치마크 미확인'] }] }] }))
    renderPage()
    expect(await screen.findByText('3거래일 측정 완료')).toBeTruthy()
    expect(screen.getByText('2.50%')).toBeTruthy()
    expect(screen.getByText('거래 비용 미확인')).toBeTruthy()
    expect(screen.getByText('벤치마크 미확인')).toBeTruthy()
  })

  it('shows a server failure with a stored-result retry instead of fallback candidates', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(json({ error: '연구 저장소를 읽지 못했습니다.' }, 503)).mockResolvedValueOnce(json({ ...snapshot(), candidates: [] }))
    renderPage()
    expect((await screen.findByRole('alert')).textContent).toContain('연구 저장소를 읽지 못했습니다.')
    expect(screen.queryByRole('article')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '저장 결과 다시 조회' }))
    expect(await screen.findByText('저장된 공급 계약 연구가 없습니다.')).toBeTruthy()
    expect(vi.mocked(fetch).mock.calls.every(([, options]) => options?.method === 'GET')).toBe(true)
  })

  it('offers login after a 401 and associates the unauthorized signal with this request token', async () => {
    const unauthorized = vi.fn()
    window.addEventListener('auth:unauthorized', unauthorized)
    try {
      vi.mocked(fetch).mockResolvedValue(json({ error: '로그인이 필요합니다.' }, 401))
      renderPage()
      expect(await screen.findByRole('link', { name: '다시 로그인' })).toBeTruthy()
      expect((unauthorized.mock.calls[0][0] as CustomEvent).detail.token).toBe('event-research-fixture-token')
    } finally { window.removeEventListener('auth:unauthorized', unauthorized) }
  })

  it('sends expired subscriptions through the app renewal gate', async () => {
    const renewal = vi.fn()
    window.addEventListener('subscription:required', renewal)
    try {
      vi.mocked(fetch).mockResolvedValue(json({ error: '이용권이 만료되었습니다.', code: 'SUBSCRIPTION_EXPIRED' }, 403))
      renderPage()
      expect(await screen.findByRole('link', { name: '이용권 확인' })).toBeTruthy()
      expect((renewal.mock.calls[0][0] as CustomEvent).detail.code).toBe('SUBSCRIPTION_EXPIRED')
    } finally { window.removeEventListener('subscription:required', renewal) }
  })

  it('allows only an admin to request collection and reads metadata separately from results', async () => {
    role = 'ADMIN'
    vi.mocked(fetch).mockImplementation(async (url, options) => {
      if (String(url).endsWith('/refresh')) return json({ accepted: true, runId: 'new-run', status: 'RUNNING' })
      if (String(url).endsWith('/status')) return json({ status: 'RUNNING', runId: 'new-run', running: true, lastCompletedAt: null, lastError: null, asOf: null, candidateCount: 0, stale: false, issues: [], coverage: {} })
      if (options?.method !== 'GET') throw new Error('Unexpected mutation')
      return json(snapshot())
    })
    renderPage()
    await screen.findByText('공시 공급사')
    expect(vi.mocked(fetch).mock.calls).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '공식 자료 수집 요청' }))
    expect(await screen.findByText('공식 자료 수집 중 · 완료된 저장 결과는 다시 조회할 수 있습니다.')).toBeTruthy()
    const refresh = vi.mocked(fetch).mock.calls.find(([url]) => String(url).endsWith('/refresh'))
    expect(refresh?.[1]?.method).toBe('POST')
    expect(new Headers(refresh?.[1]?.headers).get('Authorization')).toBe('Bearer event-research-fixture-token')
    fireEvent.click(screen.getByRole('button', { name: '수집 상태 확인' }))
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).endsWith('/status'))).toBe(true))
    expect(screen.getByRole('button', { name: '공식 자료 수집 요청' }).hasAttribute('disabled')).toBe(true)
  })

  it('shows actual provider coverage and separate official application and execution sources', async () => {
    const application = 'https://kind.krx.co.kr/external/2026/10/13/000734/20261013001629/70789.htm'
    const execution = 'https://kind.krx.co.kr/external/2026/10/13/000725/20261013001620/70800.htm'
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [], coverage: { contracts: 12, maxContracts: 12, financialReferences: 7, kisCandidates: 8, kiwoomCandidates: 5 }, buybacks: { status: 'PARTIAL', asOf: '2026-10-14', issues: ['HISTORY_WINDOW_MAX_THREE_REPORT_DAYS', 'BUYBACK_ROW_LIMIT'], rows: [{ stockCode: '014100', stockName: '실제 표 회사', applicationDate: '2026-10-14', applicationQuantity: '10000', executionDate: '2026-10-13', executionQuantity: '9871', executionAmountWon: '16812619', sourceUrl: application, executionSourceUrl: execution, historyDays: 1 }] } }))
    renderPage()
    expect(await screen.findByText(/계약 수집 12건 · 회차 상한 12건/)).toBeTruthy()
    expect(screen.getByText(/재무 근거 7건 · KIS 가격 8건 · 키움 조회 5건/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'KIND 신청 원문' }).getAttribute('href')).toBe(application)
    expect(screen.getByRole('link', { name: 'KIND 체결 원문' }).getAttribute('href')).toBe(execution)
    expect(screen.getByText('최근 최대 3개 공시일의 이행률 자료만 조회')).toBeTruthy()
    expect(screen.getByText('자사주 표시 상한 80건 · 시장별 최대 40건')).toBeTruthy()
  })

  it('does not treat a next-day buyback application as completed execution', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [], buybacks: { status: 'PARTIAL', asOf: '2026-10-14', issues: ['동일일 실행 자료 미확인'], rows: [{ market: 'KOSPI', stockCode: '012345', stockName: '공식 자사주 회사', applicationDate: '2026-10-15', applicationQuantity: '10000', executionDate: null, executionQuantity: null, executionAmountWon: null, fulfilmentRatePct: null, expectedQuantity: null, historyDays: 0, sourceUrl: 'https://kind.krx.co.kr/corpgeneral/treasurystock.do', status: 'APPLICATION_ONLY' }] } }))
    renderPage()
    const section = await screen.findByRole('region', { name: '자사주 신청과 실제 체결' })
    expect(within(section).getByText('공식 자사주 회사')).toBeTruthy()
    expect(within(section).getByText(/신청 수량 10,000주/)).toBeTruthy()
    expect(within(section).getByText('실제 체결 자료 없음')).toBeTruthy()
    expect(within(section).queryByText(/지지 가격 \d|100%/)).toBeNull()
  })
})

describe('research API request contract', () => {
  it('returns the actual empty response and carries auth and cancellation without initiating a refresh', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ...snapshot(), candidates: [] }))
    const controller = new AbortController()
    const result = await fetchEventResearch('explicit-fixture-token', controller.signal)
    expect(result.candidates).toEqual([])
    expect(vi.mocked(fetch).mock.calls).toHaveLength(1)
    const [url, options] = vi.mocked(fetch).mock.calls[0]
    expect(url).toContain('/api/research/events')
    expect(options?.method).toBe('GET')
    expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer explicit-fixture-token')
    expect(options?.signal).toBe(controller.signal)
  })

  it('reports admin refresh rejection without publishing an accepted result', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ error: '관리자 권한이 필요합니다.' }, 403))
    await expect(refreshEventResearch('user-fixture-token')).rejects.toThrow('관리자 권한이 필요합니다.')
  })
})
