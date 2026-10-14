// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import AppLayout from './AppLayout'
vi.mock('../contexts/AuthContext', () => ({ useAuth: () => ({ user: {role:'ADMIN'}, logout:vi.fn() }) }))
vi.mock('../hooks/useMarketData', () => ({ useMarketData: () => ({data:[],refresh:vi.fn()}) }))
vi.mock('../hooks/useMainConditions', () => ({ useMainConditions: () => ({data:null,refetch:vi.fn()}) }))
vi.mock('../components/app/PWAInstallPrompt', () => ({ PWAInstallPrompt: () => null }))
afterEach(cleanup)
it('offers five mobile destinations and keeps admin in the more menu', () => {
  render(<MemoryRouter><Routes><Route element={<AppLayout/>}><Route path="/" element={<p>홈 본문</p>}/><Route path="/my" element={<p>계정 본문</p>}/></Route></Routes></MemoryRouter>)
  const nav = within(screen.getByRole('navigation', {name:'모바일 주요 메뉴'}))
  expect(nav.getAllByRole('button').map(b => b.textContent)).toEqual(['홈','조건검색','알림','마이','더보기'])
  fireEvent.click(nav.getByRole('button',{name:'더보기'}))
  expect(screen.getByRole('menuitem',{name:/ADMIN/})).toBeTruthy()
  fireEvent.keyDown(window,{key:'Escape'})
  expect(screen.queryByRole('menu')).toBeNull()
  fireEvent.click(nav.getByRole('button',{name:'마이'}))
  expect(screen.getByText('계정 본문')).toBeTruthy()
})
