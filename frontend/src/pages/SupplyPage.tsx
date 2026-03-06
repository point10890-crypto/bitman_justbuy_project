export default function SupplyPage() {
  return (
    <main className="flex-1 flex flex-col items-center justify-center" style={{ padding: '14px var(--page-px) 10px' }}>
      <div className="text-center animate-slide-up">
        <span className="text-4xl block mb-4">📊</span>
        <h2 className="text-[18px] font-bold mb-2" style={{ color: 'var(--text-primary)' }}>수급 분석</h2>
        <p className="text-[13px]" style={{ color: 'var(--text-muted)' }}>준비 중입니다</p>
        <p className="text-[11px] mt-1" style={{ color: 'var(--text-muted)' }}>외인·기관 수급 데이터가 곧 제공됩니다</p>
      </div>
    </main>
  )
}
