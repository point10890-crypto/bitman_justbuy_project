# Public event research verification — 2026-10-14

## Scope and acceptance

The user authorized sequential implementation, testing, commit/push, MiniPC and Pages deployment, and exact live-app verification. Development uses the managed `codex/event-research` worktree based on `e5f353b`; unrelated primary changes are preserved.

Delivered code connects real DART supply-contract originals and named customer relationships, explicit profit scenarios, fact-bound DeepSeek evidence challenges, KIS/Kiwoom market context, durable future outcome capture and fixed-policy comparison, and KIND application/execution evidence to `/research/events`. Existing pick rankings are preserved until measured evidence justifies a separately reviewed change.

## Regression evidence

- Real DART originals are preserved unchanged with source URLs and SHA-256 metadata. Amendment-in-body, unknown prior amount, cancelled contracts, subsidiaries and undetermined periods are distinguished from new incremental orders.
- Calculator tests cover signed deltas, interval arithmetic, unsupported units, future financial facts and missing cash inputs. Missing tax/conversion/working-capital/capex does not become zero cash flow.
- AI tests reject different subjects, values, periods, units and source claims; timeouts/refusal/invalid output retain rule-only status.
- Store/pipeline regressions cover failed final publication, orphan cases, crash recovery of uncommitted evaluation anchors, concurrent refresh, rate-limit retention, archived-case maturation, coverage failures and exact paid/admin guards.
- Review reproduced reset evaluation cursors, stale initial price reuse, failed AI stage reuse and incomplete relationship tombstones. Additional regression verification is recorded below after the final suite.
- Frontend covers actual provider shapes, mock-server labelling, independent current-market context, exact decimal display, pending/unmeasurable/gross-only outcomes, comparison percent units, source URLs, expired subscriptions and explicit admin collection.

## Actual public source probe

`KindBuybackSource.fetchRecent()` ran against official KIND at `2026-10-14T01:06:31Z`, completing in approximately six seconds: `PARTIAL`, 80 rows, 40 KOSPI and 40 KOSDAQ. The disclosed limits were `BUYBACK_ROW_LIMIT` and `HISTORY_WINDOW_MAX_THREE_REPORT_DAYS`.

Actual KOSDAQ rows now have non-null historical fulfilment when the same-date application denominator exists. KOSPI thousands-of-won and KOSDAQ won remain distinct. Three unchanged official HTML documents and their origin hashes are stored alongside the DART fixtures. These fixtures validate collection/parsing; synthetic test inputs remain exception/hostile-case tests.

## Limits

This release measures evidence and future reference-price outcomes; it does not establish profits, guaranteed demand/support, realized fills or a trading advantage. Historical issuer margins are assumptions for future contract scenarios. Cash, unverified costs and missing benchmarks remain null. Relationships without verified demand allocation do not receive fabricated earnings estimates. KIND history is limited to three report days. Calendar coverage beyond 2026 requires official confirmation.

## Final verification

Local verification passed before commit:

- Backend: `gradlew test bootJar --no-daemon`, 123 test classes, **1,173 tests: 1,172 passed, zero failures/errors, one existing Windows symlink-privilege skip**. Event/controller tests account for 120 tests. The complete run finished in 1m58s after the runtime format correction.
- Frontend: **24 files / 244 tests passed**, TypeScript and Vite build passed; JS `index-BbECemln.js`, CSS `index-YeJYe1Tn.css`.
- Exact actual-format KIND regressions: 5 tests passed. Supply graph regression cycles reproduced changed-product/unknown-customer tombstones (16 tests / 2 failures), then late historical re-discovery (17 tests / 1 failure); the final full suite passed all 17.
- Pipeline recovery: 15 tests passed; Store recovery/capacity: 14 tests passed. No live keys or member databases are fixtures.
- Independent final review found no remaining release-blocking correctness/security issues. `git diff --check` passed.

Commit/push, MiniPC/Pages deployment and exact live DOM/runtime checks are the remaining final gates. Their evidence will be appended after execution.

## Runtime format correction

The first deployed collection correctly failed closed: all selected OpenDART originals were rejected. A same-MiniPC Java probe confirmed a valid key, successful listings and an actual ZIP with an XML filename containing UTF-8 HTML while declaring EUC-KR. A captured API ZIP was added unchanged; the regression first failed, then verified exact contract/customer/amount/dates after narrow root detection and strict decoding. Genuine XML failures never retry through HTML; DTD/XXE and archive bounds remain enforced. The complete backend suite above was rerun after this correction. Official raw HTML fixtures are marked binary in Git to preserve captured bytes and origin hashes.
