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

async function handleError(res: Response): Promise<never> {
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

// ─── Auth ───

export async function registerUser(name: string, email: string, password: string): Promise<AuthResponse> {
  const res = await fetch(`${API_BASE}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, email, password }),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function loginUser(email: string, password: string): Promise<AuthResponse> {
  const res = await fetch(`${API_BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function fetchCurrentUser(token: string): Promise<UserDto> {
  const res = await fetch(`${API_BASE}/api/auth/me`, {
    headers: authHeaders(token),
  })
  if (!res.ok) throw new Error('인증이 만료되었습니다.')
  return res.json()
}

// ─── Subscription ───

export async function applySubscription(token: string, depositorName: string): Promise<UserDto> {
  const res = await fetch(`${API_BASE}/api/subscription/apply`, {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ depositorName }),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

// ─── Admin ───

export async function fetchPendingSubscriptions(token: string): Promise<UserDto[]> {
  const res = await fetch(`${API_BASE}/api/admin/subscriptions/pending`, {
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function approveSubscription(token: string, userId: string): Promise<UserDto> {
  const res = await fetch(`${API_BASE}/api/admin/subscriptions/${userId}/approve`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function rejectSubscription(token: string, userId: string): Promise<UserDto> {
  const res = await fetch(`${API_BASE}/api/admin/subscriptions/${userId}/reject`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function fetchAllUsers(token: string): Promise<UserDto[]> {
  const res = await fetch(`${API_BASE}/api/admin/users`, {
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function revokeSubscription(token: string, userId: string): Promise<UserDto> {
  const res = await fetch(`${API_BASE}/api/admin/subscriptions/${userId}/revoke`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

// ─── Profile (self-service) ───

export async function updateProfile(token: string, name: string): Promise<UserDto> {
  const res = await fetch(`${API_BASE}/api/auth/me/profile`, {
    method: 'PUT',
    headers: authHeaders(token),
    body: JSON.stringify({ name }),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function changePassword(token: string, currentPassword: string, newPassword: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/auth/me/password`, {
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
  const res = await fetch(`${API_BASE}/api/admin/users/${userId}`, {
    method: 'PUT',
    headers: authHeaders(token),
    body: JSON.stringify(data),
  })
  if (!res.ok) await handleError(res)
  return res.json()
}

export async function adminResetPassword(token: string, userId: string, newPassword: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/admin/users/${userId}/password`, {
    method: 'PUT',
    headers: authHeaders(token),
    body: JSON.stringify({ newPassword }),
  })
  if (!res.ok) await handleError(res)
}
