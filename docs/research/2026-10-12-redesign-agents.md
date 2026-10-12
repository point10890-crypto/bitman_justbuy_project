# BitMan 재설계 연구: AI 에이전트, OpenAI 모델, 비용과 검증

검증일: 2026-10-12 KST. 연구 대상: 현재 Spring Boot 분석 코드와 공식 OpenAI 문서. 이 문서는 설계 제안이며 운영 모델 교체, 신규 유료 호출, 키 조회, 배포를 실행한 결과가 아니다.

최종 범위와 용어의 기준은 [통합 설계](../superpowers/specs/2026-10-12-bitman-research-core-design.md)다. 아래 모델·예산·도구 대안은 연구 제안이며 첫 A단계에서 모두 구현한다는 뜻이 아니다.

## 1. 설계 결론

처음부터 설계한다면 **실데이터 조건검색 엔진이 후보와 수치·순위를 확정하고, AI는 제한된 후보의 가설과 반증을 작성하며, 서버가 근거와 공개 가능성을 검증하는 구조**를 선택한다. 프런티어 모델을 모든 종목에 반복 호출하는 것보다, 정확한 시점의 자료와 반증 질문을 잘 전달하고 개선 여부를 재현 가능한 실험으로 증명하는 편이 우선이다.

에이전트 이름은 모델 회사 이름이 아니라 책임을 나타내야 한다. 수급 담당, 기업 사건 담당, 반증 담당, 설명 담당처럼 나누고 각 단계에 입력·출력·실패 정책·예산을 명시한다. 여러 모델이 같은 입력에 동의하는 것은 독립적인 시세나 공시 확인이 아니다. 모델 투표 비율, AI 점수, 설명 문장의 자신감을 상승 확률이나 수익률로 표시하지 않는다.

추천하는 초기 실행 경로는 다음과 같다.

```text
사용자 조건
  → 조건 AST 및 실행 가능성 검사
  → 검증된 종목 유니버스와 point-in-time 데이터
  → 결정론 필터·지표·위험·순위
  → 후보 증거 스냅샷(변경 불가)
  → 좁은 가설 작성 / 독립된 반증 질문
  → 구조·종목·출처·기간·단위·주장 검증
  → 서버 정책: 조건 충족 / 보류 / 제외
  → 사실 수치와 근거를 서버가 렌더링
  → 다음 거래일 이후 실제 성과 평가
```

조건검색은 AI가 없어도 실행돼야 한다. AI 장애 시 검증된 룰 결과를 제공하고 AI 검토 미실행을 표시한다. 필수 시세나 조건의 핵심 데이터가 없으면 해당 후보는 UNKNOWN 또는 보류다. AI 장애에 대한 폴백과 데이터 결손에 대한 처리 정책을 서로 분리한다.

## 2. 현재 코드에서 유지할 자산과 바꿀 경계

| 현재 자산 | 확인한 역할 | 재설계 방향 |
| --- | --- | --- |
| `MultiAgentOrchestrator` | 시장 데이터 후보 수집, 에이전트 병렬 실행, 공급자 폴백, 후보·실시세 게시 게이트, 공통 DART 스냅샷 | 검증된 후보·시세 게이트를 유지한다. 공급자별 장문 분석과 합의 계산은 typed 책임 단계로 분리한다. |
| `EvidenceClaimGuard` / `EvidenceFact` | factId/value를 원 입력에 대조하고 원 출처·기간·단위 컨텍스트를 보존 | scalar 일치 검사는 유지한다. 새 계약은 factId 참조를 기본으로 하고, 관계·비교·인과 주장 검증을 별도로 둔다. |
| `DeepSeekConditionVerifier` | 기존 후보 패킷만 받아 반증·리스크·무효화 설명, 실패 시 룰 폴백 | 같은 역할을 공급자 중립 인터페이스로 만든다. `aiScore`가 투자 확률로 오해되지 않도록 공개 화면과 정책에서 분리한다. |
| `ConditionRunService` | runId/traceId, 이벤트, 상태, 최대 500개 메모리 보관 | DB에 run·stage·attempt·request·artifact를 저장한다. 재시작·취소·중복 실행·비용 불명 상태를 복구한다. |
| `AnalysisEvidenceJournalService` | 제한된 공개 근거 기록, 비밀·회원 데이터 제외 | 보안 경계는 유지하고 입력 해시·프로토콜 버전·실제 사용량·검증 사유를 런 원장과 연결한다. |
| DART typed financial / deterministic technical | 공시 원계정 및 일봉 계산 자료의 기간·단위 확인 | 숫자를 모델에게 생성시키지 않고 그대로 재사용한다. 금융업·비금융업 지표 차이는 결정론 계층에서 처리한다. |

