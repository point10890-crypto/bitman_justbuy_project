# Price, performance and Home integrity repair

Status: complete. Root repair Gate A passed 392 backend and 162 frontend tests with zero failures, including all 27 price/performance integrity cases. This worker's frontend slice passes 18/18 and its final TypeScript/Vite build passes. Root owns the full project gates and independent review.

## Scope and root causes

Read `price-brief.md`, the P1-01/P1-02/P2-07/P2-08/P2-18..20 audit findings, and the preserved offline runtime/UI observations. Applied `bitman-ai-agent-workflow`, `superpowers:systematic-debugging` and `superpowers:test-driven-development` (including the good-test guidance).

- The scanner put a percentage in `highPrice`; permissive digit stripping turned `+5.21%` into a 521-won target. Price parsing now accepts positive integer won prices (plain digits or correctly grouped comma digits, optional `원`) and rejects percentages, nonfinite values, exponents, negative numbers and malformed/mixed units. Optional target/stop values remain unavailable when missing or invalid.
- Short-term and leader scans now retain a single immutable first capture containing both its price and time, while reporting the latest price separately. Invalid current-price candidates are excluded. The leader engine no longer manufactures a target/stop from a fixed percentage.
- Home previously aggregated candidates' expected target upside as verified performance. `MainConditionService` now consumes completed measured 5-trading-day records from `TrackRecordService`; no measurements yield count 0 and unavailable average/win rate. The legacy response field names remain compatible; Home labels identify measured five-trading-day results.
- Home also excludes a completed-status record whose fifth observation date is still in the future. Member target/stop hit denominators require a valid entry-relative price level, so an old 521-won target below a 10,000-won entry cannot create a positive target-hit rate. Member aggregates reject NaN/infinite returns and maximum returns. Member basis labels now identify historical 1-trading-day closes.
- Prior versions could save all 1/3/5-day prices from a current quote. Existing status/price fields cannot re-prove those old observations. Home therefore also requires `analysisDate >= bitman.performance.measured-since` (default `2026-10-12`); invalid configuration fails closed. Tests supply their own cutoff explicitly. This is a rollout policy, not a historical data repair. Existing rows remain stored and readable in member history with copy explaining that earlier calculations are separate from the new measurement basis. If rollout occurs later than the default date, advance this property to the first analysis date wholly produced with the fixed updater.
- Stored AI picks no longer receive invented rank-based rule scores. They expose a rule baseline of 0 where no rule measurement exists and use a supplied stock consensus only as the AI score. Short-term/leader scanners expose AI score 0 until an actual verifier verdict is supplied; their measured deterministic rule scores are retained through enrichment. This was coordinated with the AI worker.
- Main's stored/precomputed pick boundary now uses the operations worker's shared `AnalysisOutcome.isVerifiedPick` predicate: valid stock identity, positive strict current price, candidate/quote sources, and parseable nonfuture quote-check instant. Unproven legacy rows become `LEGACY_UNVERIFIED` with no stock signals/derived alerts. Proven historical rows may remain visible as `STALE_CACHE` at their original timestamp; fresh-cache age enforcement remains in the pipeline. Stored files/history are untouched.
- Horizon updates now look up each 1/3/5-trading-day date using `KoreanMarketCalendar` and use that date's matching historical `DailyOhlc.close`. They neither backfill every period with a current quote nor complete a record with missing observations. Current-day observations are withheld before the close. Historical target/stop checks use only eligible date-matching candles within the observation window.
- Home manual rows are stored with their entire section response and its feed revision, so rows and timestamp come from the same response. New feed responses invalidate manual overrides; older source snapshots and section requests that finish after a new feed cannot displace the new response.
- Home labels now distinguish actual short-term day change / leader capture-to-current change from precomputed target upside. Precomputed short-term/leader rows show `목표 상승 여력`; theme rows identify stock/capture/current prices correctly.
- Analysis and feed hooks use request generations, invalidate on close/unmount and only update result/error/loading for the current request. Superseded analysis requests cannot write their results into cache/history.
- Cache retention remains independent of observation freshness. Each restore recalculates freshness from `updatedAt`, rejects invalid/future timestamps, retains server-stale flags, applies the 30-minute live policy, and applies 175-minute/market-close precomputed policy only to entries saved from that source. Legacy entries without source use the conservative live policy.

