# Operation integrity repair report

Status: operation repair and approved admin readiness/monitor expansions implemented and verified. Coordinator Gate B passed 472 backend tests, 164 frontend tests and the frontend production build; this worker read the actual logs/XML. Browser, production-login and release verification remain with the coordinator.

## Scope and evidence

Read `operations-brief.md`, audit P2-11..17 and the design gaps for startup cost, publication tracking and execution budgets. Read BitMan AI workflow, BitMan service ops, systematic debugging and TDD skills. Initial unrelated dirty files were preserved; no production JSON, member DB, credentials, external API or Telegram messages were read or invoked.

Confirmed causes:

- `AnalysisService.saveAnalysis` swallowed IOException and directly replaced the target file; live code installed its cache despite the write failure.
- Scheduler called the live publishing path before periodic filters, saved again from its postprocessor, advanced heartbeat before final publication and treated exception-free zero-agent runs as success.
- `ConditionRunService.markComplete` unconditionally selected COMPLETE.
- Jobs lacked owner identity, and both result and research polling returned any known job ID.
- Admin refresh performed all modes synchronously although its frontend already supported polling.
- Health used the common four-hour server cache. Telegram void methods hid disabled/failed sends; scheduler emitted NOTIFIED unconditionally.
- Startup missing-cache refresh had no trading-day/time policy.

## Approved shared contracts

- AI worker removes orchestrator raw `TrackRecordService.recordAnalysis` calls.
- Live analysis: deterministic publication gates, atomic file save, final-set tracking, then memory cache.
- Periodic analysis: raw validated result, periodic exclusions, atomic save once, final-set tracking, then heartbeat and notification.
- FAILED = no successful agent; EMPTY = successful agents with no final picks and no good-cache overwrite; PARTIAL = some successful agents and valid picks; COMPLETE = expected agents successful and valid picks.
- Direct DeepSeek endpoints share `LiveAnalysisQuotaService`; bounded input is 2,000 text characters and 120 mode characters. No quota bypass is added.
- In-memory jobs stay in the current architecture. No migration or durable-job system is introduced.

## Implemented behavior

- `AnalysisOutcome` centralizes COMPLETE/PARTIAL/EMPTY/FAILED and normalizes `metadata.publicationStatus`. `ConditionRunService`, async jobs and scheduler statistics use the same outcome. EMPTY is an explicit partial terminal result; it does not overwrite a good file/cache, advance the last successful timestamp, create tracking rows or send a recommendation. Zero successful agents throws and marks failure.
- `AnalysisService.runAnalysisForPublication` computes without publishing. Live publication saves once, tracks the persisted final set, then installs its memory cache. Periodic publication applies ETF, identity and duplicate exclusions, rebuilds all public representations to the retained codes, saves once, tracks that set, then advances heartbeat. Per-agent retained text stays associated with that agent.
- Saving serializes before touching the target, writes a temporary sibling, replaces with `ATOMIC_MOVE`/`REPLACE_EXISTING`, cleans up the temporary file, and propagates an `AnalysisPersistenceException` with its IOException cause. There is no non-atomic fallback or cache installation after a required write failure.
- Fresh file and live caches require parseable, nonfuture analysis and quote timestamps, the mode TTL, strict positive integer won prices, six-digit codes and nonblank candidate/quote provenance. Malformed, future and legacy unverified rows fail closed. `getStoredPrecomputed` retains readable history. Shared `AnalysisOutcome.isVerifiedPick(StockPick, Instant, long)` accepts `Long.MAX_VALUE` quote age without multiplication overflow so the main feed can retain proven history with explicit stale status. Verified candidates skip redundant name-network lookups.
- Analysis computation has a configurable 120,000 ms outer deadline and four execution slots. An interrupt-ignoring timed-out worker keeps its slot and duplicate guard until it really returns; it cannot later save or cache a result. The AI worker owns the separate orchestrator/provider deadline and actual provider capacity enforcement.
- `AsyncJobManager` uses full UUID IDs and mandatory owner UUIDs, bounds retained jobs, and guards both result and research polling. Active PENDING/RUNNING jobs remain queryable; the 30-minute retention clock starts at terminal completion. Late callbacks cannot recreate removed jobs or overwrite terminal states.
- Admin refresh validates selected modes first, returns HTTP 202 with `jobId` promptly, and exposes `GET /api/admin/system/refresh-all/{jobId}` for the existing polling client. Results distinguish `ok`, `partial`, and `error`. One refresh is active at a time; owner/admin policy controls polling; active refresh jobs never expire. Manual ADMIN_REFRESH is silent for success, degradation and failure Telegram paths.
- Health's server and HTTP cache lifetimes are 30 seconds. Other cache lifetimes remain scoped to their existing usage.
- Telegram delivery returns sent/partial/skipped/failed counts and requires a parsed JSON boolean `ok: true`. Disabled recipients and rejected/malformed responses cannot emit NOTIFIED. Partial delivery can record both NOTIFIED and NOTIFICATION_FAILED; messages preserve unavailable target/stop levels rather than inventing percentage prices. The existing dedicated Telegram transport keeps its 30-second connect/read timeouts and recipients.
- Startup paid refresh defaults to enabled, but only on a trading day between 08:50 and 15:30 KST. Each missing mode is attempted at most once per boot. Sunday, night and disabled-policy startup only reads stored heartbeat information and does not run paid analysis or send alerts. Task Scheduler was not modified.

