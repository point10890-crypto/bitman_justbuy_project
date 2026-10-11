# DART event evidence implementation

Status: complete. Root's full Gate B passes 472 backend tests across 73 classes and 164 frontend tests across 19 files; no backend failures/errors/skips. The backend XML confirms all 45 DART cases and four Admin readiness cases pass. The original 37 DART cases passed before the final review fixes, and eight additional review regressions failed for their intended behaviors before being fixed. No provider call, real key access or existing production cache-file read was performed by this worker.

## Behavior and official contracts

`DartEventEvidenceService` adds bounded selected-candidate evidence for buyback, convertible bonds and rights issues. The field definitions follow the official [buyback](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS005&apiId=2020038), [convertible bond](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS005&apiId=2020033) and [rights issue](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS005&apiId=2020023) guides. Amounts and share counts accept finite nonnegative integer strings with proper comma grouping; percentage fields accept finite nonnegative decimals within 100. Dates and bounded text are retained separately; a percentage cannot become a won amount.

The service selects at most five distinct six-digit input codes in input order and uses only an eight-digit cached corp mapping. Each stock has at most three structured calls, one disclosure-list call and two selected original-document calls. A 30-day search window, list page 1 with at most 20 rows and a 20-row cap for each structured endpoint are stated in the prompt. `EMPTY` means no evidence in the inspected scope. Additional list pages/count above 20, row caps or original-document caps are reported as partial coverage and do not trigger more requests or a healthy cache entry. [Disclosure-list contract](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001).

Structured receipts must have fourteen digits and the expected corp identity; any supplied stock code must also match. List metadata requires the selected stock/corp, valid receipt and a nonfuture filing date within the query window. A matching list row provides the actual filing date and correction/withdrawal flags. A receipt's prefix is never used to invent a filing date. Missing metadata remains `UNKNOWN`; a future or contradictory matched row cannot certify an event. Capture instants stay separate from filing/event dates and a cache hit retains the original stock evidence's capture time.

Supply-contract and shareholder report titles select an original document to inspect; they never prove a numeric amount or percentage. `document.xml` is read as an in-memory ZIP using the [official original-document contract](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019003). The parser accepts only a uniquely associated exact label and one value cell for contract amount/period or ownership percentage/change. Duplicate/conflicting rows, multiple value cells and unsupported structures remain explicit unknown fields with the receipt viewer link. Wrong units, embedded/repeated suffixes and nonfinite values cannot become facts. Optional suffixes must be the field's correct end unit (`원` for amount, `%` for ratio).

The formatter includes receipt URLs, stock/corp identity, actual capture times, known filing dates, units, amendment state, unavailable reasons and unknown fields. It explicitly warns that amended/withdrawn/unknown evidence is not an established positive catalyst and cannot automatically increase scores. Root integrates `DartApiService.fetchFormattedEventEvidence(selectedStockCodes)` into the existing deterministic selected-candidate context; this worker does not change candidate selection, prices or publication validation.

## Bounds, configuration and compatibility

- `bitman.dart.event-evidence.enabled`, backed by `BITMAN_DART_EVENT_EVIDENCE_ENABLED`, defaults to false. Disabled and missing-key states perform zero mapper/network calls. The nested configuration property now matches application YAML and Admin readiness.
- `DartEventEvidenceConfig` supplies a dedicated RestTemplate with 2-second connect and 3-second read timeouts. A monotonic 10-second whole-batch budget is checked before/after calls, during reads and around original parsing. Deadline exhaustion or interruption stops further calls. The request already in flight can finish after the batch deadline within its HTTP timeout; this is not a hard thread-kill deadline.
- JSON bodies are capped at 64 KiB; original ZIPs at 1 MiB compressed, each entry at 1 MiB expanded, all entries at 4 MiB expanded and eight entries. The parser disables DOCTYPE, external general/parameter entities, external schemas/DTDs and XInclude; enables secure processing; rejects traversal/absolute/Windows-style archive paths; and extracts no files.
- Only complete valid results and normal empty responses are cached for 15 minutes, at most 128 stocks. Provider `013` is normal empty; `010/011/012` authentication and `020` quota remain errors, stop the batch and are not cached as empty. Partial, malformed, oversized, transport, budget and unknown-original outcomes also do not become healthy cache entries.
- `DartCorpCodeMapper.getCachedCorpCode` uses a valid existing memory mapping or a regular public JSON cache file at most 4 MiB and under seven days old, with strict IDs and no future file timestamp. It never invokes lazy downloads or waits on the legacy download lock. A refreshed file replaces the old map snapshot; removed expired codes cannot inherit new freshness. Missing/stale/malformed/oversized mapping yields explicit `MISSING_CORP_MAPPING`/unavailable evidence.
- Existing DartApiService's four-argument constructor and existing mapper/legacy API methods remain usable. The new five-argument Spring constructor delegates to the event adapter; the old constructor's event method returns an empty string. All existing DartApiService/DartCorpCodeMapper exception logs now use only exception class/status, never raw messages, key-bearing URLs, causes or stack traces.

