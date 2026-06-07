# AI 에이전트 작업 팀 구성

작성일: 2026-06-07 KST
기준 저장소: `C:\bitman_justbuy_project`

## 팀 운영 원칙

- 서브 에이전트는 독립 검토와 병렬 구현 보조에 사용한다.
- 각 에이전트는 명확한 소유 영역을 가진다.
- 서로 같은 파일을 동시에 수정하지 않게 write set을 분리한다.
- 모든 에이전트는 비밀 파일, 운영 DB 원본, 회원/구독 원본 데이터를 열람하거나 출력하지 않는다.
- 최종 통합자는 메인 에이전트이며, 하네스 통과 전에는 완료로 보지 않는다.

## 현재 구성

| 에이전트 | 역할 | 이번 라운드 산출물 |
| --- | --- | --- |
| Gauss | 백엔드 구현 순서 검토 | ConditionRun, 이벤트 로그, 큐/복구, AI verifier 구현 순서 |
| Boole | 하네스/검증 규율 검토 | 테스트, 빌드, smoke, 운영 보호 중단 기준 |
| Descartes | 스킬 품질 검토 | `bitman-ai-agent-workflow` 스킬의 트리거, 워크플로우, 금지사항 |

## 이번 라운드 반영 사항

- Descartes 제안 반영: 새 스킬은 AI 조건검색 개발 전용으로 두고, MiniPC 배포는 `bitman-service-ops`와 함께 사용할 때만 허용한다.
- Boole 제안 반영: 하네스 규율에 PRO 구독 보존, API smoke, 배포 스크립트 게이트, MiniPC/public health 중단 기준을 추가한다.
- Gauss 제안 반영: 낮은 리스크 구현 순서를 `API 계약 고정 -> 관찰 전용 ConditionRun/Event -> 이벤트 로그 -> scheduler ExecutionResult -> DB lease queue/복구 -> validator -> AI verifier -> publish 저장소`로 재정렬한다.

## 향후 구현 라운드 권장 분담

### Worker A: Run Persistence

소유 영역:

- `backend/src/main/java/com/bitman/justbuy/service/AsyncJobManager.java`
- 신규 `condition.run` 패키지
- 신규 run/event repository와 DTO

목표:

- 인메모리 job과 persistent ConditionRun 연결.
- 상태 전이와 이벤트 기록.
- 재시작 후 run 조회 가능.

### Worker B: Evidence/Verifier

소유 영역:

- `backend/src/main/java/com/bitman/justbuy/ai`
- 신규 EvidencePacket DTO
- DeepSeek/ChatGPT verifier adapter

목표:

- strict JSON 검증 계약.
- 파싱 실패, provider 실패, missing data 처리.
- LLM이 종목/가격 사실을 발명하지 못하도록 입력/출력 guard.

### Worker C: Validator/Ranking

소유 영역:

- `backend/src/main/java/com/bitman/justbuy/service/MainConditionService.java`
- `backend/src/main/java/com/bitman/justbuy/service/PrecomputeScheduler.java`
- 신규 stock identity/ranking validator

목표:

- 종목 정체성, ETF/ETN/스팩 제외, 가격 sanity, 중복 제거를 공통화.
- publish 전 최종 validator 도입.

### Worker D: Frontend Run UX

소유 영역:

- `frontend/src/api/conditionApi.ts`
- `frontend/src/api/analysisApi.ts`
- `frontend/src/hooks/useAnalysis.ts`
- 관련 조건검색/admin 컴포넌트

목표:

- run timeline, partial/stale/AI unavailable 상태 표시.
- 관리자 승인 UI.
- 기존 main/section 화면 회귀 방지.

### Worker E: Evaluation/Monitoring

소유 영역:

- `backend/src/main/java/com/bitman/justbuy/controller/MonitorController.java`
- `backend/src/main/java/com/bitman/justbuy/service/TrackRecordService.java`
- 성과/평가 DTO와 admin endpoint

목표:

- runId/formulaVersion/modelVersion 기반 평가.
- parse rate, hallucination reject, freshness, 0/1/3/5일 조건성과 지표.

## 핸드오프 형식

각 에이전트는 완료 시 다음을 보고한다.

- 변경 파일.
- 실행한 하네스.
- 실패/스킵한 하네스와 이유.
- 남은 리스크.
- 다른 에이전트와 충돌 가능성이 있는 파일.
