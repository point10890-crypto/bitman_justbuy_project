import { useEffect, useState } from 'react'
import {
  engineApi, isCompleteTranslation,
  type BacktestResult, type DailyConditionCheck, type RiskCheckItem, type ScreenResult, type Strategy, type Translation,
} from '../api/engineApi'
import {
  getFlowCoverage, getSearchProgress, searchKeywordProgress, searchKeywordStocks,
  type KeywordSearchMatch, type KeywordSearchResponse,
} from '../api/keywordSearchApi'
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
  conditionChecks?: DailyConditionCheck[]
}

export interface TranslationIssue {
  message: string
  translation: Translation | null
}

export type SearchUpgrade =
  | { status: 'idle' }
  | { status: 'working' }
  | { status: 'failed'; message: string }
  | { status: 'blocked'; issue: TranslationIssue }
  | {
      status: 'done'
      mode: 'keyword'
      translation: Translation | null
      translationIssue?: TranslationIssue | null
      risk: Record<string, RiskCheckItem>
      riskAsOf: string | null
      backtest: BacktestResult | null
      updatedResult?: KeywordSearchResponse
      trackingError?: string | null
      trackingExhausted?: boolean
    }
  | {
      status: 'done'
      mode: 'engine'
      translation: Translation
      screen: ScreenResult
      backtest: BacktestResult | null
      realtime: {
        query: string; result: KeywordSearchResponse | null; error: string | null
        trackingError?: string | null; trackingExhausted?: boolean
      } | null
      /** 수급 조건이 섞였을 때: 실데이터와 일봉 조건을 모두 만족한 종목. 없으면 null. */
      combined: CombinedMatch[] | null
      /** 수급 조건은 맞지만 일봉 위험 신호로 뺀 종목. */
      combinedExcluded: KeywordSearchMatch[]
      /** 교집합은 확인했지만 별도 위험 정보 보강이 실패한 경우. */
      riskError: string | null
    }

const message = (e: unknown) => (e instanceof Error ? e.message : 'AI 조건검색을 처리하지 못했습니다.')

