// @vitest-environment jsdom
// @vitest-environment-options {"url":"https://fixture.invalid/login"}
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth, type User } from './AuthContext'
import { applySubscription, fetchCurrentUser, loginUser, registerUser, updateProfile, type AuthResponse, type UserDto } from '../api/authApi'
import { ApiError } from '../api/subscriptionGate'
import SubscribedRoute from '../components/guards/SubscribedRoute'

vi.mock('../api/authApi', () => ({
  fetchCurrentUser: vi.fn(), loginUser: vi.fn(), registerUser: vi.fn(),
  applySubscription: vi.fn(), updateProfile: vi.fn(), changePassword: vi.fn(),
}))

const tokenA = 'session-fixture-dummy-A'
const tokenB = 'session-fixture-dummy-B'
let auth: ReturnType<typeof useAuth>
const network = vi.fn<typeof fetch>()

function serverUser(overrides: Partial<UserDto> = {}): UserDto {
  return {
    id: 'fixture-A', email: 'fixture-A@example.invalid', name: '샘플 A', role: 'USER',
    subscription: 'PRO', subscriptionPlan: 'MONTHLY', subscriptionEndDate: '2099-12-31',
    subscriptionApprovedAt: '2026-01-01', subscriptionRequestedAt: null,
    requestedSubscriptionPlan: null, depositorName: '샘플입금자', createdAt: '2026-01-01',
    ...overrides,
  }
}

