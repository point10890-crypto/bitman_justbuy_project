# AI and publication repair report

Status: repair Gate A and extension Gate B passed. Gate A verified 65 backend classes / 392 tests plus 162 frontend tests and a production build. Final Gate B verified 73 backend classes / 472 tests with zero failures/errors/skips, plus 19 frontend test files / 164 tests and a production build. This includes the authorized AI repair and KIS/ECOS/DART evidence extensions. This worker made no live AI/KIS/DART requests, credentials/member DB reads, commits, push or deployment.

## Reproduction and commands

All shell commands use Git Bash through PowerShell, declare `PROJECT=/c/bitman_justbuy_project`, `PYTHON=$PROJECT/.venv/Scripts/python.exe`, `FRONTEND=$PROJECT/frontend`, and enter `$PROJECT` first. Python scratch rewrites run with `PYTHONIOENCODING=utf-8` and the project venv.

Initial regression command, from backend:

```
./gradlew test --tests '*PublicationIntegrityTest' --tests '*ProviderIntegrityTest' --tests '*StructuredAnalysisParserIntegrityTest' --tests '*DeepSeekConditionVerifierTest' --tests '*DeepSeekEndpointIntegrityTest' --tests '*RuntimeAiConfigIntegrityTest' --console=plain
```

RED: compilation succeeded; 27 tests ran, 24 failed. This broad publication glob also selected 6 expected operations regressions. My 18 failures reproduced candidate and quote leakage, duplicate voting, incomplete/empty provider acceptance, lost multipart/citations/usage, invalid structured rows, incomplete/outside/contradictory verifier verdicts, arbitrary direct endpoint JSON and oversized input acceptance, and untrusted runtime URL acceptance.

Coordinator then ran exact ProviderIntegrityTest and PublicationIntegrityTest alongside the KIS/price slice. Additional RED: ProviderIntegrityTest 9 tests/2 failures (`openAiIncompleteAnalysisKeepsModelAndTokens`, `openAiSynthesisRequiresStopAndKeepsSpentTokens`); PublicationIntegrityTest 5 tests/1 failure (`interruptedOrchestrationStopsInsteadOfPublishingLateResults`). Direct quota RED and legacy Grok fixture compatibility failure were observed before the fixes. The following exact-selective command then passed 79/79:

```
./gradlew test --tests com.bitman.justbuy.ai.PublicationIntegrityTest --tests com.bitman.justbuy.ai.agent.ProviderIntegrityTest --tests com.bitman.justbuy.util.StructuredAnalysisParserIntegrityTest --tests com.bitman.justbuy.service.DeepSeekConditionVerifierTest --tests com.bitman.justbuy.service.DeepSeekEndpointIntegrityTest --tests com.bitman.justbuy.service.RuntimeAiConfigIntegrityTest --tests com.bitman.justbuy.ai.ConsensusEngineTest --tests com.bitman.justbuy.ai.agent.ChatGptAgentTest --tests com.bitman.justbuy.ai.agent.DeepSeekAgentTest --tests com.bitman.justbuy.ai.agent.GrokAgentTest --console=plain
```

Additional authorized REDs: `verifiedCodeDoesNotAuthorizeAnInventedCompanyName` failed in PublicationIntegrityTest; all 3 KeywordPublicationIntegrityTest tests failed (malformed candidate prices/codes still published, outside free prose publicly leaked, provenance absent); ProviderIntegrityTest 10 tests/1 failure `interruptedProviderCallsKeepTheirCapacityUntilActualHttpExit` proved abandoned synchronous calls could exceed the provider capacity. All were implemented and verified in the coordinator's passing full Gate A run.

Security review exposed public transport exception messages containing provider credential URLs. Exact regression `ProviderIntegrityTest.providerTransportFailuresCannotExposeCredentialUrlsOrResponseBodies` used a synthetic URL/key only and reproduced RED 1/1 failure. After generic error handling, `./gradlew test --tests com.bitman.justbuy.ai.agent.ProviderIntegrityTest --console=plain` passed 11/11 (XML failures/errors zero), also verifying the new capacity boundary. Coordinator then ran `./gradlew test --console=plain`: 65 classes / 392 tests passed with zero failures/errors/skips, including keyword/name and updated legacy error-masking assertions.

Final extension verification: the coordinator's `./gradlew test --console=plain` completed BUILD SUCCESSFUL (`/tmp/bitman-backend-gate-b.log`). This worker independently parsed the final Gradle XML results and confirmed 73 classes / 472 tests, zero failures/errors/skips. The coordinator also verified 19 frontend test files / 164 passing tests and the production build (`index-C06jIWM-.js`). Local JAR/UI verification and deployment remain coordinator-owned follow-up work.

## Changed contracts

