import { createContext, useContext, useState, useEffect, type ReactNode } from 'react'
import {
  loginUser, registerUser, fetchCurrentUser,
  applySubscription as apiApplySubscription,
  updateProfile as apiUpdateProfile,
  changePassword as apiChangePassword,
  type UserDto,
} from '../api/authApi'

export interface User {
  id: string
  email: string
  name: string
  role: 'USER' | 'ADMIN'
  subscription: 'free' | 'pending' | 'pro'
  subscriptionEndDate?: string
  depositorName?: string
  createdAt: string
}

interface AuthContextType {
  user: User | null
  isGuest: boolean
  isLoading: boolean
  login: (email: string, password: string, rememberMe?: boolean) => Promise<void>
  register: (name: string, email: string, password: string) => Promise<void>
  logout: () => void
  applySubscription: (depositorName: string) => Promise<void>
  refreshUser: () => Promise<void>
  updateProfile: (name: string) => Promise<void>
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>
}

const AuthContext = createContext<AuthContextType | null>(null)

const TOKEN_KEY = 'bitman_token'
const USER_KEY = 'bitman_auth_user'
const REMEMBER_KEY = 'bitman_remember'

function isSubscriptionExpired(endDate?: string | null): boolean {
  if (!endDate) return false
  const expiryDate = new Date(endDate)
  const now = new Date()
  return now > expiryDate
}

function dtoToUser(dto: UserDto): User {
  // PRO 플랜이 만료되었는지 확인
  const isExpired = dto.subscription.toLowerCase() === 'pro' && isSubscriptionExpired(dto.subscriptionEndDate)

  return {
    id: dto.id,
    email: dto.email,
    name: dto.name,
    role: dto.role,
    // ADMIN은 무기한 PRO 고정, 일반 유저는 만료 여부 확인
    subscription: dto.role === 'ADMIN' ? 'pro' : isExpired ? 'free' : dto.subscription.toLowerCase() as User['subscription'],
    subscriptionEndDate: dto.subscriptionEndDate ?? undefined,
    depositorName: dto.depositorName ?? undefined,
    createdAt: dto.createdAt,
  }
}

/** 로그인 유지 여부에 따라 localStorage 또는 sessionStorage 사용 */
function getStorage(): Storage {
  return localStorage.getItem(REMEMBER_KEY) === '1' ? localStorage : sessionStorage
}

function setToken(token: string) {
  getStorage().setItem(TOKEN_KEY, token)
}

function setUserData(user: string) {
  getStorage().setItem(USER_KEY, user)
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

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  // 앱 시작 시 저장된 토큰으로 사용자 정보 복원
  useEffect(() => {
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
    if (!token) {
      setIsLoading(false)
      return
    }

    fetchCurrentUser(token)
      .then(dto => {
        const u = dtoToUser(dto)
        setUser(u)
        setUserData(JSON.stringify(u))
      })
      .catch(() => {
        // 토큰 만료 또는 무효 → 정리
        clearAuth()
      })
      .finally(() => setIsLoading(false))
  }, [])

  // authApi에서 401 수신 시 전역 로그아웃 처리
  useEffect(() => {
    const onUnauthorized = () => {
      setUser(null)
      clearAuth()
      localStorage.removeItem(REMEMBER_KEY)
      // 현재 경로가 로그인/랜딩이 아닐 때만 이동 (무한 리다이렉트 방지)
      const path = window.location.pathname
      if (path !== '/login' && path !== '/landing' && path !== '/register') {
        window.location.href = '/login'
      }
    }
    window.addEventListener('auth:unauthorized', onUnauthorized)
    return () => window.removeEventListener('auth:unauthorized', onUnauthorized)
  }, [])

  const login = async (email: string, password: string, rememberMe?: boolean) => {
    const res = await loginUser(email, password, rememberMe)
    // 로그인 유지 설정 저장 (항상 localStorage에)
    if (rememberMe) {
      localStorage.setItem(REMEMBER_KEY, '1')
      // localStorage에만 저장 (브라우저 종료 후에도 유지)
      localStorage.setItem(TOKEN_KEY, res.token)
      localStorage.setItem(USER_KEY, JSON.stringify(res.user))
    } else {
      localStorage.removeItem(REMEMBER_KEY)
      // sessionStorage에만 저장 (브라우저 종료 시 삭제)
      sessionStorage.setItem(TOKEN_KEY, res.token)
      sessionStorage.setItem(USER_KEY, JSON.stringify(res.user))
    }
    const u = dtoToUser(res.user)
    setUser(u)
  }

  const register = async (name: string, email: string, password: string) => {
    const res = await registerUser(name, email, password)
    // 가입 후 자동 로그인: 토큰과 사용자 정보 저장
    setToken(res.token)
    const u = dtoToUser(res.user)
    setUser(u)
    setUserData(JSON.stringify(u))
  }

  const logout = () => {
    setUser(null)
    clearAuth()
    localStorage.removeItem(REMEMBER_KEY)
  }

  const applySubscription = async (depositorName: string) => {
    const token = getStoredToken()
    if (!token || !user) return
    const dto = await apiApplySubscription(token, depositorName)
    const u = dtoToUser(dto)
    setUser(u)
    setUserData(JSON.stringify(u))
  }

  const refreshUser = async () => {
    const token = getStoredToken()
    if (!token) return
    try {
      const dto = await fetchCurrentUser(token)
      const u = dtoToUser(dto)
      setUser(u)
      setUserData(JSON.stringify(u))
    } catch { /* ignore */ }
  }

  const updateProfile = async (name: string) => {
    const token = getStoredToken()
    if (!token || !user) return
    const dto = await apiUpdateProfile(token, name)
    const u = dtoToUser(dto)
    setUser(u)
    setUserData(JSON.stringify(u))
  }

  const changePassword = async (currentPassword: string, newPassword: string) => {
    const token = getStoredToken()
    if (!token || !user) return
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
