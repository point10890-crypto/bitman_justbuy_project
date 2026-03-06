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
  login: (email: string, password: string) => Promise<void>
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

function dtoToUser(dto: UserDto): User {
  return {
    id: dto.id,
    email: dto.email,
    name: dto.name,
    role: dto.role,
    // ADMIN은 무기한 PRO 고정
    subscription: dto.role === 'ADMIN' ? 'pro' : dto.subscription.toLowerCase() as User['subscription'],
    subscriptionEndDate: dto.subscriptionEndDate ?? undefined,
    depositorName: dto.depositorName ?? undefined,
    createdAt: dto.createdAt,
  }
}

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  // 앱 시작 시 저장된 토큰으로 사용자 정보 복원
  useEffect(() => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (!token) {
      setIsLoading(false)
      return
    }

    fetchCurrentUser(token)
      .then(dto => {
        const u = dtoToUser(dto)
        setUser(u)
        localStorage.setItem(USER_KEY, JSON.stringify(u))
      })
      .catch(() => {
        // 토큰 만료 또는 무효 → 정리
        localStorage.removeItem(TOKEN_KEY)
        localStorage.removeItem(USER_KEY)
      })
      .finally(() => setIsLoading(false))
  }, [])

  const login = async (email: string, password: string) => {
    const res = await loginUser(email, password)
    localStorage.setItem(TOKEN_KEY, res.token)
    const u = dtoToUser(res.user)
    setUser(u)
    localStorage.setItem(USER_KEY, JSON.stringify(u))
  }

  const register = async (name: string, email: string, password: string) => {
    await registerUser(name, email, password)
    // 가입 후 로그인 페이지로 이동하도록 토큰 저장 안 함
  }

  const logout = () => {
    setUser(null)
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  const applySubscription = async (depositorName: string) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (!token || !user) return
    const dto = await apiApplySubscription(token, depositorName)
    const u = dtoToUser(dto)
    setUser(u)
    localStorage.setItem(USER_KEY, JSON.stringify(u))
  }

  const refreshUser = async () => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (!token) return
    try {
      const dto = await fetchCurrentUser(token)
      const u = dtoToUser(dto)
      setUser(u)
      localStorage.setItem(USER_KEY, JSON.stringify(u))
    } catch { /* ignore */ }
  }

  const updateProfile = async (name: string) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (!token || !user) return
    const dto = await apiUpdateProfile(token, name)
    const u = dtoToUser(dto)
    setUser(u)
    localStorage.setItem(USER_KEY, JSON.stringify(u))
  }

  const changePassword = async (currentPassword: string, newPassword: string) => {
    const token = localStorage.getItem(TOKEN_KEY)
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
