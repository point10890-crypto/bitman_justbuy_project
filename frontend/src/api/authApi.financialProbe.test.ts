// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchDartFinancialEvidence, type DartFinancialProbe } from './authApi'

const evidence: DartFinancialProbe = {
  status: 'PARTIAL', source: 'DART',
  evidence: { status: 'PARTIAL', capturedAt: '2026-10-12T09:00:00Z', stocks: [{
    stockCode: '005930', status: 'PARTIAL', issues: ['LATEST_REPORT_UNKNOWN'], facts: {
      businessYear: 2026, reportCode: '11012', fsDiv: 'CFS', currency: 'KRW', receiptNo: '20260814000001',
      period: '2026 사업연도 반기 당분기(3개월)', cashFlowPeriod: '2026 사업연도 반기 누적',
      operatingCashFlow: null, risks: ['LATEST_REPORT_UNKNOWN'],
    },
  }] },
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals() })

describe('bounded DART financial source probe', () => {
  it('actually aborts a stalled fetch after twenty seconds without retrying', async () => {
    let requestSignal: AbortSignal | undefined
    let aborted = false
    const fetchMock = vi.fn((_url: RequestInfo | URL, options?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      requestSignal = options?.signal ?? undefined
      requestSignal?.addEventListener('abort', () => {
        aborted = true
        reject(requestSignal?.reason ?? new DOMException('Aborted', 'AbortError'))
      }, { once: true })
    }))
    vi.stubGlobal('fetch', fetchMock)
    const outcome = fetchDartFinancialEvidence('fixture-admin-token', '005930').then(
      result => ({ result, error: undefined }), error => ({ result: undefined, error }),
    )

    await vi.advanceTimersByTimeAsync(19_999)
    expect(aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(aborted).toBe(true)
    expect(requestSignal?.aborted).toBe(true)
    expect((await outcome).error).toMatchObject({ name: 'AbortError' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('uses one overall deadline for headers and a stalled response body', async () => {
    let requestSignal: AbortSignal | undefined
    let bodyAborted = false
    vi.stubGlobal('fetch', vi.fn((_url: RequestInfo | URL, options?: RequestInit) => new Promise<Response>(resolve => {
      requestSignal = options?.signal ?? undefined
      setTimeout(() => {
        const body = new ReadableStream<Uint8Array>({ start(controller) {
          requestSignal?.addEventListener('abort', () => {
            bodyAborted = true
            controller.error(requestSignal?.reason ?? new DOMException('Aborted', 'AbortError'))
          }, { once: true })
        } })
        resolve(new Response(body, { headers: { 'Content-Type': 'application/json' } }))
      }, 15_000)
    })))
    const outcome = fetchDartFinancialEvidence('fixture-admin-token', '005930').then(
      result => ({ result, error: undefined }), error => ({ result: undefined, error }),
    )

    await vi.advanceTimersByTimeAsync(19_999)
    expect(bodyAborted).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(bodyAborted).toBe(true)
    expect((await outcome).error).toMatchObject({ name: 'AbortError' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('preserves actual partial evidence and clears its deadline after success', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(evidence), { headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    expect(await fetchDartFinancialEvidence('fixture-admin-token', '005930')).toEqual(evidence)
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toContain('/api/admin/analysis/financial-evidence/005930')
    expect(new Headers(options.headers).get('Authorization')).toBe('Bearer fixture-admin-token')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('clears its deadline on server failure and never requests an invalid stock code', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('unavailable', { status: 503 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(fetchDartFinancialEvidence('fixture-admin-token', '005930')).rejects.toMatchObject({ status: 503 })
    expect(vi.getTimerCount()).toBe(0)
    await expect(fetchDartFinancialEvidence('fixture-admin-token', 'bad')).rejects.toThrow('6자리 종목코드')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
