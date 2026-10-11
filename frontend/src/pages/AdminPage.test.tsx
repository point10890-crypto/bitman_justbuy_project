// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import {
  approveSubscription, fetchAllUsers, fetchPendingSubscriptions,
  fetchSystemStatus, rejectSubscription, type UserDto,
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
