/**
 * 구독 때문에 막힌 응답을 감지해 재구독 흐름으로 넘기는 단일 지점.
 *
 * 이 판정이 conditionApi 안에만 있었다. 백엔드는 analysis · deepseek · condition ·
 * performance 네 컨트롤러에서 같은 403 을 내보내는데, 나머지 API 모듈은 그것을
 * 평범한 오류로 취급했다. 그래서 만료 회원이 분석이나 성과 화면을 쓰면
 * "API error 403" 만 보고 그 화면에 그대로 머물렀다.
 *
 * 라우터 밖에서는 직접 이동할 수 없으므로 이벤트만 쏘고,
 * SubscriptionGateWatcher 가 받아 `/subscribe` 로 보낸다.
 */

export const SUBSCRIPTION_CODES = [
  'SUBSCRIPTION_EXPIRED',
  'SUBSCRIPTION_PENDING',
  'SUBSCRIPTION_REQUIRED',
] as const

export type SubscriptionCode = (typeof SUBSCRIPTION_CODES)[number]

export const SUBSCRIPTION_GATE_EVENT = 'subscription:required'

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  constructor(status: number, message: string, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

interface ErrorBody {
  error?: string
  message?: string
  code?: string
}

function parseBody(body: string): ErrorBody {
  if (!body) return {}
  try {
    const parsed = JSON.parse(body) as ErrorBody
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    // 프록시 HTML 오류 페이지 등 — 코드가 없는 것으로 취급한다.
    return {}
  }
}

/** 응답 본문에서 사용자에게 보여줄 메시지를 뽑는다. */
export function errorMessageOf(body: string, fallback: string): string {
  const parsed = parseBody(body)
  return parsed.error || parsed.message || (body && !body.startsWith('<') ? body : '') || fallback
}

/**
 * 구독 문제로 막힌 403 인지. 아니면 null.
 *
 * 403 은 "관리자 전용" 같은 다른 이유로도 나오므로 코드가 있을 때만 인정한다.
 * 401 은 재로그인 흐름이라 여기서 잡지 않는다.
 */
export function subscriptionCodeOf(status: number, body: string): SubscriptionCode | null {
  if (status !== 403) return null
  const code = parseBody(body).code
  return SUBSCRIPTION_CODES.includes(code as SubscriptionCode) ? (code as SubscriptionCode) : null
}

/**
 * 구독 403 이면 전역 신호를 보내고 코드를 돌려준다. 아니면 null.
 * window 가 없는 환경(SSR·테스트 러너)에서는 조용히 넘어간다.
 */
export function notifySubscriptionGate(status: number, body: string): SubscriptionCode | null {
  const code = subscriptionCodeOf(status, body)
  if (!code) return null

  const globalWindow = (globalThis as { window?: { dispatchEvent?: (event: unknown) => unknown } }).window
  if (globalWindow?.dispatchEvent) {
    globalWindow.dispatchEvent(new CustomEvent(SUBSCRIPTION_GATE_EVENT, {
      detail: { code, message: errorMessageOf(body, '') },
    }))
  }
  return code
}

/**
 * 실패한 응답을 읽어 신호를 보내고 ApiError 를 던진다.
 * 모든 API 모듈이 실패 처리를 여기로 모으기 위한 진입점.
 */
export async function failWithApiError(res: Response, fallback?: string): Promise<never> {
  const body = await res.text().catch(() => '')
  const code = notifySubscriptionGate(res.status, body) ?? undefined
  throw new ApiError(
    res.status,
    errorMessageOf(body, fallback || `요청을 처리하지 못했습니다. (HTTP ${res.status})`),
    code,
  )
}