function cacheUser(): User {
  return {
    id: 'fixture-A', email: 'fixture-A@example.invalid', name: '샘플 A', role: 'USER',
    subscription: 'pro', subscriptionPlan: 'MONTHLY', subscriptionEndDate: '2099-12-31',
    subscriptionApprovedAt: '2026-01-01', depositorName: '샘플입금자', createdAt: '2026-01-01',
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function Probe() {
  auth = useAuth()
  return <>
    <output data-testid="loading">{String(auth.isLoading)}</output>
    <output data-testid="account">{JSON.stringify(auth.user)}</output>
  </>
}

function renderSession(withToken = true) {
  if (withToken) localStorage.setItem('bitman_token', tokenA)
  localStorage.setItem('bitman_auth_user', JSON.stringify(cacheUser()))
  localStorage.setItem('bitman_remember', '1')
  render(<AuthProvider><MemoryRouter initialEntries={['/supply']}>
    <Probe />
    <Routes>
      <Route path="/supply" element={<SubscribedRoute><p>샘플 유료 화면</p></SubscribedRoute>} />
      <Route path="/subscribe" element={<p>샘플 구독 화면</p>} />
      <Route path="/login" element={<p>샘플 로그인 화면</p>} />
    </Routes>
  </MemoryRouter></AuthProvider>)
}

async function readySession() {
  vi.mocked(fetchCurrentUser).mockResolvedValue(serverUser())
  renderSession()
  await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'))
}

function account(): User | null {
  return JSON.parse(screen.getByTestId('account').textContent || 'null') as User | null
}

function expectLoggedOut() {
  expect(account()).toBeNull()
  expect(localStorage.getItem('bitman_token')).toBeNull()
  expect(sessionStorage.getItem('bitman_token')).toBeNull()
  expect(localStorage.getItem('bitman_auth_user')).toBeNull()
  expect(screen.queryByText('샘플 유료 화면')).toBeNull()
  expect(screen.getByText('샘플 로그인 화면')).toBeTruthy()
}

describe('authentication recovery and response ownership', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.mocked(fetchCurrentUser).mockReset()
    vi.mocked(loginUser).mockReset()
    vi.mocked(registerUser).mockReset()
    vi.mocked(applySubscription).mockReset()
    vi.mocked(updateProfile).mockReset()
    network.mockReset().mockRejectedValue(new Error('Network forbidden in isolated auth fixture'))
    vi.stubGlobal('fetch', network)
  })
  afterEach(() => {
    cleanup()
    localStorage.clear()
    sessionStorage.clear()
    expect(network).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it.each([
    ['network failure', new Error('fixture offline')],
    ['temporary server failure', new ApiError(503, 'fixture unavailable')],
  ])('retains cached authentication after %s and lets a later refresh recover', async (_label, error) => {
    vi.mocked(fetchCurrentUser).mockRejectedValueOnce(error)
    renderSession()
    await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'))
    expect(localStorage.getItem('bitman_token')).toBe(tokenA)
    expect(JSON.parse(localStorage.getItem('bitman_auth_user') || '{}')).toMatchObject({ id: 'fixture-A' })
    expect(account()?.id).toBe('fixture-A')
    expect(screen.getByText('샘플 유료 화면')).toBeTruthy()

    vi.mocked(fetchCurrentUser).mockResolvedValueOnce(serverUser({ subscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2100-06-30' }))
    await act(async () => { await auth.refreshUser() })
    expect(fetchCurrentUser).toHaveBeenCalledTimes(2)
    expect(account()).toMatchObject({ subscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2100-06-30' })
    expect(localStorage.getItem('bitman_token')).toBe(tokenA)
  })

  it('clears both state and saved authentication for a real current-session 401', async () => {
    vi.mocked(fetchCurrentUser).mockRejectedValueOnce(new ApiError(401, 'fixture invalid token'))
    renderSession()
    await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'))
    await waitFor(expectLoggedOut)
  })

  it('does not grant access from a user cache that has no token', async () => {
    renderSession(false)
    await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'))
    expectLoggedOut()
    expect(fetchCurrentUser).not.toHaveBeenCalled()
  })

  it('ignores an initial user response that arrives after logout', async () => {
    const boot = deferred<UserDto>()
    vi.mocked(fetchCurrentUser).mockReturnValueOnce(boot.promise)
    renderSession()
    await act(async () => { auth.logout() })
    expect(screen.getByTestId('loading').textContent).toBe('false')
    expectLoggedOut()
    await act(async () => { boot.resolve(serverUser()); await boot.promise })
    expectLoggedOut()
  })

  it('ends restoration loading when a new login succeeds before an old boot request completes', async () => {
    const boot = deferred<UserDto>()
    vi.mocked(fetchCurrentUser).mockReturnValueOnce(boot.promise)
    renderSession()
    vi.mocked(loginUser).mockResolvedValueOnce({ token: tokenB, user: serverUser({ id: 'fixture-B' }) })
    await act(async () => { await auth.login('fixture-B@example.invalid', 'fixture-dummy-password', true) })
    expect(screen.getByTestId('loading').textContent).toBe('false')
    expect(account()?.id).toBe('fixture-B')
    expect(screen.getByText('샘플 유료 화면')).toBeTruthy()
    await act(async () => { boot.resolve(serverUser()); await boot.promise })
    expect(account()?.id).toBe('fixture-B')
    expect(localStorage.getItem('bitman_token')).toBe(tokenB)
  })

  it.each(['login', 'register'] as const)('does not restore authentication when a delayed %s completes after logout', async operation => {
    await readySession()
    const response = deferred<AuthResponse>()
    let result!: Promise<unknown>
    act(() => {
      if (operation === 'login') {
        vi.mocked(loginUser).mockReturnValueOnce(response.promise)
        result = auth.login('fixture-B@example.invalid', 'fixture-dummy-password', true).catch(error => error)
      } else {
        vi.mocked(registerUser).mockReturnValueOnce(response.promise)
        result = auth.register('샘플 B', 'fixture-B@example.invalid', 'fixture-dummy-password').catch(error => error)
      }
      auth.logout()
    })
    await act(async () => { response.resolve({ token: tokenB, user: serverUser({ id: 'fixture-B' }) }); await result })
    expect(await result).toBeInstanceOf(Error)
    expectLoggedOut()
  })

  it('preserves session-only authentication through an offline restore', async () => {
    vi.mocked(fetchCurrentUser).mockRejectedValueOnce(new Error('fixture offline'))
    localStorage.setItem('bitman_remember', '0')
    sessionStorage.setItem('bitman_token', tokenA)
    sessionStorage.setItem('bitman_auth_user', JSON.stringify(cacheUser()))
    render(<AuthProvider><Probe /></AuthProvider>)
    await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'))
    expect(account()?.id).toBe('fixture-A')
    expect(sessionStorage.getItem('bitman_token')).toBe(tokenA)
    expect(localStorage.getItem('bitman_token')).toBeNull()
    expect(JSON.parse(sessionStorage.getItem('bitman_auth_user') || '{}').id).toBe('fixture-A')
  })

  it.each(['logout', 'current-session 401'] as const)('ignores a pending refresh after %s', async reason => {
    await readySession()
    const read = deferred<UserDto>()
    vi.mocked(fetchCurrentUser).mockReturnValueOnce(read.promise)
    let refreshing!: Promise<void>
    act(() => { refreshing = auth.refreshUser() })
    await act(async () => {
      if (reason === 'logout') auth.logout()
      else window.dispatchEvent(new CustomEvent('auth:unauthorized', { detail: { token: tokenA } }))
    })
    await act(async () => { read.resolve(serverUser()); await refreshing })
    expectLoggedOut()
  })

  it('retains the new account when an old account refresh and its 401 arrive late', async () => {
    await readySession()
    const oldRead = deferred<UserDto>()
    vi.mocked(fetchCurrentUser).mockReturnValueOnce(oldRead.promise)
    let refreshing!: Promise<void>
    act(() => { refreshing = auth.refreshUser() })
    vi.mocked(loginUser).mockResolvedValueOnce({ token: tokenB, user: serverUser({ id: 'fixture-B', email: 'fixture-B@example.invalid', name: '샘플 B' }) })
    await act(async () => { await auth.login('fixture-B@example.invalid', 'fixture-dummy-password', true) })
    await act(async () => {
      window.dispatchEvent(new CustomEvent('auth:unauthorized', { detail: { token: tokenA } }))
      oldRead.resolve(serverUser())
      await refreshing
    })
    expect(account()?.id).toBe('fixture-B')
    expect(localStorage.getItem('bitman_token')).toBe(tokenB)
    expect(JSON.parse(localStorage.getItem('bitman_auth_user') || '{}').id).toBe('fixture-B')
    expect(screen.getByText('샘플 유료 화면')).toBeTruthy()
  })

  it.each(['subscription application', 'profile update'] as const)('does not let an older user read overwrite a completed %s', async operation => {
    await readySession()
    const oldRead = deferred<UserDto>()
    vi.mocked(fetchCurrentUser).mockReturnValueOnce(oldRead.promise)
    let refreshing!: Promise<void>
    act(() => { refreshing = auth.refreshUser() })
    if (operation === 'subscription application') {
      vi.mocked(applySubscription).mockResolvedValueOnce(serverUser({ subscription: 'PENDING', requestedSubscriptionPlan: 'SIX_MONTHS', subscriptionRequestedAt: '2026-10-11T00:00:00Z' }))
      await act(async () => { await auth.applySubscription('샘플입금자', 'SIX_MONTHS') })
    } else {
      vi.mocked(updateProfile).mockResolvedValueOnce(serverUser({ name: '변경된 샘플 이름' }))
      await act(async () => { await auth.updateProfile('변경된 샘플 이름') })
    }
    await act(async () => { oldRead.resolve(serverUser()); await refreshing })
    const saved = JSON.parse(localStorage.getItem('bitman_auth_user') || '{}')
    if (operation === 'subscription application') {
      expect(account()).toMatchObject({ subscriptionRenewalPending: true, requestedSubscriptionPlan: 'SIX_MONTHS' })
      expect(saved.requestedSubscriptionPlan).toBe('SIX_MONTHS')
    } else {
      expect(account()?.name).toBe('변경된 샘플 이름')
      expect(saved.name).toBe('변경된 샘플 이름')
    }
  })

  it('shares an in-flight user refresh between a button and resumed-tab reconciliation', async () => {
    await readySession()
    const read = deferred<UserDto>()
    vi.mocked(fetchCurrentUser).mockReturnValue(read.promise)
    let first!: Promise<void>
    let second!: Promise<void>
    act(() => {
      first = auth.refreshUser()
      second = auth.refreshUser()
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(fetchCurrentUser).toHaveBeenCalledTimes(2)
    await act(async () => { read.resolve(serverUser()); await Promise.all([first, second]) })
    expect(account()?.id).toBe('fixture-A')
  })
})
