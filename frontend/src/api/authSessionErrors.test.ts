// @vitest-environment jsdom
// @vitest-environment-options {"url":"https://fixture.invalid/login"}
import { createElement } from 'react'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchCurrentUser, fetchSubscriptionPlans, type UserDto } from './authApi'
import { ApiError } from './subscriptionGate'
import { AuthProvider, useAuth } from '../contexts/AuthContext'

const fixtureToken = 'api-error-fixture-dummy-token'
const fetchFixture = vi.fn<typeof fetch>()
const unauthorized = vi.fn<(event: Event) => void>()
let auth: ReturnType<typeof useAuth>

function AccountProbe() {
  auth = useAuth()
  return createElement('output', { 'data-testid': 'account' }, JSON.stringify({ user: auth.user, loading: auth.isLoading }))
}

const userFixture: UserDto = {
  id: 'api-fixture-A', name: '샘플 A', email: 'sample@example.invalid', role: 'USER',
  subscription: 'PRO', subscriptionPlan: 'MONTHLY', subscriptionEndDate: '2099-12-31',
  subscriptionRequestedAt: null, subscriptionApprovedAt: '2026-01-01',
  depositorName: null, createdAt: '2026-01-01',
}

async function renderRealApiSession() {
  localStorage.setItem('bitman_token', fixtureToken)
  fetchFixture.mockResolvedValueOnce(Response.json(userFixture))
  render(createElement(AuthProvider, null, createElement(AccountProbe)))
  await waitFor(() => expect(screen.getByTestId('account').textContent).toContain('"loading":false'))
}

describe('typed auth API failures', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    fetchFixture.mockReset()
    unauthorized.mockReset()
    vi.stubGlobal('fetch', fetchFixture)
    window.addEventListener('auth:unauthorized', unauthorized)
  })
  afterEach(() => {
    cleanup()
    localStorage.clear()
    sessionStorage.clear()
    window.removeEventListener('auth:unauthorized', unauthorized)
    vi.unstubAllGlobals()
  })

  it('attaches the request token to a 401 event and preserves its HTTP status', async () => {
    fetchFixture.mockResolvedValueOnce(new Response('', { status: 401 }))
    const failure = await fetchCurrentUser(fixtureToken).catch(error => error)
    expect(failure).toBeInstanceOf(ApiError)
    expect(failure.status).toBe(401)
    expect(unauthorized).toHaveBeenCalledTimes(1)
    expect((unauthorized.mock.calls[0][0] as CustomEvent).detail).toEqual({ token: fixtureToken })
  })

  it('preserves a server failure status without signaling expired authentication', async () => {
    fetchFixture.mockResolvedValueOnce(new Response('', { status: 503 }))
    const failure = await fetchCurrentUser(fixtureToken).catch(error => error)
    expect(failure).toBeInstanceOf(ApiError)
    expect(failure.status).toBe(503)
    expect(unauthorized).not.toHaveBeenCalled()
  })

  it('does not invalidate a signed-in session for a tokenless public-catalog 401', async () => {
    fetchFixture.mockResolvedValueOnce(new Response('', { status: 401 }))
    await expect(fetchSubscriptionPlans()).rejects.toThrow()
    expect(unauthorized).not.toHaveBeenCalled()
  })

  it('ignores the actual API 401 event when its deferred request belongs to an old account', async () => {
    await renderRealApiSession()
    let finishOldRequest!: (response: Response) => void
    fetchFixture.mockReturnValueOnce(new Promise<Response>(resolve => { finishOldRequest = resolve }))
    let refreshing!: Promise<void>
    act(() => { refreshing = auth.refreshUser() })
    const newToken = 'api-error-fixture-dummy-new-token'
    fetchFixture.mockResolvedValueOnce(Response.json({ token: newToken, user: { ...userFixture, id: 'api-fixture-B' } }))
    await act(async () => { await auth.login('new@example.invalid', 'fixture-dummy-password', true) })
    await act(async () => { finishOldRequest(new Response('', { status: 401 })); await refreshing })
    expect(auth.user?.id).toBe('api-fixture-B')
    expect(localStorage.getItem('bitman_token')).toBe(newToken)
    expect(JSON.parse(localStorage.getItem('bitman_auth_user') || '{}').id).toBe('api-fixture-B')
    expect((unauthorized.mock.calls[0][0] as CustomEvent).detail).toEqual({ token: fixtureToken })
  })

  it('clears the provider user and saved authentication for an actual current-token API 401', async () => {
    await renderRealApiSession()
    fetchFixture.mockResolvedValueOnce(new Response('', { status: 401 }))
    await act(async () => { await auth.refreshUser() })
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('bitman_token')).toBeNull()
    expect(localStorage.getItem('bitman_auth_user')).toBeNull()
    expect(unauthorized).toHaveBeenCalledTimes(1)
  })
})
