import { getSubscriptionPlan } from '../../lib/subscriptionPlans'
import type { SubscriptionPlanId } from '../../api/authApi'
import './SubscriptionExpiredNotice.css'

export function SubscriptionExpiredNotice({ plan, endDate }: { plan?: SubscriptionPlanId; endDate?: string }) {
  const previousPlan = getSubscriptionPlan(plan)
  return (
    <section className="subscription-expired-notice" aria-label="이전 이용권 정보">
      <strong>이용권이 만료되었습니다</strong>
      <dl>
        <div><dt>이전 플랜</dt><dd>{previousPlan.label} 이용권</dd></div>
        {endDate && <div><dt>이전 만료일</dt><dd>{endDate.slice(0, 10)}</dd></div>}
      </dl>
      <p>기존 계정으로 재구독할 수 있습니다. 입금 확인 후 승인된 날부터 새 이용 기간이 시작됩니다.</p>
    </section>
  )
}
