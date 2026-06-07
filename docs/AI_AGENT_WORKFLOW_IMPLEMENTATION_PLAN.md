# AI 에이전트 조건검색 구현 작업 계획서

작성일: 2026-06-07 KST
기준 저장소: `C:\bitman_justbuy_project`

## 목표

BitMan 조건검색을 "결정론적 후보 생성 + AI 검증/설명 + 사람 승인 + 평가/로그" 구조로 단계적으로 전환한다. 자동 매수/매도 판단은 구현 범위에서 제외하고, 사용자가 확인할 수 있는 정보성 조건검색 신호만 게시한다.

## 구현 원칙

- LLM은 종목/가격/공시 사실을 새로 만들 수 없다.
- 후보 생성, 종목 정체성, 가격 보정, ETF/ETN 제외는 결정론적 서비스가 맡는다.
- AI는 EvidencePacket 검증, 리스크 분류, 설명 생성에 제한한다.
- 모든 실행은 `runId`, `traceId`, `formulaVersion`, `promptVersion`, `provider`, `model`로 추적한다.
- 기존 API와 PRO 구독 게이트는 유지한다.
- `.env`, 키, 토큰, 운영 DB 원본, 회원/구독 원본 데이터는 열람하거나 출력하지 않는다.

## 단계별 작업

### 0단계: 기준선 고정

- 현재 API 흐름을 문서화한다: `AnalysisController`, `AnalysisService`, `MainConditionService`, `PrecomputeScheduler`, `MultiAgentOrchestrator`.
- 기존 테스트 기준선을 확인한다: `backend\src\test`, `frontend\package.json`.
- `backend/data/*.json`, 정적 빌드 산출물, 로그, 임시 브라우저 프로필 등 기존 dirty 상태는 사용자 변경으로 간주한다.
- `/api/main`, `/api/conditions/*`, `/api/analysis/*`, scheduler refresh의 응답 형태와 sourceStatus를 characterization test로 먼저 고정한다.

### 1단계: 실행 모델 도입

- `ConditionRun` 도메인 모델을 만든다.
- 상태: `QUEUED`, `COLLECTING`, `SCORING`, `VERIFYING`, `RANKING`, `EXPLAINING`, `PUBLISHING`, `COMPLETE`, `PARTIAL`, `FAILED`, `CANCELLED`, `EXPIRED`.
- `ConditionRunEvent`를 추가해 단계별 메시지, severity, metric, traceId를 남긴다.
- 처음에는 관찰 전용으로 붙인다. 기존 JSON 캐시와 응답 경로는 바꾸지 않고 `trigger`, `mode`, `status`, `attempt`, `startedAt`, `finishedAt`, `errorCode`, pick count만 기록한다.
- 현재 `AsyncJobManager`는 단기 메모리 job으로 유지하되, 새 run 저장소와 연결해 재시작 후에도 상태 조회가 가능하게 한다.
- live analysis, scheduler cron, startup precompute, admin refresh에 모두 run 생성을 연결한다.

### 1.5단계: 이벤트 로그와 실행 결과 명시화

- 이벤트 로그는 append-only로 둔다.
- 이벤트 타입 예: `CACHE_HIT`, `AI_STARTED`, `AI_AGENT_DONE`, `PICKS_PARSED`, `VALIDATION_DROPPED`, `SAVED`, `NOTIFIED`, `FAILED`.
- 이벤트 payload는 요약/해시 중심으로 제한하고 비밀값, 원문 토큰, 운영 원본 데이터를 남기지 않는다.
- `PrecomputeScheduler`는 내부 예외를 삼키지 않고 `ExecutionResult` 형태로 성공/부분성공/실패와 실패 사유를 반환하게 정리한다.
- `refresh-all`은 실제 실패를 성공처럼 보지 않도록 mode별 결과를 집계한다.

### 2단계: 후보/증거 패킷 분리

- 섹션별 후보 수집 인터페이스를 만든다: short-term, swing, leaders, themes.
- `ConditionCandidate`와 `EvidencePacket`을 분리한다.
- EvidencePacket에는 종목코드, 표준명, 현재가, 거래량/거래대금, 수급, 공시, 재무 요약, source/asOf/freshness를 포함한다.
- 빈 데이터나 stale 데이터는 명시적으로 표시하고, 누락 데이터를 LLM 프롬프트로 보충하지 않는다.

### 3단계: 검증/스코어링

- `ConditionRuleEngine`을 도입해 ruleScore, rejectReason, riskFlag를 계산한다.
- 기존 `MainConditionService`의 종목 적격성 필터와 `PrecomputeScheduler`의 Naver 검증/중복 제거를 공통 validator로 모은다.
- 목표가/손절가 범위 보정은 저장 전 validator 단계에서 수행한다.
- AI verifier보다 이 단계를 먼저 구현한다. 흩어진 6자리 코드 검증, Naver 종목명 보정, ETF/ETN/스팩 제외, cross-mode dedup, 목표가/손절가 보정을 하나의 rule/risk 단계로 모은 뒤 verifier를 붙인다.

