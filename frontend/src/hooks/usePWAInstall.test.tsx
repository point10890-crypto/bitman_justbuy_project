// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { usePWAInstall } from './usePWAInstall'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('waits for Android installation completion after accepting the prompt', async () => {
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  const { result } = renderHook(() => usePWAInstall())
  const event = new Event('beforeinstallprompt', { cancelable: true })
  Object.assign(event, { prompt: vi.fn().mockResolvedValue(undefined), userChoice: Promise.resolve({ outcome: 'accepted' }) })
  act(() => { window.dispatchEvent(event) })
  await act(async () => { await result.current.triggerNativeInstall() })
  expect(result.current.isInstalled).toBe(false)
  act(() => { window.dispatchEvent(new Event('appinstalled')) })
  expect(result.current.isInstalled).toBe(true)
})
