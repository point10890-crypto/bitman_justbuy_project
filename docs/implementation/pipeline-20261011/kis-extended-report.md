# KIS extended evidence implementation report

Status: implemented; selective fixture gate and final full extension Gate B passed. This worker made no actual provider request, key/member data access, deployment or commit.

## Red and green

The first compile attempt exposed a Mockito `exchange` overload matcher inferred as URI. The matcher was explicitly typed String, then compilation succeeded. Eight KisExtendedEvidenceTest tests ran: disabled/no-provider work passed, and seven intended new behaviors failed against the additive stubs. The same batch verified ECOS16, AdminSource3, AdminRefreshWorkflow9 and AdminRefreshAsync2 with zero failures/errors/skips.

After implementation, the following coordinated selective gate passed 47/47 with zero failures/errors/skips:

```
./gradlew test --tests com.bitman.justbuy.service.KisExtendedEvidenceTest --tests com.bitman.justbuy.service.EcosMarketContextIntegrityTest --tests com.bitman.justbuy.ai.SourceEvidenceIntegrationTest --tests com.bitman.justbuy.controller.AdminSourceReadinessTest --tests com.bitman.justbuy.service.KisRankingIntegrityTest --tests com.bitman.justbuy.service.KisApiRetryTest --console=plain
```

Counts: KIS extension 8, ECOS 19, source integration 4, admin readiness 3, existing KIS ranking 10 and retry 3. All requests in tests are stubbed.

Final coordinator Gate B: `./gradlew test --console=plain` completed BUILD SUCCESSFUL. Independent parsing of Gradle XML confirmed 73 backend classes / 472 tests with zero failures/errors/skips. The coordinator also reported 19 frontend test files / 164 passing tests and a passing production build (`index-C06jIWM-.js`). Local JAR/UI verification and deployment remain coordinator-owned.

## Implementation

- New service `KisExtendedEvidenceService` checks the default-false enabled flag before any provider/token work, accepts five distinct deterministic six-digit candidates, and enforces an eight-second whole-batch budget with interrupt checks between requests. Public `fetchFormattedExtendedEvidence(List<String>)` is used by the coordinator's pre-Round-1 enrichment integration; source text cannot create new candidate membership.
- `KisApiService` adds minute/program raw helpers using official endpoint/TR/parameters and rejects missing/nonzero provider success markers. Response parsing is bounded to 262144 characters and 30 rows, with no extra pagination/retries. Status distinguishes malformed input/provider response, token/config absence, interruption and budget exhaustion.
- `KisExtendedEvidenceConfig` supplies a dedicated one-second connect/read client. The existing three-argument KisApiService constructor and primary AI client remain compatible. Shared token refresh now has timed lock admission for the bounded helper; normal paths retain their original client. Token failures no longer log provider bodies or raw exceptions.
- `KisRestThrottle` applies one shared 120ms request-start spacing to all KisApiService REST requests, including token refresh. It preserves interruption and rejects expired extension admission before HTTP.
- Minute facts use only current-day validated date/time, positive integral OHLC/volume, coherent OHLC bounds and completed-minute rows. Duplicates contribute once; the most recent thirty rows define explicit coverage. Data older than five minutes or captured over two minutes ago remains unavailable. The displayed metric is a recent completed-minute close/volume weighted average, expressly not full-session VWAP. Actual source date/time and retrieval fetchedAt are separate.
- Program facts preserve provider bsop_hour and signed net quantity/amount. Official check output omits business date and money unit: status is DATE_UNKNOWN, observationDate and amountUnit remain UNKNOWN, with a note that current-session freshness and rule points cannot be inferred. No provider time is converted into an invented business date or release timestamp.

Files: `service/KisApiService.java`, `service/KisExtendedEvidenceService.java`, `service/KisRestThrottle.java`, `config/KisExtendedEvidenceConfig.java`, `test/service/KisExtendedEvidenceTest.java`. The coordinator owns YAML/env flags and orchestrator integration. The default is `bitman.kis.extended-evidence.enabled=false` (`BITMAN_KIS_EXTENDED_EVIDENCE_ENABLED=false` in coordinator YAML), budget 8000ms.

## Sources and limits

Official contracts and design links are in [kis-extended-design.md](kis-extended-design.md). The new feature remains disabled while commercial/third-party KIS rights require checking. No rule scores, order paths, selected candidate universe or final quote/publication gates were changed by extended evidence. Program date/unit uncertainty means even successful transport produces partial evidence. An in-flight HTTP request can consume its one-second connection plus one-second read timeout after the batch boundary; later requests are blocked and expired data is not published.

Independent operations review found no concrete blocker in caps/dedup, endpoint/TR fields, short client/shared throttle/token lock, row/date/OHLC/volume/staleness/identity checks or secret-safe statuses. Program date/unit uncertainty and the last-request soft timeout boundary remain disclosed above. Separate review of the coordinator's source integration/ECOS context found candidate membership remains unchanged by appended evidence; observation-period/release/capture labels and secret-safe partial/error handling are preserved.
