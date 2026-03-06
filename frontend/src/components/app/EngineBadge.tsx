interface EngineBadgeProps {
  name: string
  role: string
  color: string
}

export function EngineBadge({ name, role, color }: EngineBadgeProps) {
  return (
    <div className="engine-badge" style={{ backgroundColor: `${color}10`, border: `1px solid ${color}20` }}>
      <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: color, boxShadow: `0 0 6px ${color}60` }} />
      <div className="flex flex-col min-w-0">
        <span className="text-[10px] font-bold truncate" style={{ color }}>{name}</span>
        <span className="text-[8px]" style={{ color: 'var(--text-muted)' }}>{role}</span>
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
      <div className="engine-grid-3">
        <EngineBadge name="Claude" role="종합·공시" color="#FF6B35" />
        <EngineBadge name="Gemini" role="차트·섹터" color="#4285F4" />
        <EngineBadge name="ChatGPT" role="심층·글로벌" color="#10A37F" />
      </div>
      <div className="engine-grid-2" style={{ marginTop: '6px' }}>
        <EngineBadge name="Perplexity" role="실시간 웹" color="#20B2AA" />
        <EngineBadge name="Grok" role="소셜·감성" color="#FF4500" />
      </div>
      <div className="flex items-center gap-2" style={{ marginTop: '8px', paddingTop: '8px', borderTop: '1px solid var(--border-subtle)' }}>
        <span className="text-[8.5px]" style={{ color: 'var(--text-muted)' }}>Multi-Agent V3.0</span>
        <span className="text-[8.5px] font-mono" style={{ color: 'var(--color-bull)' }}>● ACTIVE</span>
        <span className="text-[8.5px] ml-auto" style={{ color: 'var(--text-muted)' }}>W(공시)=0.95 · W(연기금)=0.85</span>
      </div>
    </div>
  )
}
