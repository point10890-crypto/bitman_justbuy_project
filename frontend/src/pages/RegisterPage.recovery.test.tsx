// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import RegisterPage from './RegisterPage'
import { registerUser } from '../api/authApi'

vi.mock('../contexts/AuthContext', () => ({
  useAuth: () => ({ register: registerUser, user: null, isLoading: false }),
  isSubscriptionExpired: () => false,
}))
vi.mock('../components/app/PWAInstallPrompt', () => ({ PWAInstallPrompt: () => null }))
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals() })

it('releases the signup button and offers login when a successful signup response may have been lost', async () => {
  vi.useFakeTimers()
  let signal!: AbortSignal
  vi.stubGlobal('fetch', vi.fn((_url, init) => { signal = init.signal; return new Promise(() => {}) }))
  render(<MemoryRouter initialEntries={['/register?plan=SIX_MONTHS']}><RegisterPage /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('이름 / 닉네임'), { target: { value: '테스트' } })
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'fixture@example.invalid' } })
  fireEvent.change(screen.getByLabelText('비밀번호', { exact: true }), { target: { value: 'DummyPassword!123' } })
  fireEvent.change(screen.getByLabelText('비밀번호 확인'), { target: { value: 'DummyPassword!123' } })
  fireEvent.click(screen.getByLabelText('전체 동의'))
  fireEvent.click(screen.getByRole('button', { name: '회원가입 후 구독 신청' }))
  expect((screen.getByRole('button', { name: '가입 처리 중...' }) as HTMLButtonElement).disabled).toBe(true)
  await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
  expect(screen.getByRole('alert').textContent).toContain('이미 가입됐을 수 있으니')
  expect((screen.getByRole('button', { name: '회원가입 후 구독 신청' }) as HTMLButtonElement).disabled).toBe(false)
  expect(screen.getByRole('link', { name: '로그인으로 가입 확인' }).getAttribute('href')).toContain('plan=SIX_MONTHS')
  expect(signal.aborted).toBe(true)
})
