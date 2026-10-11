# DART event evidence implementation plan

> Agent execution: implement in this worker after root confirms repair Gate A, using test-driven development and root-serialized offline Gradle runs. Do not spawn agents, commit, push, deploy or call a live provider.

**Goal:** append bounded, dated, source-linked DART event facts for up to five already selected stocks without changing candidate selection, scores or verified prices.

**Architecture:** a separate disabled-default event service owns its short-timeout client, typed snapshot, bounded memory cache, request budget and hardened original-document parser. `DartApiService` exposes additive batch formatting; root owns orchestrator context integration and application flags. Existing constructor calls remain compatible.

**Tech stack:** Java 21, Spring RestTemplate, Jackson, JDK XML/ZIP parsers, JUnit/Mockito/MockRestServiceServer. No new package/dependency.

**Spec:** `docs/implementation/pipeline-20261011/dart-event-design.md` and `expansion-brief.md`.

## Constraints

- `BITMAN_DART_EVENT_EVIDENCE_ENABLED=false`; disabled or missing key performs zero mapper/provider requests.
- At most five distinct six-digit selected stocks; eight-digit corp code; fourteen-digit receipt IDs.
- Three structured + one list request + at most two selected original documents per stock. No pagination or fabricated dedicated contract/shareholder endpoint.
- Dedicated connect 2s/read 3s client, monotonic 10s batch deadline; before/after each request and during reads check deadline/interruption and stop additional calls.
- JSON response 64 KiB; compressed original 1 MiB; expanded original 4 MiB; XML entry 1 MiB; at most eight archive entries. No disk extraction.
- Valid results/normal empty cache 15 minutes, maximum 128 stock entries. Auth/quota/provider/parse/budget errors do not enter healthy cache.
- No URLs, keys, raw provider/exception messages or sensitive stack traces in logs. Error category and exception class only.

## Review focus

1. Mapper returns wrong/malformed corp identity: skip provider requests with explicit identity error.
2. Structured result contains another corp/receipt or future filing date: reject mismatched facts, never invent an observation timestamp.
3. A corrected/withdrawn filing resembles positive news: preserve warning/unknown amendment state, prohibit an unqualified positive interpretation.
4. Repeated/ambiguous XML amount or shareholder fields: link remains available, numeric field stays unknown.
5. Deadline/interruption/cache reuse: no further call after budget expires, no partially failed result cached as healthy empty.

## Task 1: bounded structured event service

Files: create `config/DartEventEvidenceConfig.java`, `service/DartEventEvidenceService.java`, `service/DartEventEvidenceServiceTest.java` under the existing backend source/test base paths.

Public interface:

```java
public EvidenceSnapshot fetchEvidence(List<String> selectedStockCodes);
public String fetchFormattedEvidence(List<String> selectedStockCodes);
public record EvidenceSnapshot(String status, String capturedAt, List<StockEvidence> stocks) {}
public record StockEvidence(String stockCode, String corpCode, String status,
    String capturedAt, List<EventFact> events, List<String> issues) {}
public record EventFact(String type, String receiptNo, String filingDate,
    String title, String sourceUrl, String amendmentStatus,
    Map<String, String> facts, List<String> unknownFields) {}
```

The package fixture constructor injects `Clock` and a monotonic `LongSupplier` in addition to the production dependencies. Production uses UTC capture instants and the Korea date for bounded searches. Store receipt/list/event dates separately; absent filing date remains unknown.

- [x] Write fixtures for disabled/missing-key zero requests; five-stock/dedup cap; buyback/CB/rights endpoint fields; wrong corp/receipt/price-like malformed amount; status `013` versus `010/011/012/020`; malformed response; 15-minute healthy cache/error retry; monotonic expiry/interruption.
- [x] Add compile-stable minimal interface returning disabled/empty snapshots, then run the service/parser selectors in a root-reserved RED slot. Assert intended behavioral failures rather than dependency/setup failures.
- [x] Implement exact endpoint mapping and bounded request extraction. Compare provided identities. Preserve raw documented facts and units; numeric unknowns stay unknown. Consume one list response to link structured receipts to actual filing dates and correction metadata.
- [x] Verify the same selector in root's full GREEN gate and record XML counts/commands/output.

## Task 2: hardened original-document facts

Files: add `service/DartOriginalDocumentParser.java` and `service/DartOriginalDocumentParserTest.java`; update the event service's selected list/document path.

Package interface:

```java
static ParsedFacts parse(byte[] compressedZip, String eventType);
record ParsedFacts(Map<String, String> facts, List<String> unknownFields) {}
```

- [x] Construct in-memory synthetic ZIP/XML fixtures for a unique explicit `계약금액(원)`/`계약기간`, unique `보유비율(%)`, duplicate/conflicting numeric rows, correction flags, invalid IDs/dates, more than two selected originals, bad ZIP path/absolute path, too many/large entries, inflated-entry limits, DOCTYPE/entities and malformed/non-ZIP input. Add wrong-unit fixtures after independent review, before their fix.
- [x] Run both event and parser selectors for RED before implementation. Keep fixture data synthetic and annotate the official contract URL.
- [x] Parse with secure JDK settings and reject unsafe XML atomically. Only uniquely associated exact label/value rows yield facts. Other XML structures preserve receipt/link and explicit unknown fields. Validate bounds while reading; no path is extracted.
- [x] Verify both selectors in root's full GREEN gate and record the XML result. Check deadline/interruption between selected documents in source review.

## Task 3: additive integration and log redaction

Files: update `service/DartApiService.java`; add `service/DartEventIntegrationTest.java`. Root updates `ai/MultiAgentOrchestrator.java` and `application.yml` independently.

Additive interface:

```java
public String fetchFormattedEventEvidence(List<String> selectedStockCodes);
```

- [x] Write tests for delegation of the selected list, disabled defaults, unchanged existing constructor, explicit unavailable/partial/amended context, and no implication that a report title proves a numeric amount. Add nested property and exact inspected-scope/pagination fixtures after independent review.
- [x] Capture logs using a mocked exception whose text contains a dummy `crtfc_key` URL; assert existing DART formatting/financial/disclosure/company error paths never log the URL/dummy key/raw message.
- [x] Run integration RED in a root-reserved slot, then add optional event-service constructor injection with the old constructor retained. Replace every DartApiService raw exception-message log with its exception class only. Add authorized bounded cache-only corp lookup with TempDir fixtures and stale-map replacement RED/GREEN.
- [x] Verify the complete DART slice GREEN, notify root that its context integration can compile against the public batch method, and record remaining unknown XML structures/disabled runtime status in `dart-event-report.md`.

## Verification wrapper

Every shell invocation declares `PROJECT="/c/bitman_justbuy_project"`, `PYTHON="$PROJECT/.venv/Scripts/python.exe"`, `FRONTEND="$PROJECT/frontend"`, then changes to `"$PROJECT"`. Gradle declares JDK21 at `/c/Program Files/Microsoft/jdk-21.0.10.7-hotspot`, uses `--offline`, and runs only in a root-reserved shared build slot. Full gates remain root-owned.