The new event path deliberately does not prepare/download corp mappings. An operator must use the existing approved pipeline to prepare a current public mapping in a separate future operation before enabling this feature. Tests use only TempDir mappings or mocks, not the workspace's real data cache. Existing legacy DART lookup can still download when separately called; the new path does not use it.

## Owned files

Production:

- `backend/src/main/java/com/bitman/justbuy/config/DartEventEvidenceConfig.java`
- `backend/src/main/java/com/bitman/justbuy/service/DartEventEvidenceService.java`
- `backend/src/main/java/com/bitman/justbuy/service/DartOriginalDocumentParser.java`
- `backend/src/main/java/com/bitman/justbuy/service/DartApiService.java`
- `backend/src/main/java/com/bitman/justbuy/service/DartCorpCodeMapper.java` (authorized additive bounded-cache method and legacy log masking)

Fixtures:

- `backend/src/test/java/com/bitman/justbuy/service/DartEventEvidenceServiceTest.java` — 22 cases
- `backend/src/test/java/com/bitman/justbuy/service/DartOriginalDocumentParserTest.java` — 15 cases
- `backend/src/test/java/com/bitman/justbuy/service/DartEventIntegrationTest.java` — 3 cases
- `backend/src/test/java/com/bitman/justbuy/service/DartCorpCodeCacheIntegrityTest.java` — 5 cases

## Test-first evidence and commands

Every shell command used `& 'C:/Program Files/Git/bin/bash.exe' -lc '<literal Bash script>'`, with `PROJECT="/c/bitman_justbuy_project"`, `PYTHON="$PROJECT/.venv/Scripts/python.exe"`, `FRONTEND="$PROJECT/frontend"`, then `cd "$PROJECT"`. Gradle additionally exports `JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.10.7-hotspot"`, prepends its `bin` to PATH and changes to `"$PROJECT/backend"`. Root reserved every build slot; none ran concurrently with another agent's Gradle build. All network boundaries are mocks or synthetic in-memory responses.

1. Original service/parser RED: `./gradlew test --tests "*DartEventEvidenceServiceTest" --tests "*DartOriginalDocumentParserTest" --offline`. Exit 1; 24 tests/24 intended failures, clean compile. `dart-event-red.log`.
2. Expanded DART and root integration RED: `./gradlew test --tests "*DartEventEvidenceServiceTest" --tests "*DartOriginalDocumentParserTest" --tests "*DartEventIntegrationTest" --tests "*DartCorpCodeCacheIntegrityTest" --tests "*EcosMarketContextIntegrityTest" --tests "*SourceEvidenceIntegrationTest" --offline`. Exit 1; 60 tests/41 failures/0 errors. XML: DART Event 20/20 failed, Parser 11/11, Integration 2/2, Mapper 4/3; ECOS 19/3 and Source integration 4/2 were separately owned by root. `dart-event-extra-red.log`. All 36 DART behavior failures were confirmed before implementation; the malformed-cache stub case already passed.
3. Review RED after original implementation: `./gradlew test --tests "*DartEventEvidenceServiceTest" --tests "*DartOriginalDocumentParserTest" --tests "*DartEventIntegrationTest" --tests "*DartCorpCodeCacheIntegrityTest" --tests "*AdminSourceReadinessTest" --offline`. Exit 1; 49 tests/8 intended failures/0 errors. XML: Event 22/2 failed, Parser 15/4, Integration 3/1, Mapper 5/1, Admin 4/0. All original 37 DART cases and all four Admin cases passed. Only the eight new wrong-unit/cache replacement/configuration/coverage regressions failed. `dart-event-review-red.log`. Their production fixes followed the actual failures.
4. Final GREEN: root's serialized full `./gradlew test --offline` Gate B produced new XML at 2026-10-11 13:28 UTC. This worker independently read the four owned suite records: Event 22/0 failures, Parser 15/0, Integration 3/0, Mapper 5/0; 45/45, no errors or skips. Admin readiness also passed 4/4. Root confirmed `/tmp/bitman-backend-gate-b.log` reports `BUILD SUCCESSFUL`: 73 classes/472 tests/0 failures/0 errors/0 skips. Root's `/tmp/bitman-frontend-gate-b.log` confirms 19 files/164 passing tests, followed by a successful frontend build (`index-C06jIWM-.js`). No additional worker build was needed. Local JAR/UI/deployment validation remains root-owned.
5. Owned tracked-file `git diff --check` passed. A targeted source scan found no remaining `getMessage()` exception log or old flat feature-property name in the DART owned classes; the only `getCorpCode` calls belong to existing legacy methods, not the new bounded event path.

Independent AI and operations reviewers inspected the final source without editing owned files. Their reported unit/configuration/coverage/cache issues were reproduced and fixed. AI's final read-only recheck found no remaining blocker; operations' initial review found no additional blocker beyond wrong units.

## Remaining limits

The adapter is disabled by default and fixture-verified; this report does not claim live provider validation or runtime feature activation. Original XML formats are not standardized by this implementation: unsupported or ambiguous real layouts will produce unknowns instead of invented numbers. The bounded scope can omit older filings, later list pages or additional original documents; that limit is explicit in context. No historical data migration, actual key creation, paid call, production data/member access, order, commit, push or deployment was performed by this worker.