function waitForProgress(delay: number, signal: AbortSignal): Promise<boolean> {
  if (signal.aborted) return Promise.resolve(false)
  return new Promise(resolve => {
    const onAbort = () => { clearTimeout(timer); resolve(false) }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve(true)
    }, delay)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

/** 실데이터 검색이 조건을 못 읽고 남은 단어를 종목명으로 찾았는지. */
export function fellBackToNameSearch(result: KeywordSearchResponse | null) {
  return !!result && result.appliedConditions.some(c => c.startsWith('종목명에'))
}

function isExactNameLookup(result: KeywordSearchResponse | null, query: string) {
  if (!result || result.unsupportedTerms.length) return false
  const normalize = (input: string) => input.trim().replace(/\s+/g, ' ').toLowerCase()
  const conditions = result.appliedConditions.filter(condition => !/^결과 최대 \d+종목$/.test(condition))
  return conditions.length === 1 && normalize(conditions[0]) === `종목명에 ${normalize(query)} 포함`
}

export function canVerifyKeywordConditions(result: KeywordSearchResponse | null) {
  return !!result && (result.outcome === 'COMPLETE' || result.outcome === 'PARTIAL' || result.outcome === 'EMPTY')
    && result.unsupportedTerms.length === 0 && !fellBackToNameSearch(result)
}

function getTranslationIssue(value: Translation | null, error: string | null): TranslationIssue | null {
  if (!error && isCompleteTranslation(value)) return null
  if (error) return { message: error, translation: null }
  if (!value) return { message: '일봉 조건 해석을 받지 못했습니다.', translation: null }
  if (value.unsupported.length || value.unparsed.length) {
    return { message: '확인할 수 있는 지표와 기간으로 조건을 다시 입력해 주세요.', translation: value }
  }
  return { message: value.error || '일봉 조건으로 해석할 수 있는 표현을 입력해 주세요.', translation: value }
}

/** An explicit result count is deterministic; only the remaining conditions need AI translation. */
function applyRequestedCount(query: string, value: Translation): Translation {
  if (!value?.strategy) return value
  const counts = [...new Set([...query.matchAll(/(?<![\d.,+\-])(\d+)\s*(?:개(?!\s*(?:거래일|영업일))|종목)(?![가-힣])/g)].map(match => Number(match[1])))]
  if (counts.length !== 1 || !Number.isSafeInteger(counts[0])) return value
  const requested = counts[0]
  const topN = Math.max(1, Math.min(50, requested))
  const countAssumption = /top_n|(?:결과|종목)\s*(?:개수|최대)|(?:결과|종목)\s*수(?:\s|[=:은는가를]|$)|(?:상위|최대|기본)\s*\d+\s*(?:개|종목)/i
  const assumptions = value.assumptions.filter(note => !countAssumption.test(note))
  const changed = value.strategy.top_n !== topN
  if (!changed && assumptions.length === value.assumptions.length) return value
  const strategy = { ...value.strategy, top_n: topN }
  if (changed) delete strategy.id // Let the engine regenerate the ID from the corrected DSL.
  assumptions.push(requested === topN
    ? `요청한 결과 수를 적용해 최대 ${topN}종목을 표시합니다.`
    : `요청한 ${requested}종목은 엔진 개수 제한에 따라 최대 ${topN}종목으로 적용합니다.`)
  return { ...value, strategy, assumptions }
}

export function useSearchUpgrade(
  query: string,
  keyword: { result: KeywordSearchResponse | null; loading: boolean; error: string | null },
  requestId = 0,
): SearchUpgrade {
  const requestKey = `${requestId}:${query}`
  const [translation, setTranslation] = useState<{ requestKey: string; value: Translation | null; error: string | null } | null>(null)
  const [state, setState] = useState<{ requestKey: string; result: SearchUpgrade } | null>(null)

  // 1) 검색어가 정해지는 즉시 번역을 시작한다 — 실데이터 검색과 병렬.
  useEffect(() => {
    if (!query) return
    const ctl = new AbortController()
    engineApi.translate(query, ctl.signal).then(
      value => { if (!ctl.signal.aborted) setTranslation({ requestKey, value: applyRequestedCount(query, value), error: null }) },
      e => { if (!ctl.signal.aborted) setTranslation({ requestKey, value: null, error: message(e) }) },
    )
    return () => ctl.abort()
  }, [query, requestKey])

  // 2) 실데이터 결과와 번역이 모두 오면 어느 쪽이 이끌지 정하고 보강한다.
  const { result, loading, error } = keyword
  useEffect(() => {
    if (!query || loading || !translation || translation.requestKey !== requestKey) return
    if (!result && !error) return
    const ctl = new AbortController()
    const plainNameLookup = isExactNameLookup(result, query)
    const t = plainNameLookup ? null : translation.value
    const done = (next: SearchUpgrade) => { if (!ctl.signal.aborted) setState({ requestKey, result: next }) }
    const keywordVerified = canVerifyKeywordConditions(result)
    const translationIssue = plainNameLookup ? null : getTranslationIssue(t, translation.error)
    if (!keywordVerified && translationIssue) {
      done({ status: 'blocked', issue: translationIssue })
      return
    }
    // Historical performance is optional enrichment, never a prerequisite for search results.
    let backtest: BacktestResult | null = null
    const startBacktest = (strategy: Strategy) => {
      void engineApi.backtest({ strategy }, ctl.signal).then(value => {
        if (ctl.signal.aborted) return
        backtest = value
        setState(current => current?.requestKey === requestKey && current.result.status === 'done'
          ? { requestKey, result: { ...current.result, backtest: value } }
          : current)
      }).catch(() => { /* Keep the verified search results if historical analysis fails. */ })
    }

    // Progress observes existing work only. Translation, screen and backtest stay fixed for this search.
    const track = async (
      initial: KeywordSearchResponse,
      accept: (next: KeywordSearchResponse) => Promise<void>,
      fail: (error: string) => void,
      exhaust: () => void,
    ) => {
      const originalProgress = getSearchProgress(initial)
      if (!originalProgress) return
      let latest = initial
      for (let attempt = 0; attempt < 3; attempt++) {
        const coverage = getFlowCoverage(latest)
        const progress = getSearchProgress(latest)
        if (!coverage?.pendingCount || !progress || ctl.signal.aborted) return
        const expiresAt = Math.min(Date.parse(originalProgress.expiresAt), Date.parse(progress.expiresAt))
        if (Date.now() >= expiresAt) { exhaust(); return }
        const delay = Math.max(10_000, Math.min(30_000, progress.retryAfterMs), (attempt + 1) * 10_000)
        if (!await waitForProgress(delay, ctl.signal)) return
        if (Date.now() >= expiresAt) { exhaust(); return }
        try {
          const next = await searchKeywordProgress(originalProgress.id, getStoredToken() || undefined, ctl.signal)
          if (ctl.signal.aborted) return
          const nextCoverage = getFlowCoverage(next)
          const flowFinished = nextCoverage !== null && nextCoverage.pendingCount === 0
          const terminalUnavailable = next.outcome === 'UNAVAILABLE' && flowFinished && next.unsupportedTerms.length === 0 && !fellBackToNameSearch(next)
          if (!canVerifyKeywordConditions(next) && !terminalUnavailable) {
            fail('추가 수급 조회에서 요청한 조건의 근거를 확인하지 못했습니다.')
            return
          }
          await accept(next)
          latest = next
        } catch (e) {
          if (!ctl.signal.aborted) fail(message(e))
          return
        }
      }
      if (getFlowCoverage(latest)?.pendingCount && getSearchProgress(latest) && !ctl.signal.aborted) exhaust()
    }

    ;(async () => {
      try {
        if (!keywordVerified && t?.strategy) {
          const strategy = t.strategy
          const screen = await engineApi.screen({ strategy }, ctl.signal)
          let realtime: Extract<SearchUpgrade, { mode: 'engine' }>['realtime'] = null
          let combined: CombinedMatch[] | null = null
          let combinedExcluded: KeywordSearchMatch[] = []
          let riskError: string | null = null
          const passing = [...new Set([...screen.results.map(r => r.code), ...screen.below_top_n])]
          const excluded = new Set(Object.keys(screen.excluded_codes ?? {}))
          const screenRows = new Map(screen.results.map(row => [row.code, row]))
          const topN = Math.max(1, Math.min(50, screen.strategy.top_n))
          let dailyByCode: Record<string, RiskCheckItem> = {}
          const publish = () => done({ status: 'done', mode: 'engine', translation: t, screen, backtest, realtime, combined, combinedExcluded, riskError })
          const combine = async (live: KeywordSearchResponse) => {
            const liveByCode = new Map(live.matches.map(m => [m.code, m]))
            const both = passing.filter(code => liveByCode.has(code)).slice(0, topN).map(code => liveByCode.get(code)!)
            combinedExcluded = live.matches.filter(m => excluded.has(m.code))
            const matched = () => both.map(m => ({ code: m.code, live: m, daily: dailyByCode[m.code] ?? null, conditionChecks: screenRows.get(m.code)?.condition_checks }))
            combined = matched()
            riskError = null
            publish()
            const missing = both.map(m => m.code).filter(code => !dailyByCode[code])
            if (missing.length) {
              try {
                const daily = (await engineApi.risk(missing, screen.as_of, ctl.signal)).items
                if (ctl.signal.aborted) return
                dailyByCode = { ...dailyByCode, ...Object.fromEntries(daily.map(d => [d.code, d])) }
                combined = matched()
              } catch {
                riskError = '일봉 위험 정보 보강을 확인하지 못했습니다. 확인된 수급·일봉 조건 일치 결과는 유지합니다.'
              }
            }
          }
          if (t.realtime_query) {
            try {
              const live = await searchKeywordStocks(t.realtime_query, getStoredToken() || undefined, ctl.signal)
              realtime = { query: t.realtime_query, result: live, error: null }
              if (!canVerifyKeywordConditions(live)) {
                realtime.error = live.outcome === 'UNAVAILABLE' ? '실시간 수급을 조회하지 못했습니다.' : '요청한 수급 조건을 확인하지 못했습니다.'
              } else {
                startBacktest(screen.strategy)
                await combine(live)
              }
            } catch (e) {
              if (ctl.signal.aborted) return
              realtime = { query: t.realtime_query, result: null, error: message(e) }
            }
          } else startBacktest(screen.strategy)
          publish()
          if (realtime?.result && !realtime.error) {
            await track(realtime.result, async next => {
              realtime = { ...realtime!, result: next }
              await combine(next)
              publish()
            }, error => {
              realtime = { ...realtime!, trackingError: error }
              publish()
            }, () => {
              realtime = { ...realtime!, trackingExhausted: true }
              publish()
            })
          }
          return
        }

        // 실데이터 검색이 이끈다 — 종목마다 일봉 위험·근거를 붙인다.
        const codes = (result?.matches ?? []).slice(0, 50).map(m => m.code)
        let risk: Record<string, RiskCheckItem> = {}
        let riskAsOf: string | null = null
        let updatedResult: KeywordSearchResponse | undefined
        let trackingError: string | null = null
        let trackingExhausted = false
        const publish = () => done({ status: 'done', mode: 'keyword', translation: t, translationIssue, risk, riskAsOf, backtest, updatedResult, trackingError, trackingExhausted })
        if (isCompleteTranslation(t)) startBacktest(t.strategy)
        publish()
        if (codes.length) {
          const riskRes = await engineApi.risk(codes, undefined, ctl.signal).catch(() => null)
          if (ctl.signal.aborted) return
          risk = Object.fromEntries((riskRes?.items ?? []).filter(i => i.found).map(i => [i.code, i]))
          riskAsOf = riskRes?.as_of ?? null
          publish()
        }
        if (result && keywordVerified) {
          await track(result, async next => {
            const missing = next.matches.slice(0, 50).map(m => m.code).filter(code => !risk[code])
            if (missing.length) {
              const extraRisk = await engineApi.risk(missing, riskAsOf ?? undefined, ctl.signal).catch(() => null)
              if (ctl.signal.aborted) return
              risk = { ...risk, ...Object.fromEntries((extraRisk?.items ?? []).filter(i => i.found).map(i => [i.code, i])) }
              riskAsOf = riskAsOf ?? extraRisk?.as_of ?? null
            }
            updatedResult = next
            publish()
          }, error => { trackingError = error; publish() }, () => { trackingExhausted = true; publish() })
        }
      } catch (e) {
        if (!ctl.signal.aborted) done({ status: 'failed', message: message(e) })
      }
    })()
    return () => ctl.abort()
  }, [query, requestKey, result, loading, error, translation])

  if (!query) return { status: 'idle' }
  return state?.requestKey === requestKey ? state.result : { status: 'working' }
}
