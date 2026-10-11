# Monthly to six-month subscription upgrade

User request: “1개월 구독자가 6개월 이용권 업그레이드 할 수 있는 워크플로우도 신설해”.

## Workflow and pricing

- An active finite monthly member sees “6개월 이용권으로 업그레이드” on My Page.
- The CTA opens `/subscribe?plan=SIX_MONTHS&renewal=1`. The selected plan survives login, reauthentication and page reload using the existing route helpers.
- Existing SIX_MONTHS pricing applies: 150,000 KRW as a single bank transfer. This request does not introduce a price difference/proration rule.
- The saved depositor is prefilled. Current expiry, amount, account and the expected expiry if approved today are shown before submission.
- Application uses the existing subscription API. It retains the existing monthly period while awaiting approval and hides duplicate upgrade CTAs.
- Approval adds six calendar months to the later of the existing expiry and the approval date. Preview dates follow the same month-end clamping as Spring `LocalDate.plusMonths` and use KST dates.
- A pending upgrade requested during the monthly period retains its identity if that period expires while waiting. Its old paid access ends at the original expiry; approval then starts the new six-month period from the approval date.
- Admin approval lists show the upgrade label, amount, old expiry and expected new expiry.
- New members, expired members without an existing upgrade request, pending members, six-month members, administrators and unlimited accounts retain their existing flows.

## Scope and verification

- No backend production changes, plan-price changes or database migrations are required. Existing approval/rejection/duplicate-request tests cover the reused API behavior.
- No production account, subscription request, bank payment, approval or member data is mutated during QA.
- Frontend tests cover member and admin DOM flows, paid-route access before/after expiry, explicit plan switching and calendar dates.
- Preserve the unrelated dirty files present before this task.
- Real-browser authenticated UI proof is limited by the currently available browser tools. Component DOM tests and deployed HTTP checks must be reported separately from an actual member browser check.

## Local validation

- Frontend: 113 tests passed across 11 files, including the real AuthProvider cache restore, paid-route guards, member and admin workflows.
- Backend: 261 tests passed with no failures or errors, including the existing monthly-to-six-month approval behavior.
- `npm run build` and `git diff --check` passed. Production assets: `index-DhxbvmW4.js` and `index-CCQIA_Bg.css`.
- Service worker cache: `bitman-v20261011-subscription-upgrade-r4`.

Deployment results are recorded below after verification.
