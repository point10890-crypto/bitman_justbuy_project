// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import SubscribedRoute from '../components/guards/SubscribedRoute'
import SubscriptionGateWatcher from '../components/guards/SubscriptionGateWatcher'
import { notifySubscriptionGate } from '../api/subscriptionGate'

/**
 * 만료 회원이 실제로 재구독 화면으로 가는지 렌더링해서 확인한다.
 *
 * 두 경로가 있고 둘 다 확인해야 한다:
 *  1) 라우트 진입 시 — 클라이언트가 아는 구독 상태로 SubscribedRoute 가 막는다.
 *  2) 사용 중 만료 — 앱을 켜 둔 채 자정을 넘긴 경우. API 403 을 잡아 이동시킨다.
 * 2번이 오래 비어 있었다. 신호를 쏘는 API 모듈이 하나뿐이라 분석·성과 화면에서는
 * 아무 일도 일어나지 않았다.
 */

type TestUser = {
  id: string; email: string; name: string
  role: 'USER' | 'ADMIN'
  subscription: 'free' | 'pending' | 'pro'
  subscriptionEndDate?: string
  subscriptionApprovedAt?: string
  createdAt: string
}

let currentUser: TestUser | null = null
const refreshUser = vi.fn(() => Promise.resolve())

vi.mock('../contexts/AuthContext', () => ({
  useAuth: () => ({ user: currentUser, isLoading: false, refreshUser }),
  getStoredToken: () => 'test-token',
}))

function yesterday() {
  return new Date(Date.now() - 86400000).toISOString().slice(0, 10)
}
function nextWeek() {
  return new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10)
}

function LocationProbe() {
  const location = useLocation()
  const state = location.state as { renewal?: boolean; message?: string } | null
  return (
    <div>
      <span data-testid="path">{location.pathname}</span>
      <span data-testid="renewal">{String(!!state?.renewal)}</span>
      <span data-testid="message">{state?.message ?? ''}</span>
    </div>
  )
}

function renderApp(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <SubscriptionGateWatcher />
      <Routes>
        <Route path="/" element={<SubscribedRoute><div>유료 화면</div></SubscribedRoute>} />
        <Route path="/supply" element={<SubscribedRoute><div>수급 화면</div></SubscribedRoute>} />
        <Route path="/subscribe" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

const lapsedMember: TestUser = {
  id: 'u1', email: 'lapsed@test.local', name: '만료회원', role: 'USER',
  subscription: 'free', subscriptionEndDate: yesterday(),
  subscriptionApprovedAt: '2026-01-01T00:00:00', createdAt: '2026-01-01T00:00:00',
}

describe('만료 회원 → 재구독 페이지', () => {
  beforeEach(() => {
    currentUser = null
    refreshUser.mockClear()
  })

  // 설정 파일 없이 vitest 를 쓰면 자동 정리가 걸리지 않아 이전 렌더가 DOM 에 남는다.
  afterEach(cleanup)

  it('만료 회원이 유료 라우트에 들어가면 재구독 안내와 함께 이동한다', async () => {
    currentUser = lapsedMember

    renderApp('/supply')

    await waitFor(() => expect(screen.getByTestId('path').textContent).toBe('/subscribe'))
    expect(screen.getByTestId('renewal').textContent).toBe('true')
    expect(screen.getByTestId('message').textContent).toContain('재구독')
  })

  it('사용 중 만료되면 API 403 을 받아 재구독 페이지로 넘어간다', async () => {
    // 클라이언트는 아직 유효한 구독으로 알고 있다 — 라우트 가드는 통과한다.
    currentUser = { ...lapsedMember, subscription: 'pro', subscriptionEndDate: nextWeek() }

    renderApp('/supply')
    expect(screen.getByText('수급 화면')).toBeTruthy()

    // 서버가 만료를 알린다 (분석/성과 API 가 내려주는 그 응답).
    notifySubscriptionGate(403, JSON.stringify({
      error: 'PRO 구독이 만료되었습니다. 재구독 신청을 해주세요.',
      code: 'SUBSCRIPTION_EXPIRED',
    }))

    await waitFor(() => expect(screen.getByTestId('path').textContent).toBe('/subscribe'))
    expect(screen.getByTestId('renewal').textContent).toBe('true')
    expect(refreshUser).toHaveBeenCalled()
  })

  it('한 번도 구독한 적 없는 회원은 연장이 아니라 신규 구독으로 간다', async () => {
    currentUser = {
      ...lapsedMember, subscription: 'free',
      subscriptionEndDate: undefined, subscriptionApprovedAt: undefined,
    }

    renderApp('/supply')

    await waitFor(() => expect(screen.getByTestId('path').textContent).toBe('/subscribe'))
    expect(screen.getByTestId('renewal').textContent).toBe('false')
  })

  it('유효한 구독자는 이동시키지 않는다', async () => {
    currentUser = { ...lapsedMember, subscription: 'pro', subscriptionEndDate: nextWeek() }

    renderApp('/supply')

    expect(screen.getByText('수급 화면')).toBeTruthy()
  })

  it('관리자는 구독 403 이 와도 이동시키지 않는다', async () => {
    currentUser = { ...lapsedMember, role: 'ADMIN' }

    renderApp('/supply')
    expect(screen.getByText('수급 화면')).toBeTruthy()

    notifySubscriptionGate(403, JSON.stringify({ code: 'SUBSCRIPTION_EXPIRED', error: '만료' }))

    await new Promise(resolve => setTimeout(resolve, 50))
    expect(screen.getByText('수급 화면')).toBeTruthy()
  })
})
