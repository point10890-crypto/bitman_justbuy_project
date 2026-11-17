// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider } from '../contexts/AuthContext'
import PublicOnlyRoute from '../components/guards/PublicOnlyRoute'
import SubscribedRoute from '../components/guards/SubscribedRoute'
import LandingPage from './LandingPage'
import RegisterPage from './RegisterPage'
import LoginPage from './LoginPage'
import SubscribePage from './SubscribePage'
import { SUBSCRIPTION_PLANS } from '../lib/subscriptionPlans'

vi.mock('../components/app/PWAInstallPrompt', () => ({ PWAInstallPrompt: () => null }))
const network = vi.fn<typeof fetch>()
let member: Record<string, unknown>
let loseApplicationResponse = false
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200 })

// Real pages, guards, AuthProvider, storage and API adapters; only HTTP is a
// fixture. Never creates a production account or submits a real payment request.
describe('public entry through authenticated subscription workflow', () => {
  beforeEach(() => {
    localStorage.clear(); sessionStorage.clear(); loseApplicationResponse = false
    member = { id: 'fixture', name: '샘플회원', email: 'fixture@example.invalid', role: 'USER', subscription: 'FREE', createdAt: '2026-01-01' }
    network.mockReset().mockImplementation(async (input, init) => {
      const path = String(input)
      if (path.endsWith('/subscription/plans')) return json(SUBSCRIPTION_PLANS)
      if (path.endsWith('/auth/register') || path.endsWith('/auth/login')) return json({ token: 'fixture-token', user: member })
      if (path.endsWith('/auth/me')) return json(member)
      if (path.endsWith('/subscription/apply')) {
        expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer fixture-token')
        const request = JSON.parse(String(init?.body))
        member = { ...member, subscription: 'PENDING', requestedSubscriptionPlan: request.plan, depositorName: request.depositorName }
        if (loseApplicationResponse) throw new TypeError('fixture response lost after commit')
        return json({ user: member })
      }
      throw new Error(`Unexpected fixture request: ${path}`)
    })
    vi.stubGlobal('fetch', network)
  })
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear() })

  it.each(['register', 'login', 'response-lost'])('completes %s from the open page with the chosen plan and restores pending state', async mode => {
    loseApplicationResponse = mode === 'response-lost'
    render(<AuthProvider><MemoryRouter initialEntries={['/']}><Routes>
      <Route path="/" element={<SubscribedRoute><p>회원 홈</p></SubscribedRoute>} />
      <Route path="/landing" element={<PublicOnlyRoute><LandingPage /></PublicOnlyRoute>} />
      <Route path="/register" element={<PublicOnlyRoute><RegisterPage /></PublicOnlyRoute>} />
      <Route path="/login" element={<PublicOnlyRoute><LoginPage /></PublicOnlyRoute>} />
      <Route path="/subscribe" element={<SubscribePage />} />
    </Routes></MemoryRouter></AuthProvider>)
    fireEvent.click(await screen.findByRole('link', { name: '6개월 구독 선택' }))
    fireEvent.click(await screen.findByRole('link', { name: mode === 'login' ? /이미 계정이/ : /무료 회원가입/ }))
    if (mode !== 'login') {
      fireEvent.change(screen.getByLabelText('이름 / 닉네임'), { target: { value: '샘플회원' } })
      fireEvent.change(screen.getByLabelText('비밀번호 확인'), { target: { value: 'FixturePassword123!' } })
      fireEvent.click(screen.getByLabelText('전체 동의'))
    }
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'fixture@example.invalid' } })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'FixturePassword123!' } })
    fireEvent.click(screen.getByRole('button', { name: mode === 'login' ? '로그인' : '회원가입 후 구독 신청' }))
    const submit = await screen.findByRole('button', { name: '구독 신청하기' })
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false))
    expect((screen.getByRole('radio', { name: /6개월/ }) as HTMLInputElement).checked).toBe(true)
    fireEvent.click(submit)
    expect(await screen.findByRole('heading', { name: '구독 승인 대기 중' })).toBeTruthy()
    expect(screen.getByRole('region', { name: '신청 및 입금 정보' }).textContent).toContain('150,000원')
    expect(network.mock.calls.filter(([url]) => String(url).endsWith('/subscription/apply'))).toHaveLength(1)
    expect(JSON.parse(localStorage.getItem('bitman_auth_user') || '{}').subscription).toBe('pending')
  })
})
