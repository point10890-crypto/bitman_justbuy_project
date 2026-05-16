import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'

const KAKAO_URL = 'https://open.kakao.com/o/sJVLbWUe'

function subscriptionLabel(subscription: string, expired?: boolean, renewalPending?: boolean) {
  if (renewalPending) return '연장 승인대기'
  if (expired) return '만료'
  if (subscription === 'pro') return '구독중'
  if (subscription === 'pending') return '승인대기'
  return '미구독'
}

function subscriptionActionLabel(subscription: string, expired?: boolean, renewalPending?: boolean) {
  if (renewalPending) return '승인 상태 확인'
  if (subscription === 'pending') return '승인 상태 확인'
  if (subscription === 'pro' && !expired) return '구독 연장 / 관리'
  return '월간 이용권 신청'
}

export default function MyPage() {
  const navigate = useNavigate()
  const { user, logout, updateProfile, changePassword } = useAuth()
  const [name, setName] = useState(user?.name || '')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [profileMessage, setProfileMessage] = useState('')
  const [passwordMessage, setPasswordMessage] = useState('')
  const [savingProfile, setSavingProfile] = useState(false)
  const [savingPassword, setSavingPassword] = useState(false)

  useEffect(() => {
    setName(user?.name || '')
  }, [user?.name])

  if (!user) return null

  const handleProfile = async (event: FormEvent) => {
    event.preventDefault()
    setProfileMessage('')
    if (name.trim().length < 2) {
      setProfileMessage('이름은 2자 이상 입력해 주세요.')
      return
    }
    try {
      setSavingProfile(true)
      await updateProfile(name.trim())
      setProfileMessage('프로필이 저장되었습니다.')
    } catch (err) {
      setProfileMessage(err instanceof Error ? err.message : '저장에 실패했습니다.')
    } finally {
      setSavingProfile(false)
    }
  }

  const handlePassword = async (event: FormEvent) => {
    event.preventDefault()
    setPasswordMessage('')
    if (!currentPassword || newPassword.length < 12 || !/[A-Z]/.test(newPassword) || !/[a-z]/.test(newPassword) || !/[0-9]/.test(newPassword) || !/[^A-Za-z0-9]/.test(newPassword)) {
      setPasswordMessage('현재 비밀번호와 새 비밀번호 12자 이상, 대문자, 소문자, 숫자, 특수문자를 입력해 주세요.')
      return
    }
    try {
      setSavingPassword(true)
      await changePassword(currentPassword, newPassword)
      setCurrentPassword('')
      setNewPassword('')
      setPasswordMessage('비밀번호가 변경되었습니다.')
    } catch (err) {
      setPasswordMessage(err instanceof Error ? err.message : '변경에 실패했습니다.')
    } finally {
      setSavingPassword(false)
    }
  }

  return (
    <main className="my-page">
      <section className="my-profile-card">
        <div className="my-avatar">{user.name.slice(0, 1).toUpperCase()}</div>
        <div>
          <h1>{user.name}</h1>
          <p>{user.email}</p>
        </div>
        <span className={`my-subscription-badge ${user.subscription}`}>
          {subscriptionLabel(user.subscription, user.subscriptionExpired, user.subscriptionRenewalPending)}
        </span>
      </section>

      <section className="my-card">
        <div className="my-card-head">
          <h2>구독 관리</h2>
          <p>월간 이용권 상태와 만료일을 확인합니다.</p>
        </div>
        <div className="my-status-grid">
          <div><span>상태</span><strong>{subscriptionLabel(user.subscription, user.subscriptionExpired, user.subscriptionRenewalPending)}</strong></div>
          <div><span>만료일</span><strong>{user.subscriptionEndDate || '-'}</strong></div>
          <div><span>입금자명</span><strong>{user.depositorName || '-'}</strong></div>
        </div>
        <button
          className="my-primary-button"
          type="button"
          onClick={() => navigate('/subscribe', {
            state: {
              renewal: user.subscription === 'pro' && !user.subscriptionExpired && !user.subscriptionRenewalPending,
              renewalPending: user.subscriptionRenewalPending,
              message: user.subscriptionRenewalPending
                ? '기존 이용권은 유지되고 있으며, 다음 달 연장 승인을 기다리는 중입니다.'
                : user.subscription === 'pro' && !user.subscriptionExpired
                ? '기존 이용 기간은 유지하면서 다음 달 이용권을 연장 신청합니다.'
                : undefined,
            },
          })}
        >
          {subscriptionActionLabel(user.subscription, user.subscriptionExpired, user.subscriptionRenewalPending)}
        </button>
      </section>

      <section className="my-card">
        <div className="my-card-head">
          <h2>프로필</h2>
          <p>운영자가 확인할 이름 또는 닉네임입니다.</p>
        </div>
        {profileMessage && <div className="my-message">{profileMessage}</div>}
        <form className="my-form" onSubmit={handleProfile}>
          <label>
            <span>이름 / 닉네임</span>
            <input value={name} onChange={event => setName(event.target.value)} />
          </label>
          <button type="submit" disabled={savingProfile}>{savingProfile ? '저장 중...' : '저장'}</button>
        </form>
      </section>

      <section className="my-card">
        <div className="my-card-head">
          <h2>비밀번호 변경</h2>
          <p>계정 보안을 위해 주기적으로 변경해 주세요.</p>
        </div>
        {passwordMessage && <div className="my-message">{passwordMessage}</div>}
        <form className="my-form" onSubmit={handlePassword}>
          <label>
            <span>현재 비밀번호</span>
            <input type="password" value={currentPassword} onChange={event => setCurrentPassword(event.target.value)} />
          </label>
          <label>
            <span>새 비밀번호</span>
            <input type="password" value={newPassword} onChange={event => setNewPassword(event.target.value)} />
          </label>
          <button type="submit" disabled={savingPassword}>{savingPassword ? '변경 중...' : '비밀번호 변경'}</button>
        </form>
      </section>

      <section className="my-action-list">
        <button type="button" onClick={() => window.open(KAKAO_URL, '_blank')}>문의하기</button>
        <button type="button" onClick={() => { logout(); navigate('/landing', { replace: true }) }}>로그아웃</button>
      </section>
    </main>
  )
}
