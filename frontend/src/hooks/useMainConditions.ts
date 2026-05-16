import { useCallback, useEffect, useState } from 'react'
import { fetchMainConditions, type MainConditionResponse } from '../api/conditionApi'
import { getStoredToken } from '../contexts/AuthContext'

export function useMainConditions() {
  const [data, setData] = useState<MainConditionResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const refetch = useCallback(async () => {
    const token = getStoredToken() || undefined
    try {
      setLoading(true)
      setError(null)
      setData(await fetchMainConditions(token))
    } catch (err) {
      setError(err instanceof Error ? err.message : '조건검색 결과를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refetch()
  }, [refetch])

  return { data, loading, error, refetch }
}
