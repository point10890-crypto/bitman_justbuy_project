import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchMainConditions, type MainConditionResponse } from '../api/conditionApi'
import { getStoredToken } from '../contexts/AuthContext'

export function useMainConditions(pollMs = 0) {
  const [data, setData] = useState<MainConditionResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const generation = useRef(0)

  const refetch = useCallback(async () => {
    const request = ++generation.current
    const token = getStoredToken() || undefined
    try {
      setLoading(true)
      setError(null)
      const latest = await fetchMainConditions(token)
      if (request === generation.current) setData(latest)
    } catch (err) {
      if (request === generation.current) setError(err instanceof Error ? err.message : '조건검색 결과를 불러오지 못했습니다.')
    } finally {
      if (request === generation.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    refetch()
    return () => { generation.current++ }
  }, [refetch])

  useEffect(() => {
    if (!pollMs) return
    const timer = window.setInterval(() => {
      refetch()
    }, pollMs)
    return () => window.clearInterval(timer)
  }, [pollMs, refetch])

  return { data, loading, error, refetch }
}
