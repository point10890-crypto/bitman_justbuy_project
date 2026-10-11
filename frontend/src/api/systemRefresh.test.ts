import { afterEach, describe, expect, it, vi } from 'vitest'
import { refreshAllAnalysis } from './authApi'

afterEach(() => vi.unstubAllGlobals())

describe('system refresh request contract', () => {
  it('sends only the failed modes and returns per-mode failure details for display', async () => {
    const transport = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      status: 'error', results: { CATALYST_BURST: 'error: provider unavailable' },
    })))
    vi.stubGlobal('fetch', transport)

    expect(await refreshAllAnalysis('fixture-admin-token', ['CATALYST_BURST'])).toEqual({
      status: 'error', results: { CATALYST_BURST: 'error: provider unavailable' },
    })
    const [url, init] = transport.mock.calls[0]
    expect(url).toMatch(/\/api\/admin\/system\/refresh-all$/)
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({ modes: ['CATALYST_BURST'] })
    expect(transport).toHaveBeenCalledTimes(1)
  })

  it('keeps the existing full-refresh request without a mode filter', async () => {
    const transport = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      status: 'ok', results: { BREAKOUT: 'success', CATALYST_BURST: 'success' },
    })))
    vi.stubGlobal('fetch', transport)
    const response = await refreshAllAnalysis('fixture-admin-token')

    expect(response.results).toEqual({ BREAKOUT: 'success', CATALYST_BURST: 'success' })
    expect(transport.mock.calls[0][1].body).toBeUndefined()
  })
})
