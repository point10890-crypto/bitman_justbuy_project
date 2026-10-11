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
- [x] Deploy MiniPC and both Cloudflare Pages branches; verify service health, exact URLs, assets and worker version.
- [x] Commit/push the final generated assets and deployment evidence; verify remote main matches HEAD.

## Verification limitation

The Windows Computer Use tool stopped this turn because it could not determine the current Chrome URL with sufficient confidence. No further UI automation was attempted. Automated real-browser/member-screen proof is unavailable; meaningful DOM integration tests cover the member workflow. This limitation must remain explicit in the completion report.

## Local evidence

- Frontend: 69 tests, 8 files; 0 failures (2026-10-11 20:01 KST).
- Backend: 261 tests; 0 failures/errors. Two new expired FREE/PRO cases verify six-month reapplication, preserved history, unchanged pending requests after duplicates and approval-day expiry calculation.
- Production build: TypeScript and Vite successful. JS `index-DVhK8AMN.js`, CSS `index-CUF6Hm_1.css`.
- PWA cache: `bitman-v20261011-subscription-renewal-r3`.
- Temporary fixture files removed before the production build; no preview server started.
- Final review found and fixed the guest login link inventing MONTHLY when no plan was selected. Its regression was observed failing before the fix and passes afterward.

## Deployment evidence

- Source commit: `27d4ba73168236d583bb465c3fd9d7664ec58fb2`.
- MiniPC: `scripts/deploy-minipc.ps1 -MiniPcHost 192.168.219.100` completed with tests, database/JAR backup, service restart and health verification. Operational DB and secrets were preserved.
- MiniPC local and public API health: `status=ok`, `service=justbuy-api`; public Spring HTML serves `index-DVhK8AMN.js`.
- Public plan catalog remains MONTHLY / 30,000 KRW and SIX_MONTHS / 150,000 KRW.
- Cloudflare main deployment: `3a534605`; production deployment: `cd40d85f`.
- `https://bitman-justbuy.pages.dev/` and `https://production.bitman-justbuy.pages.dev/` both serve the expected JS/CSS and PWA cache version. Their `/subscribe?plan=SIX_MONTHS&renewal=1` URLs resolve to the same current build.
- Deployed JS contains the re-subscription CTA, pending heading, expiry summary and returning-account login copy. CSS includes the shared expiry notice styles.
- These deployed checks used HTTP responses; they do not replace the unavailable authenticated real-browser UI check described above.
- Source and static asset commits were fast-forwarded to main and pushed. `git ls-remote origin refs/heads/main` matched local HEAD; final status contains only the unrelated changes that existed before this task.
