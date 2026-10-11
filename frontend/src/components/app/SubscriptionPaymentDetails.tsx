import { useEffect, useRef, useState } from 'react'
import type { SubscriptionPlanId } from '../../api/authApi'
import { SUBSCRIPTION_BANK_ACCOUNT } from '../../lib/subscriptionPayment'
import { formatWon, getSubscriptionPlan } from '../../lib/subscriptionPlans'
import './SubscriptionPaymentDetails.css'

export function SubscriptionPaymentDetails({ plan, depositorName }: {
  plan?: SubscriptionPlanId
  depositorName?: string
}) {
  const requested = getSubscriptionPlan(plan)
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState('')
  const copyTimeout = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => () => {
    if (copyTimeout.current) clearTimeout(copyTimeout.current)
  }, [])

  const copyAccount = async () => {
    setCopyError('')
    setCopied(false)
    try {
      await navigator.clipboard.writeText(SUBSCRIPTION_BANK_ACCOUNT.accountNumber)
      setCopied(true)
      if (copyTimeout.current) clearTimeout(copyTimeout.current)
      copyTimeout.current = setTimeout(() => setCopied(false), 1600)
    } catch {
      setCopyError('복사하지 못했습니다. 위 계좌번호를 직접 확인해 주세요.')
    }
  }

  return (
    <section className="subscription-payment-details" aria-label="신청 및 입금 정보">
      <h2>신청 및 입금 정보</h2>
      <dl>
        <div><dt>신청 플랜</dt><dd>{requested.label} 이용권</dd></div>
        <div className="subscription-payment-total"><dt>입금 금액</dt><dd>{formatWon(requested.price)}</dd></div>
        <div><dt>은행</dt><dd>{SUBSCRIPTION_BANK_ACCOUNT.bankName}</dd></div>
        <div><dt>계좌번호</dt><dd className="subscription-payment-account">{SUBSCRIPTION_BANK_ACCOUNT.accountNumber}</dd></div>
        <div><dt>예금주</dt><dd>{SUBSCRIPTION_BANK_ACCOUNT.accountHolder}</dd></div>
        <div><dt>입금자명</dt><dd>{depositorName || '-'}</dd></div>
      </dl>
      <button type="button" className="subscription-payment-copy" onClick={copyAccount}>계좌번호 복사</button>
      <span className="subscription-payment-copy-status" role="status">{copied ? '계좌번호를 복사했습니다.' : ''}</span>
      {copyError && <p className="subscription-payment-error" role="alert">{copyError}</p>}
      <p className="subscription-payment-guidance">아직 입금하지 않았다면 위 금액을 입금해 주세요. 이미 입금했다면 추가 입금 없이 관리자 승인을 기다려 주세요.</p>
    </section>
  )
}
