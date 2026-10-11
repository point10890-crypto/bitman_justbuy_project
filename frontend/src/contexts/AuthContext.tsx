import { createContext, useContext, useState, useEffect, useCallback, useRef, type ReactNode } from 'react'
import {
  loginUser, registerUser, fetchCurrentUser,
  applySubscription as apiApplySubscription,
  updateProfile as apiUpdateProfile,
  changePassword as apiChangePassword,
  type UserDto,
  type SubscriptionPlanId,
} from '../api/authApi'
import { memberTierOf } from '../lib/memberTier'
import { authPath, subscriptionPlanFromSearch } from '../lib/subscriptionPlans'
import { ApiError } from '../api/subscriptionGate'

export interface User {
  id: string
  email: string
  name: string
  role: 'USER' | 'ADMIN'
  subscription: 'free' | 'pending' | 'pro'
  subscriptionEndDate?: string
  subscriptionApprovedAt?: string
  subscriptionRequestedAt?: string
  subscriptionExpired?: boolean
  subscriptionRenewalPending?: boolean
  subscriptionPlan?: SubscriptionPlanId
  requestedSubscriptionPlan?: SubscriptionPlanId
  /** 텔레그램 알림 연결 여부. chat id 원문은 서버가 내려주지 않는다. */
  telegramLinked?: boolean
  depositorName?: string
  createdAt: string
}

interface AuthContextType {
  user: User | null
  isGuest: boolean
  isLoading: boolean
  login: (email: string, password: string, rememberMe?: boolean) => Promise<User>
  register: (name: string, email: string, password: string) => Promise<void>
  logout: () => void
  applySubscription: (depositorName: string, plan?: SubscriptionPlanId) => Promise<void>
  refreshUser: () => Promise<void>
  updateProfile: (name: string) => Promise<void>
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>
}

const AuthContext = createContext<AuthContextType | null>(null)

const TOKEN_KEY = 'bitman_token'
const USER_KEY = 'bitman_auth_user'
const REMEMBER_KEY = 'bitman_remember'

// 판정은 src/lib/memberTier.ts 한 곳에서만 한다. 여기서는 재수출만 해
// 기존 임포트 경로를 깨지 않는다.
export { isSubscriptionExpired } from '../lib/memberTier'

function dtoToUser(dto: UserDto): User {
  const tier = memberTierOf(dto)
  const rawSubscription = dto.subscription.toLowerCase() as User['subscription']
  const isExpired = tier === 'EXPIRED'
  const isPendingRenewal = tier === 'ACTIVE' && dto.subscription.toLowerCase() === 'pending'

  return {
    id: dto.id,
    email: dto.email,
    name: dto.name,
    role: dto.role,
    // ADMIN은 무기한 PRO 고정. 연장 승인 대기 중인 기존 PRO는 남은 기간 동안 계속 PRO로 취급한다.
    subscription: tier === 'ACTIVE' ? 'pro' : isExpired ? 'free' : rawSubscription,
    subscriptionEndDate: dto.subscriptionEndDate ?? undefined,
    subscriptionApprovedAt: dto.subscriptionApprovedAt ?? undefined,
    subscriptionRequestedAt: dto.subscriptionRequestedAt ?? undefined,
    subscriptionExpired: isExpired,
    subscriptionRenewalPending: isPendingRenewal,
    subscriptionPlan: dto.subscriptionPlan ?? undefined,
    requestedSubscriptionPlan: dto.requestedSubscriptionPlan ?? undefined,
    telegramLinked: dto.telegramLinked ?? false,
    depositorName: dto.depositorName ?? undefined,
    createdAt: dto.createdAt,
  }
}

function usePersistentStorage() {
  return localStorage.getItem(REMEMBER_KEY) !== '0'
}

function setToken(token: string) {
  const persistent = usePersistentStorage()
  const storage = persistent ? localStorage : sessionStorage
  const other = persistent ? sessionStorage : localStorage
  storage.setItem(TOKEN_KEY, token)
  other.removeItem(TOKEN_KEY)
}

function setUserData(user: string) {
  const persistent = usePersistentStorage()
  const storage = persistent ? localStorage : sessionStorage
  const other = persistent ? sessionStorage : localStorage
  storage.setItem(USER_KEY, user)
  other.removeItem(USER_KEY)
}

function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(USER_KEY)
}

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_KEY) || sessionStorage.getItem(TOKEN_KEY)
}

