import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import GlassCard from '../components/common/GlassCard'
import GoldButton from '../components/common/GoldButton'
import FormInput from '../components/common/FormInput'

const KAKAO_URL = 'https://open.kakao.com/o/sJVLbWUe'
const TELEGRAM_URL = 'https://t.me/+411gMUrGnNc2YzU1'

type EditMode = 'none' | 'profile' | 'password'

export default function MyPage() {
  const navigate = useNavigate()
  const { user, logout, updateProfile, changePassword } = useAuth()

  const [editMode, setEditMode] = useState<EditMode>('none')

  // Profile edit
  const [editName, setEditName] = useState('')
  const [profileLoading, setProfileLoading] = useState(false)
  const [profileError, setProfileError] = useState('')
  const [profileSuccess, setProfileSuccess] = useState('')

  // Password change
  const [currentPw, setCurrentPw] = useState('')
  const [newPw, setNewPw] = useState('')
  const [confirmPw, setConfirmPw] = useState('')
  const [pwLoading, setPwLoading] = useState(false)
  const [pwError, setPwError] = useState('')
  const [pwSuccess, setPwSuccess] = useState('')

  if (!user) return null

  const initial = user.name.charAt(0).toUpperCase()

  const openEdit = (mode: EditMode) => {
    setEditMode(mode)
    if (mode === 'profile') {
      setEditName(user.name)
      setProfileError('')
      setProfileSuccess('')
    }
    if (mode === 'password') {
      setCurrentPw('')
      setNewPw('')
      setConfirmPw('')
      setPwError('')
      setPwSuccess('')
    }
  }

  const handleProfileSave = async (e: FormEvent) => {
    e.preventDefault()
    setProfileError('')
    setProfileSuccess('')
    if (!editName.trim() || editName.trim().length < 2) {
      setProfileError('이름을 2자 이상 입력해 주세요.')
      return
    }
    try {
      setProfileLoading(true)
      await updateProfile(editName.trim())
      setProfileSuccess('프로필이 변경되었습니다.')
      setTimeout(() => { setEditMode('none'); setProfileSuccess('') }, 1200)
    } catch (err) {
      setProfileError(err instanceof Error ? err.message : '변경에 실패했습니다.')
    } finally {
      setProfileLoading(false)
    }
  }

  const handlePasswordChange = async (e: FormEvent) => {
    e.preventDefault()
    setPwError('')
    setPwSuccess('')
    if (!currentPw) { setPwError('현재 비밀번호를 입력해 주세요.'); return }
    if (newPw.length < 8) { setPwError('새 비밀번호는 8자 이상이어야 합니다.'); return }
    if (!/[A-Za-z]/.test(newPw) || !/[0-9]/.test(newPw)) { setPwError('영문과 숫자를 모두 포함해 주세요.'); return }
    if (newPw !== confirmPw) { setPwError('새 비밀번호가 일치하지 않습니다.'); return }
    try {
      setPwLoading(true)
      await changePassword(currentPw, newPw)
      setPwSuccess('비밀번호가 변경되었습니다.')
      setCurrentPw(''); setNewPw(''); setConfirmPw('')
      setTimeout(() => { setEditMode('none'); setPwSuccess('') }, 1200)
    } catch (err) {
      setPwError(err instanceof Error ? err.message : '변경에 실패했습니다.')
    } finally {
      setPwLoading(false)
    }
  }

  return (
    <main className="flex-1 overflow-y-auto" style={{ padding: '14px var(--page-px) 10px' }}>
      <div className="flex flex-col gap-4" style={{ maxWidth: '440px', margin: '0 auto', width: '100%' }}>

        {/* 프로필 카드 */}
        <div className="animate-slide-up" style={{ animationFillMode: 'backwards' }}>
          <GlassCard>
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-2xl flex items-center justify-center flex-shrink-0" style={{
                background: 'linear-gradient(135deg, rgba(255,215,0,0.2) 0%, rgba(255,152,0,0.15) 100%)',
                border: '1.5px solid rgba(255,215,0,0.3)',
              }}>
                <span className="font-black text-[18px]" style={{ color: '#FFD700' }}>{initial}</span>
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="font-bold text-[15px] truncate" style={{ color: 'var(--text-primary)' }}>{user.name}</span>
                  <span className="text-[9px] font-black px-2 py-0.5 rounded-full" style={{
                    backgroundColor: user.subscription === 'pro' ? 'rgba(124,77,255,0.15)' : 'rgba(255,255,255,0.06)',
                    color: user.subscription === 'pro' ? 'var(--color-grade-s)' : 'var(--text-muted)',
                    border: `1px solid ${user.subscription === 'pro' ? 'rgba(124,77,255,0.3)' : 'var(--border-subtle)'}`,
                  }}>
                    {user.subscription === 'pro' ? 'PRO' : user.subscription === 'pending' ? '승인대기' : '무료'}
                  </span>
                </div>
                <span className="text-[12px] block truncate" style={{ color: 'var(--text-muted)' }}>{user.email}</span>
              </div>
            </div>
          </GlassCard>
        </div>

        {/* 구독 현황 */}
        <div className="animate-slide-up" style={{ animationDelay: '0.05s', animationFillMode: 'backwards' }}>
          <GlassCard>
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-2">
                <span className="text-sm">📋</span>
                <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>구독 현황</span>
              </div>
              {user.subscription === 'pro' ? (
                <>
                  <p className="text-[12px]" style={{ color: 'var(--text-secondary)' }}>
                    구독 종료일: <span className="font-bold" style={{ color: '#FFD700' }}>{user.subscriptionEndDate || '무기한'}</span>
                  </p>
                  <p className="text-[11px]" style={{ color: 'var(--color-bull)' }}>PRO 구독이 활성화되어 있습니다.</p>
                </>
              ) : user.subscription === 'pending' ? (
                <>
                  <p className="text-[12px]" style={{ color: 'var(--text-secondary)' }}>구독 신청이 접수되었습니다.</p>
                  <p className="text-[11px]" style={{ color: 'var(--color-warning)' }}>입금 확인 후 승인됩니다.</p>
                </>
              ) : (
                <>
                  <p className="text-[12px]" style={{ color: 'var(--text-muted)' }}>현재 구독 중인 플랜이 없습니다.</p>
                  <div className="mt-1">
                    <GoldButton delay={0} onClick={() => navigate('/subscribe')}>
                      PRO 구독 시작하기
                    </GoldButton>
                  </div>
                </>
              )}
            </div>
          </GlassCard>
        </div>

        {/* 프로필 수정 폼 (조건부) */}
        {editMode === 'profile' && (
          <div className="animate-slide-up">
            <GlassCard>
              <form onSubmit={handleProfileSave} className="flex flex-col gap-3">
                <div className="flex items-center justify-between">
                  <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>프로필 수정</span>
                  <button type="button" onClick={() => setEditMode('none')}
                    className="text-[11px] font-medium px-2 py-0.5 rounded-lg transition-colors duration-150"
                    style={{ color: 'var(--text-muted)' }}
                    onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.05)' }}
                    onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'transparent' }}
                  >취소</button>
                </div>

                {profileError && (
                  <div className="flex items-center gap-2 px-3 py-2 rounded-lg text-[12px] font-medium"
                    style={{ backgroundColor: 'rgba(255,23,68,0.08)', border: '1px solid rgba(255,23,68,0.2)', color: 'var(--color-bear)' }}>
                    {profileError}
                  </div>
                )}
                {profileSuccess && (
                  <div className="flex items-center gap-2 px-3 py-2 rounded-lg text-[12px] font-medium"
                    style={{ backgroundColor: 'rgba(0,200,83,0.08)', border: '1px solid rgba(0,200,83,0.2)', color: 'var(--color-bull)' }}>
                    {profileSuccess}
                  </div>
                )}

                <FormInput
                  label="이름"
                  type="text"
                  value={editName}
                  onChange={e => setEditName(e.target.value)}
                  placeholder="이름 (2자 이상)"
                  delay={0}
                  icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>}
                />
                <div className="text-[11px] px-1" style={{ color: 'var(--text-muted)' }}>
                  이메일: {user.email} (변경 불가)
                </div>
                <GoldButton type="submit" loading={profileLoading} delay={0}>저장</GoldButton>
              </form>
            </GlassCard>
          </div>
        )}

        {/* 비밀번호 변경 폼 (조건부) */}
        {editMode === 'password' && (
          <div className="animate-slide-up">
            <GlassCard>
              <form onSubmit={handlePasswordChange} className="flex flex-col gap-3">
                <div className="flex items-center justify-between">
                  <span className="text-[13px] font-bold" style={{ color: 'var(--text-primary)' }}>비밀번호 변경</span>
                  <button type="button" onClick={() => setEditMode('none')}
                    className="text-[11px] font-medium px-2 py-0.5 rounded-lg transition-colors duration-150"
                    style={{ color: 'var(--text-muted)' }}
                    onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.05)' }}
                    onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'transparent' }}
                  >취소</button>
                </div>

                {pwError && (
                  <div className="flex items-center gap-2 px-3 py-2 rounded-lg text-[12px] font-medium"
                    style={{ backgroundColor: 'rgba(255,23,68,0.08)', border: '1px solid rgba(255,23,68,0.2)', color: 'var(--color-bear)' }}>
                    {pwError}
                  </div>
                )}
                {pwSuccess && (
                  <div className="flex items-center gap-2 px-3 py-2 rounded-lg text-[12px] font-medium"
                    style={{ backgroundColor: 'rgba(0,200,83,0.08)', border: '1px solid rgba(0,200,83,0.2)', color: 'var(--color-bull)' }}>
                    {pwSuccess}
                  </div>
                )}

                <FormInput
                  label="현재 비밀번호"
                  type="password"
                  value={currentPw}
                  onChange={e => setCurrentPw(e.target.value)}
                  placeholder="현재 비밀번호"
                  autoComplete="current-password"
                  delay={0}
                  icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/></svg>}
                />
                <FormInput
                  label="새 비밀번호"
                  type="password"
                  value={newPw}
                  onChange={e => setNewPw(e.target.value)}
                  placeholder="8자 이상, 영문+숫자"
                  autoComplete="new-password"
                  delay={0}
                  icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/></svg>}
                />
                <FormInput
                  label="새 비밀번호 확인"
                  type="password"
                  value={confirmPw}
                  onChange={e => setConfirmPw(e.target.value)}
                  placeholder="새 비밀번호 확인"
                  autoComplete="new-password"
                  delay={0}
                  icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 11.08V12a10 10 0 11-5.93-9.14"/><path d="M22 4L12 14.01l-3-3"/></svg>}
                />
                <GoldButton type="submit" loading={pwLoading} delay={0}>비밀번호 변경</GoldButton>
              </form>
            </GlassCard>
          </div>
        )}

        {/* 메뉴 */}
        <div className="animate-slide-up" style={{ animationDelay: '0.1s', animationFillMode: 'backwards' }}>
          <GlassCard noPadding>
            <div className="flex flex-col">
              {[
                { icon: '✏️', label: '프로필 수정', desc: '이름 변경', onClick: () => openEdit('profile') },
                { icon: '🔑', label: '비밀번호 변경', desc: '비밀번호 재설정', onClick: () => openEdit('password') },
                { icon: '💳', label: '구독 관리', desc: '구독 현황 및 연장', onClick: () => navigate('/subscribe') },
                { icon: '💬', label: '문의하기', desc: '카카오톡 / 텔레그램', onClick: () => window.open(KAKAO_URL, '_blank') },
              ].map((item, i) => (
                <button
                  key={item.label}
                  className="flex items-center gap-3 w-full text-left transition-colors duration-150"
                  style={{
                    padding: '14px 20px',
                    borderBottom: i < 3 ? '1px solid var(--border-subtle)' : 'none',
                  }}
                  onClick={item.onClick}
                  onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.03)' }}
                  onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'transparent' }}
                >
                  <span className="text-base">{item.icon}</span>
                  <div className="flex-1 min-w-0">
                    <span className="text-[13px] font-medium block" style={{ color: 'var(--text-primary)' }}>{item.label}</span>
                    <span className="text-[10px] block" style={{ color: 'var(--text-muted)' }}>{item.desc}</span>
                  </div>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-muted)', flexShrink: 0 }}>
                    <polyline points="9 18 15 12 9 6" />
                  </svg>
                </button>
              ))}
            </div>
          </GlassCard>
        </div>

        {/* 로그아웃 */}
        <div className="animate-slide-up" style={{ animationDelay: '0.15s', animationFillMode: 'backwards' }}>
          <button
            className="w-full py-3 rounded-xl text-[13px] font-medium transition-all duration-200"
            style={{
              backgroundColor: 'rgba(255,23,68,0.06)',
              border: '1px solid rgba(255,23,68,0.15)',
              color: 'var(--color-bear)',
            }}
            onClick={() => { logout(); navigate('/login', { replace: true }) }}
            onMouseEnter={e => { e.currentTarget.style.backgroundColor = 'rgba(255,23,68,0.1)' }}
            onMouseLeave={e => { e.currentTarget.style.backgroundColor = 'rgba(255,23,68,0.06)' }}
          >
            로그아웃
          </button>
        </div>
      </div>
    </main>
  )
}