## Files

Production:

- `backend/src/main/java/com/bitman/justbuy/service/ShortTermRealtimeScanner.java`
- `backend/src/main/java/com/bitman/justbuy/service/LeaderConditionEngine.java`
- `backend/src/main/java/com/bitman/justbuy/service/TrackRecordService.java`
- `backend/src/main/java/com/bitman/justbuy/service/MemberTrackRecordService.java`
- `backend/src/main/java/com/bitman/justbuy/service/MainConditionService.java`
- `frontend/src/pages/HomePage.tsx`
- `frontend/src/hooks/useAnalysis.ts`
- `frontend/src/hooks/useMainConditions.ts`
- `frontend/src/lib/analysisCache.ts`

Tests:

- `backend/src/test/java/com/bitman/justbuy/service/PricePerformanceIntegrityTest.java`
- `backend/src/test/java/com/bitman/justbuy/service/MainConditionServiceTest.java` (removed expectations of default/unverified prose prices)
- `backend/src/test/java/com/bitman/justbuy/service/MainConditionPreviewTest.java` (explicit valid provenance fixtures for new stored recommendations)
- `backend/src/test/java/com/bitman/justbuy/service/ShortTermRealtimeScannerTest.java` (AI score requires an actual verifier)
- `backend/src/test/java/com/bitman/justbuy/service/MemberTrackRecordServiceTest.java` (price-level fixtures now include an actual entry price)
- `frontend/src/hooks/useAnalysis.test.tsx`
- `frontend/src/hooks/useMainConditions.test.tsx`
- `frontend/src/lib/analysisCache.test.ts`
- `frontend/src/pages/HomePage.integrity.test.tsx`

## Verification commands and evidence

All shell commands used the PowerShell wrapper `& 'C:/Program Files/Git/bin/bash.exe' -lc '<literal Bash script>'`. Each script declared `PROJECT="/c/bitman_justbuy_project"`, `PYTHON="$PROJECT/.venv/Scripts/python.exe"`, `FRONTEND="$PROJECT/frontend"` and changed to `"$PROJECT"` first. Gradle also exported `JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.10.7-hotspot"` and prepended its `bin` to PATH. No Python command was required.

1. Backend original RED: `cd "$PROJECT/backend"; ./gradlew test --tests "*PricePerformanceIntegrityTest" --offline`. Exit 1; 16 tests, 14 intended behavioral failures. NaN/Infinity already returned no parsed price. No compilation or fixture setup failure. Output: `price-backend-red.log`.
2. Frontend original hook/cache RED: `cd "$FRONTEND"; npm test -- src/hooks/useAnalysis.test.tsx src/hooks/useMainConditions.test.tsx src/lib/analysisCache.test.ts`. Exit 1; 12 tests, 11 intended behavioral failures. Output: `price-frontend-red.log`.
3. Home original RED: `npm test -- src/pages/HomePage.integrity.test.tsx`. Exit 1; 3 tests failed for response/time mixing, late manual override and the old summary labels. The late manual test was tightened to explicitly settle its deferred promise in React `act` before checking the final DOM. Output: `price-home-red.log`.
4. Additional frontend RED: `npm test -- src/lib/analysisCache.test.ts src/pages/HomePage.integrity.test.tsx`. Exit 1; 2 intended failures/9 passes for concept live-cache source classification and an older manual source timestamp. Output: `price-frontend-red-extra.log`.
5. Frontend affected GREEN: `npm test -- src/hooks/useAnalysis.test.tsx src/hooks/useMainConditions.test.tsx src/lib/analysisCache.test.ts src/pages/HomePage.integrity.test.tsx`. Exit 0; initially 17/17, now 18/18 after the target-upside label regression. Output: `price-frontend-green.log`.
6. Frontend build: `npm run build`. Exit 0; TypeScript and Vite passed after the final frontend edits. Output: `price-frontend-build.log`.
7. `git diff --check` passed without whitespace errors. Git printed only working-copy CRLF conversion notices.

