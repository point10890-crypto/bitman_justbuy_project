import { isSubscriptionExpired } from '../../lib/memberTier'
import { formatWon, getSubscriptionPlan } from '../../lib/subscriptionPlans'
import { subscriptionUpgradeEndDate } from '../../lib/subscriptionUpgrade'
import './SubscriptionUpgradeDetails.css'

export function SubscriptionUpgradeDetails({ endDate, pending = false }: { endDate?: string; pending?: boolean }) {
  const plan = getSubscriptionPlan('SIX_MONTHS')
  const expectedEnd = subscriptionUpgradeEndDate(endDate)
  const expired = isSubscriptionExpired(endDate)
  return (
    <section className="subscription-upgrade-details" aria-label="6개월 업그레이드 안내">
      <h2>{pending ? '6개월 업그레이드 승인 대기' : '1개월 → 6개월 업그레이드'}</h2>
      <dl>
        <div><dt>기존 이용권</dt><dd>1개월 이용권</dd></div>
        <div><dt>업그레이드 금액</dt><dd>{formatWon(plan.price)} · 일시 납부</dd></div>
        <div><dt>현재 이용권 만료일</dt><dd>{endDate || '-'}</dd></div>
        {expectedEnd && <div><dt>오늘 승인 시 예상 만료일</dt><dd>{expectedEnd}</dd></div>}
      </dl>
      <p>{expired
        ? '기존 이용 기간이 종료되었습니다. 입금 확인 후 승인된 날부터 6개월 이용할 수 있습니다.'
        : '남은 이용 기간은 그대로 유지되며, 승인 시 기존 만료일에 6개월이 더해집니다.'}</p>
      <small>승인이 현재 만료일 이후에 이루어지면 승인일부터 6개월이 적용됩니다.</small>
    </section>
  )
}
