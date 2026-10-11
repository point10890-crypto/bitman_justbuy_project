// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { fetchMainConditions, type MainConditionResponse } from '../api/conditionApi'
import { useMainConditions } from './useMainConditions'

vi.mock('../api/conditionApi', () => ({ fetchMainConditions: vi.fn() }))
vi.mock('../contexts/AuthContext', () => ({ getStoredToken: () => undefined }))
afterEach(() => { cleanup(); vi.clearAllMocks() })

it('an older refresh cannot overwrite the latest feed response', async () => {
  let resolveOld!: (value: MainConditionResponse) => void
  const old = new Promise<MainConditionResponse>(resolve => { resolveOld = resolve })
  const latest = { asOf: '2026-05-18T12:00:00+09:00' } as MainConditionResponse
  vi.mocked(fetchMainConditions).mockReturnValueOnce(old).mockResolvedValueOnce(latest)
  const { result } = renderHook(useMainConditions)
  await act(async () => { await result.current.refetch() })
  await act(async () => { resolveOld({ asOf: '2026-05-18T09:00:00+09:00' } as MainConditionResponse); await old })
  expect(result.current.data?.asOf).toBe('2026-05-18T12:00:00+09:00')
})
