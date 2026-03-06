import type { ReactNode, CSSProperties } from 'react'

interface GlassCardProps {
  children: ReactNode
  style?: CSSProperties
  noPadding?: boolean
}

export default function GlassCard({ children, style, noPadding }: GlassCardProps) {
  return (
    <div
      className="animate-slide-up outer-frame-glow"
      style={{
        borderRadius: '22px',
        padding: '1px',
        background: 'linear-gradient(155deg, rgba(255,215,0,0.4) 0%, rgba(100,108,120,0.3) 20%, rgba(55,62,72,0.45) 45%, rgba(100,108,120,0.3) 75%, rgba(0,200,83,0.25) 100%)',
        boxShadow: '0 8px 40px rgba(0,0,0,0.45), 0 0 60px rgba(255,215,0,0.03)',
        animationDelay: '0.05s',
        animationFillMode: 'backwards',
        ...style,
      }}
    >
      <div style={{
        borderRadius: '21px',
        padding: noPadding ? '0' : '24px 20px',
        background: 'linear-gradient(180deg, rgba(22,27,34,0.98) 0%, rgba(13,17,23,0.99) 100%)',
        backdropFilter: 'blur(16px)',
      }}>
        {children}
      </div>
    </div>
  )
}