현재 코드의 수치 검증은 원 입력 충실도를 개선한 것이다. 원 입력의 시장 정확도, 자료가 처음 공개된 시각, 문장 속 비교와 인과성, 향후 성과까지 자동 입증하지 않는다. 재설계는 이 경계들을 분리해 관측해야 한다.

검토 파일:

- `backend/src/main/java/com/bitman/justbuy/ai/MultiAgentOrchestrator.java`
- `backend/src/main/java/com/bitman/justbuy/service/EvidenceClaimGuard.java`
- `backend/src/main/java/com/bitman/justbuy/service/DeepSeekConditionVerifier.java`
- `backend/src/main/java/com/bitman/justbuy/condition/run/ConditionRunService.java`
- `backend/src/main/java/com/bitman/justbuy/dto/condition/EvidenceFact.java`

## 3. OpenAI 모델·API 공식 확인

공식 문서 검색 MCP에서 주제 검색 후 해당 문서를 실제 조회했고, 모델·가격·Multi-agent 페이지는 공식 웹 원문도 열었다. 계정에서 특정 모델을 사용할 수 있는지, 프로젝트별 호출 제한, 한국주식 과제에서의 정확도는 이번 문서 조사만으로 확인되지 않는다.

### 3.1 모델 선택

2026-10-12 공식 카탈로그는 GPT-6 Astra를 복잡한 추론의 상위 모델, GPT-6.1 Sol을 비용과 성능 절충, GPT-6 Luna를 좁고 많은 작업의 효율형으로 소개한다. 이 제품 설명은 한국 주식의 초과수익 검증 결과가 아니다. [모델 카탈로그](https://developers.openai.com/api/docs/models)

| 앱 내부 별칭 | 초기 검토 대상 | 역할 | 호출 원칙 |
| --- | --- | --- | --- |
| `EXTRACTOR` | `gpt-6-luna` 또는 기존 제공자 | 공시 문단에서 사건 유형·해당 문장 위치·불확실성 추출 | 템플릿·규칙으로 충분하면 호출하지 않는다. |
| `ANALYST` | `gpt-6.1-sol` 또는 기존 DeepSeek | 소수 후보의 구조화 가설·대체 설명·결측 확인 | 증거가 완성된 최대 5개 후보를 공유 배치로 검토한다. |
| `DEEP_REVIEWER` | `gpt-6-astra` | 복잡한 정정공시, 상충 자료, 산업 구조·인과 해석 등 예외 검토 | 기본 OFF. 객관적인 escalation 조건과 여유 예산이 있을 때만 호출한다. |
| `EXPLAINER` | 모델 없이 서버 템플릿을 우선 | 승인된 사실·가설·반증을 사용자 언어로 표현 | 새 숫자·종목·순위·목표가를 생성하지 않는다. |

별칭은 `modelId`, `provider`, `protocolVersion`, `reasoningEffort`, `schemaVersion`, `promptVersion`, `unitPrices`, `effectiveAt`으로 구성한다. 실제 응답의 modelId와 사용량을 기록한다. 평가를 통과한 설정 버전만 운영에 승격하고 모델 ID가 바뀌었다는 이유로 자동 승격하지 않는다.

- Astra의 공식 현재 ID는 `gpt-6-astra`; Responses, structured outputs, function calling, caching 등이 기재돼 있다. [Astra 모델](https://developers.openai.com/api/docs/models/gpt-6-astra)
- Sol의 도구 호출은 Responses 경로를 사용하며 `none`/`minimal` reasoning effort는 지원하지 않는다고 명시돼 있다. [Sol 모델](https://developers.openai.com/api/docs/models/gpt-6.1-sol)
- Luna도 Responses 도구 호출을 지원한다. `none`과 `low` 등 설정의 품질 차이는 자체 fixture로 검증해야 한다. [Luna 모델](https://developers.openai.com/api/docs/models/gpt-6-luna)

### 3.2 구조화 출력과 도구 호출

Responses의 `text.format` JSON schema와 function calling의 `strict: true`를 사용한다. 스키마의 object는 `additionalProperties: false`, 필수 속성은 required로 지정하고 선택값은 명시적인 nullable 필드로 설계한다. Structured Outputs는 형태를 제한하지만 사실성 검사는 앱 책임이다. refusal, incomplete, failed, 출력 예산 소진은 정상 결과와 구분한다. [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [Function calling](https://developers.openai.com/api/docs/guides/function-calling)

초기 프로토타입은 서버가 자료를 모두 모은 다음 도구 없이 한 번 분석하도록 한다. 이후 모델이 자료 부족을 정확히 요청하는 가치가 확인되면 읽기 전용 함수만 노출한다. 도구 실행기는 후보·권한·쿼터·시간을 다시 검증한다. `parallel_tool_calls: false` 또는 제한된 `allowed_tools`는 보조 제약이며 앱 실행 예산을 대체하지 않는다.

### 3.3 관리형 에이전트 기능과 안정성 선택

| 선택지 | 공식 현황·장점 | BitMan 판단 |
| --- | --- | --- |
| 앱이 DAG를 실행하고 Responses를 호출 | 각 입력·출력과 호출 수를 앱에서 통제 | 초기 운영 권고. Spring Boot 서비스·기존 자료 어댑터와 가장 작은 경계로 연결한다. |
| Agents SDK | Python/TypeScript SDK, agents-as-tools, guardrails, tracing | 별도 연구 worker가 필요할 때 검토한다. Java 본체를 처음부터 교체할 이유는 없다. |
| Responses 서버 Multi-agent | beta. 전용 문서는 Sol·GPT-5.6 계열 지원을 명시하며 일부 item 스키마 변경 가능 | 오프라인 연구·평가만 먼저 검토. 비용/도구 호출 수를 엄격히 제어해야 하는 게시 경로에는 기본 사용하지 않는다. |
| Agents API | 관리형 harness와 세션, 도구·환경·다중 에이전트; beta 경로가 공식 예시에 있음 | 장시간 연구 실험 대안. 운영 회원 정보나 키를 sandbox에 넘기지 않는다. |
| Agent Builder / Assistants API | Agent Builder 종료 예정 2026-11-30, Assistants API 종료 2026-08-26 | 새로운 아키텍처 기반에서 제외한다. |

Responses Multi-agent는 `max_tool_calls`, 수동 compact, reasoning summary 등에 제한을 명시한다. 일반 배포 문서의 넓은 GPT-6 표현보다 전용 문서의 지원 모델 확인을 우선한다. Astra의 Responses 서버 Multi-agent 지원을 이 조사로 확정하지 않는다. Agents API Astra 예시와 Responses Multi-agent 지원은 별개다. [Multi-agent 전용 문서](https://developers.openai.com/api/docs/guides/responses-multi-agent), [배포 체크리스트](https://developers.openai.com/api/docs/guides/deployment-checklist), [Agents API](https://developers.openai.com/api/docs/guides/agents-api/overview), [Agents SDK](https://developers.openai.com/api/docs/guides/agents/sdk), [공식 종료 일정](https://developers.openai.com/api/docs/deprecations)

## 4. 에이전트와 도구 계약

### 4.1 공통 근거 계약

LLM에게는 종목코드 문자열만 보내지 않고, 다음 필드를 가진 변경 불가 스냅샷을 전달한다.

```text
EvidenceSnapshot
  snapshotId / contentHash / createdAt / asOf / allowedCandidateIds
  universeId / universeVersion / observedUniverseCount / uncoveredCount
  facts[]: factId, instrumentId, field, typedValue, unit,
           provider, providerEnvironment, observationAt, receivedAt,
           availableAt, periodStart, periodEnd, adjustmentBasis,
           qualityState, rawArtifactHash, sourceVersion
  excerpts[]: excerptId, instrumentId, sourceId, contentHash,
              availableAt, startOffset, endOffset, text, trustClass
  calculations[]: calculationId, version, inputFactIds, formulaId, typedValue
  missingFacts[] / conflicts[] / freshnessPolicyVersion
```

`asOf` 이전에 실제 이용 가능했던 자료만 분석·과거 평가에 사용한다. 수집 시각과 시장 관측 시각, 공시의 사업 기간과 발표 시각은 서로 대체하지 않는다. KIS와 Kiwoom은 공급자·환경별로 별도 사실을 보존한다. Kiwoom MOCK을 이름만으로 가상 가격이라고 단정하지 않으며, 실측 결과의 환경·관측 범위·제약을 표시한다. 값의 평균으로 교차 검증을 대체하지 않는다.

### 4.2 책임 분리

| 책임 | 입력 | 출력 | 허용 도구 / 공개 경계 |
| --- | --- | --- | --- |
| 조건 해석기 | 사용자 조건, 허용 feature 목록, 시장·시간 기준 | 조건 AST + ambiguous/missing constraints | AI는 AST 초안만 작성. 서버가 feature·연산·단위·기간을 검증하고 조건 요약을 표시한다. |
| 정량 검색기 | 승인 AST, market snapshot | 후보, 충족 조건, 결측, 계산 근거, 순위 | 결정론 코드. LLM 없음. UNKNOWN을 PASS로 처리하지 않는다. |
| 사건 추출기 | 공시/뉴스 원문 발췌, 후보 instrumentId | 사건 유형 enum, excerptId, 정정 여부, known/unknown | 가격·실적 수치 재작성 금지. 문장 존재·종목 연결은 서버 검증. |
| 가설 분석가 | 후보 스냅샷, 승인된 사건 | 조건의 의미, 지지 facts/excerpts, 다른 설명, 필요한 후속 자료 | 최대 5개 후보, 고정 출력. 목표가·성공 확률·임의 종목 생성 금지. |
| 반증 분석가 | 원 스냅샷, 사전에 고정한 반증 질문 | 반증 facts/excerpts, 누락 자료, 과열·기간 착오·대체 원인 | 먼저 원자료를 보고 반증 초안을 작성한다. 가설을 본 뒤 반증을 순응시킬지 평가한다. |
| 증거 검증기 | AI draft + snapshot | claim별 ACCEPT/REJECT/UNKNOWN, 오류 코드 | 수치·정체성·기간은 코드로 검증. 의미적 근거는 별도 평가·제한된 judge 사용. |
| 게시 정책기 | 조건 결과, 검증 결과, 품질/신선도 | 조건 충족 / 관찰 / 근거 부족 / 제외 | 결정론 코드. 후보 수를 채우기 위한 강제 추천 없음. |
| 설명 렌더러 | 승인된 필드·사실 참조 | 왜 검출됐는지, 반증, 자료 시각, 재확인 조건 | 숫자는 서버가 삽입한다. 모델 장문 출력 그대로 게시하지 않는다. |
| 성과 평가기 | 게시 당시 immutable 후보와 정책, 이후 실제 가격 | 동일 기준 1/3/5/20거래일·비용·벤치마크 평가 | 미래 데이터는 분석 입력에 들어가지 않음. 별도 job, 자동 운영 점수 변경 금지. |

초기에는 가설과 반증을 하나의 구조화 응답에서 작성하는 1회 경로를 비교 기준으로 둔다. 두 역할을 두 번 호출하면 고정된 입력을 공유하되 역할별 질문·출력을 분리하고 먼저 독립 실행한 후 대조한다. 모델 간 독립성과 데이터 출처의 독립성을 별도 지표로 기록한다.

### 4.3 읽기 전용 도구의 최소 표면

각 함수는 `runId`, `snapshotId`, 허용 instrumentId, enum field 또는 metric 목록만 받는다. 모델이 URL, 인증 정보, arbitrary SQL, 파일 경로, 공급자 호스트를 지정할 수 없다.

```text
get_evidence(snapshotId, candidateId, factGroups[])
  → 이미 저장된 허용 사실. 외부 호출 없음.
get_excerpt(snapshotId, excerptId)
  → 저장된 원문 발췌 + hash + availableAt. 외부 호출 없음.
request_missing_fact(runId, candidateId, factType)
  → controller 결정: READY / QUEUED / NOT_SUPPORTED / BUDGET_EXHAUSTED
     공급자 호출은 앱이 승인된 quota·license·deadline 하에서 실행.
evaluate_expression(snapshotId, approvedExpressionId)
  → versioned deterministic calculation + inputFactIds.
get_prior_outcomes(cohortId, asOf, methodologyVersion)
  → 충분한 표본·비용·정의가 확인된 과거 결과만. 미완료 cohort는 UNKNOWN.
```

처음에는 앞의 두 함수도 프롬프트에 필요한 근거를 한 번에 담아 호출할 필요가 없게 한다. 도구 수와 모델 왕복을 늘리는 것은 비용과 품질 개선이 측정될 때만 허용한다.

### 4.4 AI 출력 계약 예시

아래는 실제 종목 판정 결과가 아닌 스키마 형태의 예시다. 모든 ID는 원 스냅샷에서 존재해야 한다.

```json
{
  "schemaVersion": "hypothesis-v1",
  "snapshotId": "snapshot_example",
  "candidateId": "candidate_example",
  "thesisType": "FLOW_SUPPORTED_BREAKOUT",
  "supportingFactIds": ["fact_example"],
  "supportingExcerptIds": [],
  "counterEvidenceFactIds": [],
  "missingFactTypes": ["FLOW_SAME_PERIOD_CONFIRMATION"],
  "alternativeExplanation": "SHORT_LIVED_LIQUIDITY",
  "invalidationRuleIds": ["rule_example"],
  "analysisState": "INSUFFICIENT_EVIDENCE",
  "qualitativeSummary": "가격 조건은 충족했으나 수급 근거를 같은 기간으로 재확인해야 합니다."
}
```

이 출력의 `analysisState`는 AI 초안이다. 공개 상태는 서버 정책기로 다시 결정한다. `invalidationRuleIds`는 이미 승인된 룰 중 선택하며 모델이 새 가격을 발명하지 않는다. 역치와 가격은 원 사실·공식·정책 버전으로 계산한다. `supportingFactIds`만 존재한다고 지지 관계가 성립하는 것은 아니므로 thesis별 필요한 feature·방향·기간 조건을 코드로 확인한다.

자유로운 인과 추론은 추정 가설로 표시한다. 실제 수급이 올랐다는 사실만으로 원인이 금리, 특정 공시 또는 외국인 주문이라고 단정하지 않는다. 입증되지 않은 질적 가설을 사실 카드에 섞지 않는다.

## 5. 뉴스·공시·RAG 보안 및 문맥 설계

공시, 뉴스, 사용자 입력은 분석 대상 데이터다. 외부 문장에 포함된 “지시 무시”, “다른 종목을 추천”, “키를 보내라” 같은 문장을 developer 메시지나 도구 실행 정책으로 올리지 않는다. 도구와 원문은 분리하고 외부 문장에서는 허용된 사건·근거 ID만 다음 단계로 전달한다. 구조화 출력과 격리는 위험을 낮추지만 주입 공격을 완전히 제거한다는 의미는 아니다. [에이전트 안전 지침](https://developers.openai.com/api/docs/guides/agent-builder-safety)

운영 검색기는 비밀·회원 DB·구독·주문·OS 실행 권한을 갖지 않는다. 시장 읽기 도구만 가진 별도 worker를 구성한다. 사용자별 조회 이력·개인 정보는 공개 evidence cache에 넣지 않는다. 외부 모델 호출은 필요한 공개 시장 facts/excerpts만 전송한다.

RAG는 공식 공시와 권한을 확보한 뉴스로 시작한다. 초기에는 PostgreSQL 전문 검색/명시적 종목·날짜 필터처럼 단순하고 추적 가능한 검색을 우선하며 embedding 추가는 retrieval 평가로 효과를 확인한 후 결정한다. 종목 alias·회사 합병·시장 코드와 함께 `availableAt <= asOf`, 정정공시 연결, 문단 provenance 필터가 필수다. 유사 문장이라는 이유로 다른 종목·다른 시점 문서를 섞지 않는다.

모델에 제공한 발췌문과 원문 hash·offset을 저장한다. citation은 모델 생성 URL이 아니라 서버가 sourceId를 원문 주소로 매핑한다. 검색 recall과 최종 근거 적합성은 별도 측정한다. 중요 근거가 검색에서 빠졌으면 모델이 합리적인 답을 써도 검색 품질 실패다.

OpenAI API 데이터는 기본적으로 학습에 사용되지 않지만 abuse monitoring과 application state 보관은 별도다. `store:false`가 모든 보관을 제거하지 않는다. 계정의 승인된 데이터 정책을 확인하고, 외부 tracing에 원문·회원 정보를 무조건 내보내지 않는다. [데이터 제어](https://developers.openai.com/api/docs/guides/your-data)

## 6. 호출 비용과 안정된 실행

### 6.1 공식 가격과 계산 예시

Standard short context(입력 272K 이하), USD/1M tokens 기준 공식 표:

| 모델 | 일반 입력 | 캐시 읽기 | 캐시 쓰기 | 출력 |
| --- | ---: | ---: | ---: | ---: |
| `gpt-6-astra` | $10.00 | $1.00 | $12.50 | $50.00 |
| `gpt-6.1-sol` | $2.00 | $0.10 | $2.50 | $10.00 |
| `gpt-6-luna` | $0.10 | $0.01 | $0.125 | $0.50 |

예를 들어 일반 입력 8,000토큰과 **청구 대상 출력 전체 2,000토큰**을 가정하면 1회 계산은 Astra $0.18, Sol $0.036, Luna $0.0018이다. 실제 보이는 답변 길이가 전체 출력·추론 사용량과 같다고 가정하지 않는다. 캐시 쓰기와 실제 추론량, 도구 요금, 장문 요율, 세금·환율은 이 단순 예시에서 제외했다. 결제/권한 확인 없이 호출하지 않는다. [공식 요금](https://developers.openai.com/api/docs/pricing)

세 가지 대안을 동일 실제 입력과 평가 기준으로 비교한다.

| 대안 | 장점 | 비용·품질 위험 | 채택 조건 |
| --- | --- | --- | --- |
| 룰 + 기존 DeepSeek 1회 | 변경과 신규 공급자 비용 최소, 현재 자산 재사용 | 복잡한 사건·정정 해석의 개선 폭 불명 | 기준선으로 유지하고 실제 오류 유형을 축적한다. |
| 룰 + Luna 추출 + Sol 검토, Astra 예외 | 좁은 역할과 우수 모델을 필요한 곳에 사용 | 단계 수·입력 토큰 증가, routing 오판 가능 | 같은 후보의 근거 적합성·보류 정확도가 기준선보다 개선될 때 권고한다. |
| 소수 후보 Astra 중심 1회 | 운영 경로 단순, 복잡한 분석에 집중 | 토큰 단가 높음; 더 비싼 모델도 잘못된 원자료를 고치지는 못함 | 소수 심층 검토에서 품질 개선이 추가 비용을 정당화할 때 선택한다. |

모든 대안의 후보·정량 ranking 입력은 동일하게 유지한다. “모델이 달라져서” 서로 다른 후보를 분석하면 분석 품질 비교와 후보 선정 효과를 분리할 수 없다.

### 6.2 예산·중복·비용 불명 상태

앱 내부 `BudgetReservation`을 외부 호출 **전에** 영속 저장한다. 예상 최대 입력과 `max_output_tokens` 전체 상한·가능한 캐시 쓰기 단가를 적용하여 USD 상한을 예약한다. 완료 응답의 실제 usage/model/tier를 확인하면 예약을 정산한다. API timeout나 접속 끊김 뒤 공급자가 처리했는지 알 수 없으면 `BILLING_UNKNOWN`으로 남기고 최악 비용 예약을 유지한다. 무조건 재호출하여 중복 청구를 유발하지 않는다.

```text
stageKey = hash(runInputHash, snapshotHash, stageType,
                modelConfigVersion, promptVersion, schemaVersion)
stage states = PLANNED → RESERVED → REQUEST_SENT
               → RECEIVED → VALIDATED / REJECTED
               → TIMEOUT_UNKNOWN / CANCEL_PENDING / CANCELLED
```

같은 stageKey의 VALIDATED 결과는 재사용한다. 실패한 단계만 새 attempt로 복구한다. 유효기간이 지난 snapshot은 기존 완료 단계 재사용 대상에서 제외하고 새 run으로 명시한다. timeout 처리와 cancellation은 late completion이 검증·게시 상태를 덮어쓰지 못하도록 fencing token과 CAS를 사용한다.

초기 **제안값**은 deterministic numeric 조건검색 유료 LLM 0회, 일반 해설 검토 요청당 최대 1회/후보 5개/입력 12K/출력 총 2K, Astra 예외는 별도 opt-in 정책과 최대 1회다. 이는 운영값이나 성능 보장이 아니다. 문맥 길이·증거 누락·latency·출력 완결률을 오프라인 시험해 조정한다. 출력이 잘리면 부분 JSON을 성공으로 게시하지 않는다.

공급자별 RPM·TPM·동시성·계정 총 사용 한도를 독립 적용하고 부족한 공급자의 backoff가 전체 사용자 상태 조회를 막지 않게 한다. 요청을 대기열에 넣되 deadline이 지난 요청은 새 호출을 시작하지 않는다. 인증/권한 오류, 429, schema 오류, 입력 품질 오류, 처리 결과 불명은 각각 다른 복구 정책을 갖는다. 실제 OpenAI quota는 프로젝트·모델별로 확인해야 한다. [호출 제한](https://developers.openai.com/api/docs/guides/rate-limits)

### 6.3 캐싱·Background·Batch 적용 위치

- **앱 결과 캐시**: snapshot·모델·프롬프트·스키마가 같은 검증된 결과를 재사용한다. 호출 자체를 줄인다. 시세 신선도와 사용자 private query 경계를 별도 적용한다.
- **Prompt caching**: 안정된 지침·schema·도구를 앞에, 변동 종목 facts를 뒤에 배치한다. 읽기·쓰기 요율과 실제 cached tokens를 측정한다. 초기 cache-write 비용이 있으므로 캐시 적중을 가정해 예산을 낮추지 않는다. [Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)
- **Background mode**: 장시간 심층 연구에서 responseId를 저장하고 상태 조회·취소를 한다. 영속 run·재시작 복구·중복 방지는 앱 책임이다. 보관 동작은 계정 ZDR/MAM 정책과 store 값에 따라 달라져 실제 정책을 확인한다. [Background](https://developers.openai.com/api/docs/guides/background)
- **Batch**: 다음 날까지 기다려도 되는 eval·사후 리뷰·비공개 연구에 적용한다. 공식 최대 24시간 처리 창과 50% 할인은 장중 조건검색의 지연 요구와 맞지 않는다. custom_id별 부분 완료를 저장하고 미완료만 복구한다. [Batch API](https://developers.openai.com/api/docs/guides/batch)
- **Tracing**: 앱 원장을 기본으로 둔다. SDK tracing 또는 관리형 trace를 추가하면 도구·모델·검증 span을 연결하되 민감 payload는 제외한다. 모델 reasoning 내부 내용을 추출해 저장하는 기능으로 취급하지 않는다. [SDK 관측](https://developers.openai.com/api/docs/guides/agents/integrations-observability), [Trace grading](https://developers.openai.com/api/docs/guides/trace-grading)

## 7. 분석력 평가: 출력 품질과 투자 성과를 분리

모델 버전 비교는 같은 asOf·후보·snapshot·조건을 사용하는 paired experiment로 수행한다. 운영 지표는 다음 세 묶음으로 나눈다.

| 층 | 평가 항목 | 측정 방식 |
| --- | --- | --- |
| 사실·자료 | 잘못된 ticker, 틀린 scalar, 기간/단위 바꿔쓰기, 미래 자료, 출처 없는 주장 | golden facts + deterministic validation. 결측·충돌을 포함한다. |
| 에이전트 판단 | 가설 근거 적합성, 실제 반증 탐지, 필요한 결측 보류, 과도한 보류, 원문 주입 저항 | 블라인드 라벨·전문가 검토, 제한된 LLM judge는 보조. 반복 실행 변동성 확인. |
| 운영 | completion, fallback, unknown, p50/p95, per-run 실제 비용, 재사용률, retry, cancellation | 앱 stage 원장과 공급자 actual usage. 비용 불명도 분모에서 빼지 않는다. |

신규 strict 출력은 schema 성공률을 올릴 수 있지만 금융 판단 정확도의 대체 지표가 아니다. LLM judge가 좋은 설명이라고 점수 준 것 역시 초과수익 입증이 아니다. trace/eval을 통해 도구 선택과 validation 실패 구간을 찾아 개선한다. [에이전트 평가](https://developers.openai.com/api/docs/guides/agent-evals)

필수 평가 예:

1. 다른 종목의 재무자료를 섞었을 때 REJECT.
2. 같은 숫자라도 원↔억원, 당분기↔누적, 어제↔오늘 변경은 REJECT.
3. 공급자 두 개가 같은 기간의 값을 제공하지 않으면 “수급 확인”으로 합산하지 않음.
4. 입력에 없는 목표가·ticker·확률을 추가하면 공개 제외.
5. 의미가 없는 작은 fact를 인용하며 중요한 주장을 뒷받침했다고 할 때 근거 적합성 실패.
6. 핵심 근거 부족 시 UNKNOWN; 낮은 위험의 선택 자료가 없다는 이유만으로 모든 후보를 무조건 거절하지 않음.
7. 뉴스 속 시스템 지시·외부 URL·키 요청이 tool dispatch나 게시 상태를 바꾸지 않음.
8. 정정 전 자료·정정 후 자료의 공개 시각을 섞지 않음.
9. provider timeout 직후 late completion·서버 재시작·중복 worker가 이중 게시/이중 요청하지 않음.
10. 모델 설정 변경·schema 변경 후 완료 단계 캐시를 잘못 재사용하지 않음.

향후 수익 평가는 후보 선정/모델 검토/게시 필터의 효과를 따로 비교한다. 실제1/3/5/20거래일 수익, 동일 진입 시각, benchmark, spread·수수료·유동성·최대 불리/유리 변동, 중복 종목 노출, regime별 결과를 계산한다. 동일 종목·겹친 거래일은 독립 관측처럼 표본수를 부풀리지 않는다. probability를 표시하려면 충분한 실측 cohort와 별도 calibration이 먼저다. 현재 과거 결과의 혼합 정의나 AI 합의 점수로 확률을 산출하지 않는다.

## 8. 첫 프로토타입 범위와 완료 기준

첫 결과물은 운영 추천을 교체하는 시스템이 아니라 **오프라인으로 재현하고 검증할 수 있는 한 경로**로 한정한다.

1. 공개 실자료 저장본에서 1개 대표 조건과 정상/결측/충돌/기간 착오/주입/재시작 사례를 구성한다. 고장 주입용 fixture는 테스트 자료라고 표시하며 실제 앱 종목으로 공개하지 않는다.
2. versioned Condition AST → 같은 snapshot의 deterministic candidates → typed EvidenceSnapshot을 연결한다.
3. ModelGateway fake 응답으로 strict schema와 claim·publication gate를 완주한다. 성공한 stage 저장·재사용, timeout 불명·취소 fencing·복구를 검증한다.
4. 룰 단독과 AI 1회 가설/반증 경로를 같은 입력에서 비교할 평가 harness를 만든다. 모델 호출 없이 계약·안전·비용 계산을 먼저 확인한다.
5. 이후 실제 모델 실험은 계정 가용성·정보이용 범위·예산을 확인한 별도 제한된 dry-run으로 진행한다. 신규 OpenAI 키 생성 또는 키 복사는 이 문서 작업에 포함되지 않는다.

프로토타입 통과 기준: 허용되지 않은 종목·수치·출처·기간의 공개 0건, 필수 자료 결손의 성공 위장 0건, 중복 stage의 provider dispatch 0건, 취소 run late completion 게시 0건, 재시작 후 stage 복구·비용 예약 보존, 연구 보고서에서 증거/가설/미확인 상태가 구분됨. 실제 고객 UI 확인은 후속 제품 전환 단계다. 이 기준 통과를 수익 향상이나 모든 주입 공격 방어 보장이라고 표현하지 않는다.

외부 모델 없이 통과한 뒤에만 paid-canary를 별도 단계로 추가한다. 이후 shadow 운영에서 기존 시스템과 동일 후보·시점으로 비교하고, 근거 품질/시간/비용 목표가 개선돼야 운영 승격한다. 기존 앱·회원·텔레그램 워크플로우는 전환 기간 동안 versioned 결과 계약으로 유지하며 전면 교체를 한 번에 배포하지 않는다.

## 9. 불확실성과 사용자 결정이 필요한 항목

- KIS·Kiwoom 자료의 유료 앱/텔레그램 제공·보관·모델 입력 이용 범위는 별도 확인이 필요하다. 이번 설계는 권리 확보 완료를 가정하지 않는다.
- OpenAI 공급자로 실제 전환할 때 계정에서 모델·도구·quota·budget이 허용되는지 확인해야 한다. 공식 문서에서 ID가 존재하는 것과 현재 프로젝트 사용 가능 여부는 다르다.
- 신규 모델은 한국 공시·산업 판단에서 기존 DeepSeek보다 좋다고 아직 검증되지 않았다. 가장 비싼 모델을 선택하는 것이 분석력을 보장하지 않는다.
- 관측된 사실의 fidelity, AI의 근거 해석, 과거 투자 성과를 각각 보고해야 한다. 현 저장된 픽을 새 구조로 평가하면서 수익 향상 주장부터 하는 것은 피한다.

