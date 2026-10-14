import { useEffect, useState } from 'react'
import {
  engineApi,
  type BacktestResult, type RiskCheckItem, type ScreenResult, type Translation,
} from '../api/engineApi'
import { searchKeywordStocks, type KeywordSearchMatch, type KeywordSearchResponse } from '../api/keywordSearchApi'
import { getStoredToken } from '../contexts/AuthContext'

/**
 * 종목·조건 검색 업그레이드 — 실데이터 검색과 AI 조건검색 엔진을 한 검색으로 합친다.
 *
 * 검색어가 들어오면 실데이터 검색(HomePage)과 엔진 번역을 동시에 시작하고, 둘 다 도착하면 하나를 고른다.
 *
 * - keyword: 실데이터 검색이 질문을 해석했다. 그 결과를 그대로 쓰고, 종목마다 엔진의 위험 등급·변동 범위·
 *   일봉 근거를 붙인다. 엔진도 같은 조건을 이해했다면 그 조건의 과거 성과를 함께 보여준다.
 * - engine: 실데이터 검색이 해석하지 못했거나(이동평균·눌림·저변동 등) 종목명 검색으로 흘렀거나
 *   시세 조회에 실패했다. 엔진이 일봉 조건으로 찾는다. 질문에 수급 조건이 있으면 그 부분만 실데이터로
 *   다시 검색해 두 조건을 모두 만족하는 종목만 남긴다.
 */
export interface CombinedMatch {
  code: string
  live: KeywordSearchMatch | null
  daily: RiskCheckItem | null
}

export type SearchUpgrade =
  | { status: 'idle' }
  | { status: 'working' }
  | { status: 'failed'; message: string }
  | {
      status: 'done'
      mode: 'keyword'
      translation: Translation | null
      risk: Record<string, RiskCheckItem>
      riskAsOf: string | null
      backtest: BacktestResult | null
    }
  | {
      status: 'done'
      mode: 'engine'
      translation: Translation
      screen: ScreenResult
      backtest: BacktestResult | null
      realtime: { query: string; result: KeywordSearchResponse | null; error: string | null } | null
      /** 수급 조건이 섞였을 때: 실데이터와 일봉 조건을 모두 만족한 종목. 없으면 null. */
      combined: CombinedMatch[] | null
      /** 수급 조건은 맞지만 일봉 위험 신호로 뺀 종목. */
      combinedExcluded: KeywordSearchMatch[]
    }

const message = (e: unknown) => (e instanceof Error ? e.message : 'AI 조건검색을 처리하지 못했습니다.')

/** 실데이터 검색이 조건을 못 읽고 남은 단어를 종목명으로 찾았는지. */
export function fellBackToNameSearch(result: KeywordSearchResponse | null) {
  return !!result && result.appliedConditions.some(c => c.startsWith('종목명에'))
}

export function engineShouldLead(result: KeywordSearchResponse | null, error: string | null, translation: Translation | null) {
  if (!translation?.strategy) return false
  if (!result) return !!error
  return result.outcome === 'UNSUPPORTED' || result.outcome === 'UNAVAILABLE' || fellBackToNameSearch(result)
}

export function useSearchUpgrade(
  query: string,
  keyword: { result: KeywordSearchResponse | null; loading: boolean; error: string | null },
): SearchUpgrade {
  const [translation, setTranslation] = useState<{ query: string; value: Translation | null; error: string | null } | null>(null)
  const [state, setState] = useState<SearchUpgrade>({ status: 'idle' })

  // 1) 검색어가 정해지는 즉시 번역을 시작한다 — 실데이터 검색과 병렬.
  useEffect(() => {
    setTranslation(null)
    setState(query ? { status: 'working' } : { status: 'idle' })
    if (!query) return
    const ctl = new AbortController()
    engineApi.translate(query, ctl.signal).then(
      value => { if (!ctl.signal.aborted) setTranslation({ query, value, error: null }) },
      e => { if (!ctl.signal.aborted) setTranslation({ query, value: null, error: message(e) }) },
    )
    return () => ctl.abort()
  }, [query])

  // 2) 실데이터 결과와 번역이 모두 오면 어느 쪽이 이끌지 정하고 보강한다.
  const { result, loading, error } = keyword
  useEffect(() => {
    if (!query || loading || !translation || translation.query !== query) return
    if (!result && !error) return
    const ctl = new AbortController()
    const t = translation.value
    const done = (next: SearchUpgrade) => { if (!ctl.signal.aborted) setState(next) }

    ;(async () => {
      try {
        if (t && engineShouldLead(result, error, t)) {
          const strategy = t.strategy!
          const [screen, backtest] = await Promise.all([
            engineApi.screen({ strategy }, ctl.signal),
            engineApi.backtest({ strategy }).catch(() => null),
          ])
          let realtime: { query: string; result: KeywordSearchResponse | null; error: string | null } | null = null
          let combined: CombinedMatch[] | null = null
          let combinedExcluded: KeywordSearchMatch[] = []
          if (t.realtime_query) {
            try {
              const live = await searchKeywordStocks(t.realtime_query, getStoredToken() || undefined, ctl.signal)
              realtime = { query: t.realtime_query, result: live, error: null }
              const passing = new Set([...screen.results.map(r => r.code), ...screen.below_top_n])
              const excluded = new Set(screen.excluded_codes ?? [])
              const both = live.matches.filter(m => passing.has(m.code))
              combinedExcluded = live.matches.filter(m => excluded.has(m.code))
              const daily = both.length
                ? (await engineApi.risk(both.slice(0, 50).map(m => m.code))).items
                : []
              const byCode = Object.fromEntries(daily.map(d => [d.code, d]))
              combined = both.slice(0, 50).map(m => ({ code: m.code, live: m, daily: byCode[m.code] ?? null }))
            } catch (e) {
              if (ctl.signal.aborted) return
              realtime = { query: t.realtime_query, result: null, error: message(e) }
            }
          }
          done({ status: 'done', mode: 'engine', translation: t, screen, backtest, realtime, combined, combinedExcluded })
          return
        }

        // 실데이터 검색이 이끈다 — 종목마다 일봉 위험·근거를 붙인다.
        const codes = (result?.matches ?? []).slice(0, 50).map(m => m.code)
        const [riskRes, backtest] = await Promise.all([
          codes.length ? engineApi.risk(codes).catch(() => null) : Promise.resolve(null),
          t?.strategy && result && result.outcome !== 'UNSUPPORTED'
            ? engineApi.backtest({ strategy: t.strategy }).catch(() => null)
            : Promise.resolve(null),
        ])
        const risk = Object.fromEntries((riskRes?.items ?? []).filter(i => i.found).map(i => [i.code, i]))
        done({ status: 'done', mode: 'keyword', translation: t, risk, riskAsOf: riskRes?.as_of ?? null, backtest })
      } catch (e) {
        if (!ctl.signal.aborted) done({ status: 'failed', message: message(e) })
      }
    })()
    return () => ctl.abort()
  }, [query, result, loading, error, translation])

  // 번역 자체가 실패하면 실데이터 결과만 보여준다(엔진 장애가 검색을 막지 않는다).
  if (translation && translation.query === query && translation.error && state.status === 'working' && !loading) {
    return { status: 'failed', message: translation.error }
  }
  return state
}
