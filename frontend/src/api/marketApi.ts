/** 시장 지수 데이터 API 모듈 — Yahoo Finance (Vite 프록시) */

export interface MarketIndex {
  label: string
  symbol: string
  value: number
  prevClose: number
  change: number
  changePercent: number
  isUp: boolean
  updatedAt: string
}

/** Yahoo Finance 심볼 매핑 */
const SYMBOLS = [
  { yahoo: '^KS11', label: 'KOSPI', key: 'KS11' },
  { yahoo: '^KQ11', label: 'KOSDAQ', key: 'KQ11' },
  { yahoo: 'USDKRW=X', label: 'USD/KRW', key: 'USDKRW' },
  { yahoo: '^IXIC', label: 'NASDAQ', key: 'IXIC' },
]

/** 개별 심볼 chart API 조회 (2일 범위로 전일 종가 정확히 확보) */
async function fetchChart(yahoo: string): Promise<{ value: number; prevClose: number } | null> {
  try {
    const url = `/api/yahoo/v8/finance/chart/${encodeURIComponent(yahoo)}?range=2d&interval=1d`
    const res = await fetch(url)
    if (!res.ok) return null
    const json = await res.json()
    const result = json.chart?.result?.[0]
    if (!result) return null

    const meta = result.meta
    const closes: number[] = result.indicators?.quote?.[0]?.close ?? []

    // regularMarketPrice = 실시간 현재가
    const value = meta?.regularMarketPrice ?? closes[closes.length - 1] ?? 0
    // closes[0] = 전일 종가 (2일 범위의 첫째 날)
    const prevClose = closes.length >= 2 ? closes[0] : (meta?.chartPreviousClose ?? value)

    return { value, prevClose }
  } catch {
    return null
  }
}

/** Yahoo Finance chart API — Vite 프록시 경유 (정확한 전일 종가 기반) */
export async function fetchAllMarketData(): Promise<MarketIndex[]> {
  const results = await Promise.all(
    SYMBOLS.map(async ({ yahoo, label, key }) => {
      const data = await fetchChart(yahoo)
      if (!data || data.value === 0) {
        return { label, symbol: key, value: 0, prevClose: 0, change: 0, changePercent: 0, isUp: true, updatedAt: '' }
      }

      const { value, prevClose } = data
      const change = value - prevClose
      const changePercent = prevClose > 0 ? (change / prevClose) * 100 : 0

      return {
        label,
        symbol: key,
        value,
        prevClose,
        change,
        changePercent,
        isUp: changePercent >= 0,
        updatedAt: new Date().toISOString(),
      }
    })
  )

  return results
}
