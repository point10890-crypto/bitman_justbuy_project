import { Children, cloneElement, isValidElement, useEffect, useMemo, useState, type FormEvent, type ReactElement, type ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { getStoredToken, useAuth } from '../contexts/AuthContext'
import {
  approveSubscription,
  adminResetPassword,
  adminUpdateUser,
  expireSubscriptionsNow,
  fetchAllUsers,
  fetchPendingSubscriptions,
  fetchSystemStatus,
  refreshAllAnalysis,
  rejectSubscription,
  revokeSubscription,
  type SystemStatusResponse,
  type UserDto,
} from '../api/authApi'

type AdminView =
  | 'dashboard'
  | 'breakout'
  | 'flow'
  | 'catalyst'
  | 'reversal'
  | 'approvals'
  | 'subscriptions'
  | 'members'
  | 'system'

type AdminFocus = 'active' | 'expiring' | null

type AdminMenuEntry = {
  type: 'view' | 'route' | 'external' | 'logout'
  key: string
  label: string
  desc: string
  icon: string
  tone: 'home' | 'cyan' | 'green' | 'orange' | 'violet' | 'profile' | 'blue' | 'shield' | 'purple' | 'tool' | 'chat' | 'logout'
  view?: AdminView
  route?: string
  href?: string
}

const KAKAO_URL = 'https://open.kakao.com/o/sJVLbWUe'

const productMenu: AdminMenuEntry[] = [
  { type: 'route', key: 'home', label: '메인', desc: '오늘의 조건검색 홈', icon: '🏠', tone: 'home', route: '/' },
  { type: 'view', key: 'breakout', label: '단타', desc: '장중 빠른 포착 · 단기 후보', icon: '🚀', tone: 'cyan', view: 'breakout' },
  { type: 'view', key: 'reversal', label: '스윙', desc: '며칠 보유 관점 · 추세 후보', icon: '🔄', tone: 'violet', view: 'reversal' },
  { type: 'view', key: 'flow', label: '주도주', desc: '거래대금 · 시장 중심 종목', icon: '📈', tone: 'green', view: 'flow' },
  { type: 'view', key: 'catalyst', label: '테마주', desc: '공시 · 뉴스 기반 테마 후보', icon: '⚡', tone: 'orange', view: 'catalyst' },
  { type: 'route', key: 'mypage', label: '마이페이지', desc: '프로필 · 구독 관리', icon: '👤', tone: 'profile', route: '/my' },
]

const adminMenu: AdminMenuEntry[] = [
  { type: 'view', key: 'dashboard', label: '관리자 대시보드', desc: '회원 현황 · 통계', icon: '📊', tone: 'blue', view: 'dashboard' },
  { type: 'view', key: 'approvals', label: '구독 승인 / 해제', desc: '대기 승인 · PRO 해제', icon: '🛡', tone: 'shield', view: 'approvals' },
  { type: 'view', key: 'subscriptions', label: '구독 관리', desc: '등급 승인 · 만료 처리', icon: '💳', tone: 'green', view: 'subscriptions' },
  { type: 'view', key: 'members', label: '회원 관리', desc: '전체 회원 목록 · 상태', icon: '👥', tone: 'purple', view: 'members' },
  { type: 'view', key: 'system', label: '시스템 관리', desc: '서버 상태 · 리프레시', icon: '🔧', tone: 'tool', view: 'system' },
  { type: 'external', key: 'contact', label: 'AI 단톡방 입장문의', desc: '카카오톡 오픈채팅', icon: '💬', tone: 'chat', href: KAKAO_URL },
  { type: 'logout', key: 'logout', label: '로그아웃', desc: '계정에서 로그아웃', icon: '🚪', tone: 'logout' },
]

const viewMeta: Record<AdminView, { label: string; kicker: string; desc: string }> = {
  dashboard: { label: '관리자 대시보드', kicker: 'ADMIN HOME', desc: '구독 승인, 회원 상태, 시스템 상태를 한 화면에서 점검합니다.' },
  breakout: { label: '단타', kicker: 'SHORT-TERM', desc: '장중 빠르게 움직이는 단기 후보를 포착하는 메뉴입니다.' },
  reversal: { label: '스윙', kicker: 'SWING', desc: '며칠 보유 관점의 추세 후보를 포착하는 메뉴입니다.' },
  flow: { label: '주도주', kicker: 'LEADER STOCK', desc: '거래대금과 시장 중심 흐름이 강한 종목을 포착하는 메뉴입니다.' },
  catalyst: { label: '테마주', kicker: 'THEME STOCK', desc: '공시, 뉴스, 이벤트 기반의 테마 후보를 포착하는 메뉴입니다.' },
  approvals: { label: '구독 승인 / 해제', kicker: 'SUBSCRIPTION', desc: '입금 확인 후 구독 승인과 PRO 해제를 처리합니다.' },
  subscriptions: { label: '구독 관리', kicker: 'PRO PLAN', desc: '구독 만료일, 입금자명, 상태를 확인합니다.' },
  members: { label: '회원 관리', kicker: 'MEMBERS', desc: '전체 회원 목록과 권한 상태를 검색합니다.' },
  system: { label: '시스템 관리', kicker: 'SYSTEM', desc: 'KIS, DART, DeepSeek, 분석 캐시 상태를 확인합니다.' },
}

const adminViewSet = new Set<AdminView>(Object.keys(viewMeta) as AdminView[])

function parseAdminView(value: string | null): AdminView {
  return value && adminViewSet.has(value as AdminView) ? value as AdminView : 'dashboard'
}

function parseAdminFocus(value: string | null): AdminFocus {
  return value === 'active' || value === 'expiring' ? value : null
}

const strategyViews: Record<Exclude<AdminView, 'dashboard' | 'approvals' | 'subscriptions' | 'members' | 'system'>, {
  mode: string
  section: string
  checks: string[]
}> = {
  breakout: {
    mode: 'BREAKOUT',
    section: 'short-term',
    checks: ['장중 강세', '거래량 증가', '단기 수익률', '단타 후보 TOP3'],
  },
  reversal: {
    mode: 'REVERSAL_EDGE',
    section: 'swing',
    checks: ['눌림목', '추세 회복', '분할 진입', '스윙 후보 TOP3'],
  },
  flow: {
    mode: 'FLOW_LEADER',
    section: 'leaders',
    checks: ['시장 중심', '기관/외국인', '거래대금', '주도주 TOP3'],
  },
  catalyst: {
    mode: 'CATALYST_BURST',
    section: 'themes',
    checks: ['공시 이벤트', '뉴스 모멘텀', '테마 확산', '테마주 TOP3'],
  },
}

function formatDate(value?: string | null) {
  if (!value) return '-'
  return new Date(value).toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  return new Date(value).toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatTime(value?: string | null) {
  if (!value) return '미갱신'
  return new Date(value).toLocaleTimeString('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatRunDuration(startedAt?: string | null, finishedAt?: string | null) {
  if (!startedAt || !finishedAt) return '-'
  const started = new Date(startedAt).getTime()
  const finished = new Date(finishedAt).getTime()
  if (!Number.isFinite(started) || !Number.isFinite(finished) || finished < started) return '-'
  return `${Math.round((finished - started) / 1000)}s`
}

function runStatusTone(status?: string | null) {
  if (status === 'COMPLETE') return 'ok'
  if (status === 'FAILED' || status === 'CANCELLED' || status === 'EXPIRED') return 'warn'
  return 'pending'
}

function runStatusLabel(status?: string | null) {
  if (!status) return '-'
  return status.replaceAll('_', ' ')
}

function runTriggerLabel(trigger?: string | null) {
  if (!trigger) return '-'
  return trigger.replaceAll('_', ' ')
}

function matchesUserSearch(user: UserDto, rawQuery: string) {
  const q = rawQuery.trim().toLowerCase()
  if (!q) return true
  return [
    user.name,
    user.email,
    user.id,
    user.depositorName ?? '',
    user.subscription,
    user.subscriptionRequestedAt ?? '',
    user.subscriptionEndDate ?? '',
  ].some(value => value.toLowerCase().includes(q))
}

function approvalRequestTime(user: UserDto) {
  const requestedAt = user.subscriptionRequestedAt ?? user.createdAt
  return requestedAt ? new Date(requestedAt).getTime() : 0
}

function newestApprovalRequestFirst(a: UserDto, b: UserDto) {
  const aRequestTime = approvalRequestTime(a)
  const bRequestTime = approvalRequestTime(b)
  if (aRequestTime !== bRequestTime) return bRequestTime - aRequestTime
  return a.name.localeCompare(b.name, 'ko-KR')
}

function StatusBadge({ status }: { status: string }) {
  return <span className={`admin-status admin-status-${status.toLowerCase()}`}>{status}</span>
}

export default function AdminPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const { logout } = useAuth()
  const [view, setView] = useState<AdminView>(() => parseAdminView(searchParams.get('view')))
  const [focus, setFocus] = useState<AdminFocus>(() => parseAdminFocus(searchParams.get('focus')))
  const [menuOpen, setMenuOpen] = useState(false)
  const [users, setUsers] = useState<UserDto[]>([])
  const [pendingUsers, setPendingUsers] = useState<UserDto[]>([])
  const [systemStatus, setSystemStatus] = useState<SystemStatusResponse | null>(null)
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(true)
  const [actionId, setActionId] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const [editingUser, setEditingUser] = useState<UserDto | null>(null)
  const [editForm, setEditForm] = useState({ name: '', email: '', subscription: 'FREE' })
  const [resetPassword, setResetPassword] = useState('')
  const [lastRefreshedAt, setLastRefreshedAt] = useState<string | null>(null)
  const [lastRefreshLabel, setLastRefreshLabel] = useState('관리자 데이터')

  const markRefreshed = (label: string) => {
    setLastRefreshLabel(label)
    setLastRefreshedAt(new Date().toISOString())
  }

  const loadUsers = async (showSpinner = true) => {
    const token = getStoredToken()
    if (!token) return false
    if (showSpinner) setLoading(true)
    try {
      const [all, pending] = await Promise.all([
        fetchAllUsers(token),
        fetchPendingSubscriptions(token),
      ])
      setUsers(all)
      setPendingUsers(pending)
      return true
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '관리자 데이터를 불러오지 못했습니다.')
      return false
    } finally {
      if (showSpinner) setLoading(false)
    }
  }

  const loadSystem = async () => {
    const token = getStoredToken()
    if (!token) return false
    try {
      setSystemStatus(await fetchSystemStatus(token))
      return true
    } catch (error) {
      setSystemStatus({ error: error instanceof Error ? error.message : '시스템 상태 조회 실패' })
      return false
    }
  }

  useEffect(() => {
    const bootstrap = async () => {
      await Promise.all([loadUsers(), loadSystem()])
      markRefreshed('관리자 데이터')
    }
    bootstrap()
  }, [])

  useEffect(() => {
    if (view === 'system') loadSystem()
  }, [view])

  useEffect(() => {
    const nextView = parseAdminView(searchParams.get('view'))
    const nextFocus = parseAdminFocus(searchParams.get('focus'))
    if (nextView !== view) setView(nextView)
    if (nextFocus !== focus) setFocus(nextFocus)
  }, [focus, searchParams, view])

  useEffect(() => {
    if (!menuOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [menuOpen])

  const filteredUsers = useMemo(() => {
    return users.filter(user => matchesUserSearch(user, search))
  }, [search, users])

  const filteredPendingUsers = useMemo(() => {
    return pendingUsers
      .filter(user => matchesUserSearch(user, search))
      .sort(newestApprovalRequestFirst)
  }, [pendingUsers, search])

  const subscriptionUsers = useMemo(() => {
    const tierRank = (user: UserDto) => {
      if (user.subscription === 'PRO') return 0
      if (user.subscription === 'PENDING') return 1
      return 2
    }

    return [...filteredUsers].sort((a, b) => {
      const rankDiff = tierRank(a) - tierRank(b)
      if (rankDiff !== 0) return rankDiff

      if (focus === 'expiring' && a.subscription === 'PRO' && b.subscription === 'PRO') {
        const aEnd = a.subscriptionEndDate ? new Date(a.subscriptionEndDate).getTime() : Number.MAX_SAFE_INTEGER
        const bEnd = b.subscriptionEndDate ? new Date(b.subscriptionEndDate).getTime() : Number.MAX_SAFE_INTEGER
        if (aEnd !== bEnd) return aEnd - bEnd
      }

      return a.name.localeCompare(b.name, 'ko-KR')
    })
  }, [filteredUsers, focus])

  const visibleSubscriptionUsers = useMemo(() => {
    if (focus === 'active') return subscriptionUsers.filter(user => user.subscription === 'PRO')
    if (focus === 'expiring') return subscriptionUsers.filter(user => user.subscription === 'PRO' && user.subscriptionEndDate)
    return subscriptionUsers
  }, [focus, subscriptionUsers])

  const activeCount = users.filter(user => user.subscription === 'PRO').length
  const freeCount = users.filter(user => user.subscription === 'FREE').length
  const pendingCount = pendingUsers.length
  const expiringCount = users.filter(user => user.subscription === 'PRO' && user.subscriptionEndDate).length

  const goAdminView = (nextView: AdminView, nextSearch = '', nextFocus: AdminFocus = null) => {
    setView(nextView)
    setFocus(nextFocus)
    setMenuOpen(false)
    setMessage('')
    setEditingUser(null)
    setSearch(nextSearch)
    const nextParams: Record<string, string> = {}
    if (nextView !== 'dashboard') nextParams.view = nextView
    if (nextFocus) nextParams.focus = nextFocus
    setSearchParams(nextParams)
  }

  const runAction = async (user: UserDto, action: 'approve' | 'reject' | 'revoke') => {
    if (action === 'reject' || action === 'revoke') {
      const actionLabel = action === 'reject' ? '구독 신청을 반려' : 'PRO 구독을 해제'
      const ok = window.confirm(
        `${user.name} 회원의 ${actionLabel}할까요?\n\n회원의 앱 접근 권한이 바뀔 수 있습니다.`
      )
      if (!ok) return
    }

    const token = getStoredToken()
    if (!token) return
    setActionId(user.id)
    setMessage('')
    try {
      if (action === 'approve') await approveSubscription(token, user.id)
      if (action === 'reject') await rejectSubscription(token, user.id)
      if (action === 'revoke') await revokeSubscription(token, user.id)
      setMessage(`${user.name} 회원의 상태가 처리되었습니다.`)
      await loadUsers()
      markRefreshed('회원/구독 데이터')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '처리에 실패했습니다.')
    } finally {
      setActionId(null)
    }
  }

  const startEditUser = (user: UserDto) => {
    setEditingUser(user)
    setEditForm({ name: user.name, email: user.email, subscription: user.role === 'ADMIN' ? 'PRO' : user.subscription })
    setResetPassword('')
    setMessage('')
  }

  const saveEditedUser = async (event: FormEvent) => {
    event.preventDefault()
    const token = getStoredToken()
    if (!token || !editingUser) return
    setActionId(editingUser.id)
    setMessage('')
    try {
      await adminUpdateUser(token, editingUser.id, {
        name: editForm.name.trim(),
        email: editForm.email.trim().toLowerCase(),
        subscription: editForm.subscription,
      })
      setMessage(`${editForm.name} 회원 정보가 저장되었습니다.`)
      setEditingUser(null)
      await loadUsers()
      markRefreshed('회원/구독 데이터')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '회원 정보 저장에 실패했습니다.')
    } finally {
      setActionId(null)
    }
  }

  const resetEditedUserPassword = async () => {
    const token = getStoredToken()
    if (!token || !editingUser) return
    if (!resetPassword.trim()) {
      setMessage('초기화할 새 비밀번호를 입력해 주세요.')
      return
    }
    setActionId(`${editingUser.id}-password`)
    setMessage('')
    try {
      await adminResetPassword(token, editingUser.id, resetPassword.trim())
      setResetPassword('')
      setMessage(`${editingUser.name} 회원의 비밀번호가 초기화되었습니다.`)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '비밀번호 초기화에 실패했습니다.')
    } finally {
      setActionId(null)
    }
  }

  const changeSubscription = async (user: UserDto, subscription: 'FREE' | 'PENDING' | 'PRO') => {
    const token = getStoredToken()
    if (!token) return
    if (user.role === 'ADMIN') {
      setMessage('관리자 계정은 항상 PRO 상태로 보호됩니다.')
      return
    }
    const actionLabel =
      subscription === 'PRO' ? 'PRO 승인' :
      subscription === 'PENDING' ? '승인 대기 상태로 변경' :
      'PRO 구독 해제'
    const ok = window.confirm(
      `${user.name} 회원을 ${actionLabel}할까요?\n\n회원의 앱 접근 권한이 즉시 반영됩니다.`
    )
    if (!ok) return

    setActionId(user.id)
    setMessage('')
    try {
      await adminUpdateUser(token, user.id, { subscription })
      setMessage(`${user.name} 회원 등급이 ${subscription} 상태로 변경되었습니다.`)
      await loadUsers()
      markRefreshed('회원/구독 데이터')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '등급 변경에 실패했습니다.')
    } finally {
      setActionId(null)
    }
  }

  const refreshSystem = async () => {
    const token = getStoredToken()
    if (!token) return
    setActionId('system-refresh')
    try {
      await refreshAllAnalysis(token)
      setMessage('검색식과 분석 캐시 갱신을 요청했습니다.')
      await loadSystem()
      markRefreshed('시스템/검색식 캐시')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '시스템 갱신에 실패했습니다.')
    } finally {
      setActionId(null)
    }
  }

  const runExpiryNow = async () => {
    const ok = window.confirm(
      '만료일이 지난 PRO 구독을 즉시 FREE로 전환할까요?\n\n운영 회원 권한이 변경되는 작업입니다.'
    )
    if (!ok) return

    const token = getStoredToken()
    if (!token) return
    setActionId('expire-now')
    setMessage('')
    try {
      const result = await expireSubscriptionsNow(token)
      setMessage(`만료 처리 완료: 대상 ${result.targetCount}명, 전환 ${result.expiredCount}명, 실패 ${result.failedCount}명`)
      await loadUsers()
      await loadSystem()
      markRefreshed('구독 만료 데이터')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '구독 만료 처리에 실패했습니다.')
    } finally {
      setActionId(null)
    }
  }

  const handleMenu = (item: AdminMenuEntry) => {
    setMenuOpen(false)
    if (item.type === 'view' && item.view) {
      goAdminView(item.view)
      return
    }
    if (item.type === 'route' && item.route) {
      navigate(item.route)
      return
    }
    if (item.type === 'external' && item.href) {
      window.open(item.href, '_blank', 'noopener,noreferrer')
      return
    }
    if (item.type === 'logout') {
      logout()
      navigate('/landing', { replace: true })
    }
  }

  const openHomeSection = (section: string) => {
    navigate(`/#${section}`)
  }

  const refreshCurrentView = async () => {
    const label = view === 'system'
      ? '시스템 상태'
      : view === 'dashboard'
        ? '관리자 데이터'
        : '회원/구독 데이터'
    setActionId('admin-refresh')
    setMessage(`${label} 새로고침 중입니다...`)
    try {
      const ok = view === 'system'
        ? await loadSystem()
        : view === 'dashboard'
          ? (await Promise.all([loadUsers(false), loadSystem()])).every(Boolean)
          : await loadUsers(false)
      if (ok) {
        markRefreshed(label)
        setMessage(`${label} 새로고침 완료 · ${formatTime(new Date().toISOString())} 기준`)
      } else {
        setMessage(`${label} 새로고침 중 일부 항목을 확인하지 못했습니다.`)
      }
    } finally {
      setActionId(null)
    }
  }

  const meta = viewMeta[view]
  const kpiCards = [
    { label: '승인 대기', value: pendingCount, desc: '입금 확인 후 승인/반려', view: 'approvals' as AdminView, focus: null as AdminFocus },
    { label: '활성 구독자', value: activeCount, desc: 'PRO 구독 현황 관리', view: 'subscriptions' as AdminView, focus: 'active' as AdminFocus },
    { label: '무료 회원', value: freeCount, desc: '전체 회원 목록 확인', view: 'members' as AdminView, focus: null as AdminFocus },
    { label: '만료일 관리', value: expiringCount, desc: '만료/연장 대상 점검', view: 'subscriptions' as AdminView, focus: 'expiring' as AdminFocus },
  ]

  const onlineAgents = systemStatus?.engines?.filter(engine => engine.online).length ?? 0
  const totalAgents = systemStatus?.engines?.length ?? systemStatus?.totalAgents ?? 0
  const cacheList = systemStatus?.cache ?? []
  const conditionRuns = systemStatus?.conditionRuns ?? []
  const latestRun = conditionRuns[0]
  const runningRunCount = conditionRuns.filter(run => !['COMPLETE', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(run.status)).length
  const staleCacheCount = cacheList.filter(item => item.status !== 'valid').length
  const cacheSummary = cacheList.length
    ? `${cacheList.length - staleCacheCount}/${cacheList.length} valid`
    : 'cache pending'
  const systemCards = [
    {
      name: 'KIS 조건검색',
      value: systemStatus?.kisAvailable ? 'ONLINE' : 'CHECK',
      desc: systemStatus?.kisAvailable ? '실시간/랭킹 API 사용 가능' : 'KIS 키 또는 호출 상태 확인 필요',
      tone: systemStatus?.kisAvailable ? 'ok' : 'warn',
    },
    {
      name: 'AI 엔진',
      value: `${onlineAgents}/${totalAgents || '-'}`,
      desc: systemStatus?.engines?.map(engine => `${engine.name}:${engine.online ? 'on' : 'off'}`).join(' · ') || '엔진 상태 대기',
      tone: onlineAgents > 0 ? 'ok' : 'warn',
    },
    {
      name: '조건검색 캐시',
      value: cacheSummary,
      desc: cacheList.map(item => `${item.mode} ${item.status}${item.elapsed ? ` ${item.elapsed}` : ''}`).join(' · ') || '캐시 상태 대기',
      tone: staleCacheCount === 0 && cacheList.length > 0 ? 'ok' : 'warn',
    },
    {
      name: '스케줄러',
      value: systemStatus?.schedulerEnabled ? 'ACTIVE' : 'OFF',
      desc: systemStatus?.responseTime != null ? `응답 ${systemStatus.responseTime}ms` : '스케줄러 상태 대기',
      tone: systemStatus?.schedulerEnabled ? 'ok' : 'warn',
    },
    {
      name: 'ConditionRun',
      value: latestRun ? runStatusLabel(latestRun.status) : 'READY',
      desc: latestRun
        ? `${latestRun.mode || '-'} · ${runTriggerLabel(latestRun.trigger)} · ${formatTime(latestRun.updatedAt)}`
        : 'No observed condition run yet',
      tone: runningRunCount > 0 ? 'pending' : latestRun?.status === 'FAILED' ? 'warn' : 'ok',
    },
  ]
  const isRefreshing = actionId === 'admin-refresh'
  const refreshSummary = view === 'system'
    ? `${onlineAgents}/${totalAgents || '-'} AI 엔진 · 캐시 ${cacheSummary}`
    : `전체 ${users.length}명 · 승인대기 ${pendingCount}건 · PRO ${activeCount}명`

  return (
    <main className={`admin-page${menuOpen ? ' admin-menu-open' : ''}`}>
      <header className="admin-mobile-topbar">
        <button
          className="admin-mobile-menu-button"
          type="button"
          aria-label="관리 메뉴 열기"
          aria-controls="admin-menu-drawer"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen(true)}
        >
          <span />
          <span />
          <span />
        </button>
        <div>
          <strong>{meta.label}</strong>
          <span>{meta.kicker}</span>
        </div>
        <AdminRefreshButton busy={isRefreshing} lastRefreshedAt={lastRefreshedAt} onRefresh={refreshCurrentView} />
      </header>

      <button
        className="admin-menu-backdrop"
        type="button"
        aria-label="관리 메뉴 닫기"
        onClick={() => setMenuOpen(false)}
      />

      <aside className="admin-sidebar" id="admin-menu-drawer" aria-label="관리자 메뉴">
        <div className="admin-brand">
          <span className="admin-brand-mark">B</span>
          <div>
            <strong>BitMan 운영센터</strong>
            <span>AI CONDITION ADMIN</span>
          </div>
          <button className="admin-sidebar-close" type="button" aria-label="관리 메뉴 닫기" onClick={() => setMenuOpen(false)}>닫기</button>
        </div>

        <AdminMenuGroup items={productMenu} activeView={view} onSelect={handleMenu} />

        <div className="admin-menu-divider">
          <span>관리</span>
        </div>

        <AdminMenuGroup items={adminMenu} activeView={view} onSelect={handleMenu} />

        <div className="admin-menu-status">
          <span />
          <strong>ACTIVE</strong>
          <em>Dual-Agent V4.0</em>
        </div>
      </aside>

      <section className="admin-workspace">
        <header className="admin-topbar">
          <div>
            <p>{meta.kicker}</p>
            <h1>{meta.label}</h1>
            <span>{meta.desc}</span>
          </div>
          <AdminRefreshButton busy={isRefreshing} lastRefreshedAt={lastRefreshedAt} onRefresh={refreshCurrentView} />
        </header>

        {message && <div className="admin-message">{message}</div>}

        <div className={`admin-refresh-status${isRefreshing ? ' is-refreshing' : ''}`}>
          <span />
          <strong>{isRefreshing ? '새로고침 중...' : `${lastRefreshLabel} · ${formatTime(lastRefreshedAt)} 기준`}</strong>
          <em>{refreshSummary}</em>
        </div>

        {view !== 'system' && (
          <div className="admin-kpi-grid">
            {kpiCards.map(card => (
              <button
                className="admin-kpi-card"
                type="button"
                key={card.label}
                onClick={() => goAdminView(card.view, '', card.focus)}
              >
                <span>{card.label}</span>
                <strong>{card.value}</strong>
                <em>{card.desc}</em>
              </button>
            ))}
          </div>
        )}

        {view === 'dashboard' && (
          <>
            <div className="admin-dashboard-grid">
              {Object.entries(strategyViews).map(([key, strategy]) => (
                <button className="admin-strategy-card" type="button" key={key} onClick={() => goAdminView(key as AdminView)}>
                  <span>{strategy.mode}</span>
                  <strong>{viewMeta[key as AdminView].label}</strong>
                  <p>{viewMeta[key as AdminView].desc}</p>
                </button>
              ))}
            </div>

            <AdminPanel title="오늘 처리할 운영 업무" description="대기 승인과 주요 운영 상태를 빠르게 확인합니다.">
              <div className="admin-task-list">
                <button type="button" onClick={() => goAdminView('approvals')}>
                  <strong>구독 승인 대기 {pendingCount}건</strong>
                  <span>입금 확인 후 PRO 권한을 부여하세요.</span>
                </button>
                <button type="button" onClick={() => goAdminView('system')}>
                  <strong>검색식 캐시 상태 확인</strong>
                  <span>KIS, DART, DeepSeek 연동과 분석 캐시를 점검하세요.</span>
                </button>
                <button type="button" onClick={() => goAdminView('members')}>
                  <strong>회원 목록 점검</strong>
                  <span>신규 회원, 무료 회원, PRO 회원 상태를 확인하세요.</span>
                </button>
              </div>
            </AdminPanel>
          </>
        )}

        {view in strategyViews && (
            <AdminPanel title={`${meta.label} 관리`} description={meta.desc}>
              <div className="admin-strategy-detail">
                <div>
                <span>조건검색식 엔드포인트</span>
                <strong>/api/analysis/{strategyViews[view as keyof typeof strategyViews].mode}</strong>
                <p>실시간 조건검색 분석 호출: /api/analysis/live</p>
              </div>
              <div>
                <span>검색식 체크리스트</span>
                <ul>
                  {strategyViews[view as keyof typeof strategyViews].checks.map(check => <li key={check}>{check}</li>)}
                </ul>
              </div>
            </div>
            <div className="admin-action-row">
              <button type="button" onClick={() => openHomeSection(strategyViews[view as keyof typeof strategyViews].section)}>
                사용자 화면에서 확인
              </button>
              <button type="button" disabled={actionId === 'system-refresh'} onClick={refreshSystem}>
                분석 캐시 새로고침
              </button>
            </div>
          </AdminPanel>
        )}

        {view === 'approvals' && (
          <>
            <AdminPanel title="승인 대기" description="입금 확인 또는 수동 승인 요청을 처리합니다.">
              <div className="approval-guide">
                <strong>처리 순서</strong>
                <span>1. 입금자명 확인</span>
                <span>2. 신청 회원 확인</span>
                <span>3. 승인 또는 반려</span>
                <span>4. 사용자 권한 자동 활성화</span>
              </div>
              <AdminToolbar search={search} onSearch={setSearch} placeholder="승인대기 회원명, 이메일, 입금자명, ID 검색" />
              <div className="admin-result-summary" data-sort="pending-requested-at-desc-v3">
                최신 신청순 · 승인대기 검색 결과 {filteredPendingUsers.length}명 / 전체 {pendingUsers.length}명
              </div>
              <AdminTable
                columns={['회원', '이메일', '입금자명', '신청일', '상태', '액션']}
                emptyText={loading ? '불러오는 중입니다.' : search.trim() ? '검색된 승인 대기 회원이 없습니다.' : '승인 대기 회원이 없습니다.'}
              >
                {filteredPendingUsers.map(user => (
                  <tr key={user.id}>
                    <td>{user.name}</td>
                    <td>{user.email}</td>
                    <td>{user.depositorName || '-'}</td>
                    <td>{formatDateTime(user.subscriptionRequestedAt ?? user.createdAt)}</td>
                    <td><StatusBadge status={user.subscription} /></td>
                    <td className="admin-row-actions">
                      <button disabled={actionId === user.id} onClick={() => runAction(user, 'approve')}>승인</button>
                      <button disabled={actionId === user.id} onClick={() => runAction(user, 'reject')}>반려</button>
                    </td>
                  </tr>
                ))}
              </AdminTable>
            </AdminPanel>

            <AdminPanel title="PRO 구독 해제" description="승인된 회원을 수동으로 FREE 상태로 되돌립니다.">
              <AdminToolbar search={search} onSearch={setSearch} placeholder="회원명, 이메일, ID 검색" />
              <AdminTable columns={['회원', '입금자명', '플랜', '만료일', '상태', '액션']} emptyText="구독 회원이 없습니다.">
                {filteredUsers.filter(user => user.subscription === 'PRO').map(user => (
                  <tr key={user.id}>
                    <td>{user.name}</td>
                    <td>{user.depositorName || '-'}</td>
                    <td>{user.subscription}</td>
                    <td>{formatDate(user.subscriptionEndDate)}</td>
                    <td><StatusBadge status={user.subscription} /></td>
                    <td className="admin-row-actions">
                      <button disabled={actionId === user.id} onClick={() => runAction(user, 'revoke')}>구독 해제</button>
                    </td>
                  </tr>
                ))}
              </AdminTable>
            </AdminPanel>
          </>
        )}

        {view === 'subscriptions' && (
          <AdminPanel title="구독 관리" description="정상 구독, 만료 예정, 수동 해제를 관리합니다.">
            <div className="admin-filter-tabs" aria-label="구독 관리 필터">
              <button type="button" className={!focus ? 'active' : ''} onClick={() => goAdminView('subscriptions')}>전체</button>
              <button type="button" className={focus === 'active' ? 'active' : ''} onClick={() => goAdminView('subscriptions', search, 'active')}>활성 PRO</button>
              <button type="button" className={focus === 'expiring' ? 'active' : ''} onClick={() => goAdminView('subscriptions', search, 'expiring')}>만료일순</button>
              <button type="button" onClick={() => goAdminView('approvals', search)}>승인대기</button>
              <button type="button" onClick={() => goAdminView('members', search)}>전체 회원</button>
            </div>
            <AdminToolbar search={search} onSearch={setSearch} placeholder="회원명, 이메일, ID 검색" />
            {focus === 'active' && <div className="admin-filter-banner">활성 PRO 구독자만 표시합니다.</div>}
            {focus === 'expiring' && <div className="admin-filter-banner">만료일이 있는 PRO 구독자를 만료일이 가까운 순서로 표시합니다.</div>}
            <div className="admin-result-summary">
              구독 목록 {visibleSubscriptionUsers.length}명 / 전체 회원 {users.length}명
            </div>
            <div className="admin-action-row admin-subscription-actions">
              <button type="button" disabled={actionId === 'expire-now'} onClick={runExpiryNow}>
                만료 구독 즉시 처리
              </button>
              <button type="button" onClick={() => goAdminView('approvals')}>
                승인 대기 보기
              </button>
            </div>
            <AdminTable columns={['회원', '입금자명', '플랜', '승인일', '만료일', '상태', '액션']} emptyText="회원이 없습니다.">
              {visibleSubscriptionUsers.map(user => (
                <tr key={user.id}>
                  <td>{user.name}</td>
                  <td>{user.depositorName || '-'}</td>
                  <td>{user.subscription}</td>
                  <td>{formatDate(user.subscriptionApprovedAt)}</td>
                  <td>{formatDate(user.subscriptionEndDate)}</td>
                  <td><StatusBadge status={user.subscription} /></td>
                  <td className="admin-row-actions">
                    {user.role === 'ADMIN' ? (
                      <span className="admin-protected-label">관리자 보호</span>
                    ) : user.subscription === 'PRO' ? (
                      <button disabled={actionId === user.id} onClick={() => runAction(user, 'revoke')}>구독 해제</button>
                    ) : (
                      <button disabled={actionId === user.id} onClick={() => changeSubscription(user, 'PRO')}>수동 승인</button>
                    )}
                    <button disabled={actionId === user.id} onClick={() => startEditUser(user)}>수정</button>
                  </td>
                </tr>
              ))}
            </AdminTable>
            {editingUser && <AdminUserEditor
              user={editingUser}
              form={editForm}
              password={resetPassword}
              actionId={actionId}
              onFormChange={setEditForm}
              onPasswordChange={setResetPassword}
              onSave={saveEditedUser}
              onResetPassword={resetEditedUserPassword}
              onCancel={() => setEditingUser(null)}
            />}
          </AdminPanel>
        )}

        {view === 'members' && (
          <AdminPanel title="회원 관리" description="회원 상태, 가입일, 구독권한을 빠르게 확인합니다.">
            <AdminToolbar search={search} onSearch={setSearch} placeholder="회원명, 이메일, ID 검색" />
            <div className="admin-result-summary">
              회원 검색 결과 {filteredUsers.length}명 / 전체 {users.length}명
            </div>
            <AdminTable columns={['회원ID', '회원', '이메일', '역할', '구독', '가입일', '액션']} emptyText="회원이 없습니다.">
              {filteredUsers.map(user => (
                <tr key={user.id}>
                  <td className="mono-cell">{user.id.slice(0, 8)}</td>
                  <td>{user.name}</td>
                  <td>{user.email}</td>
                  <td>{user.role}</td>
                  <td><StatusBadge status={user.subscription} /></td>
                  <td>{formatDate(user.createdAt)}</td>
                  <td className="admin-row-actions">
                    <button disabled={actionId === user.id} onClick={() => startEditUser(user)}>수정</button>
                    {user.role === 'ADMIN' ? (
                      <span className="admin-protected-label">관리자 보호</span>
                    ) : user.subscription !== 'PRO' ? (
                      <button disabled={actionId === user.id} onClick={() => changeSubscription(user, 'PRO')}>PRO 승인</button>
                    ) : (
                      <button disabled={actionId === user.id} onClick={() => changeSubscription(user, 'FREE')}>해제</button>
                    )}
                  </td>
                </tr>
              ))}
            </AdminTable>
            {editingUser && <AdminUserEditor
              user={editingUser}
              form={editForm}
              password={resetPassword}
              actionId={actionId}
              onFormChange={setEditForm}
              onPasswordChange={setResetPassword}
              onSave={saveEditedUser}
              onResetPassword={resetEditedUserPassword}
              onCancel={() => setEditingUser(null)}
            />}
          </AdminPanel>
        )}

        {view === 'system' && (
          <AdminPanel title="시스템 관리" description="KIS, DART, DeepSeek, 검색식 캐시 상태를 확인합니다.">
            <div className="system-grid">
              {systemCards.map(card => (
                <div className={`system-card system-card-${card.tone}`} key={card.name}>
                  <span>{card.name}</span>
                  <strong>{systemStatus?.error ? '확인 필요' : card.value}</strong>
                  <p>{systemStatus?.error || card.desc}</p>
                </div>
              ))}
            </div>
            <div className="admin-run-summary">
              <strong>ConditionRun timeline</strong>
              <span>{runningRunCount > 0 ? `${runningRunCount} active` : 'No active run'}</span>
            </div>
            <AdminTable
              columns={['Run', 'Mode', 'Trigger', 'Status', 'Picks', 'Agents', 'Duration', 'Updated']}
              emptyText="No condition runs observed yet."
            >
              {conditionRuns.map(run => (
                <tr key={run.runId}>
                  <td className="mono-cell">{run.traceId || run.runId.slice(0, 8)}</td>
                  <td>{run.mode || '-'}</td>
                  <td>{runTriggerLabel(run.trigger)}</td>
                  <td><span className={`admin-run-status admin-run-${runStatusTone(run.status)}`}>{runStatusLabel(run.status)}</span></td>
                  <td>{run.pickCount ?? '-'}</td>
                  <td>{run.agentsUsed != null ? `${run.agentsSucceeded ?? 0}/${run.agentsUsed}` : '-'}</td>
                  <td>{formatRunDuration(run.startedAt, run.finishedAt)}</td>
                  <td>{formatDateTime(run.updatedAt)}</td>
                </tr>
              ))}
            </AdminTable>
            <button className="admin-primary-action" type="button" disabled={actionId === 'system-refresh'} onClick={refreshSystem}>
              조건검색식 / 분석 캐시 새로고침
            </button>
            <button className="admin-secondary-action" type="button" disabled={actionId === 'expire-now'} onClick={runExpiryNow}>
              구독 만료 배치 즉시 실행
            </button>
          </AdminPanel>
        )}
      </section>
    </main>
  )
}

