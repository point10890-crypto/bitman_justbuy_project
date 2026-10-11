// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import {
  adminUpdateUser, approveSubscription, fetchAllUsers, fetchPendingSubscriptions,
  fetchSystemStatus, refreshAllAnalysis, rejectSubscription, type UserDto,
} from '../api/authApi'
import AdminPage from './AdminPage'

vi.mock('../contexts/AuthContext', () => ({
  getStoredToken: () => 'admin-upgrade-fixture-token',
  useAuth: () => ({ logout: vi.fn() }),
}))
vi.mock('../api/authApi', () => ({
  approveSubscription: vi.fn(),
  adminResetPassword: vi.fn(),
  adminUpdateUser: vi.fn(),
  expireSubscriptionsNow: vi.fn(),
  fetchAllUsers: vi.fn(),
  fetchPendingSubscriptions: vi.fn(),
  fetchSystemStatus: vi.fn(),
  refreshAllAnalysis: vi.fn(),
  rejectSubscription: vi.fn(),
  revokeSubscription: vi.fn(),
}))

function pendingMember(overrides: Partial<UserDto> = {}): UserDto {
  return {
    id: 'sample-upgrade-member',
    name: 'Sample upgrade member',
    email: 'upgrade@example.invalid',
    role: 'USER',
    subscription: 'PENDING',
    subscriptionPlan: 'MONTHLY',
    requestedSubscriptionPlan: 'SIX_MONTHS',
    depositorName: 'Sample payer',
    subscriptionRequestedAt: '2026-10-10T12:00:00',
    subscriptionApprovedAt: '2026-10-01T12:00:00',
    subscriptionEndDate: '2026-10-31',
    createdAt: '2026-10-01T12:00:00',
    ...overrides,
  }
}

async function renderPendingRow(user: UserDto) {
  vi.mocked(fetchAllUsers).mockResolvedValue([user])
  vi.mocked(fetchPendingSubscriptions).mockResolvedValue([user])
  render(<MemoryRouter initialEntries={['/admin?view=approvals']}><AdminPage /></MemoryRouter>)
  const row = (await screen.findByText(user.name)).closest('tr')!
  return within(row)
}

describe('admin approval details for monthly to six-month upgrades', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-11T06:00:00+09:00'))
    vi.mocked(fetchSystemStatus).mockResolvedValue({})
  })
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  it('identifies a legacy monthly upgrade and shows the full payment with retained access', async () => {
    const row = await renderPendingRow(pendingMember({ subscriptionPlan: null }))

    expect(row.getByText('1개월 → 6개월 업그레이드')).toBeTruthy()
    expect(row.getByText('6개월 · 150,000원 · 일시 납부')).toBeTruthy()
    expect(row.getByText(/현재 만료일.*이용 유지/)).toBeTruthy()
    expect(row.getByText('승인 시 기존 만료일에 6개월 추가')).toBeTruthy()
    expect(row.getByText('오늘 승인 시 만료일 2027. 4. 30.')).toBeTruthy()
    expect(row.getByRole('button', { name: '승인' })).toBeTruthy()
    expect(row.getByRole('button', { name: '반려' })).toBeTruthy()
    expect(approveSubscription).not.toHaveBeenCalled()
    expect(rejectSubscription).not.toHaveBeenCalled()
  })

  it('keeps the upgrade classification after expiry while removing the claim of current access', async () => {
    const row = await renderPendingRow(pendingMember({ subscriptionEndDate: '2026-10-10', subscriptionRequestedAt: '2026-10-09T12:00:00' }))

    expect(row.getByText('1개월 → 6개월 업그레이드')).toBeTruthy()
    expect(row.getByText(/기존 만료일.*이용 기간 종료/)).toBeTruthy()
    expect(row.queryByText(/이용 유지/)).toBeNull()
    expect(row.getByText('승인일부터 6개월 이용')).toBeTruthy()
    expect(row.getByText('오늘 승인 시 만료일 2027. 4. 11.')).toBeTruthy()
  })

  it('shows a six-month re-subscription submitted after expiry without labeling it an active upgrade', async () => {
    const row = await renderPendingRow(pendingMember({ subscriptionEndDate: '2026-10-10', subscriptionRequestedAt: '2026-10-11T06:00:00' }))

    expect(row.getByText('6개월 · 150,000원')).toBeTruthy()
    expect(row.queryByText('1개월 → 6개월 업그레이드')).toBeNull()
    expect(row.queryByText(/이용 유지/)).toBeNull()
  })
})