### 4단계: AI verifier 계약

- DeepSeek/ChatGPT 등 provider별 호출을 `ConditionAiVerifier` 인터페이스로 감싼다.
- 입력은 EvidencePacket batch, 출력은 strict JSON schema만 허용한다.
- 출력 필드: `stockCode`, `aiStatus`, `confidence`, `riskFlags`, `evidenceUsed`, `missingData`, `explanation`, `disallowedClaims`.
- 파싱 실패 시 1회 축소 재시도 후 `AI_UNAVAILABLE`로 처리하고 publish 가중치를 낮추거나 제외한다.

### 5단계: 랭킹/게시

- finalScore는 `ruleScore`, `dataQualityScore`, `aiConfidence`, `financialScore`, `trackRecordPrior`, `riskPenalty`를 조합한다.
- 게시 전 `PublishedConditionSignal` 형태를 고정하고, explainer가 종목/가격 필드를 변경하지 못하게 한다.
- `sourceStatus`는 `REALTIME_SCAN`, `PRECOMPUTED`, `STALE_CACHE`, `PARTIAL_AI`, `AI_UNAVAILABLE`, `DATA_UNAVAILABLE`처럼 사용자 의미가 분명한 값으로 확장한다.
- `PublishedConditionSignal`을 최종 source of truth로 만들고, `MainConditionService`는 새 저장소 우선, 기존 JSON 캐시는 fallback으로 읽게 전환한다.
- 성과기록과 텔레그램은 게시 성공 이벤트 이후로 이동한다.

### 5.5단계: 단일 노드 큐와 복구

- MiniPC 단일 운영 기준으로 복잡한 분산 큐보다 DB row lease를 우선한다.
- `ConditionRun`에 `leaseUntil`, `lockedBy`, `heartbeatAt`, `attempt`, `idempotencyKey`를 둔다.
- 재시작 시 `QUEUED/RUNNING` 중 lease가 만료된 run을 재시도하거나 `FAILED`로 정리하는 sweeper를 둔다.
- idempotency key는 `mode + tradingDate + round + trigger`로 잡아 중복 실행과 중복 Telegram 발송을 막는다.

### 6단계: 프론트엔드 UX

- 메인/섹션 카드에 데이터 시각, sourceStatus, AI 검증 상태, 리스크 플래그를 표시한다.
- 관리자 화면에 run timeline, 단계별 실패 사유, provider latency, parse success, publish 승인 버튼을 추가한다.
- live analysis polling은 기존 `pollJob`을 유지하되, run event 조회 API가 생기면 stage progress로 확장한다.

### 7단계: 알림과 사람 승인

- 알림은 published signal만 소비한다.
- 사용자 opt-in, quiet hours, 중복키, 발송 제한을 둔다.
- 대량 알림, 새 템플릿, high-impact batch는 관리자 승인 후 발송한다.
- 알림 문구는 "조건 충족/관찰/위험 확인"으로 제한하고 "매수 지시/수익 보장" 표현을 금지한다.

### 8단계: 평가/모니터링

- 기존 `TrackRecordService`를 runId/formulaVersion/modelVersion과 연결한다.
- 지표: run 성공률, partial 비율, 단계별 latency, schema parse 성공률, 허위 종목 차단률, stale cache 사용률, 0/1/3/5일 조건 신호 성과.
- 관리자 health에 source freshness, AI provider 상태, scheduler heartbeat, queue backlog를 노출한다.

## 권장 구현 순서

1. 현재 API 계약과 sourceStatus characterization test.
2. 관찰 전용 `ConditionRunStatus`, `ConditionRunEvent` 모델과 저장소.
3. live analysis, scheduler cron, startup precompute, admin refresh의 run 생성 연결.
4. append-only 이벤트 로그.
5. `PrecomputeScheduler`의 `ExecutionResult` 정리.
6. DB 기반 단일 노드 queue lease와 복구 sweeper.
7. 공통 `StockIdentityValidator`와 price sanity validator.
8. `EvidencePacket` DTO와 deterministic candidate 수집 인터페이스.
9. `ConditionAiVerifier` strict JSON 계약.
10. ranking/publish service와 `PublishedConditionSignal` 저장소 분리.
11. frontend sourceStatus/run timeline 확장.
12. alert event/approval model.
13. replay eval endpoint와 관리자 지표.

## 완료 기준

- 기존 `/api/main`, `/api/conditions/{section}`, `/api/analysis/live`가 회귀 없이 동작한다.
- 재시작 후에도 최근 condition run 상태를 조회할 수 있다.
- LLM 파싱 실패가 publish 실패 또는 명시적 partial 상태로 남는다.
- 허위 종목/비상장/ETF성 상품이 최종 신호에 들어가지 않는다.
- 전체 하네스: backend test, frontend build, 관련 브라우저 smoke가 통과한다.
