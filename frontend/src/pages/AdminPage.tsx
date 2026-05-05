import { useState, useEffect, useMemo, useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import GlassCard from '../components/common/GlassCard'
import GoldButton from '../components/common/GoldButton'
import FormInput from '../components/common/FormInput'
import { getStoredToken } from '../contexts/AuthContext'
import {
  fetchPendingSubscriptions, approveSubscription, rejectSubscription,
  fetchAllUsers, revokeSubscription, adminUpdateUser, adminResetPassword,
  fetchSystemStatus, refreshAllAnalysis,
  fetchDeepSeekConfig, saveDeepSeekConfig, deleteDeepSeekConfig, testDeepSeekConfig,
  type UserDto, type DeepSeekConfigStatus,
} from '../api/authApi'

type Tab = 'dashboard' | 'subscriptions' | 'members' | 'system' | 'monitor'

const VALID_TABS: Tab[] = ['dashboard', 'subscriptions', 'members', 'system', 'monitor']

export default function AdminPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const rawTab = searchParams.get('tab')
  const initialTab = (VALID_TABS.includes(rawTab as Tab) ? rawTab as Tab : 'dashboard')
  const [toast, setToast] = useState<{ msg: string; type: 'error' | 'success' } | null>(null)
  const showToast = (msg: string, type: 'error' | 'success' = 'error') => {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 4000)
  }
  const [tab, setTab] = useState<Tab>(initialTab)
  const [pendingUsers, setPendingUsers] = useState<UserDto[]>([])
  const [allUsers, setAllUsers] = useState<UserDto[]>([])
  const [loading, setLoading] = useState(true)
  const [actionId, setActionId] = useState<string | null>(null)

  // ─── 회원관리: 검색/선택/편집 ───
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedUser, setSelectedUser] = useState<UserDto | null>(null)
  const [editingUser, setEditingUser] = useState(false)
  const [resetPwMode, setResetPwMode] = useState(false)

  // 편집 폼
  const [editName, setEditName] = useState('')
  const [editEmail, setEditEmail] = useState('')
  const [editSub, setEditSub] = useState('')
  const [editLoading, setEditLoading] = useState(false)
  const [editError, setEditError] = useState('')
  const [editSuccess, setEditSuccess] = useState('')

  // 비밀번호 초기화
  const [newPassword, setNewPassword] = useState('')
  const [resetLoading, setResetLoading] = useState(false)
  const [resetError, setResetError] = useState('')
  const [resetSuccess, setResetSuccess] = useState('')

  // 시스템 관리
  const [systemStatus, setSystemStatus] = useState<any>(null)
  const [systemLoading, setSystemLoading] = useState(false)
  const [refreshLoading, setRefreshLoading] = useState(false)
  const [refreshResult, setRefreshResult] = useState<any>(null)
  const [deepSeekStatus, setDeepSeekStatus] = useState<DeepSeekConfigStatus | null>(null)
  const [deepSeekKey, setDeepSeekKey] = useState('')
  const [deepSeekModel, setDeepSeekModel] = useState('deepseek-v4-flash')
  const [deepSeekBaseUrl, setDeepSeekBaseUrl] = useState('https://api.deepseek.com')
  const [deepSeekSaving, setDeepSeekSaving] = useState(false)
  const [deepSeekDeleting, setDeepSeekDeleting] = useState(false)
  const [deepSeekTesting, setDeepSeekTesting] = useState(false)
  const [deepSeekTestResult, setDeepSeekTestResult] = useState<any>(null)

  // 모니터링
  const [monitorData, setMonitorData] = useState<any>(null)
  const [monitorLoading, setMonitorLoading] = useState(false)
  const [autoRefresh, setAutoRefresh] = useState(false)

  // ─── 빠른 액션 확인 모달 ───
  const [confirmAction, setConfirmAction] = useState<{
    userId: string; userName: string; type: 'grant' | 'approve' | 'reject' | 'revoke'
  } | null>(null)

  useEffect(() => { loadData() }, [])

  const loadData = async () => {
    const token = getStoredToken()
    if (!token) return
    setLoading(true)
    try {
      const [pending, users] = await Promise.all([
        fetchPendingSubscriptions(token),
        fetchAllUsers(token),
      ])
      setPendingUsers(pending)
      setAllUsers(users)
    } catch (e) { showToast('데이터 로드 실패: ' + (e instanceof Error ? e.message : '알 수 없는 오류')) }
    finally { setLoading(false) }
  }

  const changeTab = (t: Tab) => {
    setTab(t)
    setSearchParams(t === 'dashboard' ? {} : { tab: t })
    setSelectedUser(null)
    setEditingUser(false)
    setResetPwMode(false)
  }

  const handleApprove = async (userId: string) => {
    const token = getStoredToken()
    if (!token) return
    setActionId(userId)
    try {
      const updated = await approveSubscription(token, userId)
      setPendingUsers(prev => prev.filter(u => u.id !== userId))
      setAllUsers(prev => prev.map(u => u.id === userId ? updated : u))
    } catch (e) { showToast('처리 실패: ' + (e instanceof Error ? e.message : '오류')) }
    finally { setActionId(null) }
  }

  const handleReject = async (userId: string) => {
    const token = getStoredToken()
    if (!token) return
    setActionId(userId)
    try {
      const updated = await rejectSubscription(token, userId)
      setPendingUsers(prev => prev.filter(u => u.id !== userId))
      setAllUsers(prev => prev.map(u => u.id === userId ? updated : u))
    } catch (e) { showToast('처리 실패: ' + (e instanceof Error ? e.message : '오류')) }
    finally { setActionId(null) }
  }

  const handleRevoke = async (userId: string) => {
    const token = getStoredToken()
    if (!token) return
    setActionId(userId)
    try {
      const updated = await revokeSubscription(token, userId)
      setAllUsers(prev => prev.map(u => u.id === userId ? updated : u))
    } catch (e) { showToast('처리 실패: ' + (e instanceof Error ? e.message : '오류')) }
    finally { setActionId(null) }
  }

  // ─── 빠른 액션 실행 (확인 모달에서 호출) ───
  const executeQuickAction = async () => {
    if (!confirmAction) return
    const { userId, type } = confirmAction
    setConfirmAction(null)
    switch (type) {
      case 'approve': await handleApprove(userId); break
      case 'reject': await handleReject(userId); break
      case 'revoke': await handleRevoke(userId); break
      case 'grant': await handleGrantPro(userId); break
    }
    showToast(
      type === 'grant' ? 'PRO 구독이 부여되었습니다.' :
      type === 'approve' ? '구독이 승인되었습니다.' :
      type === 'reject' ? '구독이 거절되었습니다.' :
      '구독이 해제되었습니다.',
      'success'
    )
  }

  // FREE → PRO 직접 부여 (adminUpdateUser 사용)
  const handleGrantPro = async (userId: string) => {
    const token = getStoredToken()
    if (!token) return
    setActionId(userId)
    try {
      const user = allUsers.find(u => u.id === userId)
      if (!user) return
      const updated = await adminUpdateUser(token, userId, {
        name: user.name, email: user.email, subscription: 'PRO',
      })
      setAllUsers(prev => prev.map(u => u.id === userId ? updated : u))
      if (selectedUser?.id === userId) setSelectedUser(updated)
    } catch (e) { showToast('처리 실패: ' + (e instanceof Error ? e.message : '오류')) }
    finally { setActionId(null) }
  }

  // ─── 회원 상세 선택 ───
  const handleSelectUser = (user: UserDto) => {
    setSelectedUser(user)
    setEditingUser(false)
    setResetPwMode(false)
    setEditError('')
    setEditSuccess('')
    setResetError('')
    setResetSuccess('')
  }

  // ─── 회원 정보 수정 ───
  const handleStartEdit = () => {
    if (!selectedUser) return
    setEditName(selectedUser.name)
    setEditEmail(selectedUser.email)
    setEditSub(selectedUser.subscription)
    setEditingUser(true)
    setResetPwMode(false)
    setEditError('')
    setEditSuccess('')
  }

  const handleSaveUser = async () => {
    const token = getStoredToken()
    if (!token || !selectedUser) return
    setEditLoading(true)
    setEditError('')
    try {
      const updated = await adminUpdateUser(token, selectedUser.id, {
        name: editName.trim(),
        email: editEmail.trim(),
        subscription: editSub,
      })
      setAllUsers(prev => prev.map(u => u.id === selectedUser.id ? updated : u))
      setSelectedUser(updated)
      setEditSuccess('회원 정보가 수정되었습니다.')
      setEditingUser(false)
    } catch (err) {
      setEditError(err instanceof Error ? err.message : '수정에 실패했습니다.')
    } finally {
      setEditLoading(false)
    }
  }

  // ─── 비밀번호 초기화 ───
  const handleStartResetPw = () => {
    setResetPwMode(true)
    setEditingUser(false)
    setNewPassword('')
    setResetError('')
    setResetSuccess('')
  }

  const handleResetPassword = async () => {
    const token = getStoredToken()
    if (!token || !selectedUser) return
    if (newPassword.length < 8) { setResetError('8자 이상 입력해 주세요.'); return }
    setResetLoading(true)
    setResetError('')
    try {
      await adminResetPassword(token, selectedUser.id, newPassword)
      setResetSuccess('비밀번호가 초기화되었습니다.')
      setNewPassword('')
      setResetPwMode(false)
    } catch (err) {
      setResetError(err instanceof Error ? err.message : '초기화에 실패했습니다.')
    } finally {
      setResetLoading(false)
    }
  }

  // ─── 시스템 상태 로드 ───
  const loadSystemStatus = async () => {
    const token = getStoredToken()
    if (!token) return
    setSystemLoading(true)
    try {
      const startTime = Date.now()
      const [data, deepseek] = await Promise.all([
        fetchSystemStatus(token),
        fetchDeepSeekConfig(token),
      ])
      data.responseTime = Date.now() - startTime
      setSystemStatus(data)
      setDeepSeekStatus(deepseek)
      setDeepSeekModel(deepseek.model || 'deepseek-v4-flash')
      setDeepSeekBaseUrl(deepseek.baseUrl || 'https://api.deepseek.com')
    } catch (e: any) {
      setSystemStatus({ error: e.message, status: 'error' })
    } finally {
      setSystemLoading(false)
    }
  }

  const handleSaveDeepSeek = async () => {
    const token = getStoredToken()
    if (!token) return
    if (!deepSeekKey.trim()) {
      showToast('DeepSeek API key를 붙여넣어 주세요.')
      return
    }
    setDeepSeekSaving(true)
    try {
      const saved = await saveDeepSeekConfig(token, {
        apiKey: deepSeekKey.trim(),
        baseUrl: deepSeekBaseUrl.trim() || 'https://api.deepseek.com',
        model: deepSeekModel.trim() || 'deepseek-v4-flash',
      })
      setDeepSeekStatus(saved)
      setDeepSeekKey('')
      showToast('DeepSeek API key가 저장되었습니다.', 'success')
      loadSystemStatus()
    } catch (e) {
      showToast('DeepSeek 저장 실패: ' + (e instanceof Error ? e.message : '오류'))
    } finally {
      setDeepSeekSaving(false)
    }
  }

  const handleDeleteDeepSeek = async () => {
    const token = getStoredToken()
    if (!token) return
    setDeepSeekDeleting(true)
    try {
      const deleted = await deleteDeepSeekConfig(token)
      setDeepSeekStatus(deleted)
      setDeepSeekKey('')
      showToast('DeepSeek runtime key가 삭제되었습니다.', 'success')
      loadSystemStatus()
    } catch (e) {
      showToast('DeepSeek 삭제 실패: ' + (e instanceof Error ? e.message : '오류'))
    } finally {
      setDeepSeekDeleting(false)
    }
  }

  const handleTestDeepSeek = async () => {
    const token = getStoredToken()
    if (!token) return
    setDeepSeekTesting(true)
    setDeepSeekTestResult(null)
    try {
      const result = await testDeepSeekConfig(token)
      setDeepSeekTestResult(result)
      showToast(result.status === 'success' ? 'DeepSeek 연결 테스트 성공' : 'DeepSeek 연결 테스트 확인 필요', result.status === 'success' ? 'success' : 'error')
    } catch (e) {
      showToast('DeepSeek 테스트 실패: ' + (e instanceof Error ? e.message : '오류'))
    } finally {
      setDeepSeekTesting(false)
    }
  }

  const handleRefreshAll = async () => {
    const token = getStoredToken()
    if (!token) return
    setRefreshLoading(true)
    setRefreshResult(null)
    try {
      const result = await refreshAllAnalysis(token)
      setRefreshResult(result)
      loadSystemStatus()
    } catch (e: any) {
      setRefreshResult({ error: e.message })
    } finally {
      setRefreshLoading(false)
    }
  }

  // 모니터링 데이터 로드
  const loadMonitorData = useCallback(async () => {
    setMonitorLoading(true)
    try {
      const API = import.meta.env.VITE_API_BASE_URL || ''
      const token = getStoredToken()
      const headers: Record<string, string> = {}
      if (token) headers['Authorization'] = `Bearer ${token}`
      const res = await fetch(`${API}/api/monitor/health`, { headers })
      if (res.ok) setMonitorData(await res.json())
    } catch (e) { showToast('모니터 로드 실패: ' + (e instanceof Error ? e.message : '오류')) }
    finally { setMonitorLoading(false) }
  }, [])

  useEffect(() => {
    if (tab === 'system') loadSystemStatus()
    if (tab === 'monitor') loadMonitorData()
  }, [tab, loadMonitorData])

  // 자동 새로고침 (30초)
  useEffect(() => {
    if (!autoRefresh || tab !== 'monitor') return
    const id = setInterval(loadMonitorData, 30000)
    return () => clearInterval(id)
  }, [autoRefresh, tab, loadMonitorData])

  const fmt = (iso: string) => {
    const d = new Date(iso)
    return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
  }

  const subBadge = (s: string) => {
    const map: Record<string, { bg: string; color: string; border: string }> = {
      PRO: { bg: 'rgba(124,77,255,0.12)', color: '#7C4DFF', border: 'rgba(124,77,255,0.3)' },
      PENDING: { bg: 'rgba(255,152,0,0.12)', color: '#FF9800', border: 'rgba(255,152,0,0.25)' },
      FREE: { bg: 'rgba(255,255,255,0.05)', color: 'var(--text-muted)', border: 'var(--border-subtle)' },
    }
    const st = map[s] || map.FREE
    return (
      <span className="text-[8px] font-bold px-2 py-0.5 rounded-full flex-shrink-0" style={{
        backgroundColor: st.bg, color: st.color, border: `1px solid ${st.border}`,
      }}>{s}</span>
    )
  }

  // 검색 필터링 + 만료일 정렬 (가장 가까운 순)
  const filteredUsers = useMemo(() => {
    let result = allUsers

    // 검색 필터링
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase()
      result = result.filter(u =>
        u.name.toLowerCase().includes(q) || u.email.toLowerCase().includes(q)
      )
    }

    // ⭐ 정렬: 만료일 순 (가장 가까운 순)
    // 1. 만료된 사용자 먼저
    // 2. 남은 기간 적은 순
    // 3. 만료일 없는 사용자 (FREE 등) 마지막
    return result.sort((a, b) => {
      const aHasEnd = a.subscriptionEndDate && a.subscription === 'PRO'
      const bHasEnd = b.subscriptionEndDate && b.subscription === 'PRO'

      if (!aHasEnd && !bHasEnd) return 0  // 둘 다 만료일 없음
      if (!aHasEnd) return 1              // a가 없으면 뒤로
      if (!bHasEnd) return -1             // b가 없으면 a가 앞으로

      const aEnd = new Date(a.subscriptionEndDate!)
      const bEnd = new Date(b.subscriptionEndDate!)
      return aEnd.getTime() - bEnd.getTime()  // 오름차순 (가까운 순)
    })
  }, [searchQuery, allUsers])

  // ⭐ subscriptions 탭 검색 필터링
  const filteredPendingUsers = useMemo(() => {
    if (!searchQuery.trim()) return pendingUsers
    const q = searchQuery.toLowerCase()
    return pendingUsers.filter(u =>
      u.name.toLowerCase().includes(q) || u.email.toLowerCase().includes(q)
    )
  }, [searchQuery, pendingUsers])

  const filteredProUsers = useMemo(() => {
    if (!searchQuery.trim()) return allUsers.filter(u => u.subscription === 'PRO')
    const q = searchQuery.toLowerCase()
    return allUsers.filter(u =>
      u.subscription === 'PRO' &&
      (u.name.toLowerCase().includes(q) || u.email.toLowerCase().includes(q))
    )
  }, [searchQuery, allUsers])

  // 통계
  const totalUsers = allUsers.length
  const proUsers = allUsers.filter(u => u.subscription === 'PRO').length
  const pendingCount = pendingUsers.length
  const freeUsers = allUsers.filter(u => u.subscription === 'FREE').length

  const tabs: { key: Tab; icon: string; label: string }[] = [
    { key: 'dashboard', icon: '📊', label: '대시보드' },
    { key: 'subscriptions', icon: '🛡️', label: '승인/해제' },
    { key: 'members', icon: '👥', label: '회원관리' },
    { key: 'system', icon: '🔧', label: '시스템' },
    { key: 'monitor', icon: '🔍', label: '모니터링' },
  ]

  return (
    <main className="flex-1 overflow-y-auto" style={{ padding: '14px var(--page-px) 10px' }}>
      {/* 토스트 알림 */}
      {toast && (
        <div style={{ position: 'fixed', top: 16, left: '50%', transform: 'translateX(-50%)', zIndex: 9999, padding: '10px 20px', borderRadius: 8, fontSize: 13, fontWeight: 600, color: '#fff', backgroundColor: toast.type === 'error' ? '#D32F2F' : '#00C853', boxShadow: '0 4px 12px rgba(0,0,0,0.3)' }}>
          {toast.msg}
        </div>
      )}
      {/* 확인 모달 */}
      {confirmAction && (
        <div style={{ position: 'fixed', inset: 0, zIndex: 9998, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ position: 'absolute', inset: 0, backgroundColor: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)' }}
            onClick={() => setConfirmAction(null)} />
          <div style={{
            position: 'relative', width: '90%', maxWidth: 360, padding: 24, borderRadius: 16,
            backgroundColor: 'rgba(20,20,25,0.98)', border: '1px solid var(--border-subtle)',
            boxShadow: '0 8px 32px rgba(0,0,0,0.5)',
          }}>
            <p className="text-[14px] font-bold text-center mb-1" style={{ color: 'var(--text-primary)' }}>
              {confirmAction.type === 'grant' ? 'PRO 구독 부여' :
               confirmAction.type === 'approve' ? '구독 승인' :
               confirmAction.type === 'reject' ? '구독 거절' : '구독 해제'}
            </p>
            <p className="text-[12px] text-center mb-5" style={{ color: 'var(--text-muted)' }}>
              <span className="font-bold" style={{ color: 'var(--text-primary)' }}>{confirmAction.userName}</span>
              {confirmAction.type === 'grant' && ' 님에게 PRO 구독을 부여합니다.'}
              {confirmAction.type === 'approve' && ' 님의 구독 신청을 승인합니다.'}
              {confirmAction.type === 'reject' && ' 님의 구독 신청을 거절합니다.'}
              {confirmAction.type === 'revoke' && ' 님의 PRO 구독을 해제합니다.'}
            </p>
            <div className="flex gap-2">
              <button onClick={() => setConfirmAction(null)}
                className="flex-1 py-2.5 rounded-xl text-[12px] font-bold"
                style={{ backgroundColor: 'rgba(255,255,255,0.05)', color: 'var(--text-muted)', border: '1px solid var(--border-subtle)' }}>
                취소
              </button>
              <button onClick={executeQuickAction}
                className="flex-1 py-2.5 rounded-xl text-[12px] font-bold transition-all duration-150"
                style={{
                  backgroundColor: confirmAction.type === 'reject' || confirmAction.type === 'revoke'
                    ? 'rgba(255,23,68,0.15)' : 'rgba(124,77,255,0.15)',
                  color: confirmAction.type === 'reject' || confirmAction.type === 'revoke'
                    ? '#FF1744' : '#7C4DFF',
                  border: `1px solid ${confirmAction.type === 'reject' || confirmAction.type === 'revoke'
                    ? 'rgba(255,23,68,0.3)' : 'rgba(124,77,255,0.3)'}`,
                }}>
                확인
              </button>
            </div>
          </div>
        </div>
      )}
      <div className="flex flex-col gap-4" style={{ maxWidth: '480px', margin: '0 auto', width: '100%' }}>

          {/* 타이틀 */}
          <div className="animate-slide-up text-center" style={{ animationFillMode: 'backwards' }}>
            <h1 className="text-[20px] font-black" style={{ color: '#FFD700' }}>관리자 패널</h1>
            <p className="text-[11px] mt-1" style={{ color: 'var(--text-muted)' }}>BitMan 서비스 관리</p>
          </div>

          {/* 탭 바 */}
          <div className="animate-slide-up flex rounded-xl overflow-hidden" style={{
            animationDelay: '0.05s', animationFillMode: 'backwards',
            backgroundColor: 'rgba(255,255,255,0.04)', border: '1px solid var(--border-subtle)',
          }}>
            {tabs.map(t => (
              <button
                key={t.key}
                onClick={() => changeTab(t.key)}
                className="flex-1 py-2.5 text-[11px] font-bold transition-all duration-200 flex items-center justify-center gap-1"
                style={{
                  backgroundColor: tab === t.key ? 'rgba(255,215,0,0.1)' : 'transparent',
                  color: tab === t.key ? '#FFD700' : 'var(--text-muted)',
                  borderBottom: tab === t.key ? '2px solid #FFD700' : '2px solid transparent',
                }}
              >
                <span>{t.icon}</span> {t.label}
                {t.key === 'subscriptions' && pendingCount > 0 && (
                  <span className="ml-0.5 w-4 h-4 rounded-full text-[9px] flex items-center justify-center font-black" style={{
                    backgroundColor: 'rgba(255,23,68,0.2)', color: '#FF1744',
                  }}>{pendingCount}</span>
                )}
              </button>
            ))}
          </div>

          {loading ? (
            <div className="flex items-center justify-center py-16">
              <div className="w-8 h-8 border-2 border-t-transparent rounded-full animate-spin" style={{ borderColor: 'rgba(255,215,0,0.3)', borderTopColor: 'transparent' }} />
            </div>
          ) : (
            <>
              {/* ===== 대시보드 탭 ===== */}
              {tab === 'dashboard' && (
                <div className="flex flex-col gap-3 animate-slide-up" style={{ animationDelay: '0.1s', animationFillMode: 'backwards' }}>
                  <div className="grid grid-cols-2 gap-3">
                    {[
                      { icon: '👥', label: '전체 회원', value: totalUsers, color: '#FFD700' },
                      { icon: '💎', label: 'PRO 구독', value: proUsers, color: '#7C4DFF' },
                      { icon: '⏳', label: '승인 대기', value: pendingCount, color: '#FF9800' },
                      { icon: '🆓', label: 'FREE 회원', value: freeUsers, color: 'var(--text-muted)' },
                    ].map(s => (
                      <GlassCard key={s.label}>
                        <div className="text-center">
                          <span className="text-lg block">{s.icon}</span>
                          <span className="text-[22px] font-black block mt-1" style={{ color: s.color }}>{s.value}</span>
                          <span className="text-[10px] block" style={{ color: 'var(--text-muted)' }}>{s.label}</span>
                        </div>
                      </GlassCard>
                    ))}
                  </div>

                  <GlassCard>
                    <div className="flex items-center gap-2 mb-3">
                      <span className="text-sm">🕐</span>
                      <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>최근 가입 회원</span>
                    </div>
                    <div className="flex flex-col gap-2">
                      {allUsers.slice(-5).reverse().map(u => (
                        <div key={u.id} className="flex items-center justify-between py-1.5" style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                          <div className="flex items-center gap-2">
                            <div className="w-7 h-7 rounded-lg flex items-center justify-center" style={{
                              background: 'linear-gradient(135deg, rgba(255,215,0,0.15), rgba(124,77,255,0.1))',
                            }}>
                              <span className="text-[11px] font-black" style={{ color: '#FFD700' }}>{u.name.charAt(0).toUpperCase()}</span>
                            </div>
                            <div>
                              <span className="text-[12px] font-medium block" style={{ color: 'var(--text-primary)' }}>{u.name}</span>
                              <span className="text-[9px] block" style={{ color: 'var(--text-muted)' }}>{u.email}</span>
                            </div>
                          </div>
                          <div className="text-right">
                            {subBadge(u.subscription)}
                            <span className="text-[9px] block mt-0.5" style={{ color: 'var(--text-muted)' }}>{fmt(u.createdAt)}</span>
                          </div>
                        </div>
                      ))}
                    </div>
                  </GlassCard>
                </div>
              )}

              {/* ===== 구독 승인/해제 탭 ===== */}
              {tab === 'subscriptions' && (
                <div className="flex flex-col gap-3 animate-slide-up" style={{ animationDelay: '0.1s', animationFillMode: 'backwards' }}>
                  {/* ⭐ 검색 입력창 */}
                  <div className="flex items-center gap-2" style={{
                    padding: '10px 14px', borderRadius: '12px',
                    backgroundColor: 'var(--bg-elevated)', border: '1px solid var(--border-default)',
                  }}>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
                      style={{ color: 'var(--text-muted)', flexShrink: 0 }}>
                      <circle cx="11" cy="11" r="8" /><path d="M21 21l-4.35-4.35" />
                    </svg>
                    <input
                      type="text"
                      value={searchQuery}
                      onChange={e => setSearchQuery(e.target.value)}
                      placeholder="승인 대기 또는 PRO 구독자 검색..."
                      className="flex-1 bg-transparent text-[12px] outline-none"
                      style={{ color: 'var(--text-primary)' }}
                    />
                    {searchQuery && (
                      <button onClick={() => setSearchQuery('')}
                        className="text-[10px] font-medium px-2 py-0.5 rounded-lg"
                        style={{ color: 'var(--text-muted)', backgroundColor: 'rgba(255,255,255,0.05)' }}>
                        초기화
                      </button>
                    )}
                  </div>

                  <div className="flex items-center gap-2 mt-1">
                    <span className="text-sm">⏳</span>
                    <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>승인 대기</span>
                    <span className="text-[11px] font-bold px-1.5 py-0.5 rounded-full" style={{
                      backgroundColor: pendingCount > 0 ? 'rgba(255,152,0,0.12)' : 'rgba(255,255,255,0.05)',
                      color: pendingCount > 0 ? '#FF9800' : 'var(--text-muted)',
                    }}>{pendingCount}건{searchQuery && ` / ${filteredPendingUsers.length}건`}</span>
                  </div>

                  {filteredPendingUsers.length === 0 ? (
                    <GlassCard>
                      <p className="text-center text-[12px] py-4" style={{ color: 'var(--text-muted)' }}>
                        {searchQuery ? '검색 결과가 없습니다' : '대기 중인 신청이 없습니다'}
                      </p>
                    </GlassCard>
                  ) : filteredPendingUsers.map(u => (
                    <GlassCard key={u.id}>
                      <div className="flex items-start justify-between mb-2">
                        <div className="flex items-center gap-2">
                          <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{
                            background: 'linear-gradient(135deg, rgba(255,152,0,0.2), rgba(255,215,0,0.1))',
                            border: '1.5px solid rgba(255,152,0,0.3)',
                          }}>
                            <span className="font-black text-[14px]" style={{ color: '#FF9800' }}>{u.name.charAt(0).toUpperCase()}</span>
                          </div>
                          <div>
                            <span className="text-[13px] font-bold block" style={{ color: 'var(--text-primary)' }}>{u.name}</span>
                            <span className="text-[10px] block" style={{ color: 'var(--text-muted)' }}>{u.email}</span>
                          </div>
                        </div>
                        {subBadge('PENDING')}
                      </div>
                      <div className="rounded-lg p-2 mb-2" style={{ backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)' }}>
                        <div className="flex justify-between text-[10px]">
                          <span style={{ color: 'var(--text-muted)' }}>입금자명</span>
                          <span className="font-bold" style={{ color: '#FFD700' }}>{u.depositorName || '—'}</span>
                        </div>
                        <div className="flex justify-between text-[10px] mt-0.5">
                          <span style={{ color: 'var(--text-muted)' }}>가입일</span>
                          <span style={{ color: 'var(--text-secondary)' }}>{fmt(u.createdAt)}</span>
                        </div>
                      </div>
                      <div className="flex gap-2">
                        <button onClick={() => handleApprove(u.id)} disabled={actionId === u.id}
                          className="flex-1 py-2 rounded-xl text-[11px] font-bold" style={{
                            backgroundColor: 'rgba(0,200,83,0.1)', color: 'var(--color-bull)',
                            border: '1px solid rgba(0,200,83,0.25)', opacity: actionId === u.id ? 0.5 : 1,
                          }}>승인</button>
                        <button onClick={() => handleReject(u.id)} disabled={actionId === u.id}
                          className="flex-1 py-2 rounded-xl text-[11px] font-bold" style={{
                            backgroundColor: 'rgba(255,23,68,0.1)', color: 'var(--color-bear)',
                            border: '1px solid rgba(255,23,68,0.25)', opacity: actionId === u.id ? 0.5 : 1,
                          }}>거절</button>
                      </div>
                    </GlassCard>
                  ))}

                  <div className="flex items-center gap-2 mt-3">
                    <span className="text-sm">💎</span>
                    <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>PRO 구독자</span>
                    <span className="text-[11px] font-bold px-1.5 py-0.5 rounded-full" style={{
                      backgroundColor: 'rgba(124,77,255,0.12)', color: '#7C4DFF',
                    }}>{proUsers}명{searchQuery && ` / ${filteredProUsers.length}명`}</span>
                  </div>

                  {filteredProUsers.length === 0 ? (
                    <GlassCard>
                      <p className="text-center text-[12px] py-4" style={{ color: 'var(--text-muted)' }}>
                        {searchQuery ? '검색 결과가 없습니다' : 'PRO 구독자가 없습니다'}
                      </p>
                    </GlassCard>
                  ) : filteredProUsers.map(u => (
                    <GlassCard key={u.id}>
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{
                            background: 'linear-gradient(135deg, rgba(124,77,255,0.2), rgba(255,215,0,0.1))',
                            border: '1.5px solid rgba(124,77,255,0.3)',
                          }}>
                            <span className="font-black text-[14px]" style={{ color: '#7C4DFF' }}>{u.name.charAt(0).toUpperCase()}</span>
                          </div>
                          <div>
                            <span className="text-[13px] font-bold block" style={{ color: 'var(--text-primary)' }}>{u.name}</span>
                            <span className="text-[9px] block" style={{ color: 'var(--text-muted)' }}>만료: {u.subscriptionEndDate || '무기한'}</span>
                          </div>
                        </div>
                        <button onClick={() => handleRevoke(u.id)} disabled={actionId === u.id}
                          className="px-3 py-1.5 rounded-lg text-[10px] font-bold" style={{
                            backgroundColor: 'rgba(255,23,68,0.08)', color: 'var(--color-bear)',
                            border: '1px solid rgba(255,23,68,0.2)', opacity: actionId === u.id ? 0.5 : 1,
                          }}>{actionId === u.id ? '...' : '해제'}</button>
                      </div>
                    </GlassCard>
                  ))}
                </div>
              )}

              {/* ===== 회원 관리 탭 (강화) ===== */}
              {tab === 'members' && (
                <div className="flex flex-col gap-3 animate-slide-up" style={{ animationDelay: '0.1s', animationFillMode: 'backwards' }}>

                  {/* 검색바 */}
                  <div className="flex items-center gap-2" style={{
                    padding: '10px 14px', borderRadius: '12px',
                    backgroundColor: 'var(--bg-elevated)', border: '1px solid var(--border-default)',
                  }}>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
                      style={{ color: 'var(--text-muted)', flexShrink: 0 }}>
                      <circle cx="11" cy="11" r="8" /><path d="M21 21l-4.35-4.35" />
                    </svg>
                    <input
                      type="text"
                      value={searchQuery}
                      onChange={e => setSearchQuery(e.target.value)}
                      placeholder="이름 또는 이메일로 검색..."
                      className="flex-1 bg-transparent text-[12px] outline-none"
                      style={{ color: 'var(--text-primary)' }}
                    />
                    {searchQuery && (
                      <button onClick={() => setSearchQuery('')}
                        className="text-[10px] font-medium px-2 py-0.5 rounded-lg"
                        style={{ color: 'var(--text-muted)', backgroundColor: 'rgba(255,255,255,0.05)' }}>
                        초기화
                      </button>
                    )}
                  </div>

                  {/* 회원 목록 헤더 */}
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span className="text-sm">👥</span>
                      <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>
                        {searchQuery ? '검색 결과' : '전체 회원'}
                      </span>
                    </div>
                    <span className="text-[11px]" style={{ color: 'var(--text-muted)' }}>
                      {filteredUsers.length}명{searchQuery ? ` / ${totalUsers}명` : ''}
                    </span>
                  </div>

                  {/* 선택된 회원 상세 패널 */}
                  {selectedUser && (
                    <GlassCard>
                      <div className="flex flex-col gap-3">
                        {/* 헤더 */}
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <div className="w-10 h-10 rounded-xl flex items-center justify-center" style={{
                              background: selectedUser.role === 'ADMIN'
                                ? 'linear-gradient(135deg, rgba(255,215,0,0.25), rgba(255,152,0,0.15))'
                                : 'linear-gradient(135deg, rgba(124,77,255,0.2), rgba(255,215,0,0.1))',
                              border: selectedUser.role === 'ADMIN'
                                ? '1.5px solid rgba(255,215,0,0.4)'
                                : '1.5px solid rgba(124,77,255,0.3)',
                            }}>
                              <span className="font-black text-[16px]" style={{
                                color: selectedUser.role === 'ADMIN' ? '#FFD700' : '#7C4DFF',
                              }}>{selectedUser.name.charAt(0).toUpperCase()}</span>
                            </div>
                            <div>
                              <div className="flex items-center gap-1.5">
                                <span className="text-[14px] font-bold" style={{ color: 'var(--text-primary)' }}>{selectedUser.name}</span>
                                {selectedUser.role === 'ADMIN' && (
                                  <span className="text-[7px] font-black px-1.5 py-0.5 rounded-full" style={{
                                    backgroundColor: 'rgba(255,215,0,0.15)', color: '#FFD700', border: '1px solid rgba(255,215,0,0.3)',
                                  }}>ADMIN</span>
                                )}
                                {subBadge(selectedUser.subscription)}
                              </div>
                              <span className="text-[10px] block" style={{ color: 'var(--text-muted)' }}>{selectedUser.email}</span>
                            </div>
                          </div>
                          <button onClick={() => { setSelectedUser(null); setEditingUser(false); setResetPwMode(false) }}
                            className="w-7 h-7 rounded-lg flex items-center justify-center"
                            style={{ backgroundColor: 'rgba(255,255,255,0.05)' }}>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" strokeWidth="2"><path d="M18 6L6 18M6 6l12 12"/></svg>
                          </button>
                        </div>

                        {/* 정보 요약 */}
                        <div className="rounded-lg p-2.5" style={{ backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)' }}>
                          {(() => {
                            const items: Array<{ k: string; v: string; color?: string }> = [
                              { k: '가입일', v: fmt(selectedUser.createdAt) },
                              { k: '구독', v: selectedUser.subscription },
                            ]

                            // ⭐ PRO 부여일 (승인일)
                            if (selectedUser.subscription === 'PRO' && selectedUser.subscriptionApprovedAt) {
                              items.push({ k: 'PRO부여일', v: fmt(selectedUser.subscriptionApprovedAt) })
                            }

                            // ⭐ 만료일 + 남은 기간
                            if (selectedUser.subscriptionEndDate) {
                              const endDate = new Date(selectedUser.subscriptionEndDate)
                              const now = new Date()
                              const daysLeft = Math.ceil((endDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24))
                              const isExpired = daysLeft < 0

                              items.push({
                                k: '만료일',
                                v: fmt(selectedUser.subscriptionEndDate),
                                color: isExpired ? '#FF6B6B' : daysLeft < 7 ? '#FFD93D' : 'var(--text-secondary)'
                              })

                              if (!isExpired) {
                                items.push({
                                  k: '남은기간',
                                  v: `${daysLeft}일`,
                                  color: daysLeft < 7 ? '#FFD93D' : 'var(--text-secondary)'
                                })
                              } else {
                                items.push({
                                  k: '상태',
                                  v: `❌ ${Math.abs(daysLeft)}일 전 만료`,
                                  color: '#FF6B6B'
                                })
                              }
                            }

                            items.push({ k: '입금자명', v: selectedUser.depositorName || '—' })

                            return items.map(r => (
                              <div key={r.k} className="flex justify-between text-[10px] py-0.5">
                                <span style={{ color: 'var(--text-muted)' }}>{r.k}</span>
                                <span className="font-medium" style={{ color: r.color || 'var(--text-secondary)' }}>{r.v}</span>
                              </div>
                            ))
                          })()}
                        </div>

                        {/* 성공/에러 메시지 */}
                        {(editSuccess || resetSuccess) && (
                          <div className="flex items-center gap-2 px-3 py-2 rounded-lg text-[12px] font-medium"
                            style={{ backgroundColor: 'rgba(0,200,83,0.08)', border: '1px solid rgba(0,200,83,0.2)', color: 'var(--color-bull)' }}>
                            {editSuccess || resetSuccess}
                          </div>
                        )}
                        {(editError || resetError) && (
                          <div className="flex items-center gap-2 px-3 py-2 rounded-lg text-[12px] font-medium"
                            style={{ backgroundColor: 'rgba(255,23,68,0.08)', border: '1px solid rgba(255,23,68,0.2)', color: 'var(--color-bear)' }}>
                            {editError || resetError}
                          </div>
                        )}

                        {/* 편집 폼 (조건부) */}
                        {editingUser && (
                          <div className="flex flex-col gap-2.5 pt-1">
                            <FormInput label="이름" type="text" value={editName} onChange={e => setEditName(e.target.value)} delay={0}
                              icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>} />
                            <FormInput label="이메일" type="email" value={editEmail} onChange={e => setEditEmail(e.target.value)} delay={0}
                              icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="M22 7l-10 6L2 7"/></svg>} />

                            {/* 구독 상태 선택 */}
                            <div>
                              <label className="text-[11px] font-medium block mb-1.5" style={{ color: 'var(--text-secondary)' }}>구독 상태</label>
                              <div className="flex gap-2">
                                {(['FREE', 'PENDING', 'PRO'] as const).map(s => {
                                  const active = editSub === s
                                  const colors: Record<string, { bg: string; color: string; border: string }> = {
                                    FREE: { bg: 'rgba(255,255,255,0.05)', color: 'var(--text-muted)', border: 'var(--border-subtle)' },
                                    PENDING: { bg: 'rgba(255,152,0,0.12)', color: '#FF9800', border: 'rgba(255,152,0,0.3)' },
                                    PRO: { bg: 'rgba(124,77,255,0.12)', color: '#7C4DFF', border: 'rgba(124,77,255,0.3)' },
                                  }
                                  const c = colors[s]
                                  return (
                                    <button key={s} onClick={() => setEditSub(s)}
                                      className="flex-1 py-2 rounded-lg text-[11px] font-bold transition-all duration-150"
                                      style={{
                                        backgroundColor: active ? c.bg : 'transparent',
                                        color: active ? c.color : 'var(--text-muted)',
                                        border: `1.5px solid ${active ? c.border : 'var(--border-subtle)'}`,
                                        opacity: active ? 1 : 0.5,
                                      }}>
                                      {s}
                                    </button>
                                  )
                                })}
                              </div>
                            </div>

                            <div className="flex gap-2 mt-1">
                              <button onClick={() => setEditingUser(false)}
                                className="flex-1 py-2.5 rounded-xl text-[11px] font-bold"
                                style={{ backgroundColor: 'rgba(255,255,255,0.05)', color: 'var(--text-muted)', border: '1px solid var(--border-subtle)' }}>
                                취소
                              </button>
                              <div className="flex-1">
                                <GoldButton onClick={handleSaveUser} loading={editLoading} delay={0}>저장</GoldButton>
                              </div>
                            </div>
                          </div>
                        )}

                        {/* 비밀번호 초기화 폼 (조건부) */}
                        {resetPwMode && (
                          <div className="flex flex-col gap-2.5 pt-1">
                            <FormInput label="새 비밀번호" type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)}
                              placeholder="8자 이상" delay={0}
                              icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/></svg>} />
                            <div className="flex gap-2">
                              <button onClick={() => setResetPwMode(false)}
                                className="flex-1 py-2.5 rounded-xl text-[11px] font-bold"
                                style={{ backgroundColor: 'rgba(255,255,255,0.05)', color: 'var(--text-muted)', border: '1px solid var(--border-subtle)' }}>
                                취소
                              </button>
                              <div className="flex-1">
                                <GoldButton onClick={handleResetPassword} loading={resetLoading} delay={0}>초기화</GoldButton>
                              </div>
                            </div>
                          </div>
                        )}

                        {/* 액션 버튼들 (편집/초기화 모드가 아닐 때) */}
                        {!editingUser && !resetPwMode && (
                          <div className="flex gap-2">
                            <button onClick={handleStartEdit}
                              className="flex-1 py-2 rounded-xl text-[11px] font-bold transition-all duration-150"
                              style={{ backgroundColor: 'rgba(255,215,0,0.08)', color: '#FFD700', border: '1px solid rgba(255,215,0,0.2)' }}
                              onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,215,0,0.15)' }}
                              onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(255,215,0,0.08)' }}>
                              정보 수정
                            </button>
                            <button onClick={handleStartResetPw}
                              className="flex-1 py-2 rounded-xl text-[11px] font-bold transition-all duration-150"
                              style={{ backgroundColor: 'rgba(255,152,0,0.08)', color: '#FF9800', border: '1px solid rgba(255,152,0,0.2)' }}
                              onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,152,0,0.15)' }}
                              onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(255,152,0,0.08)' }}>
                              비밀번호 초기화
                            </button>
                          </div>
                        )}
                      </div>
                    </GlassCard>
                  )}

                  {/* 회원 카드 목록 */}
                  {filteredUsers.length === 0 ? (
                    <GlassCard>
                      <p className="text-center text-[12px] py-4" style={{ color: 'var(--text-muted)' }}>
                        {searchQuery ? '검색 결과가 없습니다' : '등록된 회원이 없습니다'}
                      </p>
                    </GlassCard>
                  ) : filteredUsers.map(u => (
                    <div
                      key={u.id}
                      className="w-full text-left transition-all duration-150 rounded-2xl"
                      style={{
                        padding: '14px 16px',
                        backgroundColor: selectedUser?.id === u.id ? 'rgba(255,215,0,0.06)' : 'rgba(255,255,255,0.03)',
                        border: selectedUser?.id === u.id ? '1px solid rgba(255,215,0,0.2)' : '1px solid var(--border-subtle)',
                        backdropFilter: 'blur(20px)',
                      }}
                      onMouseEnter={e => { if (selectedUser?.id !== u.id) e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.05)' }}
                      onMouseLeave={e => { if (selectedUser?.id !== u.id) e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.03)' }}
                    >
                      <button onClick={() => handleSelectUser(u)} className="w-full text-left">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2.5">
                            <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{
                              background: u.role === 'ADMIN'
                                ? 'linear-gradient(135deg, rgba(255,215,0,0.25), rgba(255,152,0,0.15))'
                                : 'linear-gradient(135deg, rgba(255,255,255,0.08), rgba(255,255,255,0.04))',
                              border: u.role === 'ADMIN'
                                ? '1.5px solid rgba(255,215,0,0.4)'
                                : '1.5px solid var(--border-subtle)',
                            }}>
                              <span className="font-black text-[14px]" style={{
                                color: u.role === 'ADMIN' ? '#FFD700' : 'var(--text-secondary)',
                              }}>{u.name.charAt(0).toUpperCase()}</span>
                            </div>
                            <div>
                              <div className="flex items-center gap-1.5">
                                <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>{u.name}</span>
                                {u.role === 'ADMIN' && (
                                  <span className="text-[7px] font-black px-1.5 py-0.5 rounded-full" style={{
                                    backgroundColor: 'rgba(255,215,0,0.15)', color: '#FFD700', border: '1px solid rgba(255,215,0,0.3)',
                                  }}>ADMIN</span>
                                )}
                              </div>
                              <span className="text-[10px] block" style={{ color: 'var(--text-muted)' }}>{u.email}</span>
                            </div>
                          </div>
                          <div className="text-right flex flex-col items-end gap-0.5">
                            {subBadge(u.subscription)}
                            <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>{fmt(u.createdAt)}</span>

                            {/* ⭐ PRO 사용자: 만료일 + 남은 기간 */}
                            {u.subscription === 'PRO' && u.subscriptionEndDate && (() => {
                              const endDate = new Date(u.subscriptionEndDate)
                              const now = new Date()
                              const daysLeft = Math.ceil((endDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24))
                              const isExpired = daysLeft < 0
                              const color = isExpired ? '#FF6B6B' : daysLeft < 7 ? '#FFD93D' : 'var(--text-secondary)'

                              return (
                                <span className="text-[9px] font-medium" style={{ color }}>
                                  {isExpired ? `❌ ${Math.abs(daysLeft)}일 전 만료` : `${daysLeft}일 남음`}
                                </span>
                              )
                            })()}
                          </div>
                        </div>
                      </button>

                      {/* 구독 관리 인라인 버튼 (ADMIN 제외) */}
                      {u.role !== 'ADMIN' && (
                        <div className="flex gap-1.5 mt-2 pt-2" style={{ borderTop: '1px solid var(--border-subtle)' }}>
                          {u.subscription === 'FREE' && (
                            <button
                              disabled={actionId === u.id}
                              onClick={e => { e.stopPropagation(); setConfirmAction({ userId: u.id, userName: u.name, type: 'grant' }) }}
                              className="flex-1 py-1.5 rounded-lg text-[10px] font-bold transition-all duration-150"
                              style={{ backgroundColor: 'rgba(124,77,255,0.1)', color: '#7C4DFF', border: '1px solid rgba(124,77,255,0.25)' }}
                              onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(124,77,255,0.2)' }}
                              onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(124,77,255,0.1)' }}>
                              {actionId === u.id ? '...' : 'PRO 부여'}
                            </button>
                          )}
                          {u.subscription === 'PENDING' && (
                            <>
                              <button
                                disabled={actionId === u.id}
                                onClick={e => { e.stopPropagation(); setConfirmAction({ userId: u.id, userName: u.name, type: 'approve' }) }}
                                className="flex-1 py-1.5 rounded-lg text-[10px] font-bold transition-all duration-150"
                                style={{ backgroundColor: 'rgba(0,200,83,0.1)', color: '#00C853', border: '1px solid rgba(0,200,83,0.25)' }}
                                onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(0,200,83,0.2)' }}
                                onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(0,200,83,0.1)' }}>
                                {actionId === u.id ? '...' : '승인'}
                              </button>
                              <button
                                disabled={actionId === u.id}
                                onClick={e => { e.stopPropagation(); setConfirmAction({ userId: u.id, userName: u.name, type: 'reject' }) }}
                                className="flex-1 py-1.5 rounded-lg text-[10px] font-bold transition-all duration-150"
                                style={{ backgroundColor: 'rgba(255,23,68,0.08)', color: '#FF1744', border: '1px solid rgba(255,23,68,0.2)' }}
                                onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,23,68,0.15)' }}
                                onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(255,23,68,0.08)' }}>
                                {actionId === u.id ? '...' : '거절'}
                              </button>
                            </>
                          )}
                          {u.subscription === 'PRO' && (
                            <button
                              disabled={actionId === u.id}
                              onClick={e => { e.stopPropagation(); setConfirmAction({ userId: u.id, userName: u.name, type: 'revoke' }) }}
                              className="flex-1 py-1.5 rounded-lg text-[10px] font-bold transition-all duration-150"
                              style={{ backgroundColor: 'rgba(255,23,68,0.08)', color: '#FF1744', border: '1px solid rgba(255,23,68,0.2)' }}
                              onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,23,68,0.15)' }}
                              onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(255,23,68,0.08)' }}>
                              {actionId === u.id ? '...' : 'PRO 해제'}
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}

              {/* ===== 시스템 관리 탭 ===== */}
              {tab === 'system' && (
                <div className="flex flex-col gap-3 animate-slide-up" style={{ animationDelay: '0.1s', animationFillMode: 'backwards' }}>
                  {systemLoading ? (
                    <div className="flex items-center justify-center py-16">
                      <div className="w-8 h-8 border-2 border-t-transparent rounded-full animate-spin" style={{ borderColor: 'rgba(255,215,0,0.3)', borderTopColor: 'transparent' }} />
                    </div>
                  ) : systemStatus?.error ? (
                    <GlassCard>
                      <div className="text-center py-4">
                        <span className="text-lg block mb-2">⚠️</span>
                        <p className="text-[12px] font-medium" style={{ color: '#FF1744' }}>{systemStatus.error}</p>
                        <button onClick={loadSystemStatus} className="mt-3 px-4 py-1.5 rounded-lg text-[11px] font-bold" style={{
                          backgroundColor: 'rgba(255,215,0,0.08)', color: '#FFD700', border: '1px solid rgba(255,215,0,0.2)',
                        }}>재시도</button>
                      </div>
                    </GlassCard>
                  ) : systemStatus ? (
                    <>
                      <GlassCard>
                        <div className="flex items-center justify-between mb-3">
                          <div className="flex items-center gap-2">
                            <span className="text-sm">DS</span>
                            <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>DeepSeek API Key</span>
                          </div>
                          <span className="text-[10px] font-bold px-2 py-0.5 rounded-full" style={{
                            backgroundColor: deepSeekStatus?.configured ? 'rgba(0,200,83,0.1)' : 'rgba(255,23,68,0.08)',
                            color: deepSeekStatus?.configured ? '#00C853' : '#FF1744',
                            border: `1px solid ${deepSeekStatus?.configured ? 'rgba(0,200,83,0.25)' : 'rgba(255,23,68,0.2)'}`,
                          }}>
                            {deepSeekStatus?.configured ? 'CONNECTED' : 'NOT SET'}
                          </span>
                        </div>

                        <div className="rounded-lg p-3 mb-3" style={{ backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)' }}>
                          <div className="flex items-center justify-between text-[11px] mb-1">
                            <span style={{ color: 'var(--text-muted)' }}>Source</span>
                            <span className="font-bold" style={{ color: 'var(--text-primary)' }}>{deepSeekStatus?.source || 'none'}</span>
                          </div>
                          <div className="flex items-center justify-between text-[11px]">
                            <span style={{ color: 'var(--text-muted)' }}>Key</span>
                            <span className="font-mono" style={{ color: deepSeekStatus?.configured ? '#00C853' : 'var(--text-muted)' }}>
                              {deepSeekStatus?.maskedKey || 'empty'}
                            </span>
                          </div>
                        </div>

                        <div className="flex flex-col gap-2">
                          <input
                            type="password"
                            value={deepSeekKey}
                            onChange={e => setDeepSeekKey(e.target.value)}
                            placeholder="DeepSeek API key 붙여넣기"
                            autoComplete="off"
                            className="w-full rounded-xl px-3 py-3 text-[12px] outline-none"
                            style={{ backgroundColor: 'rgba(255,255,255,0.05)', color: 'var(--text-primary)', border: '1px solid var(--border-subtle)' }}
                          />
                          <input
                            value={deepSeekModel}
                            onChange={e => setDeepSeekModel(e.target.value)}
                            placeholder="deepseek-v4-flash"
                            className="w-full rounded-xl px-3 py-2.5 text-[12px] outline-none"
                            style={{ backgroundColor: 'rgba(255,255,255,0.04)', color: 'var(--text-primary)', border: '1px solid var(--border-subtle)' }}
                          />
                          <input
                            value={deepSeekBaseUrl}
                            onChange={e => setDeepSeekBaseUrl(e.target.value)}
                            placeholder="https://api.deepseek.com"
                            className="w-full rounded-xl px-3 py-2.5 text-[12px] outline-none"
                            style={{ backgroundColor: 'rgba(255,255,255,0.04)', color: 'var(--text-primary)', border: '1px solid var(--border-subtle)' }}
                          />
                          <div className="flex gap-2 mt-1">
                            <button
                              onClick={handleSaveDeepSeek}
                              disabled={deepSeekSaving}
                              className="flex-1 py-2.5 rounded-xl text-[12px] font-black transition-all duration-200"
                              style={{
                                background: deepSeekSaving ? 'rgba(255,215,0,0.1)' : 'linear-gradient(135deg, #FFD700, #FF9800)',
                                color: deepSeekSaving ? '#FFD700' : '#0D1117',
                                border: deepSeekSaving ? '1px solid rgba(255,215,0,0.2)' : 'none',
                              }}
                            >
                              {deepSeekSaving ? '저장 중...' : '붙여넣은 키 저장'}
                            </button>
                            <button
                              onClick={handleDeleteDeepSeek}
                              disabled={deepSeekDeleting || !deepSeekStatus?.configured}
                              className="px-4 py-2.5 rounded-xl text-[12px] font-bold"
                              style={{
                                backgroundColor: 'rgba(255,23,68,0.08)',
                                color: deepSeekStatus?.configured ? '#FF1744' : 'var(--text-muted)',
                                border: '1px solid rgba(255,23,68,0.18)',
                                opacity: deepSeekStatus?.configured ? 1 : 0.5,
                              }}
                            >
                              삭제
                            </button>
                          </div>
                          <button
                            onClick={handleTestDeepSeek}
                            disabled={deepSeekTesting || !deepSeekStatus?.configured}
                            className="w-full py-2.5 rounded-xl text-[12px] font-bold"
                            style={{
                              backgroundColor: 'rgba(0,200,83,0.08)',
                              color: deepSeekStatus?.configured ? '#00C853' : 'var(--text-muted)',
                              border: '1px solid rgba(0,200,83,0.18)',
                              opacity: deepSeekStatus?.configured ? 1 : 0.5,
                            }}
                          >
                            {deepSeekTesting ? '연결 테스트 중...' : 'DeepSeek 연결 테스트'}
                          </button>
                          {deepSeekTestResult && (
                            <div className="rounded-lg p-3 text-[11px]" style={{ backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)' }}>
                              <div className="flex items-center justify-between mb-1">
                                <span style={{ color: 'var(--text-muted)' }}>Status</span>
                                <span className="font-bold" style={{ color: deepSeekTestResult.status === 'success' ? '#00C853' : '#FF1744' }}>{deepSeekTestResult.status}</span>
                              </div>
                              <div className="flex items-center justify-between mb-1">
                                <span style={{ color: 'var(--text-muted)' }}>Latency</span>
                                <span className="font-mono" style={{ color: 'var(--text-primary)' }}>{deepSeekTestResult.latencyMs ?? '-'}ms</span>
                              </div>
                              {deepSeekTestResult.error && (
                                <p style={{ color: '#FF1744' }}>{deepSeekTestResult.error}</p>
                              )}
                            </div>
                          )}
                        </div>
                      </GlassCard>

                      {/* 서버 상태 */}
                      <GlassCard>
                        <div className="flex items-center gap-2 mb-3">
                          <span className="text-sm">🖥️</span>
                          <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>서버 상태</span>
                        </div>
                        <div className="rounded-lg p-3" style={{ backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)' }}>
                          <div className="flex items-center justify-between mb-2">
                            <div className="flex items-center gap-2">
                              <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: '#00C853', boxShadow: '0 0 6px rgba(0,200,83,0.5)' }} />
                              <span className="text-[12px] font-bold" style={{ color: '#00C853' }}>서버 온라인</span>
                            </div>
                            <span className="text-[10px] font-mono" style={{ color: 'var(--text-muted)' }}>
                              {systemStatus.responseTime}ms
                            </span>
                          </div>
                          <div className="text-[10px]" style={{ color: 'var(--text-muted)' }}>
                            {systemStatus.timestamp ? new Date(systemStatus.timestamp).toLocaleString('ko-KR') : new Date().toLocaleString('ko-KR')}
                          </div>
                        </div>
                      </GlassCard>

                      {/* AI 엔진 상태 */}
                      <GlassCard>
                        <div className="flex items-center justify-between mb-3">
                          <div className="flex items-center gap-2">
                            <span className="text-sm">🤖</span>
                            <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>AI 엔진 상태</span>
                          </div>
                          <span className="text-[11px] font-bold px-2 py-0.5 rounded-full" style={{
                            backgroundColor: 'rgba(0,200,83,0.1)', color: '#00C853', border: '1px solid rgba(0,200,83,0.2)',
                          }}>
                            {systemStatus.engines ? systemStatus.engines.filter((e: any) => e.online).length : 5}/5 ONLINE
                          </span>
                        </div>
                        <div className="flex flex-col gap-2">
                          {(systemStatus.engines || [
                            { name: 'Claude', online: true },
                            { name: 'Gemini', online: true },
                            { name: 'ChatGPT', online: true },
                            { name: 'Perplexity', online: true },
                            { name: 'Grok', online: true },
                          ]).map((engine: any) => (
                            <div key={engine.name} className="flex items-center justify-between py-1.5 px-3 rounded-lg" style={{
                              backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)',
                            }}>
                              <span className="text-[12px] font-medium" style={{ color: 'var(--text-primary)' }}>{engine.name}</span>
                              <div className="flex items-center gap-1.5">
                                <span className="w-2 h-2 rounded-full" style={{
                                  backgroundColor: engine.online ? '#00C853' : '#FF1744',
                                  boxShadow: engine.online ? '0 0 4px rgba(0,200,83,0.4)' : '0 0 4px rgba(255,23,68,0.4)',
                                }} />
                                <span className="text-[10px] font-bold" style={{
                                  color: engine.online ? '#00C853' : '#FF1744',
                                }}>{engine.online ? 'ONLINE' : 'OFFLINE'}</span>
                              </div>
                            </div>
                          ))}
                        </div>
                      </GlassCard>

                      {/* 캐시 상태 */}
                      <GlassCard>
                        <div className="flex items-center gap-2 mb-3">
                          <span className="text-sm">💾</span>
                          <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>캐시 상태</span>
                        </div>
                        <div className="flex flex-col gap-2">
                          {(systemStatus.cache || [
                            { mode: 'BREAKOUT', status: 'missing' },
                            { mode: 'FLOW_LEADER', status: 'missing' },
                            { mode: 'CATALYST_BURST', status: 'missing' },
                            { mode: 'REVERSAL_EDGE', status: 'missing' },
                          ]).map((c: any) => {
                            const statusColor = c.status === 'valid' ? '#00C853' : c.status === 'expired' ? '#FF9800' : '#FF1744'
                            const statusLabel = c.status === 'valid' ? '유효' : c.status === 'expired' ? '만료됨' : '없음'
                            return (
                              <div key={c.mode} className="rounded-lg p-3" style={{
                                backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)',
                              }}>
                                <div className="flex items-center justify-between mb-1">
                                  <span className="text-[12px] font-bold" style={{ color: 'var(--text-primary)' }}>{c.mode}</span>
                                  <div className="flex items-center gap-1.5">
                                    <span className="w-2 h-2 rounded-full" style={{
                                      backgroundColor: statusColor,
                                      boxShadow: `0 0 4px ${statusColor}66`,
                                    }} />
                                    <span className="text-[10px] font-bold" style={{ color: statusColor }}>{statusLabel}</span>
                                  </div>
                                </div>
                                {c.lastUpdated && (
                                  <div className="flex items-center justify-between text-[10px]" style={{ color: 'var(--text-muted)' }}>
                                    <span>마지막 업데이트: {new Date(c.lastUpdated).toLocaleString('ko-KR')}</span>
                                    {c.elapsed && <span>{c.elapsed}</span>}
                                  </div>
                                )}
                                {!c.lastUpdated && (
                                  <div className="text-[10px]" style={{ color: 'var(--text-muted)' }}>데이터 없음</div>
                                )}
                              </div>
                            )
                          })}
                        </div>
                      </GlassCard>

                      {/* 리프레시 버튼 */}
                      <button
                        onClick={handleRefreshAll}
                        disabled={refreshLoading}
                        className="w-full py-3.5 rounded-2xl text-[14px] font-black transition-all duration-200"
                        style={{
                          background: refreshLoading
                            ? 'rgba(255,215,0,0.1)'
                            : 'linear-gradient(135deg, #FFD700, #FF9800)',
                          color: refreshLoading ? '#FFD700' : '#0D1117',
                          border: refreshLoading ? '1px solid rgba(255,215,0,0.2)' : 'none',
                          opacity: refreshLoading ? 0.7 : 1,
                          cursor: refreshLoading ? 'not-allowed' : 'pointer',
                          boxShadow: refreshLoading ? 'none' : '0 4px 15px rgba(255,215,0,0.25)',
                        }}
                      >
                        {refreshLoading ? (
                          <span className="flex items-center justify-center gap-2">
                            <span className="w-4 h-4 border-2 border-t-transparent rounded-full animate-spin" style={{ borderColor: 'rgba(255,215,0,0.4)', borderTopColor: 'transparent' }} />
                            리프레시 중...
                          </span>
                        ) : '🔄 시스템 리프레시'}
                      </button>

                      {/* 리프레시 결과 */}
                      {refreshResult && (
                        <GlassCard>
                          <div className="flex items-center gap-2 mb-3">
                            <span className="text-sm">{refreshResult.error ? '❌' : '✅'}</span>
                            <span className="text-[13px] font-bold" style={{ color: refreshResult.error ? '#FF1744' : '#00C853' }}>
                              {refreshResult.error ? '리프레시 실패' : '리프레시 완료'}
                            </span>
                          </div>
                          {refreshResult.error ? (
                            <p className="text-[12px]" style={{ color: '#FF1744' }}>{refreshResult.error}</p>
                          ) : (
                            <div className="flex flex-col gap-1.5">
                              {(refreshResult.results || []).map((r: any, i: number) => (
                                <div key={i} className="flex items-center justify-between py-1.5 px-3 rounded-lg" style={{
                                  backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)',
                                }}>
                                  <span className="text-[11px] font-medium" style={{ color: 'var(--text-primary)' }}>{r.mode}</span>
                                  <div className="flex items-center gap-1.5">
                                    <span className="w-2 h-2 rounded-full" style={{
                                      backgroundColor: r.success ? '#00C853' : '#FF1744',
                                    }} />
                                    <span className="text-[10px] font-bold" style={{
                                      color: r.success ? '#00C853' : '#FF1744',
                                    }}>{r.success ? '성공' : '실패'}</span>
                                  </div>
                                </div>
                              ))}
                              {refreshResult.message && (
                                <p className="text-[11px] mt-1" style={{ color: 'var(--text-muted)' }}>{refreshResult.message}</p>
                              )}
                            </div>
                          )}
                        </GlassCard>
                      )}
                    </>
                  ) : null}
                </div>
              )}

              {/* ═══ 모니터링 탭 ═══ */}
              {tab === 'monitor' && (
                <div className="flex flex-col gap-3 animate-slide-up" style={{ animationDelay: '0.1s', animationFillMode: 'backwards' }}>
                  {/* 헤더 + 자동새로고침 */}
                  <div className="flex items-center justify-between">
                    <h2 className="text-[15px] font-black" style={{ color: 'var(--text-primary)' }}>서비스 모니터링</h2>
                    <div className="flex items-center gap-2">
                      <button onClick={() => setAutoRefresh(!autoRefresh)}
                        className="text-[9px] font-bold px-2 py-1 rounded-lg"
                        style={{
                          backgroundColor: autoRefresh ? 'rgba(0,200,83,0.12)' : 'rgba(255,255,255,0.05)',
                          color: autoRefresh ? '#00C853' : 'var(--text-muted)',
                          border: `1px solid ${autoRefresh ? 'rgba(0,200,83,0.3)' : 'var(--border-subtle)'}`,
                        }}>
                        {autoRefresh ? '● LIVE' : '○ LIVE'}
                      </button>
                      <button onClick={loadMonitorData} disabled={monitorLoading}
                        className="text-[9px] font-bold px-2 py-1 rounded-lg"
                        style={{ backgroundColor: 'rgba(255,215,0,0.08)', color: '#FFD700', border: '1px solid rgba(255,215,0,0.2)' }}>
                        {monitorLoading ? '로딩...' : '새로고침'}
                      </button>
                    </div>
                  </div>

                  {monitorData ? (
                    <>
                      {/* 전체 상태 */}
                      <GlassCard>
                        <div className="flex items-center justify-between mb-3">
                          <span className="text-[12px] font-bold" style={{ color: 'var(--text-primary)' }}>시스템 상태</span>
                          <span className="text-[10px] font-black px-2 py-0.5 rounded-full" style={{
                            backgroundColor: monitorData.status === 'healthy' ? 'rgba(0,200,83,0.12)' : monitorData.status === 'degraded' ? 'rgba(255,152,0,0.12)' : 'rgba(255,23,68,0.12)',
                            color: monitorData.status === 'healthy' ? '#00C853' : monitorData.status === 'degraded' ? '#FF9800' : '#FF1744',
                            border: `1px solid ${monitorData.status === 'healthy' ? 'rgba(0,200,83,0.3)' : monitorData.status === 'degraded' ? 'rgba(255,152,0,0.3)' : 'rgba(255,23,68,0.3)'}`,
                          }}>
                            {monitorData.status === 'healthy' ? '정상' : monitorData.status === 'degraded' ? '저하' : '장애'}
                          </span>
                        </div>
                        <div className="grid grid-cols-3 gap-2">
                          <div className="text-center p-2 rounded-lg" style={{ backgroundColor: 'rgba(255,255,255,0.03)' }}>
                            <div className="text-[16px] font-black" style={{ color: '#FFD700' }}>{monitorData.agentsAvailable || '?'}</div>
                            <div className="text-[8px]" style={{ color: 'var(--text-muted)' }}>AI 에이전트</div>
                          </div>
                          <div className="text-center p-2 rounded-lg" style={{ backgroundColor: 'rgba(255,255,255,0.03)' }}>
                            <div className="text-[16px] font-black" style={{ color: '#00C853' }}>{monitorData.stats?.successRate ?? 0}%</div>
                            <div className="text-[8px]" style={{ color: 'var(--text-muted)' }}>성공률</div>
                          </div>
                          <div className="text-center p-2 rounded-lg" style={{ backgroundColor: 'rgba(255,255,255,0.03)' }}>
                            <div className="text-[16px] font-black" style={{ color: 'var(--text-primary)' }}>{Math.floor((monitorData.uptime || 0) / 3600)}h</div>
                            <div className="text-[8px]" style={{ color: 'var(--text-muted)' }}>업타임</div>
                          </div>
                        </div>
                        {monitorData.memory && (
                          <div className="mt-2 flex items-center gap-2">
                            <div className="flex-1 h-1.5 rounded-full" style={{ backgroundColor: 'rgba(255,255,255,0.06)' }}>
                              <div className="h-full rounded-full" style={{
                                width: `${monitorData.memory.usagePercent}%`,
                                backgroundColor: monitorData.memory.usagePercent > 80 ? '#FF1744' : '#00C853',
                              }} />
                            </div>
                            <span className="text-[8px]" style={{ color: 'var(--text-muted)' }}>RAM {monitorData.memory.used}/{monitorData.memory.max}</span>
                          </div>
                        )}
                      </GlassCard>

                      {/* 외부 서비스 상태 */}
                      <GlassCard>
                        <p className="text-[12px] font-bold mb-2" style={{ color: 'var(--text-primary)' }}>외부 서비스</p>
                        <div className="flex flex-col gap-1.5">
                          {(monitorData.services || []).map((svc: any, i: number) => (
                            <div key={i} className="flex items-center justify-between py-1.5 px-3 rounded-lg" style={{
                              backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)',
                            }}>
                              <div className="flex items-center gap-2">
                                <span className="w-2 h-2 rounded-full" style={{
                                  backgroundColor: svc.status === 'healthy' ? '#00C853' : svc.status === 'degraded' ? '#FF9800' : '#FF1744',
                                }} />
                                <span className="text-[11px] font-medium" style={{ color: 'var(--text-primary)' }}>{svc.name}</span>
                              </div>
                              <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>
                                {svc.latencyMs ? `${svc.latencyMs}ms` : svc.error || svc.status}
                              </span>
                            </div>
                          ))}
                        </div>
                      </GlassCard>

                      {/* AI 에이전트 */}
                      {monitorData.agents && (
                        <GlassCard>
                          <p className="text-[12px] font-bold mb-2" style={{ color: 'var(--text-primary)' }}>AI 에이전트</p>
                          <div className="flex flex-col gap-1.5">
                            {Object.entries(monitorData.agents).map(([name, info]: [string, any]) => (
                              <div key={name} className="flex items-center justify-between py-1.5 px-3 rounded-lg" style={{
                                backgroundColor: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)',
                              }}>
                                <div className="flex items-center gap-2">
                                  <span className="w-2 h-2 rounded-full" style={{ backgroundColor: info.available ? '#00C853' : '#FF1744' }} />
                                  <span className="text-[11px] font-bold capitalize" style={{
                                    color: { claude: '#FF6B35', gemini: '#4285F4', chatgpt: '#10A37F', perplexity: '#20B2AA', grok: '#FF4500' }[name] || '#888',
                                  }}>{name}</span>
                                </div>
                                <span className="text-[9px] font-bold" style={{ color: info.available ? '#00C853' : '#FF1744' }}>
                                  {info.available ? 'ONLINE' : 'OFFLINE'}
                                </span>
                              </div>
                            ))}
                          </div>
                        </GlassCard>
                      )}

                      {/* 최근 분석 로그 */}
                      <GlassCard>
                        <p className="text-[12px] font-bold mb-2" style={{ color: 'var(--text-primary)' }}>최근 분석 실행</p>
                        {(monitorData.recentAnalyses || []).length === 0 ? (
                          <p className="text-[10px]" style={{ color: 'var(--text-muted)' }}>아직 실행 기록이 없습니다</p>
                        ) : (
                          <div className="flex flex-col gap-1">
                            {(monitorData.recentAnalyses || []).slice(0, 10).map((log: any, i: number) => (
                              <div key={i} className="flex items-center justify-between py-1 px-2 rounded" style={{
                                backgroundColor: 'rgba(255,255,255,0.02)',
                              }}>
                                <div className="flex items-center gap-1.5">
                                  <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: log.success ? '#00C853' : '#FF1744' }} />
                                  <span className="text-[10px] font-bold" style={{ color: 'var(--text-primary)' }}>{log.mode}</span>
                                </div>
                                <div className="flex items-center gap-2">
                                  <span className="text-[8px]" style={{ color: 'var(--text-muted)' }}>
                                    {log.agentsSucceeded}/{log.agentsUsed} AI · {(log.durationMs / 1000).toFixed(0)}s
                                  </span>
                                  <span className="text-[8px]" style={{ color: 'var(--text-muted)' }}>
                                    {log.timestamp ? new Date(log.timestamp).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' }) : ''}
                                  </span>
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </GlassCard>

                      {/* 마지막 체크 시간 */}
                      <p className="text-[8px] text-center" style={{ color: 'var(--text-muted)' }}>
                        마지막 체크: {new Date(monitorData.timestamp).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}
                      </p>
                    </>
                  ) : monitorLoading ? (
                    <div className="text-center py-8">
                      <p className="text-[12px]" style={{ color: 'var(--text-muted)' }}>모니터링 데이터 로딩 중...</p>
                    </div>
                  ) : (
                    <GlassCard>
                      <p className="text-[11px] text-center" style={{ color: 'var(--text-muted)' }}>모니터링 데이터를 불러오지 못했습니다</p>
                    </GlassCard>
                  )}
                </div>
              )}

              {/* 새로고침 */}
              <button onClick={loadData}
                className="w-full py-2.5 rounded-xl text-[11px] font-medium transition-all duration-200 mt-2"
                style={{ backgroundColor: 'rgba(255,215,0,0.06)', border: '1px solid rgba(255,215,0,0.15)', color: '#FFD700' }}
                onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,215,0,0.1)' }}
                onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(255,215,0,0.06)' }}
              >새로고침</button>
            </>
          )}
      </div>
    </main>
  )
}