function AdminUserEditor({
  user,
  form,
  password,
  actionId,
  onFormChange,
  onPasswordChange,
  onSave,
  onResetPassword,
  onCancel,
}: {
  user: UserDto
  form: { name: string; email: string; subscription: string }
  password: string
  actionId: string | null
  onFormChange: (form: { name: string; email: string; subscription: string }) => void
  onPasswordChange: (value: string) => void
  onSave: (event: FormEvent) => void
  onResetPassword: () => void
  onCancel: () => void
}) {
  return (
    <form className="admin-user-editor" onSubmit={onSave}>
      <div className="admin-user-editor-head">
        <div>
          <strong>{user.name}</strong>
          <span>{user.email}</span>
        </div>
        <button type="button" onClick={onCancel}>닫기</button>
      </div>

      <div className="admin-user-editor-grid">
        <label>
          <span>회원명</span>
          <input value={form.name} onChange={event => onFormChange({ ...form, name: event.target.value })} />
        </label>
        <label>
          <span>이메일</span>
          <input value={form.email} onChange={event => onFormChange({ ...form, email: event.target.value })} />
        </label>
        <label>
          <span>등급</span>
          <select
            value={form.subscription}
            disabled={user.role === 'ADMIN'}
            onChange={event => onFormChange({ ...form, subscription: event.target.value })}
          >
            <option value="FREE">FREE</option>
            <option value="PENDING">PENDING</option>
            <option value="PRO">PRO</option>
          </select>
          {user.role === 'ADMIN' && <em>관리자 계정은 PRO 상태로 고정됩니다.</em>}
        </label>
      </div>

      <div className="admin-user-editor-actions">
        <button type="submit" disabled={actionId === user.id}>회원정보 저장</button>
      </div>

      <div className="admin-password-reset">
        <label>
          <span>새 비밀번호</span>
          <input
            type="password"
            value={password}
            onChange={event => onPasswordChange(event.target.value)}
            placeholder="대/소문자+숫자+특수문자 12자 이상"
          />
        </label>
        <button type="button" disabled={actionId === `${user.id}-password`} onClick={onResetPassword}>
          비밀번호 초기화
        </button>
      </div>
    </form>
  )
}