Owned production files changed: `service/AnalysisService.java`, new `AnalysisOutcome.java` and `AnalysisPublicationView.java`, `PrecomputeScheduler.java`, `AsyncJobManager.java`, `TelegramNotifier.java`, `condition/run/ConditionRunService.java` and `ConditionRunEventType.java`, `controller/AnalysisController.java`, `AdminController.java`, `MonitorController.java`, `config/AppConfig.java`, and `dto/AnalysisRequest.java` / `AnalysisResponse.java`. The existing admin frontend already supported 202/job polling, so it needed no change in this slice.

## Behavioral regressions and red evidence

All filesystem fixtures use `@TempDir`. Providers, Telegram and tracking are mocked; no real credentials, member DB, external API or messages were used. New fixtures were written before their respective fixes.

Initial seven-class RED command, after two unrelated shared-source compilation issues were corrected by their owners:

```bash
PROJECT="/c/bitman_justbuy_project"
PYTHON="$PROJECT/.venv/Scripts/python.exe"
FRONTEND="$PROJECT/frontend"
cd "$PROJECT"
cd "$PROJECT/backend"
./gradlew test --offline --tests "*AnalysisPublicationIntegrityTest" --tests "*ConditionRunOutcomeTest" --tests "*AnalysisJobOwnershipTest" --tests "*AdminRefreshAsyncTest" --tests "*TelegramDeliveryIntegrityTest" --tests "*MonitorHealthCacheTest" --tests "*PrecomputePublicationIntegrityTest"
```

Observed 13 tests / 11 intended failures. The two basic publication tests had already demonstrated RED in the AI worker's serialized publication batch and were green after the first persistence fix. Failures exposed unconditional completion, foreign-owner polling, synchronous refresh, four-hour health cache, false Telegram acknowledgement, disabled NOTIFIED, empty overwrite and Sunday paid startup. The build took 58 seconds because the original startup routine slept inside the directly tested method.

The coordinator's first mixed GREEN batch verified 37 selected operation tests with zero failures; the three failures among 46 combined tests were separate new KIS RED cases. Evidence: `/tmp/bitman-ops-green-kis-red.log`.

Additional meaningful fixtures cover interrupted/uncooperative computation, serialization preserving old bytes and temporary cleanup, finalized tracking/public rows, legacy/malformed/future cache rejection, absent Telegram levels, silent manual refresh, and active/terminal job retention. The coordinator's final repair RED batch had 66 tests / 23 failures: 15 fresh-cache failures, one redundant Naver lookup, one invented Telegram level, and six separate price-feed provenance failures. Atomic publication fixtures were already green. Evidence: `/tmp/bitman-final-repair-red.log` (read directly).

The last retention/manual RED command was:

```bash
PROJECT="/c/bitman_justbuy_project"
PYTHON="$PROJECT/.venv/Scripts/python.exe"
FRONTEND="$PROJECT/frontend"
cd "$PROJECT"
cd "$PROJECT/backend"
./gradlew test --offline --tests "*AsyncJobRetentionTest" --tests "*AdminRefreshAsyncTest" --tests "*PrecomputePublicationIntegrityTest"
```

Observed 11 tests / 4 intended failures: active analysis and admin jobs disappeared at 31 minutes, manual refresh still invoked notification paths, and the earlier Naver regression remained red. Seven tests passed. The build took 12 seconds. All four observed causes were then repaired.

## Final verification

The shared Gradle reservation was explicitly granted by the coordinator and released after this run:

```bash
PROJECT="/c/bitman_justbuy_project"
PYTHON="$PROJECT/.venv/Scripts/python.exe"
FRONTEND="$PROJECT/frontend"
cd "$PROJECT"
cd "$PROJECT/backend"
./gradlew test --offline --tests "*AnalysisPublicationIntegrityTest" --tests "*AnalysisFreshCacheIntegrityTest" --tests "*ConditionRunOutcomeTest" --tests "*AnalysisJobOwnershipTest" --tests "*AdminRefreshAsyncTest" --tests "*AsyncJobRetentionTest" --tests "*TelegramDeliveryIntegrityTest" --tests "*MonitorHealthCacheTest" --tests "*PrecomputePublicationIntegrityTest" --tests "*AnalysisControllerTest" --tests "*ConditionRunServiceTest" --tests "*AnalysisServiceCacheStatusTest" --tests "*PrecomputeSchedulerRefreshTest" --tests "*AdminRefreshWorkflowTest" --tests "*TelegramNotifierTimeoutTest"
```

Result: BUILD SUCCESSFUL in 17 seconds; actual test XML contains 15 classes / 62 tests / 0 failures / 0 errors / 0 skipped. Log: `/tmp/bitman-operations-final-green.log`. The existing admin workflow, controller, run-state, cache-cutoff, selected retry, scheduler and Telegram-timeout tests remain in that selection. Scoped tracked `git diff --check` also passed.

Independent review by the price-integrity worker checked atomic publication, finalized tracking, empty preservation, startup policy, outcome/ownership, job retention, manual silence and Telegram acknowledgement. No further concrete blocker was reported. Its noted total-budget limitation is recorded below. Full integrated Gate A belongs to the coordinator; no claim about its result is made here.

## Remaining limits and handoff

- Jobs, run summaries, live cache and quota state remain in memory. Restart resume, durable cancellation/idempotency and new DB migrations were not introduced.
- The 120-second bound covers analysis computation. Atomic file I/O, tracking persistence and automatic notification delivery occur afterward, outside a single total publication deadline. Each Telegram request is transport-bounded, but multiple synchronous recipients can delay an automatic run. Manual refresh is silent and verified names skip network revalidation, reducing its avoidable delay. A durable overall execution/recovery budget remains a separate design task.
- JSON replacement and tracking persistence are ordered but are not a cross-store transaction. A tracking failure after a successful file replacement can leave a persisted response without corresponding rows; the run reports failure rather than claiming completion. Recovery/transaction design remains outside this scope.
- Legacy files/history are preserved and are not repaired or deleted. Fresh publication rejects unverified stored rows; Sunday/night restart does not rebuild them through paid models.
- This slice's tests exercise mocked ACKs, ownership and persistence failure behavior, not production credentials, live provider behavior, browsers or actual message delivery. Existing unrelated dirty files were preserved.
- Release, commit, push, deployment and integrated gates are owned by the coordinator. This worker performed none of those actions.

## Approved admin source-readiness expansion after Gate A

The coordinator reported Gate A passed with 392 backend and 162 frontend tests before expanding the sources. The following additive change is separately tested and does not activate any source:

- `/api/admin/system/status` adds `dataSources` entries for KIS additional evidence, DART event evidence and ECOS. Each exposes only id/name, feature enabled, credential presence, readiness state and an operator message. Configuration is read from the Environment; no provider probe occurs and no credential value is returned.
- Flags are `bitman.kis.extended-evidence.enabled`, `bitman.dart.event-evidence.enabled` and `bitman.ecos.enabled`, all default false. Enabling a flag without credentials yields CREDENTIALS_MISSING. Configured KIS still yields RIGHTS_CHECK_REQUIRED because the user's third-party/commercial rights are unchecked. DART/ECOS CONFIGURED means configuration presence, with actual connection explicitly unverified.
- `frontend/src/api/authApi.ts` adds an optional response type. `AdminPage.tsx` displays the three entries in its existing table styling, separately showing flag and credentials. KIS rights and missing ECOS key are explained; the block has no activation controls. Old status responses without the additive field remain renderable.
- Optional Brave remains documentation-only in `user-setup.md`, pending key/budget/storage rights; no adapter or account action was added.

Backend RED:

```bash
PROJECT="/c/bitman_justbuy_project"
PYTHON="$PROJECT/.venv/Scripts/python.exe"
FRONTEND="$PROJECT/frontend"
cd "$PROJECT"
cd "$PROJECT/backend"
./gradlew test --offline --tests "*AdminSourceReadinessTest" --tests "*EcosMarketContextIntegrityTest"
```

Actual XML: 19 tests / 15 failures, no compile errors; AdminSourceReadinessTest failed all three behavior assertions because `dataSources` was absent, and the coordinator's ECOS stub failed 12 of 16 cases. Log `/tmp/bitman-admin-ecos-red.log`. The admin tests use an isolated controller-only context and MockEnvironment, with fake credential strings checked for response redaction; no DB, startup hooks or real HTTP occurs.

Frontend RED/GREEN command, using the standard Bash path-variable preamble then `cd "$FRONTEND"`:

