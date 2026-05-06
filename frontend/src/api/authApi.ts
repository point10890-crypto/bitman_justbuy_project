/** Auth / Subscription / Admin API 클라이언트 */
import { API_BASE } from './config'

export interface UserDto {
  id: string
  email: string
  name: string
  role: 'USER' | 'ADMIN'
  subscription: 'FREE' | 'PENDING' | 'PRO'
  depositorName: string | null
  subscriptionEndDate: string | null
  subscriptionApprovedAt: string | null  // ⭐ PRO 부여일 (승인일)
  createdAt: string
}

export interface AuthResponse {
  token: string
  user: UserDto
}

function authHeaders(token: string): HeadersInit {
  return {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  }
}

/** 401 발생 시 AuthContext에 전역 신호 전송 (순환 import 회피) */
function emitUnauthorized() {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('auth:unauthorized'))
  }
}

/**
 * 공통 에러 처리:
 * - 401 → 전역 unauthorized 이벤트 발송 (AuthContext가 logout 처리)
 * - 5xx → "서버에 일시적으로 연결할 수 없습니다..."
 * - 그 외 → 서버 응답 메시지
 * @param silent401 - fetchCurrentUser처럼 무한 로그아웃 루프를 유발하는 경로에서 true
 */
async function handleError(res: Response, silent401 = false): Promise<never> {
  if (res.status === 401) {
    if (!silent401) emitUnauthorized()
    throw new Error('인증이 만료되었습니다. 다시 로그인해주세요.')
  }
  if (res.status >= 500) {
    throw new Error('서버에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해주세요.')
  }
  const text = await res.text()
  let msg: string
  try {
    const json = JSON.parse(text)
    msg = json.error || text
  } catch {
    msg = text || `HTTP ${res.status}`
  }
  throw new Error(msg)
}

/** fetch 래퍼: 네트워크 에러(TypeError) → 사용자 친화 메시지로 변환 */
async function apiFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(input, init)
  } catch (err) {
    if (err instanceof TypeError) {
      throw new Error('네트워크 연결을 확인해주세요.')
    }
    throw err
  }
}

// ─── Auth ───

export async function registerUser(name: string, email: string, password: string): Promise<AuthResponse> {
  const res = await apiFetch(`${API_BASE}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, email, password }),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function loginUser(email: string, password: string, rememberMe?: boolean): Promise<AuthResponse> {
  const res = await apiFetch(`${API_BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, rememberMe: !!rememberMe }),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function fetchCurrentUser(token: string): Promise<UserDto> {
  const res = await apiFetch(`${API_BASE}/api/auth/me`, {
    headers: authHeaders(token),
  })
  // 초기 토큰 복원 경로 — 401이어도 전역 이벤트는 발송하지 않음 (AuthContext가 자체 처리)
  if (!res.ok) await handleError(res, true)
  return res.json()
}

// ─── Subscription ───

export async function applySubscription(token: string, depositorName: string): Promise<UserDto> {
  const res = await apiFetch(`${API_BASE}/api/subscription/apply`, {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ depositorName }),
  })
  if (!res.ok) await handleError(res)
  const data = await res.json()
  return data.user || data  // API는 { user: UserDto } 형태로 반환
}

// ─── Admin ───

export async function fetchPendingSubscriptions(token: string): Promise<UserDto[]> {
  const res = await apiFetch(`${API_BASE}/api/admin/subscriptions/pending`, {
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function approveSubscription(token: string, userId: string): Promise<UserDto> {
  const res = await apiFetch(`${API_BASE}/api/admin/subscriptions/${userId}/approve`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function rejectSubscription(token: string, userId: string): Promise<UserDto> {
  const res = await apiFetch(`${API_BASE}/api/admin/subscriptions/${userId}/reject`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function fetchAllUsers(token: string): Promise<UserDto[]> {
  const res = await apiFetch(`${API_BASE}/api/admin/users`, {
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function revokeSubscription(token: string, userId: string): Promise<UserDto> {
  const res = await apiFetch(`${API_BASE}/api/admin/subscriptions/${userId}/revoke`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

// ─── Profile (self-service) ───

export async function updateProfile(token: string, name: string): Promise<UserDto> {
  const res = await apiFetch(`${API_BASE}/api/auth/me/profile`, {
    method: 'PUT',
    headers: authHeaders(token),
    body: JSON.stringify({ name }),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function changePassword(token: string, currentPassword: string, newPassword: string): Promise<void> {
  const res = await apiFetch(`${API_BASE}/api/auth/me/password`, {
    method: 'PUT',
    headers: authHeaders(token),
    body: JSON.stringify({ currentPassword, newPassword }),
  })
  if (!res.ok) await handleError(res)
}

// ─── Admin: User Management ───

export async function adminUpdateUser(
  token: string, userId: string,
  data: { name?: string; email?: string; subscription?: string }
): Promise<UserDto> {
  const res = await apiFetch(`${API_BASE}/api/admin/users/${userId}`, {
    method: 'PUT',
    headers: authHeaders(token),
    body: JSON.stringify(data),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function adminResetPassword(token: string, userId: string, newPassword: string): Promise<void> {
  const res = await apiFetch(`${API_BASE}/api/admin/users/${userId}/password`, {
    method: 'PUT',
    headers: authHeaders(token),
    body: JSON.stringify({ newPassword }),
  })
  if (!res.ok) await handleError(res)
}

// ─── Admin: System ───

export async function fetchSystemStatus(token: string) {
  const res = await apiFetch(`${API_BASE}/api/admin/system/status`, {
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function refreshAllAnalysis(token: string) {
  const res = await apiFetch(`${API_BASE}/api/admin/system/refresh-all`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  const started = await res.json()
  if (!started.jobId) return started

  const deadline = Date.now() + 8 * 60_000
  while (Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 3000))
    const statusRes = await apiFetch(`${API_BASE}/api/admin/system/refresh-all/${started.jobId}`, {
      headers: authHeaders(token),
    })
    if (!statusRes.ok) await handleError(statusRes)
    const status = await statusRes.json()
    if (status.status === 'complete') return status
    if (status.status === 'error') throw new Error(status.error || 'Refresh failed')
  }

  return {
    ...started,
    status: 'running',
    message: 'Refresh is still running. Check system status again shortly.',
    results: [],
  }
}

export interface DeepSeekConfigStatus {
  provider: 'deepseek'
  configured: boolean
  source: string
  baseUrl: string
  model: string
  maskedKey: string | null
  updatedAt: string | null
}

export async function fetchDeepSeekConfig(token: string): Promise<DeepSeekConfigStatus> {
  const res = await apiFetch(`${API_BASE}/api/admin/ai/deepseek`, {
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function saveDeepSeekConfig(
  token: string,
  data: { apiKey: string; baseUrl?: string; model?: string }
): Promise<DeepSeekConfigStatus> {
  const res = await apiFetch(`${API_BASE}/api/admin/ai/deepseek`, {
    method: 'PUT',
    headers: authHeaders(token),
    body: JSON.stringify(data),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function deleteDeepSeekConfig(token: string): Promise<DeepSeekConfigStatus> {
  const res = await apiFetch(`${API_BASE}/api/admin/ai/deepseek`, {
    method: 'DELETE',
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function testDeepSeekConfig(token: string, message = 'ping') {
  const res = await apiFetch(`${API_BASE}/api/admin/ai/deepseek/test`, {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ message }),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

