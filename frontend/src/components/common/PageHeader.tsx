import { useNavigate } from 'react-router-dom'

interface PageHeaderProps {
  showBack?: boolean
}

export default function PageHeader({ showBack = true }: PageHeaderProps) {
  const navigate = useNavigate()

  return (
    <div className="w-full">
      {/* Gold top line */}
      <div className="h-[2px] w-full animate-gradient" style={{ backgroundImage: 'var(--gradient-gold-line)', backgroundSize: '200% 200%' }} />

      <div style={{ paddingTop: 'max(12px, env(safe-area-inset-top))' }}>
        <div className="flex items-center justify-between" style={{ padding: '10px 20px 14px' }}>
          {/* Back button */}
          {showBack ? (
            <button
              type="button"
              onClick={() => navigate(-1)}
              className="flex items-center justify-center w-9 h-9 rounded-full transition-colors"
              style={{ backgroundColor: 'var(--bg-card)', border: '1px solid var(--border-subtle)' }}
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--text-secondary)' }}>
                <path d="M15 18l-6-6 6-6" />
              </svg>
            </button>
          ) : <div className="w-9" />}

          {/* Logo */}
          <div className="flex items-center gap-2">
            <div className="relative w-8 h-8 rounded-lg flex items-center justify-center logo-gold">
              <span className="font-black text-base leading-none logo-gold-text">B</span>
            </div>
            <div className="flex items-baseline gap-1">
              <span className="animate-gold-shimmer font-black text-[15px] tracking-tight" style={{ filter: 'drop-shadow(0 0 5px rgba(255,215,0,0.35))' }}>BitMan</span>
              <span className="font-bold text-[13px]" style={{ color: '#E8E0D0' }}>오늘</span>
              <span className="animate-gold-shimmer font-black text-[13px]" style={{ filter: 'drop-shadow(0 0 5px rgba(255,215,0,0.35))' }}>뭐사?</span>
            </div>
          </div>

          <div className="w-9" />
        </div>
      </div>
    </div>
  )
}