```bash
npm test -- src/pages/AdminPage.test.tsx
```

RED: one intended missing-region failure / eight existing tests green. GREEN after the display/type change: nine tests passed. Logs `/tmp/bitman-admin-readiness-frontend-red.log` and `/tmp/bitman-admin-readiness-frontend-green.log`. The three backend readiness cases subsequently passed in the mixed-outcome RED run below.

Independent review of the coordinator's ECOS implementation identified one concrete malformed-dimension gap: object/array ITEM_CODE2/3/4 values can appear blank through Jackson `asText()`. The coordinator added RED cases and required textual blank dimensions. This worker read the corrected source; the final 19-case ECOS class passed in Gate B. ECOS files were not changed by this worker. Other reviewed bounds, timestamps, provenance, unknown release time and redaction paths were consistent with the design.

## Approved explicit monitor outcomes

Follow-up inspection found the legacy monitor stored only `success=false` for EMPTY/PARTIAL, and AdminPage counted terminal PARTIAL ConditionRuns as active. The coordinator approved a focused repair:

- Each new monitor entry includes COMPLETE/PARTIAL/EMPTY/FAILED `outcome`, preserving the binary field for compatibility. Legitimate EMPTY/PARTIAL do not carry an execution error. FAILED entries retain only EXECUTION_FAILED or ANALYSIS_TIMEOUT; raw provider exception text, URLs, bodies and credentials are discarded.
- Monitor and admin status expose explicit outcome counts, `completionRate`, total attempts and duration. Existing `successRate` retains its original full-completion/all-attempts denominator as a compatibility alias. The UI clearly labels this `전체 완료율`; it does not describe it as a binary failure rate.
- Admin status additively exposes `analysisStats` and its recent safe entries. The existing table displays explicit outcomes first, so EMPTY is `추천 없음` and PARTIAL is `부분 완료`. PARTIAL/EMPTY are terminal for the active-run count.
- One mixed backend fixture verifies all four outcomes, their counts, the 25% completion rate, and absence of fake key-bearing provider text. It snapshots/restores the in-memory log ring and calls only the admin status contract, avoiding health probes.

Mixed backend RED, using the standard Bash path preamble and backend directory:

```bash
./gradlew test --offline --tests "*AdminSourceReadinessTest"
```

Observed four cases: the new mixed statistics case failed on the absent additive response, and all three readiness cases passed. BUILD FAILED in eight seconds with no compile errors. Log `/tmp/bitman-admin-outcomes-backend-red.log`. All four cases subsequently passed in the coordinator's full Gate B; no duplicate broad test run was launched by this worker.

Frontend targeted RED:

```bash
npm test -- src/pages/AdminPage.test.tsx -t "uses explicit mixed outcomes"
```

Observed one intended missing-result-region failure / nine unselected tests skipped. After the UI change, `npm test -- src/pages/AdminPage.test.tsx` passed all ten cases. Logs `/tmp/bitman-admin-outcomes-frontend-red.log` and `/tmp/bitman-admin-outcomes-frontend-green.log`.

The same independent expansion review found no concrete KIS blocker in caps, official requests/TRs, short transport/throttle, token budget, completed-bar coverage, strict OHLC/volume/time, provenance and explicit unknown program date/unit. Its final eight-case class passed in Gate B. DART review found one concrete original-XML numeric-unit gap: removing both percent and won suffixes for every field allowed a percentage contract amount or won ownership percentage. Its owner reported all four wrong-unit fixtures failed in RED, then enforced field-specific optional terminal suffixes. This worker read the corrected parser, and its containing 22-case service class passed in Gate B. DART source/parser files were not changed by this worker; other identity, provider status, cache, bounded ZIP/XML, amendment and safe-error paths matched the brief.

## Integrated final evidence read by this worker

- `/tmp/bitman-backend-gate-b.log`: BUILD SUCCESSFUL in 54 seconds. Actual `backend/build/test-results/test/TEST-*.xml` totals: 73 classes, 472 tests, 0 failures, 0 errors, 0 skips; AdminSourceReadinessTest has four passing cases.
- `/tmp/bitman-frontend-gate-b.log`: 19 files, 164 passing tests.
- `/tmp/bitman-frontend-build-b.log`: `npm run build` completed `tsc -b && vite build`; 86 modules transformed and production assets built successfully.
- Scoped tracked `git diff --check` and trailing-whitespace checks on the new test/report passed. Java/frontend source remained frozen during Gate B.

These are integrated offline fixture/build results. They do not claim successful live credentials, KIS usage rights, ECOS account availability, actual Telegram delivery or completion of the coordinator's later browser/release steps.
