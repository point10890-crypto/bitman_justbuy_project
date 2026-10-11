# KIS Source Expansion Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement the independent source adapters and integrate in the parent agent. Steps use checkbox syntax for tracking.

**Goal:** Connect the five audited source categories to verified condition analysis and the existing Telegram schedule, with implementation and deployment targeted for 2026-10-12 09:00 KST.

**Architecture:** A closed read-only gateway shares existing KIS authentication, short HTTP transport and global throttling. Independent identity/candidate, research evidence and realtime services provide explicit source status and original observation metadata. The orchestrator and keyword search consume those services, while the existing scheduler delivers the resulting analysis through the current Telegram destinations.

**Tech Stack:** Java 21, Spring Boot, Jackson, JDK WebSocket, JUnit/Mockito, existing React/Vite.

**Spec:** docs/KIS_SOURCE_INTEGRATION_AUDIT_2026_10_12.md and the user's approval to connect categories 1 through 5 and activate the existing Telegram workflow.

## Global Constraints

- Preserve existing member DB, secrets, unrelated generated data and source edits.
- No trading/order/account adapters. Gateway path and TR must match a closed query allowlist.
- New production KIS sources require confirmed information-use rights. Missing rights or HTS setup stays explicit, never a successful empty response.
- Never log credentials, approval keys, transport error bodies or HTS ID.
- No invented prices, identifiers, timestamps or zero substitutions for missing facts.
- Keep 08:50, 11:50 and 14:50 KST weekday delivery, existing calendar and current destinations.
- Backend changes require full tests, deployment script, remote/local JAR equality and public health verification; frontend changes require Pages and live DOM checks.

## Review Focus

- Provider failure with apparently valid rows must remain unavailable.
- ZIP traversal, invalid fixed-width identity or partial master download must not replace a validated universe.
- Missing/short technical coverage must not satisfy a moving-average or volume-ratio filter.
- Undated intraday estimates and stale/out-of-order realtime frames must not become current confirmed facts.
- Delivery retries or source failures must not create duplicate or misleading Telegram messages.

## Task 1 Shared gateway

- [x] Add KisSourceGateway, gated by requested and licensed flags; readiness reports booleans only.
- [x] Add fetchVerifiedReadOnly and WS approval to KisApiService, sharing private credentials and throttle.
- [x] Verify rights-gate, allowed query, wrong-TR/order blocking, provider failure and documented HTS empty response tests.

Interface: `Snapshot fetch(String path,String trId,Map<String,String> params,long deadlineNanos)` with `Snapshot(status,fetchedAt,JsonNode body)`, `boolean isEnabled()`, `String htsId()`, `String wsApproval(long deadlineNanos)`.

## Task 2 Identity and candidates

- [x] StockUniverseService validates official KOSPI/KOSDAQ/theme/sector ZIP inputs using TLS, bounded extraction and CP949 fields, then swaps a full valid cache.
- [x] KisCandidateSourceService connects HTS title/result and 30-symbol multiprice requests with row identity and field verification.
- [x] Use snapshot and lookup in KeywordSearchDataService; attach candidate identities to orchestrator.
- [x] Verify traversal, duplicate IDs, atomically preserved prior cache, unknown quotes, HTS 100-row cap and no fabricated observation time.

Interfaces: `Optional<Stock> find(String code)`, `List<Stock> stocks()`, `void refresh()`, `String status()`; candidate `KeywordSearchDataService.Snapshot snapshot()`, `Quote lookup(String code)`, `List<String> candidates()`, `Map<String,Object> readiness()`.

## Task 3 Technical and fundamental evidence

- [x] KisResearchEvidenceService validates daily OHLC coverage and computes MA5/20/60, prior-20-day breakout and average-volume ratio.
- [x] Add actual 52-week/PER/PBR quote fields, financial ratios and separately identified intraday investor estimates; short/loan sources retain their own status.
- [x] Attach formatted facts to AI evidence and add supported technical/PER/PBR keyword predicates without silently ignoring unsupported terms.
- [x] Verify duplicate/future dates, short coverage, absent metrics, quarterly periods and undated estimates.

Interfaces: `EvidenceBatch fetchEvidence(List<String> codes)`, `String formattedEvidence(List<String> codes)`, `Map<String,Object> readiness()`; CandidateEvidence exposes stockCode, technical, valuation, financial, investorEstimate, shortSale and loan maps.

## Task 4 Realtime and delivery

- [x] KisRealtimeFeedService subscribes only the bounded watch set to KRX trade frames; handles heartbeat, disconnect and bounded reconnection.
- [x] Verify official field positions, frame batches, wrong identifiers, future/stale/duplicate/reversed timestamps.
- [x] Add whitelisted source coverage footer to Telegram metadata; retain old constructors and payload compatibility.
- [x] Warm sources before the existing schedule; new analysis metadata must survive filtering and persistence.

Interfaces: `void watch(List<String> codes)`, `Map<String,Object> facts(String code)`, `Map<String,Object> readiness()`; AnalysisResponse.Metadata adds sourceExpansion while retaining existing constructors.

## Task 5 Integration and operational gate

- [x] Focused tests followed by full backend suite and frontend build.
- [x] Review every category's actual consumer, not just class existence.
- [ ] Commit/push intended files only, deploy to MiniPC, verify matching JAR and health.
- [ ] Verify configured rights/HTS presence without exposing values and perform permitted bounded live checks.
- [ ] Verify existing cron and actual acknowledged delivery evidence, preserving unconfirmed or future scheduled outcomes as pending.