8. Additional backend RED: root ran the exact `*PricePerformanceIntegrityTest` selector in a serialized combined build. XML confirmed 19 tests, 3 failures exactly: `memberTargetHitRateIgnoresAnInvalidLegacyTargetBelowEntry`, `memberDoesNotCountNonfiniteReturnsAsVerifiedMeasurements`, `homeCannotCountFutureFiveDayObservationsEvenWithCompletedDatabaseStatus`. The original 16 cases passed. Member/future-summary production changes were made only after these failures were confirmed.
9. Backend affected GREEN before final review additions: `cd "$PROJECT/backend"; ./gradlew test --tests "*PricePerformanceIntegrityTest" --tests "*TrackRecordServiceTest" --tests "*MainConditionServiceTest" --tests "*MainConditionPreviewTest" --tests "*ShortTermRealtimeScannerTest" --tests "*ShortTermRealtimeScannerAiVerifyTest" --tests "*LeaderConditionEngineTest" --offline`. Exit 0. XML confirmed 61 tests/0 failures/0 errors/0 skipped across PricePerformanceIntegrity (19), TrackRecord (4), MemberTrackRecord (8), JonggaTrackRecord (7), MainCondition (8), MainConditionPreview (4), ShortTerm scanner (4), scanner AI verification (4), Leader (3). Output: `price-backend-green.log`.
10. Measurement-cutover RED: `./gradlew test --tests "*PricePerformanceIntegrityTest" --offline`. Exit 1; 20 tests, one intended failure (`legacyCompletedRowsBeforeTheMeasurementRolloutCannotClaimVerifiedHomePerformance`), 19 passes. Output: `price-cutover-red.log`. After implementation root's serialized batch confirmed 20/20 GREEN.
11. Home target-upside label RED: `npm test -- src/pages/HomePage.integrity.test.tsx`. Exit 1; one intended label failure/four passes. Output: `price-home-label-red.log`. After the label repair all affected frontend tests pass 18/18; final build was rerun and exited 0.
12. Stored-feed provenance RED: root serialized exact `*PricePerformanceIntegrityTest`. XML confirmed 27 tests, six intended failures (five `unverifiedStoredPicksCannotBecomeHomeSignals` variants and `legacyPrecomputedResponseCannotBypassHomeProvenanceBoundary`), 21 passes including valid stale-history control. No production boundary change was made before this failure.
13. Final repair Gate A: root's full offline backend suite passed 392 tests; frontend suite passed 162 tests, all with zero failures. `PricePerformanceIntegrityTest` passed all 27 cases, including stored-feed provenance and cutover. No price/performance source changes were made after that gate.

The build printed existing deprecated/unchecked test compiler notes and the standard Mockito JVM class-sharing notice; these were not test failures. No additional failing check was observed in this slice.

## Boundaries and remaining integration work

No real API/provider/DB calls; all test KIS/DB boundaries are mocks. No secrets, members, production data, migrations, server restarts, commits, pushes or deployments were accessed/performed. Unrelated dirty files were preserved. Existing DB columns remain unchanged. Previously reconstructed observations remain stored and excluded from Home's newly measured summary by the explicit rollout cutoff; this change does not claim to repair or re-prove historical production records. Full backend/frontend gates and independent review are assigned to root. DOM tests verify Home behavior with fixtures; this slice does not claim live production/browser verification. Calendar accuracy inherits the existing `KoreanMarketCalendar` maintained holiday list.

Request cancellation here invalidates ownership of UI/cache/history updates; it does not cancel a job already executing on the server. Explicit network abort/server-job cancellation would require expanding the API/controller contract and is outside this owned slice.

## Independent operations review

Reviewed computation/publication separation, atomic persistence, final-set tracking, empty-cache preservation, startup calendar/window guard, outcome mapping, Telegram acknowledgement, admin job ownership/retention, and frontend refresh polling. Reported a concrete budget/retention issue to root and the operations worker: computation timeout ended before serial stock-name postprocessing and Telegram delivery; the 30-minute admin cleanup initially removed even running jobs while `refreshRunning` stayed true. Final source now skips serial name lookups for verified picks, suppresses manual admin Telegram result/degradation/failure sends, and expires only terminal jobs based on `finishedAt`. The running-job retention regression is owned by operations. Scheduled/startup notifications remain synchronous with individual HTTP timeouts; the 120-second analysis timeout bounds computation rather than the entire automatic run. This remaining scope limit was reported to root. No additional blocker was found in the reviewed publication/atomic-save/tracking/empty-preservation paths; no edits were made to operations-owned files.