function getStoredUser(): User | null {
  const raw = localStorage.getItem(USER_KEY) || sessionStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as User | UserDto
    const subscription = (parsed as { subscription?: string }).subscription
    if (subscription && subscription === subscription.toUpperCase()) {
      return dtoToUser(parsed as UserDto)
    }
    // 캐시된 사용자도 같은 판정을 다시 통과시킨다. 날짜가 넘어간 뒤 앱을 열면
    // 저장 시점의 티어가 더 이상 맞지 않는다.
    const user = parsed as User
    const rawSubscription = user.subscriptionRenewalPending ? 'pending' : user.subscription
    const tier = memberTierOf({ ...user, subscription: rawSubscription })
    return {
      ...user,
      subscription: tier === 'ACTIVE' ? 'pro' : tier === 'EXPIRED' ? 'free' : rawSubscription,
      subscriptionExpired: tier === 'EXPIRED',
      subscriptionRenewalPending: tier === 'ACTIVE' && rawSubscription === 'pending',
    }
  } catch {
    return null
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => getStoredToken() ? getStoredUser() : null)
  const [isLoading, setIsLoading] = useState(true)
  const mounted = useRef(true)
  const sessionVersion = useRef(0)
  const userWriteVersion = useRef(0)
  const currentUser = useRef(user)
  const pendingRefresh = useRef<{
    token: string, session: number, write: number, promise: Promise<void>
  } | null>(null)

  const saveUser = useCallback((nextUser: User) => {
    currentUser.current = nextUser
    setUser(nextUser)
    setUserData(JSON.stringify(nextUser))
  }, [])

  const isCurrentSession = useCallback((session: number, token: string) => (
    mounted.current && sessionVersion.current === session && getStoredToken() === token
  ), [])

  const logout = useCallback(() => {
    sessionVersion.current += 1
    userWriteVersion.current += 1
    pendingRefresh.current = null
    currentUser.current = null
    setUser(null)
    setIsLoading(false)
    clearAuth()
    localStorage.removeItem(REMEMBER_KEY)
  }, [])

  const refreshUser = useCallback((): Promise<void> => {
    const token = getStoredToken()
    if (!token) return Promise.resolve()
    const session = sessionVersion.current
    const write = userWriteVersion.current
    const existing = pendingRefresh.current
    if (existing?.token === token && existing.session === session && existing.write === write) {
      return existing.promise
    }

    const request = { token, session, write, promise: Promise.resolve() }
    pendingRefresh.current = request
    request.promise = (async () => {
      try {
        const dto = await fetchCurrentUser(token)
        // Account writes are newer than any user lookup that started before them.
        if (isCurrentSession(session, token) && userWriteVersion.current === write) {
          saveUser(dtoToUser(dto))
        }
      } catch (error) {
        // Offline/5xx preserves cached authentication so a later refresh can retry.
        if (error instanceof ApiError && error.status === 401 && isCurrentSession(session, token)) {
          logout()
        }
      } finally {
        if (pendingRefresh.current === request) pendingRefresh.current = null
      }
    })()
    return request.promise
  }, [isCurrentSession, logout, saveUser])

  // 앱 시작 시 저장된 토큰으로 사용자 정보 복원
  useEffect(() => {
    mounted.current = true
    let cancelled = false
    // ─── 레거시 키 마이그레이션 (justbuy_* → bitman_*) ───
    const legacyToken = localStorage.getItem('justbuy_token')
    if (legacyToken && !localStorage.getItem(TOKEN_KEY)) {
      localStorage.setItem(REMEMBER_KEY, '1')
      localStorage.setItem(TOKEN_KEY, legacyToken)
      localStorage.removeItem('justbuy_token')
      const legacyUser = localStorage.getItem('justbuy_user')
      if (legacyUser) {
        localStorage.setItem(USER_KEY, legacyUser)
        localStorage.removeItem('justbuy_user')
      }
    }

    const token = getStoredToken()
    const cachedUser = getStoredUser()
    if (token && cachedUser) {
      currentUser.current = cachedUser
      setUser(cachedUser)
    }
    if (!token) {
      logout()
      setIsLoading(false)
    } else {
      void refreshUser().finally(() => {
        if (!cancelled) setIsLoading(false)
      })
    }
    return () => {
      cancelled = true
      mounted.current = false
      sessionVersion.current += 1
      pendingRefresh.current = null
    }
  }, [logout, refreshUser])

  // authApi에서 401 수신 시 전역 로그아웃 처리
  useEffect(() => {
    const onUnauthorized = (event: Event) => {
      const token = (event as CustomEvent<{ token?: string }>).detail?.token
      if (!getStoredToken() || (token && token !== getStoredToken())) return
      logout()
      // 현재 경로가 로그인/랜딩이 아닐 때만 이동 (무한 리다이렉트 방지)
      const path = window.location.pathname
      if (path !== '/login' && path !== '/landing' && path !== '/register') {
        const params = new URLSearchParams(window.location.search)
        window.location.href = path === '/subscribe'
          ? authPath('login', params.has('plan') ? subscriptionPlanFromSearch(window.location.search) : undefined, params.get('renewal') === '1')
          : '/login'
      }
    }
    window.addEventListener('auth:unauthorized', onUnauthorized)
    return () => window.removeEventListener('auth:unauthorized', onUnauthorized)
  }, [logout])

  // Keep long-running browser/PWA sessions aligned with server-side member
  // deletion and subscription expiry. Refresh as soon as the app is resumed.
  useEffect(() => {
    if (!user || !getStoredToken()) return

    const reconcile = () => { void refreshUser() }
    // 탭이 보일 때만, 그리고 최소 간격을 두고 확인한다. 이전 구현은 백그라운드
    // 탭에서도 60초마다 때려 PWA 를 켜둔 사용자 한 명당 하루 1,440회가 나갔다.
    const MIN_INTERVAL_MS = 10 * 60_000
    let lastCheckedAt = Date.now()
    const reconcileIfStale = () => {
      if (document.visibilityState !== 'visible') return
      if (Date.now() - lastCheckedAt < MIN_INTERVAL_MS) return
      lastCheckedAt = Date.now()
      reconcile()
    }
    const onVisibilityChange = () => {
      // 앱으로 돌아온 순간은 서버 상태와 어긋나 있을 확률이 가장 높다 — 즉시 확인.
      if (document.visibilityState === 'visible') {
        lastCheckedAt = Date.now()
        reconcile()
      }
    }
    const interval = window.setInterval(reconcileIfStale, MIN_INTERVAL_MS)
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      window.clearInterval(interval)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [user?.id, refreshUser])

  const login = async (email: string, password: string, rememberMe?: boolean) => {
    const session = ++sessionVersion.current
    userWriteVersion.current += 1
    pendingRefresh.current = null
    const res = await loginUser(email, password, rememberMe)
    if (!mounted.current || sessionVersion.current !== session) {
      throw new Error('로그인 상태가 변경되었습니다. 다시 시도해 주세요.')
    }
    const u = dtoToUser(res.user)
    // Keep auth until the user explicitly logs out, and store the normalized
    // user shape used by route guards.
    localStorage.setItem(REMEMBER_KEY, rememberMe ? '1' : '0')
    setToken(res.token)
    saveUser(u)
    setIsLoading(false)
    return u
  }

  const register = async (name: string, email: string, password: string) => {
    const session = ++sessionVersion.current
    userWriteVersion.current += 1
    pendingRefresh.current = null
    const res = await registerUser(name, email, password)
    if (!mounted.current || sessionVersion.current !== session) {
      throw new Error('로그인 상태가 변경되었습니다. 다시 시도해 주세요.')
    }
    // 가입 후 자동 로그인. REMEMBER_KEY 를 먼저 정해두지 않으면 직전 사용자가 남긴
    // 값에 따라 토큰이 sessionStorage 로 새어 브라우저를 닫는 순간 로그아웃된다.
    localStorage.setItem(REMEMBER_KEY, '1')
    setToken(res.token)
    const u = dtoToUser(res.user)
    saveUser(u)
    setIsLoading(false)
  }

  const applySubscription = async (depositorName: string, plan: SubscriptionPlanId = 'MONTHLY') => {
    const token = getStoredToken()
    if (!token || !currentUser.current) throw new Error('로그인 후 구독을 신청해 주세요.')
    const session = sessionVersion.current
    const dto = await apiApplySubscription(token, depositorName, plan)
    if (!isCurrentSession(session, token)) return
    userWriteVersion.current += 1
    saveUser(dtoToUser(dto))
  }

  const updateProfile = async (name: string) => {
    const token = getStoredToken()
    if (!token || !currentUser.current) return
    const session = sessionVersion.current
    const dto = await apiUpdateProfile(token, name)
    if (!isCurrentSession(session, token)) return
    userWriteVersion.current += 1
    saveUser(dtoToUser(dto))
  }

  const changePassword = async (currentPassword: string, newPassword: string) => {
    const token = getStoredToken()
    if (!token || !currentUser.current) return
    await apiChangePassword(token, currentPassword, newPassword)
  }

  return (
    <AuthContext.Provider value={{
      user, isGuest: !user, isLoading,
      login, register, logout,
      applySubscription, refreshUser,
      updateProfile, changePassword,
    }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
