import { useState, useEffect, useCallback } from 'react'
import { fetchAllMarketData, type MarketIndex } from '../api/marketApi'

const POLL_INTERVAL = 5 * 60 * 1000 // 5분

/** 더미 폴백 데이터 (API 실패 시) */
const FALLBACK_DATA: MarketIndex[] = [
  { label: 'KOSPI', symbol: 'KS11', value: 2644.28, prevClose: 2671.0, change: -26.72, changePercent: -1.0, isUp: false, updatedAt: '' },
  { label: 'KOSDAQ', symbol: 'KQ11', value: 732.51, prevClose: 730.17, change: 2.34, changePercent: 0.32, isUp: true, updatedAt: '' },
  { label: 'USD/KRW', symbol: 'USDKRW', value: 1452.3, prevClose: 1450.12, change: 2.18, changePercent: 0.15, isUp: true, updatedAt: '' },
  { label: 'NASDAQ', symbol: 'IXIC', value: 19627, prevClose: 19461.62, change: 165.38, changePercent: 0.85, isUp: true, updatedAt: '' },
]

export function useMarketData() {
  const [data, setData] = useState<MarketIndex[]>(FALLBACK_DATA)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const refresh = useCallback(async () => {
    try {
      setLoading(true)
      setError(null)
      const result = await fetchAllMarketData()
      setData(result)
      setLastUpdated(new Date())
    } catch (err) {
      console.warn('[MarketData] API 호출 실패, 폴백 데이터 사용:', err)
      setError(err instanceof Error ? err.message : 'Unknown error')
      // 폴백 데이터 유지 (이전 데이터가 있으면 그대로)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
    const interval = setInterval(refresh, POLL_INTERVAL)
    return () => clearInterval(interval)
  }, [refresh])

  return { data, loading, error, lastUpdated, refresh }
}
