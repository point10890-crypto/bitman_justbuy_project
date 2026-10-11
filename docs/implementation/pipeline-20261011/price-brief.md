# Price, performance and UI integrity requirements

Read the audit's P1-01, P1-02, P2-07, P2-08, P2-18..20 and relevant evidence under `docs/audits/agent-pipeline-20261011/` first.

## Owned files

`service/ShortTermRealtimeScanner.java`, `LeaderConditionEngine.java`, `TrackRecordService.java`, `MemberTrackRecordService.java`, `MainConditionService.java`, their DTOs/repository if necessary, their tests; frontend `pages/HomePage.tsx`, `hooks/useAnalysis.ts`, `hooks/useMainConditions.ts`, `lib/analysisCache.ts` and directly relevant tests. Backend base is `backend/src/main/java/com/bitman/justbuy/`. Do not edit KisApiService, the orchestrator/provider classes or AnalysisService/PrecomputeScheduler.

## Required behavior

1. Regression: capture 10000, close 9700, scanner change +5.21% must report -3% and never hit a 521 target. Price strings reject percentages, NaN/infinity and malformed units; absent target stays absent, not defaulted.
2. Home summary uses measured, completed track records only. No completed records means 0 measured records and unavailable average/win rate; no +6% expected-gain substitution. Client labels reflect this and do not imply expected gains are verified results.
3. Preserve first capture price together with first capture time across subsequent scans (10000 at 10:00 remains paired at 10:03/12000). Retain separate current price.
4. 1/3/5-day observations use trading days and each historical date's available price, not current quote for every missing period. No future/reconstructed observation is marked completed when its date's data is absent. Preserve existing DB columns; no production migration/repair.
5. AI score does not originate deterministic rule score; any change to scanner score must retain a clear measured rule baseline. Coordinate verifier changes with the AI worker.
6. Home rows and timestamp derive from the same latest response; manual endpoint rows must not permanently override later refreshes.
7. Analysis request generation/cancellation prevents a closed or older request from overwriting a newer request's result/error/loading state.
8. Cached analysis recalculates freshness from its actual timestamp/server mode policy. Cached live results must not remain fresh solely because they were fresh when saved.

## Verification

Write behavioral regressions first and run to confirm the intended failures. Coordinate Gradle with root before running. No real API/DB calls. Run the related unit slice and frontend affected tests; report commands, red/green evidence, exact files, unresolved integration needs. Do not commit or deploy. Root handles full gate and independent review.
