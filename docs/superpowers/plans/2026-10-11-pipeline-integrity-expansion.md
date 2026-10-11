# BitMan pipeline integrity and low-cost expansion implementation plan

> For agentic workers: follow the assigned brief, reproduce failures before production changes, and report targeted verification to the coordinator.

**Goal:** Fix the audited data, AI and operation failures first, then connect deterministic KIS/DART evidence and ECOS market context without introducing trading or unlicensed news redistribution.

**Architecture:** Keep the existing Spring/Vite interfaces. Enforce candidate identity and quote provenance before publication; derive performance from completed observations; preserve failed/partial states and atomic persistence. Add source adapters behind configuration with explicit unavailable states.

**Tech stack:** Java 21, Spring Boot, Gradle/JUnit/Mockito, React/Vite/Vitest.

**Approved brief:** The user approved the prior recommended sequence: repair problems, implement improvements in order, and identify account/permission steps requiring the user. The audit is `docs/AGENT_PIPELINE_AUDIT_2026_10_11.md`.

## Global constraints

- Preserve the 21 unrelated dirty paths recorded at the start. Do not stage them.
- Do not read/print .env, credentials, member data or production databases. Use existing injected runtime configuration without exposing values.
- No automatic orders, new paid subscription, account registration, Telegram messages or historical production-data repair.
- Source facts and publishable prices must come from deterministic adapters, never an LLM.
- No live KIS/AI/DART call in unit tests. HTTP fixtures must use local stub responses.
- Keep Korean user copy and existing endpoint compatibility; additive fields may describe provenance and unavailable/partial states.
- Agents own disjoint files and do not commit, push or deploy. The coordinator runs the full gate, requests independent review and handles release.
- Coordinate Gradle runs; never run concurrent builds against the shared backend build directory.

## Review focus

- A percentage or malformed number must never become a target/current price.
- A missing quote or empty agent response must not become a successful, fresh, published pick.
- Failed persistence must not overwrite a good result or mark a run successful.
- A different member must not fetch an analysis job; an older UI request must not replace a newer result.
- Missing credentials, a stale observation or a provider error must stay explicit and must not produce invented data.

## Tasks and gates

- [x] Task 1: Price/performance/UI integrity. Requirements: `docs/implementation/pipeline-20261011/price-brief.md`.
- [x] Task 2: AI publication and provider integrity. Requirements: `docs/implementation/pipeline-20261011/agent-brief.md`.
- [x] Task 3: Operation/job/persistence integrity. Requirements: `docs/implementation/pipeline-20261011/operations-brief.md`.
- [x] Gate A: Full backend 392/392 (65 classes; no errors/skips), frontend 162/162 and build passed; independent review findings addressed.
- [x] Task 4: KIS ranking/minute/program evidence expansion using official contracts, bounded rate and source timestamps.
- [x] Task 5: DART event/original-document evidence expansion, safely parsed XML, corrections and explicit coverage.
- [x] Task 6: ECOS configurable adapter and market-context integration; no fabricated current-day statistics.
- [x] Task 7: Optional Brave integration is deferred until credentials and permitted storage/application scope are resolved. No NAVER Hub/KRX/FRED automatic additions.
- [x] Gate B: Expansion fixture tests, integration tests, full tests/build, independent review and local browser smoke.
- [x] Release: Commit only intended changes after all tests pass; push, deploy using existing MiniPC/Pages scripts and verify the actual public URLs. Protected live Home/admin DOM remains pending user login, as documented below.
- [x] Handoff: Record exact completed/pending checks and a short user-owned checklist for KIS commercial rights, ECOS key and optional news access.

## Expansion acceptance contracts

KIS must use `/uapi/domestic-stock/v1/ranking/fluctuation` rather than locally sorting volume rank as a second independent ranking. Minute/program evidence is bounded to final candidates and records source, exchange, capture time and availability. Authentication failures and quota exhaustion do not create fake candidates.

DART structured major reports cover capital issuance, convertible bonds and buybacks. Supply contracts and shareholder changes use list + original document; no nonexistent dedicated API is assumed. Facts retain receipt ID, report/date and source link; ambiguous extraction remains unknown. XML external entities and zip expansion are bounded/disabled.

ECOS series use explicit table/item/cycle configuration and real observation dates. Capture time is not substituted for release time. Missing key, old series or error yields unavailable context and does not block other valid sources. Expose only public-dataset fields selected for use and attribution.

## Progress

2026-10-11: Start HEAD `56f2a13`; main ahead of origin by the prior audit commit. No implementation files dirty at start. The existing production/member data and runtime configuration are excluded from changes.

Repair evidence: original price/backend slice 61/61 and frontend 17/17 plus build passed; AI selective slice 79/79 passed after real failing regressions; operation regressions reproduced (13 tests, 11 intended failures, two atomic-publication tests already green after their prior failing run). Review added legacy-performance exclusion, keyword-path publication, missing-success-marker/foreign-ranking and transport-redaction regressions. All are covered by the final full gate.

The user confirmed KIS rights need checking and no ECOS key exists. New KIS evidence and ECOS remain disabled by default. Setup instructions are recorded in `docs/implementation/pipeline-20261011/user-setup.md`; expansion contracts in `expansion-brief.md`.

Gate A passed on 2026-10-11 at 22:10 KST after correcting legacy success-marker fixtures and transport-message assertions. The new KIS/ECOS/context integration slice passed 47/47, including 19 ECOS and four actual orchestrator integration cases. DART's 37 initial regression cases were reproduced with 36 intended failures; eight independent-review regressions were reproduced and fixed. The final DART slice passed 45/45.

Gate B tests and build passed at 22:29 KST: backend 73 classes / 472 tests, zero failures/errors/skips; frontend 19 files / 164 tests; Vite build JS `index-C06jIWM-.js`. Independent reviews have no remaining blocking findings. Local built-JAR browser smoke passed on port 18080 with memory H2, synthetic admin credentials and no provider keys: Home shows measured-only statistics, system view shows disabled/readiness states and explicit outcome classes, and performance shows no fabricated returns when measurements are absent. Scheduled computation, startup/backfill and external alerts were disabled in the local fixture. All provider tests are offline fixtures. No existing production DB, account credential, paid provider call or alert send was used.

Release at 22:40 KST: implementation commit `eeb5f96` pushed and remote SHA matched; MiniPC deploy script reran all 472 backend tests and passed, replaced/restarted the JAR with operational DB/secrets preserved. Local/public health passed, remote JAR SHA256 equals the tested build. Pages main (`fba10921`) and production (`905aab12`) deployed; three public origins have identical new JS hash and service worker. Exact user URL DOM/public subscription navigation was verified. Protected live Home/admin DOM remains unverified because the browser is signed out; the optional user login request has not been answered. New source activation/live account responses remain pending KIS rights/ECOS key/DART cache and real response verification. Full results are in `docs/implementation/pipeline-20261011/implementation-result.md`.
