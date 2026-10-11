# Job, persistence and operation integrity requirements

Read audit P2-11..17 and design-gap notes for final publication, startup cost and execution budget first.

## Owned files

`service/AnalysisService.java`, `PrecomputeScheduler.java`, `AsyncJobManager.java`, `TelegramNotifier.java`, `condition/run/*`, `controller/AnalysisController.java`, `AdminController.java`, `MonitorController.java`, relevant configuration/test files and admin refresh frontend API/polling if needed. Do not edit MultiAgentOrchestrator/DeepSeek controllers, scanners/main-condition, KIS/DART adapters, HomePage/useAnalysis.

## Required behavior

1. 0 successful agents/0 valid picks is a failed run, never COMPLETE or scheduler success. Partial/empty legitimate outcomes stay distinguishable. Preserve last known-good cache and timestamp on failure.
2. Atomic JSON persistence (temporary sibling + replace) and explicit IOException propagation/status. Do not report live cache or successful run if required persistence failed. No production data read/overwrite in tests.
3. Jobs record owner ID and polling validates matching principal/admin policy. Knowing another member's jobId cannot return result; deny without leaking result. Coordinate direct DeepSeek limits with AI worker.
4. Monitor health server cache is short (<=30 seconds) or disabled, not shared four-hour quote/analysis cache.
5. Telegram events distinguish confirmed sent, disabled/skipped and failed. Do not emit NOTIFIED if no send occurred. Tests are mocked; no actual messages. Do not change channels/recipients.
6. Admin refresh returns a jobId promptly and supports owner/admin polling using existing frontend contract; avoid a four-mode long synchronous request.
7. Initial missing-cache refresh follows trading-day/time/cost policy so a Sunday/night restart does not silently run paid models. Do not change Task Scheduler.
8. Track records are saved only for the finalized published set, after deterministic gates and periodic exclusions. Live and periodic contracts must be consistent; coordinate orchestrator raw tracking removal.
9. Bound execution and make failures diagnosable; implement focused recovery/status improvements possible within current architecture. Do not create an unreviewed durable job subsystem or new DB migration. Report larger remaining design gaps explicitly.

## Verification

Behavioral regressions first (failed run, failed write, other principal, disabled notification, admin job). Coordinate Gradle with root. No real keys/API/DB/messages. Report red/green, files and contracts. No commit/push/deploy/subagents.
