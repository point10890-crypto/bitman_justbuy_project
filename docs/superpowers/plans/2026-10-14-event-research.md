# Event research implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Connect actual public supply-contract research and buyback execution evidence to the BitMan app, with durable capture, explicit unknowns and future outcome tracking.
**Architecture:** Dedicated source, pure calculator and verified research workflow alongside existing rankings; authenticated stored-result API; source-backed React view. First-seen evidence is durable and immutable.
**Tech Stack:** Java 21/Spring Boot, Jackson, existing read-only KIS/Kiwoom/DART/AI clients, Vite/React/TypeScript/Vitest.
**Spec:** `docs/superpowers/specs/2026-10-14-event-research-design.md`.

## Global Constraints

- Do not read/print secrets or member DB; preserve runtime config and subscription state.
- Actual source identities only. Unknown input remains unknown. Forecast/scenario is not observed fact.
- No automatic order path, fabricated rows, notification blast or unproven ranking replacement.
- DART source max 3 list pages/12 original documents per refresh; daily collection and AI budgets bounded.
- Fixed outcome horizons 1/3/5/20; no future bars or invented intraday disclosure timestamps.

## Review Focus

- DART labels vary and correction tables duplicate amounts: ambiguous delta must remain unknown.
- Historical total-company margin is not product-specific margin: label proxy and retain cash-flow unknown.
- GET API must not cause paid/source calls; repeated refresh single-flight and persistent old snapshot retained on failure.
- Public status must not leak candidate names or paid evidence; controller guard tests required.
- Live app may use cached PWA assets: deployed SW, asset hashes and DOM checked together.

### Task 1: Public DART contracts and relationships

Files: create `research/event/DartSupplyContractSource.java`, `SupplyContractDocumentParser.java` and tests. Interface: `fetchRecent()` -> immutable Snapshot; `Contract` fields exactly as specified.
- [x] Write tests for named contract, duplicated conflicting amounts, corrections/withdrawals, malformed amounts/dates/units, XXE and archive bounds.
- [x] Run focused Gradle tests; verify absent behavior fails before implementation.
- [x] Implement bounded source and parser; immutable contract identity; partial coverage status.
- [x] Run tests green and review actual captured original.

### Task 2: Profit/cash scenarios and evidence challenge

Files: create `CashImpactCalculator.java`, `EventEvidenceVerifier.java` and tests.
- [x] Test `calculate(input)` with known allocation/recognition/margin ranges; assert actual arithmetic, not mock calls.
- [x] Test missing cash inputs -> cash=null, amendment unknown -> no incremental estimate, signed deltas, future fact/scenario-as-fact -> rejection.
- [x] Implement pure calculator and fact-bound AI adapter with honest rule-only fallback.
- [x] Focused tests green; report failure and success evidence.

### Task 3: Durable workflow, market/outcome capture, buybacks and API

Files: create `EventResearchPipeline.java`, `EventResearchStore.java`, `KindBuybackSource.java`, `EventResearchController.java`, status/scheduler config and tests.
- [x] Test concurrent refresh, atomic persistence/restart, failed refresh retention, immutable first-seen, missing finance/quotes, duplicate candidates and delayed outcomes.
- [x] Build stages source -> finance -> scenarios -> challenge -> KIS/Kiwoom evidence -> store, using bounded candidate/AI limits and completion reuse.
- [x] Test KIND application/execution matching, KOSPI thousands vs KOSDAQ won, cutoff and zero actual execution.
- [x] Test paid guard and metadata-only public status; GET serves stored snapshot only.
- [x] Complete actual-source runtime probe and preserve public fixture with origin metadata.

### Task 4: App workflow

Files: create `frontend/src/api/eventResearchApi.ts`, `pages/EventResearchPage.tsx`, its CSS/tests; modify HomePage/main.tsx/AdminPage or dedicated admin controls and SW.
- [x] Test loading, source-backed details, unknown cash, rule-only verification, no measured future returns, empty/error/auth state and refresh button.
- [x] Implement stored-result UI with explicit source/scenario/outcome distinctions, home entry and protected route.
- [x] Entire frontend tests and build; exact production browser check remains a final gate.

### Task 5: Independent review and production handoff

- [x] Review whole diff independently; fix evidence correctness, security, restart/concurrency and UI failures with regressions.
- [x] Entire backend/frontend suites, bootJar; count tests/skips from reports.
- [ ] Commit only intended source/docs/tests; integrate and push main; verify remote hash.
- [ ] Repository MiniPC deploy and both Pages deploy; confirm JAR local/public health, asset/SW hashes.
- [ ] Exact public UI DOM and operational stored-data/API checks; record limitations and final SHA in verification report.

## Progress

2026-10-14: User explicitly selected full implementation and deployment in this thread. Native isolated worktree created on `codex/event-research`; initial unrelated primary changes preserved.
