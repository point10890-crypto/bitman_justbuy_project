# Agent and publication integrity requirements

Read audit P1-03 and P2-01..06, P2-17, plus shared publication/track-record timing notes and offline fixtures first.

## Owned files

`ai/MultiAgentOrchestrator.java`, `ai/ConsensusEngine.java`, `ai/agent/*`, `dto/AgentResult.java`, `util/StructuredAnalysisParser.java`, `service/DeepSeekConditionVerifier.java`, `service/DeepSeekEndpointService.java`, `controller/DeepSeekAnalysisController.java` and relevant tests. Do not edit scanner/main-condition, AnalysisService/PrecomputeScheduler, KisApiService/Dart adapters.

## Required behavior

1. Candidate membership and verified positive finite price are mandatory publication gates in the shared periodic/live orchestrator. Candidate 005930 does not authorize output 999999. Missing/error quote must remove the pick, never preserve an LLM price. Preserve candidate source and quote provenance. Public analysis text/structured consensus must not keep rejected actionable rows while only JSON picks are filtered.
2. One agent has one vote per stock. A duplicate vote cannot turn 50 into 100 consensus. Independent validated successful voters only.
3. Provider success requires completed nonblank meaningful content. Grok incomplete/empty object, Gemini MAX_TOKENS/SAFETY-only and missing completion indicators must not count as successful. Preserve usage/model identifiers on errors, including DeepSeek used tokens and Gemini total/thought tokens.
4. Grok joins all output_text items and retains supported string citations/annotations. No source text mistaken for analysis.
5. Structured parser validates six-digit codes, finite bounded confidence, nonempty reasons and valid nonzero normalized probability distributions. Invalid fields must reject the structure or row consistently, not leak NaN.
6. Condition verifier rejects unknown verdicts, missing evidence/risk/invalidation, duplicate contradictory same-code records and outside candidate codes. Invalid verification cannot increase a candidate's score.
7. Direct DeepSeek endpoints use the same applicable usage/input limits as ordinary live analysis and validate result schema rather than treating any parsed Map as success. Coordinate AsyncJobManager/AnalysisController policy with operations worker.
8. Do not persist track records from unverified, pre-finalized raw picks. Provide a final-publication contract to the operations worker if necessary.
9. Runtime configs keep credentials confined to intended HTTPS provider endpoints; preserve documented proxy support explicitly rather than allow arbitrary credential destinations.

## Verification

Tests first; show red/green using stubs. Read current official response docs if a response field's meaning is uncertain. Do not run actual AI/KIS or read keys. Coordinate Gradle with root. Report commands, exact files and downstream contracts; no commit/deploy/subagents.
