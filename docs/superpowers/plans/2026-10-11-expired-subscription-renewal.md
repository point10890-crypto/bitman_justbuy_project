# Expired subscription renewal implementation plan

> **For agentic workers:** Use the subagent-driven-development workflow to implement and verify each task below.

**Goal:** Let an expired member renew with the existing account, previous plan and saved depositor name.

**Architecture:** Reuse the existing subscription request and approval APIs. Persist plan selection and renewal intent in route query parameters, and use the shared member tier rules to distinguish expired members from pending, active and unlimited members.

**Tech stack:** React 19, React Router, TypeScript, Vitest, Spring Boot, Gradle.

**Spec:** User request: “구독 기간 만료 회원이 재구독 신청을 편리하게 할 수 있게 워크플로우를 개선 해줘”.

## Constraints

- MONTHLY: 1 month / 30,000 KRW; SIX_MONTHS: 6 calendar months / 150,000 KRW.
- Explicit plan selection takes precedence over the previous approved plan.
- Keep the existing KST expiry rule: the end date itself remains valid.
- Preserve pending requests, active access, unlimited members and production member data.
- No production test account, subscription application, payment or approval mutation.

## Tasks and review focus

- [x] Add failing regression tests for ordinary expired login, direct subscription entry, the My Page CTA, explicit plan overrides and pending renewal approval.
- [x] Restore the previous approved plan on login and protected-route redirects; retain query parameters during reauthentication.
- [x] Show a re-subscription heading, past expiry and previous plan on Subscribe/My pages. Prefill the existing depositor, use an explicit re-subscription CTA and shorten the returning-member page.
- [x] Canonicalize expired direct entry to `?plan=<selected>&renewal=1` so reloading retains the choice.
- [x] Continue to show server-requested plan/payment details while pending and resume the home screen after approval.
- [x] Add backend regression coverage for expired FREE/PRO six-month reapplication, duplicate-request immutability and approval from KST today.
- [x] Run all frontend/backend tests and production builds, review the final diff and bump the PWA cache.
- [ ] Deploy MiniPC and both Cloudflare Pages branches; verify service health, exact URLs, assets and worker version; commit/push intended files only.

## Verification limitation

The Windows Computer Use tool stopped this turn because it could not determine the current Chrome URL with sufficient confidence. No further UI automation was attempted. Automated real-browser/member-screen proof is unavailable; meaningful DOM integration tests cover the member workflow. This limitation must remain explicit in the completion report.

## Local evidence

- Frontend: 69 tests, 8 files; 0 failures (2026-10-11 20:01 KST).
- Backend: 261 tests; 0 failures/errors. Two new expired FREE/PRO cases verify six-month reapplication, preserved history, unchanged pending requests after duplicates and approval-day expiry calculation.
- Production build: TypeScript and Vite successful. JS `index-DVhK8AMN.js`, CSS `index-CUF6Hm_1.css`.
- PWA cache: `bitman-v20261011-subscription-renewal-r3`.
- Temporary fixture files removed before the production build; no preview server started.
- Final review found and fixed the guest login link inventing MONTHLY when no plan was selected. Its regression was observed failing before the fix and passes afterward.