function AdminMenuGroup({
  items,
  activeView,
  onSelect,
}: {
  items: AdminMenuEntry[]
  activeView: AdminView
  onSelect: (item: AdminMenuEntry) => void
}) {
  return (
    <nav className="admin-menu-list">
      {items.map(item => (
        <button
          className={`admin-menu-item admin-menu-${item.tone}${item.view === activeView ? ' active' : ''}`}
          type="button"
          key={item.key}
          onClick={() => onSelect(item)}
        >
          <span className="admin-menu-icon">{item.icon}</span>
          <span className="admin-menu-copy">
            <strong>{item.label}</strong>
            <em>{item.desc}</em>
          </span>
        </button>
      ))}
    </nav>
  )
}

function AdminPanel({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return (
    <section className="admin-panel">
      <div className="admin-panel-header">
        <div>
          <h2>{title}</h2>
          <p>{description}</p>
        </div>
      </div>
      {children}
    </section>
  )
}

function AdminRefreshButton({
  busy,
  lastRefreshedAt,
  onRefresh,
}: {
  busy: boolean
  lastRefreshedAt: string | null
  onRefresh: () => void
}) {
  return (
    <button className={`admin-refresh-button${busy ? ' is-refreshing' : ''}`} type="button" disabled={busy} onClick={onRefresh}>
      <strong>{busy ? '갱신중' : '새로고침'}</strong>
      <span>{busy ? '데이터 확인' : `${formatTime(lastRefreshedAt)} 기준`}</span>
    </button>
  )
}

function AdminToolbar({ search, onSearch, placeholder }: { search: string; onSearch: (value: string) => void; placeholder: string }) {
  return (
    <div className="admin-toolbar">
      <input value={search} onChange={event => onSearch(event.target.value)} placeholder={placeholder} />
    </div>
  )
}

function AdminTable({
  columns,
  children,
  emptyText,
}: {
  columns: string[]
  children: ReactNode
  emptyText: string
}) {
  const hasRows = Array.isArray(children) ? children.length > 0 : !!children
  const labeledRows = hasRows ? labelAdminTableRows(children, columns) : null

  return (
    <div className="admin-table-wrap">
      <table className="admin-table">
        <thead>
          <tr>{columns.map(column => <th key={column}>{column}</th>)}</tr>
        </thead>
        <tbody>
          {hasRows ? labeledRows : (
            <tr>
              <td colSpan={columns.length} className="admin-empty">{emptyText}</td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

function labelAdminTableRows(children: ReactNode, columns: string[]) {
  return Children.map(children, row => {
    if (!isValidElement(row)) return row

    const rowElement = row as ReactElement<any>

    return cloneElement(rowElement, {
      children: Children.map(rowElement.props.children, (cell, index) => {
        if (!isValidElement(cell)) return cell

        const cellElement = cell as ReactElement<any>
        const existingLabel = cellElement.props['data-label']

        return cloneElement(cellElement, {
          'data-label': existingLabel || columns[index] || '',
        })
      }),
    })
  })
}
