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

  const analyze = useCallback(async (query: string, mode?: string): Promise<AnalysisResult | null> => {
    if (!query.trim()) return null
    try {
      setLoading(true)
      setError(null)
      setResult(null)

      const effectiveMode = mode || '분석해줘'
      const token = getStoredToken() || undefined

      // 프리컴퓨트 모드: 서버 캐시 먼저 확인 → 없으면 라이브 폴백
      const PRECOMPUTED_MODES = new Set(['BREAKOUT', 'FLOW_LEADER', 'CATALYST_BURST', 'REVERSAL_EDGE'])

      if (mode && PRECOMPUTED_MODES.has(mode)) {
        // 컨셉 모드 — 프리컴퓨트 우선, 없으면 라이브 폴백
        try {
          const precomputed = await fetchPrecomputed(mode, token)
          if (precomputed && precomputed.metadata.agentsSucceeded > 0) {
            const cached = getCached(query, effectiveMode)
            const serverTime = new Date(precomputed.updatedAt).getTime()
            const cachedTime = cached ? new Date(cached.updatedAt).getTime() : 0
            if (serverTime > cachedTime || !cached) {
              const nextResult = { ...precomputed, isPrecomputed: true }
              setResult(nextResult)
              setCache(query, effectiveMode, precomputed)
              addHistory(query, mode, precomputed.content)
              return nextResult
            } else {
              const nextResult = { ...cached, isPrecomputed: true }
              setResult(nextResult)
              return nextResult
            }
          }
        } catch {
          // 프리컴퓨트 실패 → 라이브 폴백
        }
      } else {
        // 라이브 모드 (분석해줘, 수급분석): 클라이언트 캐시 우선
        const cached = getCached(query, effectiveMode)
        if (cached && cached.metadata.agentsSucceeded > 0) {
          const nextResult = { ...cached, isPrecomputed: false }
          setResult(nextResult)
          return nextResult
        }
      }

      // 라이브 멀티에이전트 분석
      const res = await fetchLiveAnalysis(query, effectiveMode, token)
      const nextResult = { ...res, isPrecomputed: false }
      setResult(nextResult)
      setCache(query, effectiveMode, res)
      addHistory(query, mode, res.content)
      return nextResult
    } catch (err) {
      const msg = (err instanceof Error ? err.message : '분석 중 오류 발생') || '분석 중 오류 발생'
      if (msg.includes('403') || msg.includes('PRO') || msg.includes('구독자만')) {
        setError('PRO 구독자만 사용 가능한 기능입니다.')
      } else if (msg.includes('credit balance') || msg.includes('크레딧')) {
        setError('API 크레딧이 부족합니다. 콘솔에서 크레딧을 충전해 주세요.')
      } else if (msg.includes('401') || msg.includes('인증')) {
        setError('로그인이 만료되었습니다. 다시 로그인해 주세요.')
      } else if (msg.includes('429') || msg.includes('진행 중')) {
        setError('요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.')
      } else if (msg.includes('500') || msg.includes('503') || msg.includes('서버 오류')) {
        setError('서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
      } else {
        setError(msg)
      }
      return null
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
