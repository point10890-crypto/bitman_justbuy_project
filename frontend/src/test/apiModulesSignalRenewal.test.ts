// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

/**
 * 결함의 진원지를 직접 태운다.
 *
 * 백엔드는 analysis · deepseek · condition · performance 네 곳에서 같은 구독 403 을
 * 내보내는데, 그 응답을 재구독 신호로 바꾸는 코드는 conditionApi 에만 있었다.
 * 그래서 만료 회원이 분석·성과 화면을 열면 "API error 403" 만 보고 그 자리에 머물렀다.
 *
 * 이 테스트는 각 API 모듈에 실제 403 응답을 물려 신호가 나가는지 확인한다.
 * 수정 전이라면 conditionApi 만 통과하고 나머지는 전부 실패한다.
 */

const EXPIRED_BODY = JSON.stringify({
  error: 'PRO 구독이 만료되었습니다. 재구독 신청을 해주세요.',
  code: 'SUBSCRIPTION_EXPIRED',
})

let signals: Array<{ code?: string; message?: string }> = []

function listen() {
  signals = []
  const handler = (event: Event) => {
    signals.push((event as CustomEvent).detail)
  }
  window.addEventListener('subscription:required', handler)
  return () => window.removeEventListener('subscription:required', handler)
}

function respondWith(status: number, body: string) {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(
    new Response(body, { status, headers: { 'Content-Type': 'application/json' } }),
  )))
}

let stopListening: () => void

beforeEach(() => { stopListening = listen() })
afterEach(() => { stopListening(); vi.unstubAllGlobals() })

describe('구독 403 을 받은 API 모듈은 재구독 신호를 보낸다', () => {
  it('performanceApi', async () => {
    respondWith(403, EXPIRED_BODY)
    const { fetchShortTermDailyClose } = await import('../api/performanceApi')

    await expect(fetchShortTermDailyClose(undefined, 'token')).rejects.toThrow()

    expect(signals).toHaveLength(1)
    expect(signals[0].code).toBe('SUBSCRIPTION_EXPIRED')
  })

  it('conditionApi', async () => {
    respondWith(403, EXPIRED_BODY)
    const { fetchMainConditions } = await import('../api/conditionApi')

    await expect(fetchMainConditions('token')).rejects.toThrow()

    expect(signals).toHaveLength(1)
    expect(signals[0].code).toBe('SUBSCRIPTION_EXPIRED')
  })

  it('analysisApi — 실패를 삼키고 null 을 돌려주는 경로에서도 신호는 나가야 한다', async () => {
    respondWith(403, EXPIRED_BODY)
    const { fetchPrecomputed } = await import('../api/analysisApi')

    // 이 함수는 프리컴퓨트가 없을 수 있어 의도적으로 null 을 반환한다.
    // 그래도 구독 403 이라는 사실은 흘려보내면 안 된다.
    await expect(fetchPrecomputed('swing', 'token')).resolves.toBeNull()

    expect(signals).toHaveLength(1)
    expect(signals[0].code).toBe('SUBSCRIPTION_EXPIRED')
  })

  it('구독과 무관한 403 은 재구독 흐름을 건드리지 않는다', async () => {
    respondWith(403, JSON.stringify({ error: '관리자만 접근 가능합니다.' }))
    const { fetchMainConditions } = await import('../api/conditionApi')

    await expect(fetchMainConditions('token')).rejects.toThrow()

    expect(signals).toHaveLength(0)
  })

  it('401 은 재로그인 흐름이므로 재구독 신호를 보내지 않는다', async () => {
    respondWith(401, JSON.stringify({ error: '인증이 필요합니다.' }))
    const { fetchMainConditions } = await import('../api/conditionApi')

    await expect(fetchMainConditions('token')).rejects.toThrow()

    expect(signals).toHaveLength(0)
  })
})