describe('admin analysis refresh recovery', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchAllUsers).mockResolvedValue([])
    vi.mocked(fetchPendingSubscriptions).mockResolvedValue([])
    vi.mocked(fetchSystemStatus).mockResolvedValue({ conditionRuns: [{
      runId: 'failed-run', traceId: 'failed-trace', trigger: 'ADMIN_REFRESH', mode: 'CATALYST_BURST', query: null,
      status: 'FAILED', attempt: 1, startedAt: '2026-10-11T12:00:00Z', updatedAt: '2026-10-11T12:01:00Z', finishedAt: '2026-10-11T12:01:00Z',
      errorCode: 'EXECUTION_FAILED', errorMessage: 'Analysis provider timed out', pickCount: null, agentsUsed: null, agentsSucceeded: null,
    }] })
  })
  afterEach(cleanup)

  async function openSystem() {
    render(<MemoryRouter initialEntries={['/admin?view=system']}><AdminPage /></MemoryRouter>)
    return screen.findByRole('button', { name: '조건검색식 / 분석 캐시 새로고침' })
  }

  it('shows the failed mode and retries it without recomputing successful modes', async () => {
    vi.mocked(refreshAllAnalysis).mockResolvedValueOnce({ status: 'partial', results: { BREAKOUT: 'success', CATALYST_BURST: 'error: Analysis provider timed out' } })
      .mockResolvedValueOnce({ status: 'ok', results: { CATALYST_BURST: 'success' } })
    fireEvent.click(await openSystem())

    const results = await screen.findByRole('region', { name: '분석 캐시 갱신 결과' })
    expect(within(results).getByText('BREAKOUT')).toBeTruthy()
    expect(within(results).getByText('CATALYST_BURST')).toBeTruthy()
    expect(within(results).getByText('error: Analysis provider timed out')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '실패한 모드만 재시도' }))

    await waitFor(() => expect(refreshAllAnalysis).toHaveBeenLastCalledWith('admin-upgrade-fixture-token', ['CATALYST_BURST']))
    await waitFor(() => expect(screen.queryByRole('button', { name: '실패한 모드만 재시도' })).toBeNull())
    expect(within(results).getAllByText('성공')).toHaveLength(2)
  })

  it('does not report completion when all attempted modes failed', async () => {
    vi.mocked(refreshAllAnalysis).mockResolvedValue({ status: 'error', results: { BREAKOUT: 'error: market unavailable', CATALYST_BURST: 'error: provider unavailable' } })
    fireEvent.click(await openSystem())

    expect(await screen.findByText(/갱신 실패.*2개/)).toBeTruthy()
    const results = await screen.findByRole('region', { name: '분석 캐시 갱신 결과' })
    expect(within(results).queryByText('성공')).toBeNull()
    expect(screen.getByRole('button', { name: '실패한 모드만 재시도' })).toBeTruthy()
  })

  it('shows the recorded failure cause before any refresh is requested', async () => {
    await openSystem()
    expect(await screen.findByText('Analysis provider timed out')).toBeTruthy()
    expect(screen.getByText('EXECUTION_FAILED')).toBeTruthy()
    expect(refreshAllAnalysis).not.toHaveBeenCalled()
  })

  it('uses explicit mixed outcomes and never treats terminal partial runs as active failures', async () => {
    vi.mocked(fetchSystemStatus).mockResolvedValue({
      analysisStats: { totalAnalyses: 4, completionRate: 25, outcomeCounts: { COMPLETE: 1, PARTIAL: 1, EMPTY: 1, FAILED: 1 } },
      recentAnalyses: [
        { mode: 'EMPTY_MODE', timestamp: '2026-10-11T12:00:00Z', success: false, outcome: 'EMPTY' },
        { mode: 'PARTIAL_MODE', timestamp: '2026-10-11T12:00:00Z', success: false, outcome: 'PARTIAL' },
        { mode: 'FAILED_MODE', timestamp: '2026-10-11T12:00:00Z', success: false, outcome: 'FAILED' },
        { mode: 'COMPLETE_MODE', timestamp: '2026-10-11T12:00:00Z', success: true, outcome: 'COMPLETE' },
      ],
      conditionRuns: [{ runId: 'partial-run', traceId: 'partial-trace', trigger: 'ADMIN_REFRESH', mode: 'PARTIAL_MODE', query: null,
        status: 'PARTIAL', attempt: 1, startedAt: '2026-10-11T12:00:00Z', updatedAt: '2026-10-11T12:01:00Z', finishedAt: '2026-10-11T12:01:00Z',
        errorCode: null, errorMessage: null, pickCount: 1, agentsUsed: 2, agentsSucceeded: 1 }],
    })
    render(<MemoryRouter initialEntries={['/admin?view=system']}><AdminPage /></MemoryRouter>)

    const results = await screen.findByRole('region', { name: '분석 실행 결과' })
    expect(within(results).getByText('전체 완료율 25%')).toBeTruthy()
    for (const [mode, label] of [['EMPTY_MODE', '추천 없음'], ['PARTIAL_MODE', '부분 완료'], ['FAILED_MODE', '실패'], ['COMPLETE_MODE', '완료']]) {
      const row = within(results).getByText(mode).closest('tr')!
      expect(within(row).getByText(label)).toBeTruthy()
    }
    expect(screen.getByText('No active run')).toBeTruthy()
  })
})

