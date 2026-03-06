import { useCountUp, useBlinkOnMount } from '../../hooks/useAnimations'

export function formatValue(value: number, symbol: string): string {
  if (symbol === 'USDKRW') return value.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  if (symbol === 'IXIC') return Math.round(value).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return value.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

export function formatChange(percent: number): string {
  const sign = percent >= 0 ? '+' : ''
  return `${sign}${percent.toFixed(2)}%`
}

interface MarketChipProps {
  label: string
  value: string
  change: string
  isUp: boolean
  delay?: number
}

export function MarketChip({ label, value, change, isUp, delay = 0 }: MarketChipProps) {
  const animatedValue = useCountUp(value, 1400)
  const showChange = useBlinkOnMount(delay + 800)
  return (
    <div className="flex-shrink-0 flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] market-chip" style={{ backgroundColor: 'var(--bg-card)', border: `1px solid ${isUp ? 'rgba(0,200,83,0.12)' : 'rgba(255,23,68,0.12)'}` }}>
      <span className="font-medium" style={{ color: 'var(--text-secondary)' }}>{label}</span>
      <span className="font-bold font-mono tabular-nums" style={{ color: 'var(--text-primary)' }}>{animatedValue}</span>
      <span className={`font-bold font-mono tabular-nums transition-all duration-500 ${showChange ? 'market-change-glow' : ''}`} style={{ color: isUp ? 'var(--color-bull)' : 'var(--color-bear)', opacity: showChange ? 1 : 0, transform: showChange ? 'translateY(0)' : 'translateY(3px)' }}>
        {isUp ? '▲' : '▼'} {change}
      </span>
    </div>
  )
}

interface MarketData {
  symbol: string
  label: string
  value: number
  changePercent: number
  isUp: boolean
}

interface MarketTickerBarProps {
  marketData: MarketData[]
}

export function MarketTickerBar({ marketData }: MarketTickerBarProps) {
  return (
    <div className="overflow-hidden relative" style={{ padding: '2px var(--page-px) 6px' }}>
      <div className="absolute left-0 top-0 bottom-0 w-5 z-10" style={{ background: 'linear-gradient(to right, var(--bg-primary), transparent)' }} />
      <div className="absolute right-0 top-0 bottom-0 w-5 z-10" style={{ background: 'linear-gradient(to left, var(--bg-primary), transparent)' }} />
      <div className="market-ticker-track flex gap-2.5 w-max">
        {marketData.map((m, i) => (
          <MarketChip key={m.symbol} label={m.label} value={formatValue(m.value, m.symbol)} change={formatChange(m.changePercent)} isUp={m.isUp} delay={i * 150} />
        ))}
        {marketData.map((m, i) => (
          <MarketChip key={`dup-${m.symbol}`} label={m.label} value={formatValue(m.value, m.symbol)} change={formatChange(m.changePercent)} isUp={m.isUp} delay={600 + i * 150} />
        ))}
      </div>
    </div>
  )
}
