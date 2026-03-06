import type { ButtonHTMLAttributes, ReactNode } from 'react'

interface GoldButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className'> {
  children: ReactNode
  variant?: 'filled' | 'outline'
  loading?: boolean
  fullWidth?: boolean
  delay?: number
}

export default function GoldButton({ children, variant = 'filled', loading, fullWidth = true, disabled, delay = 0, ...props }: GoldButtonProps) {
  const isFilled = variant === 'filled'
  const isDisabled = disabled || loading

  return (
    <button
      disabled={isDisabled}
      className={`flex items-center justify-center gap-2 font-bold text-[15px] py-3.5 rounded-xl animate-slide-up ${fullWidth ? 'w-full' : 'px-8'}`}
      style={{
        background: isFilled
          ? 'linear-gradient(135deg, #FFD700 0%, #FF9800 100%)'
          : 'transparent',
        color: isFilled ? '#1A1A1A' : '#FFD700',
        border: isFilled ? 'none' : '1.5px solid rgba(255, 215, 0, 0.5)',
        opacity: isDisabled ? 0.5 : 1,
        cursor: isDisabled ? 'not-allowed' : 'pointer',
        boxShadow: isFilled && !isDisabled ? '0 4px 24px rgba(255, 215, 0, 0.3)' : 'none',
        transition: 'all 0.25s ease',
        transform: 'translateY(0)',
        animationDelay: `${delay}s`,
        animationFillMode: 'backwards',
      }}
      onMouseEnter={e => {
        if (!isDisabled) {
          e.currentTarget.style.transform = 'translateY(-1px)'
          e.currentTarget.style.boxShadow = isFilled
            ? '0 6px 28px rgba(255, 215, 0, 0.4)'
            : '0 2px 16px rgba(255, 215, 0, 0.15)'
        }
      }}
      onMouseLeave={e => {
        e.currentTarget.style.transform = 'translateY(0)'
        e.currentTarget.style.boxShadow = isFilled && !isDisabled
          ? '0 4px 24px rgba(255, 215, 0, 0.3)'
          : 'none'
      }}
      {...props}
    >
      {loading ? (
        <>
          <span className="w-4 h-4 border-2 border-current border-t-transparent rounded-full animate-spin" />
          <span>처리 중...</span>
        </>
      ) : children}
    </button>
  )
}
