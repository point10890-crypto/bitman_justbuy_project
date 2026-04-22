interface NavItemProps {
  icon: string
  label: string
  active?: boolean
  onClick?: () => void
}

function NavItem({ icon, label, active = false, onClick }: NavItemProps) {
  return (
    <button className="nav-item" style={{ opacity: active ? 1 : 0.45 }} onClick={onClick}>
      <span className="text-lg">{icon}</span>
      <span className="text-[9px] font-bold" style={{ color: active ? 'var(--color-bull)' : 'var(--text-muted)' }}>{label}</span>
      {active && <div className="w-4 h-0.5 rounded-full" style={{ backgroundColor: 'var(--color-bull)', marginTop: '1px' }} />}
    </button>
  )
}

export function BottomNav() {
  const handleContactAdmin = () => {
    window.open('https://open.kakao.com/o/sJVLbWUe', '_blank')
  }

  return (
    <nav className="sticky bottom-0 z-50 w-full" style={{ backgroundColor: 'rgba(13,17,23,0.95)', backdropFilter: 'blur(20px)', WebkitBackdropFilter: 'blur(20px)', borderTop: '1px solid var(--border-subtle)' }}>
      <div className="bottom-nav">
        <NavItem icon="🏠" label="홈" active />
        <NavItem icon="📊" label="수급" />
        <NavItem icon="👤" label="마이" />
        <NavItem icon="💬" label="문의" onClick={handleContactAdmin} />
      </div>
    </nav>
  )
}