describe('admin data source readiness', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchAllUsers).mockResolvedValue([])
    vi.mocked(fetchPendingSubscriptions).mockResolvedValue([])
  })
  afterEach(cleanup)

  it('explains disabled sources, KIS rights and the missing ECOS key without activation controls', async () => {
    vi.mocked(fetchSystemStatus).mockResolvedValue({ dataSources: [
      { id: 'KIS', name: 'KIS 추가 근거', enabled: false, credentialsConfigured: true, state: 'DISABLED', message: '유료 회원 앱의 KIS 이용 권한 확인이 필요합니다.' },
      { id: 'DART', name: 'DART 공시 근거', enabled: false, credentialsConfigured: true, state: 'DISABLED', message: '신규 공시 근거는 비활성 상태입니다.' },
      { id: 'ECOS', name: '한국은행 ECOS', enabled: false, credentialsConfigured: false, state: 'DISABLED', message: 'ECOS 인증키 발급과 서버 설정이 필요합니다.' },
    ] })
    render(<MemoryRouter initialEntries={['/admin?view=system']}><AdminPage /></MemoryRouter>)

    const sources = await screen.findByRole('region', { name: '신규 데이터 소스 준비 상태' })
    expect(within(sources).getByText('KIS 추가 근거')).toBeTruthy()
    expect(within(sources).getByText(/KIS 이용 권한 확인/)).toBeTruthy()
    expect(within(sources).getByText(/ECOS 인증키 발급/)).toBeTruthy()
    expect(within(sources).getAllByText('비활성')).toHaveLength(3)
    expect(within(sources).getByText('실제 연결 미검증')).toBeTruthy()
    expect(within(sources).queryByRole('button')).toBeNull()
    expect(refreshAllAnalysis).not.toHaveBeenCalled()
  })
})

describe('admin member editor preserves concurrent subscription requests', () => {
  const activeMember = pendingMember({ subscription: 'PRO', requestedSubscriptionPlan: null, subscriptionEndDate: '2050-10-31' })
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchAllUsers).mockResolvedValue([activeMember])
    vi.mocked(fetchPendingSubscriptions).mockResolvedValue([])
    vi.mocked(fetchSystemStatus).mockResolvedValue({})
  })
  afterEach(cleanup)

  async function openEditor() {
    render(<MemoryRouter initialEntries={['/admin?view=members']}><AdminPage /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: '수정' }))
    return screen.getByLabelText('회원명').closest('form')!
  }

  it('omits an unchanged subscription when a newer six-month request arrives during profile editing', async () => {
    const form = await openEditor()
    const requestedMember = pendingMember({ name: 'Updated member', subscriptionEndDate: '2050-10-31' })
    vi.mocked(fetchAllUsers).mockResolvedValue([requestedMember])
    vi.mocked(fetchPendingSubscriptions).mockResolvedValue([requestedMember])
    vi.mocked(adminUpdateUser).mockResolvedValue(requestedMember)
    fireEvent.change(screen.getByLabelText('회원명'), { target: { value: 'Updated member' } })
    fireEvent.submit(form)

    await waitFor(() => expect(adminUpdateUser).toHaveBeenCalledWith('admin-upgrade-fixture-token', activeMember.id, {
      name: 'Updated member', email: 'upgrade@example.invalid',
    }))
    const row = (await screen.findByText('Updated member')).closest('tr')!
    expect(within(row).getByText('PENDING')).toBeTruthy()
    expect(approveSubscription).not.toHaveBeenCalled()
  })

  it('sends the subscription only when the administrator explicitly changes the selected status', async () => {
    const form = await openEditor()
    vi.mocked(adminUpdateUser).mockResolvedValue({ ...activeMember, subscription: 'FREE' })
    fireEvent.change(screen.getByLabelText('등급'), { target: { value: 'FREE' } })
    fireEvent.submit(form)

    await waitFor(() => expect(adminUpdateUser).toHaveBeenCalledWith('admin-upgrade-fixture-token', activeMember.id, {
      name: activeMember.name, email: activeMember.email, subscription: 'FREE',
    }))
  })
})
