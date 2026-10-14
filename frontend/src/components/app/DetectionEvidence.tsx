import type { ConditionSignal } from '../../api/conditionApi'
import { formatConditionAsOf } from '../../lib/conditionPresentation'
import './DetectionEvidence.css'

const categories = [
  ['liquidity', '거래대금'], ['technical', '기술적 분석'], ['catalyst', '재료'],
  ['fundamental', '기본적 분석'], ['supply', '수급'],
] as const

export function DetectionEvidence({ signal, onAnalyze }: { signal: ConditionSignal; onAnalyze: () => void }) {
  const checks = signal.conditionChecks ?? []
  const passed = checks.filter(c => c.status === 'PASS').length
  const failed = checks.filter(c => c.status === 'FAIL').length
  return <section className="detection-evidence" aria-label={`${signal.stockName} 검출 근거`}>
    <header><strong>검출 근거 · {signal.stockName} ({signal.stockCode})</strong>
      <span>포착 {formatConditionAsOf(signal.capturedAt)} · 통과 {passed} · 미충족 {failed}</span></header>
    <p>아래는 저장된 관측값의 판정입니다. 후보 편입이 모든 조건의 통과를 의미하지 않습니다. 거래대금 재조회 시각과 최초 포착 시각은 다를 수 있습니다.</p>
    {categories.map(([key, title]) => {
      const items = checks.filter(c => c.category === key)
      return <div className="detection-category" key={key}>
        <h3>{title}</h3>
        {items.length ? items.map(item => <div className="detection-check" key={item.id}>
          <div><strong>{item.criterion}</strong><span className={`check-status check-${item.status}`}>
            {item.status === 'PASS' ? '통과' : item.status === 'FAIL' ? '미충족' : '미확인'}</span></div>
          <p>{item.observedValue || '관측값 없음'}</p>
          <small>출처: {item.source || '미확인'} · 기준 시각: {item.observedAt ? formatConditionAsOf(item.observedAt) : '미확인'}</small>
        </div>) : <div className="detection-check"><span className="check-status check-UNKNOWN">미확인</span><p>구조화된 조건·관측값이 없는 저장 결과입니다.</p></div>}
      </div>
    })}
    <details className="detection-commentary"><summary>저장된 분석·엔진 설명 (통과 판정과 구분)</summary>
      <p>{signal.summary || '저장된 설명 없음'}</p>
      <ul>{signal.evidence?.map((text, i) => <li key={i}>{text}</li>)}</ul>
      <p>이 설명에는 AI 해석과 조건 이름이 포함될 수 있습니다. 위의 관측값 없는 항목을 통과로 인정하지 않습니다.</p>
    </details>
    <div className="detection-risks"><strong>위험·무효화 조건</strong>
      <ul>{signal.riskFlags?.map((risk, i) => <li key={i}>{risk}</li>)}</ul>
      <p>{signal.invalidation || '저장된 무효화 조건 없음'}</p></div>
    <button type="button" className="section-endpoint-button" onClick={onAnalyze}>추가 AI 분석</button>
  </section>
}
