import { useState, useCallback } from 'react'
import { fetchPrecomputed, fetchLiveAnalysis, type AnalysisResponse } from '../api/analysisApi'
import { addHistory } from '../lib/analysisHistory'
import { getCached, setCache } from '../lib/analysisCache'
import { getStoredToken } from '../contexts/AuthContext'

export interface AnalysisResult extends AnalysisResponse {
  isPrecomputed: boolean
}

export function useAnalysis() {
  const [result, setResult] = useState<AnalysisResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const analyze = useCallback(async (query: string, mode?: string) => {
    if (!query.trim()) return
    try {
      setLoading(true)
      setError(null)
      setResult(null)

      const effectiveMode = mode || '분석해줘'
      const token = getStoredToken() || undefined

      // 프리컴퓨트 모드(오늘뭐사, 수급분석, 종가매매, 스윙매매)는 항상 서버 최신 데이터 확인
      if (mode && mode !== '분석해줘') {
        const cached = getCached(query, effectiveMode)
        const precomputed = await fetchPrecomputed(mode, token)

        if (precomputed && precomputed.metadata.agentsSucceeded > 0) {
          // 서버 데이터가 캐시보다 최신이면 갱신, 동일하면 캐시 사용
          const serverTime = new Date(precomputed.updatedAt).getTime()
          const cachedTime = cached ? new Date(cached.updatedAt).getTime() : 0
          if (serverTime > cachedTime || !cached) {
            setResult({ ...precomputed, isPrecomputed: true })
            setCache(query, effectiveMode, precomputed)
            addHistory(query, mode, precomputed.content)
          } else {
            setResult({ ...cached, isPrecomputed: true })
          }
          return
        }
        // 서버 응답 실패 시 캐시 폴백
        if (cached && cached.metadata.agentsSucceeded > 0) {
          setResult({ ...cached, isPrecomputed: true })
          return
        }
        // 프리컴퓨트 없음 → 라이브 분석으로 폴스루
      } else {
        // 라이브 분석 모드: 캐시 우선
        const cached = getCached(query, effectiveMode)
        if (cached && cached.metadata.agentsSucceeded > 0) {
          setResult({ ...cached, isPrecomputed: false })
          return
        }
      }

      // 라이브 멀티에이전트 분석
      const res = await fetchLiveAnalysis(query, effectiveMode, token)
      setResult({ ...res, isPrecomputed: false })
      setCache(query, effectiveMode, res)
      addHistory(query, mode, res.content)
    } catch (err) {
      const msg = err instanceof Error ? err.message : '분석 중 오류 발생'
      if (msg.includes('403') || msg.includes('PRO') || msg.includes('구독자만')) {
        setError('PRO 구독자만 사용 가능한 기능입니다.')
      } else if (msg.includes('credit balance') || msg.includes('크레딧')) {
        setError('API 크레딧이 부족합니다. 콘솔에서 크레딧을 충전해 주세요.')
      } else if (msg.includes('401') || msg.includes('인증')) {
        setError('API 인증에 실패했습니다. API 키를 확인해 주세요.')
      } else if (msg.includes('429') || msg.includes('진행 중')) {
        setError('요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.')
      } else if (msg.includes('500') || msg.includes('503')) {
        setError('서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
      } else {
        setError(msg)
      }
    } finally {
      setLoading(false)
    }
  }, [])

  const clear = useCallback(() => {
    setResult(null)
    setError(null)
  }, [])

  return { result, loading, error, analyze, clear }
}
