import { useState, useEffect, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import GlassCard from '../components/common/GlassCard'
import GoldButton from '../components/common/GoldButton'
import FormInput from '../components/common/FormInput'
import { getStoredToken } from '../contexts/AuthContext'
import {
  fetchPendingSubscriptions, approveSubscription, rejectSubscription,
  fetchAllUsers, revokeSubscription, adminUpdateUser, adminResetPassword,
  type UserDto,
} from '../api/authApi'

type Tab = 'dashboard' | 'subscriptions' | 'members'

export default function AdminPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const initialTab = (searchParams.get('tab') as Tab) || 'dashboard'
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
    } catch { /* ignore */ }
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
    } catch { /* ignore */ }
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
    } catch { /* ignore */ }
    finally { setActionId(null) }
  }

  const handleRevoke = async (userId: string) => {
    const token = getStoredToken()
    if (!token) return
    setActionId(userId)
    try {
      const updated = await revokeSubscription(token, userId)
      setAllUsers(prev => prev.map(u => u.id === userId ? updated : u))
    } catch { /* ignore */ }
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

  // 검색 필터링
  const filteredUsers = useMemo(() => {
    if (!searchQuery.trim()) return allUsers
    const q = searchQuery.toLowerCase()
    return allUsers.filter(u =>
      u.name.toLowerCase().includes(q) || u.email.toLowerCase().includes(q)
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
  ]

  return (
    <main className="flex-1 overflow-y-auto" style={{ padding: '14px var(--page-px) 10px' }}>
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
                  <div className="flex items-center gap-2 mt-1">
                    <span className="text-sm">⏳</span>
                    <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>승인 대기</span>
                    <span className="text-[11px] font-bold px-1.5 py-0.5 rounded-full" style={{
                      backgroundColor: pendingCount > 0 ? 'rgba(255,152,0,0.12)' : 'rgba(255,255,255,0.05)',
                      color: pendingCount > 0 ? '#FF9800' : 'var(--text-muted)',
                    }}>{pendingCount}건</span>
                  </div>

                  {pendingUsers.length === 0 ? (
                    <GlassCard>
                      <p className="text-center text-[12px] py-4" style={{ color: 'var(--text-muted)' }}>대기 중인 신청이 없습니다</p>
                    </GlassCard>
                  ) : pendingUsers.map(u => (
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
                    }}>{proUsers}명</span>
                  </div>

                  {allUsers.filter(u => u.subscription === 'PRO').length === 0 ? (
                    <GlassCard>
                      <p className="text-center text-[12px] py-4" style={{ color: 'var(--text-muted)' }}>PRO 구독자가 없습니다</p>
                    </GlassCard>
                  ) : allUsers.filter(u => u.subscription === 'PRO').map(u => (
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
                          {[
                            { k: '가입일', v: fmt(selectedUser.createdAt) },
                            { k: '구독', v: selectedUser.subscription },
                            { k: '만료일', v: selectedUser.subscriptionEndDate || '—' },
                            { k: '입금자명', v: selectedUser.depositorName || '—' },
                          ].map(r => (
                            <div key={r.k} className="flex justify-between text-[10px] py-0.5">
                              <span style={{ color: 'var(--text-muted)' }}>{r.k}</span>
                              <span className="font-medium" style={{ color: 'var(--text-secondary)' }}>{r.v}</span>
                            </div>
                          ))}
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
                    <button
                      key={u.id}
                      onClick={() => handleSelectUser(u)}
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
                        <div className="text-right flex flex-col items-end gap-1">
                          {subBadge(u.subscription)}
                          <span className="text-[9px]" style={{ color: 'var(--text-muted)' }}>{fmt(u.createdAt)}</span>
                        </div>
                      </div>
                    </button>
                  ))}
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
