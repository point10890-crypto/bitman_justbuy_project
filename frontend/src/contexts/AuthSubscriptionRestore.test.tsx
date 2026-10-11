// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth, type User } from './AuthContext'
import { fetchCurrentUser, type UserDto } from '../api/authApi'
import SubscribedRoute from '../components/guards/SubscribedRoute'
import { canUpgradeToSixMonths, isSixMonthUpgradePending } from '../lib/subscriptionUpgrade'

// Exercise the real storage restoration and normalization. Only the API is
// replaced; storage is the test's isolated jsdom storage, containing dummy data.
vi.mock('../api/authApi', () => ({
  fetchCurrentUser: vi.fn(),
  loginUser: vi.fn(),
  registerUser: vi.fn(),
  applySubscription: vi.fn(),
  updateProfile: vi.fn(),
  changePassword: vi.fn(),
}))
const networkMock = vi.fn<typeof fetch>()
const fixtureToken = 'auth-restore-fixture-dummy-token'

function cachedMonthlyUpgrade(overrides: Partial<User> = {}): User {
  return {
    id: 'restore-fixture', email: 'sample@example.invalid', name: '샘플회원', role: 'USER',
    subscription: 'pro', subscriptionPlan: 'MONTHLY', subscriptionEndDate: '2099-12-31',
    subscriptionApprovedAt: '2026-01-01', subscriptionRenewalPending: true,
    requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionRequestedAt: '2026-10-10T00:00:00Z',
    depositorName: '샘플입금자', createdAt: '2026-01-01', ...overrides,
  }
}

function AccountProbe() {
  const { user, isLoading } = useAuth()
  return <>
    <output data-testid="restore-loading">{String(isLoading)}</output>
    <output data-testid="restored-account">{JSON.stringify(user)}</output>
    {canUpgradeToSixMonths(user) && <button type="button">6개월 이용권으로 업그레이드</button>}
    {isSixMonthUpgradePending(user) && <p>샘플 업그레이드 승인 대기</p>}
  </>
}

function renderRestoration(cached: User) {
  localStorage.setItem('bitman_token', fixtureToken)
  localStorage.setItem('bitman_auth_user', JSON.stringify(cached))
  localStorage.setItem('bitman_remember', '1')
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/supply']}>
        <AccountProbe />
        <Routes>
          <Route path="/supply" element={<SubscribedRoute><p>샘플 유료 수급 화면</p></SubscribedRoute>} />
          <Route path="/subscribe" element={<p>샘플 승인 대기 화면</p>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

function restoredAccount(): User {
  return JSON.parse(screen.getByTestId('restored-account').textContent || 'null') as User
}

describe('cached subscription upgrade restoration', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.mocked(fetchCurrentUser).mockReset().mockRejectedValue(new Error('fixture offline'))
    networkMock.mockReset().mockRejectedValue(new Error('Unexpected network request in restore fixture'))
    vi.stubGlobal('fetch', networkMock)
  })
  afterEach(() => {
    cleanup()
    localStorage.clear()
    sessionStorage.clear()
    vi.unstubAllGlobals()
  })

  it('keeps the cached upgrade pending offline while the monthly period remains valid', async () => {
    renderRestoration(cachedMonthlyUpgrade())
    await waitFor(() => expect(screen.getByTestId('restore-loading').textContent).toBe('false'))

    expect(restoredAccount()).toMatchObject({
      subscription: 'pro', subscriptionPlan: 'MONTHLY', subscriptionRenewalPending: true,
      requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2099-12-31',
    })
    expect(screen.getByText('샘플 업그레이드 승인 대기')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '6개월 이용권으로 업그레이드' })).toBeNull()
    expect(screen.getByText('샘플 유료 수급 화면')).toBeTruthy()
    expect(fetchCurrentUser).toHaveBeenCalledExactlyOnceWith(fixtureToken)
    expect(networkMock).not.toHaveBeenCalled()
  })

  it('restores raw pending and blocks paid access when the cached monthly period has expired', async () => {
    renderRestoration(cachedMonthlyUpgrade({ subscriptionEndDate: '2000-01-01', subscriptionRequestedAt: '1999-12-31T00:00:00Z' }))
    await waitFor(() => expect(screen.getByTestId('restore-loading').textContent).toBe('false'))

    expect(restoredAccount()).toMatchObject({
      subscription: 'pending', subscriptionRenewalPending: false,
      subscriptionPlan: 'MONTHLY', requestedSubscriptionPlan: 'SIX_MONTHS',
      subscriptionRequestedAt: '1999-12-31T00:00:00Z',
    })
    expect(screen.getByText('샘플 업그레이드 승인 대기')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '6개월 이용권으로 업그레이드' })).toBeNull()
    expect(screen.queryByText('샘플 유료 수급 화면')).toBeNull()
    expect(await screen.findByText('샘플 승인 대기 화면')).toBeTruthy()
    expect(networkMock).not.toHaveBeenCalled()
  })

  it('uses fresh approved six-month server data to replace the cached monthly pending state', async () => {
    const approved: UserDto = {
      id: 'restore-fixture', email: 'sample@example.invalid', name: '샘플회원', role: 'USER',
      subscription: 'PRO', subscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2100-06-30',
      subscriptionApprovedAt: '2026-10-11', requestedSubscriptionPlan: null,
      subscriptionRequestedAt: null, depositorName: '샘플입금자', createdAt: '2026-01-01',
    }
    vi.mocked(fetchCurrentUser).mockResolvedValue(approved)
    renderRestoration(cachedMonthlyUpgrade())
    await waitFor(() => expect(screen.getByTestId('restore-loading').textContent).toBe('false'))

    expect(restoredAccount()).toMatchObject({ subscription: 'pro', subscriptionPlan: 'SIX_MONTHS', subscriptionRenewalPending: false, subscriptionEndDate: '2100-06-30' })
    expect(restoredAccount().requestedSubscriptionPlan).toBeUndefined()
    expect(screen.queryByText('샘플 업그레이드 승인 대기')).toBeNull()
    expect(screen.queryByRole('button', { name: '6개월 이용권으로 업그레이드' })).toBeNull()
    expect(screen.getByText('샘플 유료 수급 화면')).toBeTruthy()
    expect(JSON.parse(localStorage.getItem('bitman_auth_user') || '{}')).toMatchObject({ subscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2100-06-30' })
    expect(fetchCurrentUser).toHaveBeenCalledExactlyOnceWith(fixtureToken)
    expect(networkMock).not.toHaveBeenCalled()
  })
})