- MultiAgentOrchestrator constructs deterministic candidate membership from supplied market/KIS code rows and explicitly queried tool prices, verifies a positive integer quote, and uses that same gated set for StockPick, consensus and all public analysis text. Unquoted/outside rows are excluded. Unvalidated target/stop prices remain `미확인`; no default price is invented.
- Company names also come from the selected deterministic source rows/query name mapping. A model-provided company name cannot replace the verified name for an allowed code. Per-agent public action/reason remains the individual agent's own validated row.
- StockPick adds nullable `candidateSource`, `quoteSource`, `quoteCheckedAt` fields with compatible 7/9 argument constructors. Existing quote adapter source is NAVER_FINANCE. Checked time means retrieval validation time, not exchange observation/trade time.
- AgentResult adds thoughtTokens and totalTokens with compatible 8 argument constructor. Gemini outputTokens includes non-input tokens so input+output retains total usage; visible candidate and thinking distinction is retained by thoughtTokens. Completed status/content gates run before sources are appended.
- Independent agent/code rows are counted once by ConsensusEngine. Structured parser rejects nonnumeric codes, nonnumeric/nonfinite/out-of-range confidence, empty reasons and incomplete/zero/invalid scenario distributions.
- Condition verifier rejects outside codes, unknown verdicts, missing summary/evidence/risk/invalidation, invalid scores and repeated same-code records. An absent verdict returns no AI score to the scanner.
- Direct DeepSeek results require the relevant schema and input-candidate identity. Invalid JSON/Map/schema becomes error and retains usage/model; raw invalid model content is not exposed as a successful result.
- Direct paid endpoints enforce the same 2000-character input/120-character mode bounds as the ordinary analysis controller and share LiveAnalysisQuotaService before provider execution. JSON map input is bounded by serialized length.
- DeepSeek credentials are confined to HTTPS `api.deepseek.com` (optional `/v1`) or a precise explicit HTTPS proxy base URL in `bitman.ai.deepseek-trusted-proxy-urls` (comma separated). Runtime saves and actual requests both enforce this policy. User info, query, fragments, non-443 port and ambiguous paths are rejected. No stored config/keys were inspected.
- Raw orchestrator tracking is removed. Operations owns `TrackRecordService.recordAnalysis(finalResponse)` after successful atomic persistence and final periodic filtering; its final publication helper also removes filtered rows from public content/consensus.
- Orchestrator uses one configurable deadline (`bitman.analysis.agent.timeout-ms`, default 100000) and shutdownNow rather than executor close-wait. Cancellation propagates before later processing. Operations provides the 120-second outer service guard.
- ProviderCallLimiter permits at most 8 synchronous provider calls total and 4 per provider. Capacity is acquired immediately before the actual request and held until that request actually exits, including when caller cancellation cannot stop its HTTP call. Excess calls fail as busy without retries.
- Public provider transport/parsing exceptions use a generic error instead of the raw exception text, preventing credential URL/header/body leakage. Orchestrator future failures log only a safe exception class, without raw message/stack. Completed-response metadata/error paths still retain actual model and spent tokens.
- KeywordConditionAnalysisService applies the same strict price/code boundary to KIS dual-net-buy candidates. Zero verified candidates skip DeepSeek. The paid analyst returns the evidence verdict schema; malformed/free-prose output becomes error with preserved usage, and only candidate-validated summaries, evidence, risks and invalidations enter public content. Published picks include KIS_DOUBLE_NET_BUY candidate source, returned KIS quote source and retrieval checkedAt.

## Files

Owned production files: ai/MultiAgentOrchestrator.java; ai/ConsensusEngine.java; ai/agent/ChatGptAgent.java, DeepSeekAgent.java, GrokAgent.java, GeminiAgent.java, OpenAiCompatibleAgent.java, ProviderEndpointPolicy.java, ProviderCallLimiter.java; dto/AgentResult.java; util/StructuredAnalysisParser.java; service/DeepSeekConditionVerifier.java; service/DeepSeekEndpointService.java; controller/DeepSeekAnalysisController.java.

Coordinator-authorized additive scope: dto/StockPick.java, service/RuntimeAiConfigService.java, service/KeywordConditionAnalysisService.java.

Tests: ai/PublicationIntegrityTest.java; ai/agent/ProviderIntegrityTest.java; ai/agent/DeepSeekAgentTest.java, GrokAgentTest.java, ChatGptAgentTest.java (realistic completion fixtures); util/StructuredAnalysisParserIntegrityTest.java; service/DeepSeekConditionVerifierTest.java, DeepSeekEndpointIntegrityTest.java, RuntimeAiConfigIntegrityTest.java, KeywordPublicationIntegrityTest.java.

## Sources and limitations

Official response contracts checked: [xAI Responses](https://docs.x.ai/developers/rest-api-reference/inference/responses), [Gemini generateContent](https://ai.google.dev/api/generate-content?hl=en), [DeepSeek chat completions](https://api-docs.deepseek.com/api/create-chat-completion/). Tests use stubs only.

Public content now derives from validated rows instead of arbitrary model prose. This intentionally prevents raw structured/model rows rejected by publication gates from remaining actionable in displayed analysis. Provider success is distinct from an EMPTY final publication; operations normalizes the final publication status. Candidate extraction currently consumes existing formatted tool output and can become typed when the market adapter exposes typed candidates. Naver quote adapter currently supplies retrieval validation timing, not exchange trade freshness.

Read-only cross-review of the later DART extension found configuration binding, bounded coverage and expired-mapping replacement defects; the owning worker reproduced these before fixing them. Final recheck confirms the nested enabled flag matches YAML/Admin, formatted facts explicitly state the 30-day/list-page-20/structured-20/original-2 scope, unqueried pages produce uncached PARTIAL evidence, removed mappings cannot be revived by an expired snapshot, and original-document units are field-specific. No remaining blocker was found in filing dates, corrections, receipt bounds or source-coverage labels.
