// @vitest-environment jsdom
import { createContext, useContext, useState, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import type { User } from '../contexts/AuthContext'
import { fetchSubscriptionPlans, type SubscriptionPlanDto } from '../api/authApi'
import { isSubscriptionExpired } from '../lib/memberTier'
import PublicOnlyRoute from '../components/guards/PublicOnlyRoute'
import SubscribedRoute from '../components/guards/SubscribedRoute'
import SubscribePage from './SubscribePage'
import LoginPage from './LoginPage'
import RegisterPage from './RegisterPage'
import MyPage from './MyPage'

type PlanId = 'MONTHLY' | 'SIX_MONTHS'
type FixtureUser = User
type FixtureAuth = {
  user: FixtureUser | null
  isGuest: boolean
  isLoading: boolean
  login: (email: string, password: string, rememberMe?: boolean) => Promise<FixtureUser>
  register: (name: string, email: string, password: string) => Promise<void>
  applySubscription: (name: string, plan?: PlanId) => Promise<void>
  refreshUser: () => Promise<void>
  updateProfile: (name: string) => Promise<void>
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>
  logout: () => void
}
const FixtureAuthContext = createContext<FixtureAuth | null>(null)
const loginMock = vi.fn<(email: string, password: string, rememberMe?: boolean) => Promise<FixtureUser>>()
const registerMock = vi.fn<(name: string, email: string, password: string) => Promise<void>>()
const applyMock = vi.fn<(name: string, plan: PlanId) => Promise<void>>()
const refreshMock = vi.fn<() => Promise<FixtureUser | void>>()
const updateProfileMock = vi.fn<(name: string) => Promise<void>>()
const changePasswordMock = vi.fn<(currentPassword: string, newPassword: string) => Promise<void>>()

// Real pages and router run together; only their auth boundary and platform
// install prompt are fixtures. No token, network, or real account is read.
vi.mock('../contexts/AuthContext', () => ({
  useAuth: () => useContext(FixtureAuthContext),
  getStoredToken: () => 'subscription-flow-fixture-token',
  isSubscriptionExpired: (date?: string) => isSubscriptionExpired(date),
}))
vi.mock('../components/app/PWAInstallPrompt', () => ({ PWAInstallPrompt: () => null }))
vi.mock('../api/authApi', () => ({
  fetchSubscriptionPlans: vi.fn(),
}))
const validCatalog: SubscriptionPlanDto[] = [
  { id: 'MONTHLY', label: '1개월', months: 1, price: 30000 },
  { id: 'SIX_MONTHS', label: '6개월', months: 6, price: 150000 },
]

function freeUser(overrides: Partial<FixtureUser> = {}): FixtureUser {
  return {
    id: 'subscription-flow-fixture', email: 'sample@example.invalid', name: '샘플회원',
    role: 'USER', subscription: 'free', createdAt: '2026-01-01T00:00:00Z', ...overrides,
  }
}
function expiredUser(plan: PlanId = 'SIX_MONTHS', overrides: Partial<FixtureUser> = {}): FixtureUser {
  return freeUser({
    subscriptionExpired: true, subscriptionPlan: plan,
    subscriptionEndDate: '2000-01-01', subscriptionApprovedAt: '1999-12-01',
    depositorName: '샘플입금자', ...overrides,
  })
}
function activeMonthlyUser(overrides: Partial<FixtureUser> = {}): FixtureUser {
  return freeUser({
    subscription: 'pro', subscriptionPlan: 'MONTHLY', subscriptionEndDate: '2099-12-31',
    subscriptionApprovedAt: '2026-01-01', depositorName: '샘플입금자', ...overrides,
  })
}
function FixtureAuthProvider({ initialUser, children }: { initialUser: FixtureUser | null; children: ReactNode }) {
  const [user, setUser] = useState(initialUser)
  const login: FixtureAuth['login'] = async (...args) => {
    const next = await loginMock(...args)
    setUser(next)
    return next
  }
  const register: FixtureAuth['register'] = async (name, email, password) => {
    await registerMock(name, email, password)
    setUser(freeUser({ name, email }))
  }
  const applySubscription: FixtureAuth['applySubscription'] = async (name, plan = 'MONTHLY') => {
    await applyMock(name, plan)
    setUser(previous => previous && {
      ...previous,
      subscription: previous.subscription === 'pro' ? 'pro' : 'pending',
      subscriptionRenewalPending: previous.subscription === 'pro',
      requestedSubscriptionPlan: plan,
    })
  }
  const refreshUser: FixtureAuth['refreshUser'] = async () => {
    const next = await refreshMock()
    if (next) setUser(next)
  }
  return (
    <FixtureAuthContext.Provider value={{ user, isGuest: !user, isLoading: false, login, register, applySubscription, refreshUser, updateProfile: updateProfileMock, changePassword: changePasswordMock, logout: () => setUser(null) }}>
      {children}
    </FixtureAuthContext.Provider>
  )
}
function LocationProbe() {
  const location = useLocation()
  return <>
    <output data-testid="flow-route">{location.pathname}{location.search}</output>
    <Link to="/supply">샘플 유료 수급 확인</Link>
    <Link to="/my">샘플 계정 페이지</Link>
  </>
}
function renderFlow(path: string, user: FixtureUser | null = null, renewal = false) {
  const url = new URL(path, 'https://fixture.invalid')
  return render(
    <FixtureAuthProvider initialUser={user}>
      <MemoryRouter initialEntries={[{ pathname: url.pathname, search: url.search, state: renewal ? { renewal: true } : undefined }]}>
        <LocationProbe />
        <Routes>
          <Route path="/subscribe" element={<SubscribePage />} />
          <Route path="/register" element={<PublicOnlyRoute><RegisterPage /></PublicOnlyRoute>} />
          <Route path="/login" element={<PublicOnlyRoute><LoginPage /></PublicOnlyRoute>} />
          <Route path="/" element={<div>샘플 앱 홈</div>} />
          <Route path="/my" element={<MyPage />} />
          <Route path="/supply" element={<SubscribedRoute><div>샘플 유료 수급 화면</div></SubscribedRoute>} />
        </Routes>
      </MemoryRouter>
    </FixtureAuthProvider>,
  )
}
function selectedPrice() {
  return document.querySelector('.subscription-price')?.textContent ?? ''
}
function fillRegistration() {
  fireEvent.change(screen.getByLabelText('이름 / 닉네임'), { target: { value: '샘플회원' } })
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'sample@example.invalid' } })
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'SampleFixture123!' } })
  fireEvent.change(screen.getByLabelText('비밀번호 확인'), { target: { value: 'SampleFixture123!' } })
  fireEvent.click(screen.getByLabelText('전체 동의'))
}
async function chooseSixMonths() {
  fireEvent.click(await screen.findByRole('radio', { name: /6개월/ }))
}
async function submitSubscription() {
  fireEvent.change(screen.getByLabelText('입금자명'), { target: { value: '샘플입금자' } })
  const name = /^(?:(?:재)?구독 신청하기|6개월 업그레이드 신청하기)$/
  await waitFor(() => expect((screen.getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(false))
  fireEvent.click(screen.getByRole('button', { name }))
}
function expectSixMonthPaymentDetails() {
  const payment = screen.getByRole('region', { name: '신청 및 입금 정보' })
  expect(within(payment).getByRole('heading', { name: '신청 및 입금 정보', level: 2 })).toBeTruthy()
  for (const value of ['6개월', '150,000원', '국민은행', '2259-02-04-057670', '이종민', '샘플입금자']) {
    expect(payment.textContent).toContain(value)
  }
  expect(within(payment).getByRole('button', { name: '계좌번호 복사' })).toBeTruthy()
}

describe('subscription selection through public auth and renewal pages', () => {
  beforeEach(() => {
    vi.mocked(fetchSubscriptionPlans).mockReset().mockResolvedValue(validCatalog)
    loginMock.mockReset().mockResolvedValue(freeUser())
    registerMock.mockReset().mockResolvedValue(undefined)
    applyMock.mockReset().mockResolvedValue(undefined)
    refreshMock.mockReset().mockResolvedValue(undefined)
    updateProfileMock.mockReset().mockResolvedValue(undefined)
    changePasswordMock.mockReset().mockResolvedValue(undefined)
    localStorage.clear()
  })
  afterEach(cleanup)

  it('shows a guest the six-month price and preserves the plan through registration', async () => {
    renderFlow('/subscribe?plan=SIX_MONTHS')
    await waitFor(() => expect(selectedPrice()).toContain('150,000원'))
    fireEvent.click(screen.getByRole('link', { name: /회원가입/ }))
    expect(screen.getByTestId('flow-route').textContent).toBe('/register?plan=SIX_MONTHS')
    fillRegistration()
    fireEvent.click(screen.getByRole('button', { name: /회원가입 후 구독 신청/ }))

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/subscribe?plan=SIX_MONTHS'))
    expect(registerMock).toHaveBeenCalledWith('샘플회원', 'sample@example.invalid', 'SampleFixture123!')
    expect(selectedPrice()).toContain('150,000원')
    expect((screen.getByRole('radio', { name: /6개월/ }) as HTMLInputElement).checked).toBe(true)
  })

  it('keeps the six-month renewal choice when an existing PRO member logs in', async () => {
    loginMock.mockResolvedValue(freeUser({ subscription: 'pro', subscriptionPlan: 'MONTHLY', subscriptionEndDate: '2099-12-31', subscriptionApprovedAt: '2026-01-01' }))
    renderFlow('/subscribe?plan=SIX_MONTHS')
    fireEvent.click(await screen.findByRole('link', { name: /로그인/ }))
    expect(screen.getByTestId('flow-route').textContent).toBe('/login?plan=SIX_MONTHS')
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'sample@example.invalid' } })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'SampleFixture123!' } })
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/subscribe?plan=SIX_MONTHS&renewal=1'))
    expect(loginMock).toHaveBeenCalledOnce()
    expect(selectedPrice()).toContain('150,000원')
  })

  it('sends the selected plan and depositor name, then prevents another pending request', async () => {
    renderFlow('/subscribe', freeUser())
    await chooseSixMonths()
    expect(selectedPrice()).toContain('150,000원')
    await submitSubscription()

    await waitFor(() => expect(screen.getByRole('heading', { name: /승인 대기/ })).toBeTruthy())
    expect(applyMock).toHaveBeenCalledExactlyOnceWith('샘플입금자', 'SIX_MONTHS')
    expect(screen.queryByRole('button', { name: '구독 신청하기' })).toBeNull()
  })

  it('shows the actual pending six-month request even when the URL chooses monthly', () => {
    renderFlow('/subscribe?plan=MONTHLY', freeUser({ subscription: 'pending', requestedSubscriptionPlan: 'SIX_MONTHS', depositorName: '샘플입금자' }))
    const pending = screen.getByRole('heading', { name: /승인 대기/ }).closest('section')!
    expect(pending.textContent).toContain('6개월')
    expect(pending.textContent).toContain('150,000원')
    expectSixMonthPaymentDetails()
    expect(screen.queryByRole('button', { name: '구독 신청하기' })).toBeNull()
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('lets an expired monthly member choose six months for renewal', async () => {
    renderFlow('/subscribe', freeUser({ subscriptionExpired: true, subscriptionPlan: 'MONTHLY', subscriptionEndDate: '2000-01-01', subscriptionApprovedAt: '1999-12-01' }), true)
    await chooseSixMonths()
    await submitSubscription()

    await waitFor(() => expect(applyMock).toHaveBeenCalledExactlyOnceWith('샘플입금자', 'SIX_MONTHS'))
    expect(screen.getByRole('heading', { name: /승인 대기/ })).toBeTruthy()
  })

  it('lets a current PRO member request six-month renewal and return to account management', async () => {
    renderFlow('/subscribe?plan=SIX_MONTHS', freeUser({ subscription: 'pro', subscriptionPlan: 'MONTHLY', subscriptionEndDate: '2099-12-31', subscriptionApprovedAt: '2026-01-01' }), true)
    expect(selectedPrice()).toContain('150,000원')
    await submitSubscription()

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/my'))
    expect(applyMock).toHaveBeenCalledExactlyOnceWith('샘플입금자', 'SIX_MONTHS')
  })

  it('keeps an unqualified legacy subscription URL on the monthly 30,000 won plan', async () => {
    renderFlow('/subscribe', freeUser())
    expect(selectedPrice()).toContain('30,000원')
    await submitSubscription()

    await waitFor(() => expect(applyMock).toHaveBeenCalledExactlyOnceWith('샘플입금자', 'MONTHLY'))
    expect(screen.getByRole('heading', { name: /승인 대기/ })).toBeTruthy()
  })

  it('opens the app after a pending six-month request is approved, instead of reopening renewal', async () => {
    renderFlow('/subscribe?plan=SIX_MONTHS', freeUser({ subscription: 'pending', requestedSubscriptionPlan: 'SIX_MONTHS' }))
    refreshMock.mockResolvedValue(freeUser({ subscription: 'pro', subscriptionPlan: 'SIX_MONTHS', requestedSubscriptionPlan: undefined, subscriptionEndDate: '2099-12-31', subscriptionApprovedAt: '2026-01-01' }))
    fireEvent.click(screen.getByRole('button', { name: '승인 상태 새로고침' }))

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/'))
    expect(screen.getByText('샘플 앱 홈')).toBeTruthy()
    expect(applyMock).not.toHaveBeenCalled()
    expect(refreshMock).toHaveBeenCalledOnce()
  })

  it('blocks a mismatched catalog price and enables the six-month application after retry', async () => {
    vi.mocked(fetchSubscriptionPlans).mockResolvedValueOnce(validCatalog.map(plan => plan.id === 'SIX_MONTHS' ? { ...plan, price: 30000 } : plan))
    renderFlow('/subscribe?plan=SIX_MONTHS', freeUser())
    const retry = await screen.findByRole('button', { name: '이용권 다시 확인' })
    const blockedSubmit = screen.getByRole('button', { name: '구독 신청하기' }) as HTMLButtonElement

    expect(blockedSubmit.disabled).toBe(true)
    blockedSubmit.click()
    expect(applyMock).not.toHaveBeenCalled()
    fireEvent.click(retry)

    await waitFor(() => expect((screen.getByRole('button', { name: '구독 신청하기' }) as HTMLButtonElement).disabled).toBe(false))
    expect(selectedPrice()).toContain('150,000원')
    expect(vi.mocked(fetchSubscriptionPlans)).toHaveBeenCalledTimes(2)
    await submitSubscription()
    await waitFor(() => expect(applyMock).toHaveBeenCalledExactlyOnceWith('샘플입금자', 'SIX_MONTHS'))
  })

  it('sends an unlimited PRO member home without displaying a bank account or application form', async () => {
    renderFlow('/subscribe?plan=SIX_MONTHS', freeUser({ subscription: 'pro', subscriptionEndDate: undefined, subscriptionApprovedAt: '2026-01-01' }))

    expect(screen.queryByText('입금 계좌')).toBeNull()
    expect(screen.queryByRole('button', { name: '구독 신청하기' })).toBeNull()
    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/'))
    expect(screen.getByText('샘플 앱 홈')).toBeTruthy()
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('shows a pending six-month member the payment details on the actual account page', () => {
    renderFlow('/my', freeUser({ subscription: 'pending', requestedSubscriptionPlan: 'SIX_MONTHS', depositorName: '샘플입금자' }))

    expectSixMonthPaymentDetails()
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('keeps the current PRO expiry while showing the six-month renewal payment details', () => {
    renderFlow('/my', freeUser({ subscription: 'pro', subscriptionRenewalPending: true, subscriptionPlan: 'MONTHLY', requestedSubscriptionPlan: 'SIX_MONTHS', depositorName: '샘플입금자', subscriptionEndDate: '2099-12-31', subscriptionApprovedAt: '2026-01-01' }))

    expectSixMonthPaymentDetails()
    expect(screen.getAllByText('2099-12-31').length).toBeGreaterThan(0)
    expect(screen.getAllByText('연장 승인대기').length).toBeGreaterThan(0)
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('does not show another payment request for a regular active PRO member', () => {
    renderFlow('/my', freeUser({ subscription: 'pro', subscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2099-12-31', subscriptionApprovedAt: '2026-01-01' }))

    expect(screen.queryByRole('region', { name: '신청 및 입금 정보' })).toBeNull()
    expect(screen.queryByText('2259-02-04-057670')).toBeNull()
    expect(screen.queryByRole('button', { name: '계좌번호 복사' })).toBeNull()
    expect(screen.getByText('2099-12-31')).toBeTruthy()
  })

  it('removes the pending payment details when account status refresh confirms approval', async () => {
    renderFlow('/my', freeUser({ subscription: 'pending', requestedSubscriptionPlan: 'SIX_MONTHS', depositorName: '샘플입금자' }))
    expectSixMonthPaymentDetails()
    refreshMock.mockResolvedValue(freeUser({ subscription: 'pro', subscriptionPlan: 'SIX_MONTHS', requestedSubscriptionPlan: undefined, subscriptionEndDate: '2099-12-31', subscriptionApprovedAt: '2026-01-01' }))

    fireEvent.click(screen.getByRole('button', { name: '승인 상태 새로고침' }))

    await waitFor(() => expect(screen.queryByRole('region', { name: '신청 및 입금 정보' })).toBeNull())
    expect(screen.queryByText('2259-02-04-057670')).toBeNull()
    expect(screen.queryByRole('button', { name: '계좌번호 복사' })).toBeNull()
    expect(screen.getAllByText('구독중').length).toBeGreaterThan(0)
    expect(screen.getByTestId('flow-route').textContent).toBe('/my')
    expect(refreshMock).toHaveBeenCalledOnce()
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('restores the expired six-month plan after an ordinary login without a selected plan', async () => {
    loginMock.mockResolvedValue(expiredUser())
    renderFlow('/login')
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'sample@example.invalid' } })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'SampleFixture123!' } })
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/subscribe?plan=SIX_MONTHS&renewal=1'))
    expect((screen.getByRole('radio', { name: /6개월/ }) as HTMLInputElement).checked).toBe(true)
    expect(selectedPrice()).toContain('150,000원')
    expect(loginMock).toHaveBeenCalledOnce()
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('keeps a plain guest login free of an implicit monthly plan and restores the previous six-month plan', async () => {
    loginMock.mockResolvedValue(expiredUser())
    renderFlow('/subscribe')
    fireEvent.click(await screen.findByRole('link', { name: '이미 계정이 있나요? 로그인' }))

    expect(screen.getByTestId('flow-route').textContent).toBe('/login')
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'sample@example.invalid' } })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'SampleFixture123!' } })
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/subscribe?plan=SIX_MONTHS&renewal=1'))
    expect((screen.getByRole('radio', { name: /6개월/ }) as HTMLInputElement).checked).toBe(true)
    expect(selectedPrice()).toContain('150,000원')
    expect(loginMock).toHaveBeenCalledOnce()
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('opens plain subscription on the previous six-month plan with an expired membership summary', async () => {
    renderFlow('/subscribe', expiredUser('SIX_MONTHS', { subscriptionExpired: undefined }))

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/subscribe?plan=SIX_MONTHS&renewal=1'))
    expect(screen.getByRole('heading', { name: '재구독 신청', level: 1 })).toBeTruthy()
    expect((screen.getByRole('radio', { name: /6개월/ }) as HTMLInputElement).checked).toBe(true)
    expect(selectedPrice()).toContain('150,000원')
    expect(screen.getByText('2000-01-01')).toBeTruthy()
    expect((screen.getByLabelText('입금자명') as HTMLInputElement).value).toBe('샘플입금자')
    expect(screen.getByRole('button', { name: '재구독 신청하기' })).toBeTruthy()
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('keeps the last six-month plan when an expired member starts renewal from their account', async () => {
    renderFlow('/my', expiredUser('SIX_MONTHS', { subscriptionExpired: undefined }))
    fireEvent.click(screen.getByRole('button', { name: '재구독 신청' }))

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/subscribe?plan=SIX_MONTHS&renewal=1'))
    expect(screen.getByRole('heading', { name: '재구독 신청', level: 1 })).toBeTruthy()
    expect((screen.getByRole('radio', { name: /6개월/ }) as HTMLInputElement).checked).toBe(true)
    expect(selectedPrice()).toContain('150,000원')
  })

  it('honors an explicit monthly choice over an expired six-month plan and submits monthly', async () => {
    renderFlow('/subscribe?plan=MONTHLY', expiredUser())

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/subscribe?plan=MONTHLY&renewal=1'))
    expect((screen.getByRole('radio', { name: /1개월/ }) as HTMLInputElement).checked).toBe(true)
    expect(selectedPrice()).toContain('30,000원')
    expect(screen.getByRole('button', { name: '재구독 신청하기' })).toBeTruthy()
    await submitSubscription()

    await waitFor(() => expect(screen.getByRole('heading', { name: '재구독 승인 대기 중' })).toBeTruthy())
    expect(applyMock).toHaveBeenCalledExactlyOnceWith('샘플입금자', 'MONTHLY')
    expect(screen.queryByRole('button', { name: /^(?:재)?구독 신청하기$/ })).toBeNull()
  })

  it('blocks another expired renewal while pending and resumes the app after approval', async () => {
    renderFlow('/subscribe?plan=MONTHLY&renewal=1', expiredUser('MONTHLY', { subscription: 'pending', requestedSubscriptionPlan: 'SIX_MONTHS' }))

    expect(screen.getByRole('heading', { name: '재구독 승인 대기 중' })).toBeTruthy()
    expectSixMonthPaymentDetails()
    expect(screen.queryByRole('radio')).toBeNull()
    expect(screen.queryByRole('button', { name: /^(?:재)?구독 신청하기$/ })).toBeNull()
    expect(applyMock).not.toHaveBeenCalled()
    refreshMock.mockResolvedValue(freeUser({ subscription: 'pro', subscriptionPlan: 'SIX_MONTHS', requestedSubscriptionPlan: undefined, subscriptionEndDate: '2099-12-31', subscriptionApprovedAt: '2026-01-01' }))
    fireEvent.click(screen.getByRole('button', { name: '승인 상태 새로고침' }))

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/'))
    expect(screen.getByText('샘플 앱 홈')).toBeTruthy()
    expect(refreshMock).toHaveBeenCalledOnce()
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('opens the dedicated six-month upgrade from an active monthly account with its current expiry preserved', async () => {
    renderFlow('/my', activeMonthlyUser())
    fireEvent.click(screen.getByRole('button', { name: '6개월 이용권으로 업그레이드' }))

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/subscribe?plan=SIX_MONTHS&renewal=1'))
    expect(screen.getByRole('heading', { name: '6개월 이용권 업그레이드', level: 1 })).toBeTruthy()
    const summary = screen.getByRole('region', { name: '6개월 업그레이드 안내' })
    for (const value of ['1개월', '2099-12-31', '2100-06-30', '150,000원']) expect(summary.textContent).toContain(value)
    expect((screen.getByLabelText('입금자명') as HTMLInputElement).value).toBe('샘플입금자')
    expect(screen.getByText(/국민은행/)).toBeTruthy()
    expect(screen.getByRole('button', { name: '6개월 업그레이드 신청하기' })).toBeTruthy()
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('restores an explicit upgrade URL on entry and switches back to ordinary monthly renewal when monthly is selected', async () => {
    renderFlow('/subscribe?plan=SIX_MONTHS&renewal=1', activeMonthlyUser())

    expect(screen.getByRole('heading', { name: '6개월 이용권 업그레이드', level: 1 })).toBeTruthy()
    expect((screen.getByRole('radio', { name: /6개월/ }) as HTMLInputElement).checked).toBe(true)
    expect(selectedPrice()).toContain('150,000원')
    fireEvent.click(screen.getByRole('radio', { name: /1개월/ }))

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/subscribe?plan=MONTHLY&renewal=1'))
    expect(screen.getByRole('heading', { name: '이용권 연장', level: 1 })).toBeTruthy()
    expect(screen.queryByRole('region', { name: '6개월 업그레이드 안내' })).toBeNull()
    expect(screen.queryByRole('button', { name: '6개월 업그레이드 신청하기' })).toBeNull()
    expect(selectedPrice()).toContain('30,000원')
    await submitSubscription()
    await waitFor(() => expect(applyMock).toHaveBeenCalledExactlyOnceWith('샘플입금자', 'MONTHLY'))
  })

  it('submits six-month upgrade once, retains monthly access while pending, and displays the approved six-month expiry after refresh', async () => {
    renderFlow('/subscribe?plan=SIX_MONTHS&renewal=1', activeMonthlyUser())
    expect(screen.getByRole('button', { name: '6개월 업그레이드 신청하기' })).toBeTruthy()
    await submitSubscription()

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toBe('/my'))
    expect(applyMock).toHaveBeenCalledExactlyOnceWith('샘플입금자', 'SIX_MONTHS')
    expect(screen.getByRole('heading', { name: '6개월 업그레이드 승인 대기' })).toBeTruthy()
    expectSixMonthPaymentDetails()
    expect(screen.getAllByText('2099-12-31').length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: '6개월 이용권으로 업그레이드' })).toBeNull()
    expect(screen.queryByRole('button', { name: '6개월 업그레이드 신청하기' })).toBeNull()

    fireEvent.click(screen.getByRole('link', { name: '샘플 유료 수급 확인' }))
    await waitFor(() => expect(screen.getByText('샘플 유료 수급 화면')).toBeTruthy())
    expect(screen.getByTestId('flow-route').textContent).toBe('/supply')
    fireEvent.click(screen.getByRole('link', { name: '샘플 계정 페이지' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: '6개월 업그레이드 승인 대기' })).toBeTruthy())

    refreshMock.mockResolvedValue(activeMonthlyUser({ subscriptionPlan: 'SIX_MONTHS', subscriptionEndDate: '2100-06-30', requestedSubscriptionPlan: undefined, subscriptionRenewalPending: false }))
    fireEvent.click(screen.getByRole('button', { name: '승인 상태 새로고침' }))

    await waitFor(() => expect(screen.queryByRole('region', { name: '신청 및 입금 정보' })).toBeNull())
    expect(screen.getByText('2100-06-30')).toBeTruthy()
    expect(screen.getByText('6개월')).toBeTruthy()
    expect(screen.getAllByText('구독중').length).toBeGreaterThan(0)
    expect(screen.queryByRole('heading', { name: '6개월 업그레이드 승인 대기' })).toBeNull()
    expect(screen.queryByRole('button', { name: '6개월 이용권으로 업그레이드' })).toBeNull()
    expect(screen.getByTestId('flow-route').textContent).toBe('/my')
    expect(refreshMock).toHaveBeenCalledOnce()
    expect(applyMock).toHaveBeenCalledOnce()
  })

  it('keeps a six-month request made before monthly expiry as an upgrade but blocks paid access after expiry', async () => {
    const pendingUser = {
      ...expiredUser('MONTHLY', { subscription: 'pending', requestedSubscriptionPlan: 'SIX_MONTHS' }),
      subscriptionRequestedAt: '1999-12-31T00:00:00Z',
    }
    renderFlow('/supply', pendingUser)

    await waitFor(() => expect(screen.getByTestId('flow-route').textContent).toMatch(/^\/subscribe(?:\?|$)/))
    expect(screen.queryByText('샘플 유료 수급 화면')).toBeNull()
    expect(screen.getByRole('heading', { name: '6개월 업그레이드 승인 대기 중', level: 1 })).toBeTruthy()
    expectSixMonthPaymentDetails()
    expect(screen.queryByRole('button', { name: '6개월 업그레이드 신청하기' })).toBeNull()
    expect(screen.queryByRole('button', { name: '6개월 이용권으로 업그레이드' })).toBeNull()
    expect(applyMock).not.toHaveBeenCalled()
  })

  it.each([
    ['an existing six-month member', activeMonthlyUser({ subscriptionPlan: 'SIX_MONTHS' })],
    ['an administrator', activeMonthlyUser({ role: 'ADMIN' })],
    ['an unlimited member', activeMonthlyUser({ subscriptionEndDate: undefined })],
    ['an expired monthly member', expiredUser('MONTHLY')],
    ['a member awaiting renewal approval', activeMonthlyUser({ subscriptionRenewalPending: true, requestedSubscriptionPlan: 'SIX_MONTHS' })],
    ['a pending member without current access', expiredUser('MONTHLY', { subscription: 'pending', requestedSubscriptionPlan: 'SIX_MONTHS' })],
  ] as const)('does not offer another six-month upgrade to %s', (_label, user) => {
    renderFlow('/my', user)

    expect(screen.queryByRole('button', { name: '6개월 이용권으로 업그레이드' })).toBeNull()
    expect(screen.queryByRole('button', { name: '6개월 업그레이드 신청하기' })).toBeNull()
    expect(applyMock).not.toHaveBeenCalled()
  })
})
