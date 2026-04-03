interface EngineBadgeProps {
  name: string
  role: string
  color: string
  layers?: string
}

export function EngineBadge({ name, role, color, layers }: EngineBadgeProps) {
  return (
    <div className="engine-badge" style={{ backgroundColor: `${color}10`, border: `1px solid ${color}20` }}>
      <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: color, boxShadow: `0 0 6px ${color}60` }} />
      <div className="flex flex-col min-w-0">
        <span className="text-[10px] font-bold truncate" style={{ color }}>{name}</span>
        <span className="text-[8px]" style={{ color: 'var(--text-muted)' }}>{role}</span>
        {layers && <span className="text-[7px]" style={{ color: `${color}90` }}>{layers}</span>}
      </div>
    </div>
  )
}

export function EngineStatusPanel() {
  return (
    <div style={{ padding: '12px', borderRadius: '14px', backgroundColor: 'var(--bg-glass)', border: '1px solid var(--border-default)', backdropFilter: 'blur(12px)' }}>
      <div className="section-header" style={{ marginBottom: '6px' }}>
        <span className="section-title">AI Engine Status</span>
        <span className="text-[8.5px] font-bold px-2 py-0.5 rounded-full" style={{ backgroundColor: 'rgba(0,200,83,0.12)', color: 'var(--color-bull)' }}>ALL ONLINE</span>
      </div>
      <div className="engine-grid-2">
        <EngineBadge name="ChatGPT" role="매크로·기술·펀더멘털" color="#10A37F" layers="L1+L2+L4+L6" />
        <EngineBadge name="Grok" role="수급·파생·SNS/X" color="#FF4500" layers="L3+L5+X피드" />
      </div>
      <div className="flex items-center gap-2" style={{ marginTop: '8px', paddingTop: '8px', borderTop: '1px solid var(--border-subtle)' }}>
        <span className="text-[8.5px]" style={{ color: 'var(--text-muted)' }}>Dual-Agent V4.0</span>
        <span className="text-[8.5px] font-mono" style={{ color: 'var(--color-bull)' }}>● ACTIVE</span>
        <span className="text-[8.5px] ml-auto" style={{ color: 'var(--text-muted)' }}>3R 토론 · KIS수급 교차검증</span>
      </div>
    </div>
  )
}
