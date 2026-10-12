import type { ConditionSectionResponse } from '../api/conditionApi'

const AI_MODES = new Set(['BREAKOUT', 'REVERSAL_EDGE', 'FLOW_LEADER', 'CATALYST_BURST'])
const dateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
})
const timeFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

/** Dates remain explicit when a retained snapshot comes from another KST day. */
export function formatConditionAsOf(value?: string, now = new Date(Date.now())) {
  if (!value) return ''
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return ''
  const day = dateFormatter.format(date)
  const label = day === dateFormatter.format(now) ? '오늘' : day.replaceAll('-', '.')
  return `${label} ${timeFormatter.format(date)}`
}

export function latestAnalysisPresentation(section?: ConditionSectionResponse, now = new Date(Date.now())) {
  const latest = section?.latestAnalysis
  if (!section || !AI_MODES.has(section.mode) || !latest) return null
  // Scanner candidates are a separate result from the last scheduled AI attempt.
  if (section.sourceStatus === 'REALTIME_SCAN' || section.sourceStatus === 'LEADER_SCAN') return null
  const checkedAt = Date.parse(latest.checkedAt)
  if (!Number.isFinite(checkedAt) || checkedAt > now.getTime() + 60_000) return null
  const counts = [latest.agentsUsed, latest.agentsSucceeded, latest.publishedCount]
  if (counts.some(value => !Number.isSafeInteger(value) || value < 0)
    || latest.agentsSucceeded > latest.agentsUsed) return null
  const time = formatConditionAsOf(latest.checkedAt, now)
  const priorCandidates = section.signals?.length > 0
    && ['PRECOMPUTED', 'STALE_CACHE'].includes(section.sourceStatus)
    && Date.parse(section.asOf) < checkedAt
  const retained = priorCandidates ? ' 표에는 이전에 저장한 후보가 표시됩니다.' : ''
  switch (latest.outcome) {
    case 'EMPTY':
      if (latest.agentsSucceeded === 0 || latest.publishedCount !== 0) return null
      return {
        label: latest.agentsSucceeded < latest.agentsUsed ? '일부 AI 분석 완료' : '분석 완료',
        message: latest.agentsSucceeded < latest.agentsUsed
          ? `${time} 일부 AI 분석 완료 (${latest.agentsSucceeded}/${latest.agentsUsed}) · 현재 확인된 후보가 없습니다.${retained}`
          : `${time} 분석 완료 · 현재 조건을 통과한 종목이 없습니다.${retained}`,
      }
    case 'PARTIAL':
      if (latest.agentsSucceeded === 0) return null
      return { label: '일부 AI 분석 완료', message: `${time} 일부 AI 분석 완료 (${latest.agentsSucceeded}/${latest.agentsUsed}) · 확인된 결과만 표시합니다.` }
    case 'FAILED':
      return { label: '분석 확인 필요', message: `${time} 분석 결과를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.${retained}` }
    case 'COMPLETE':
      if (latest.agentsUsed === 0 || latest.agentsSucceeded !== latest.agentsUsed || latest.publishedCount === 0) return null
      return { label: '분석 완료', message: `${time} 분석 완료 · 조건을 통과한 후보 ${latest.publishedCount}개.` }
    default:
      return null
  }
}
