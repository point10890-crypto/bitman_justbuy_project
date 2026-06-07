# AI 에이전트 조건검색 하네스 규율

작성일: 2026-06-07 KST
기준 저장소: `C:\bitman_justbuy_project`

## 기본 규율

1. 시작 전 `git status --short`로 기준선을 기록한다.
2. `.env`, API 키, 토큰, 운영 DB 원본, 회원/구독 원본 데이터는 열람하거나 출력하지 않는다.
3. 기존 dirty 파일은 사용자 변경으로 간주하고 되돌리지 않는다.
4. `backend/data/*.json`, `frontend/data-snapshot/*.json`, `logs`, `tmp-*`, static build asset은 구현 검증 목적이 아니면 수정하지 않는다.
5. 금융 도메인 변경은 자동 주문/매수/매도 확정으로 이어지면 중단한다.
6. 새 LLM 출력은 schema validation 전에는 저장/게시/알림에 사용하지 않는다.
7. 실제 KIS/DART/AI 외부 호출이 필요한 테스트는 사용자가 런타임 검증을 요구했거나 명확히 승인한 경우에만 실행한다.
8. PRO 구독, 관리자 권한, 회원 등급/상태를 바꾸는 테스트는 운영 원본이 아닌 테스트 컨텍스트에서만 실행한다.

## 로컬 구현 하네스

### 백엔드 단위 검증

관련 영역별로 먼저 좁은 테스트를 실행한다.

```powershell
cd C:\bitman_justbuy_project\backend
.\gradlew.bat test --tests com.bitman.justbuy.service.MainConditionServiceTest
.\gradlew.bat test --tests com.bitman.justbuy.service.TrackRecordServiceTest
.\gradlew.bat test --tests com.bitman.justbuy.ai.ConsensusEngineTest
```

AI provider 어댑터를 수정한 경우:

```powershell
cd C:\bitman_justbuy_project\backend
.\gradlew.bat test --tests com.bitman.justbuy.ai.agent.ChatGptAgentTest
.\gradlew.bat test --tests com.bitman.justbuy.ai.agent.DeepSeekAgentTest
.\gradlew.bat test --tests com.bitman.justbuy.ai.agent.GrokAgentTest
```

전체 백엔드 게이트:

```powershell
cd C:\bitman_justbuy_project\backend
.\gradlew.bat test
```

구독/운영 경계에 닿는 변경은 다음 테스트가 깨지면 배포 금지다.

- `SubscriptionWorkflowServiceTest`
- `SubscriptionExpiryResilienceTest`
- `KisApiRetryTest`
- `TelegramNotifierTimeoutTest`
- AI agent adapter tests

### 프론트엔드 검증

```powershell
cd C:\bitman_justbuy_project\frontend
npm run build
```

UI를 변경한 경우 브라우저 smoke를 수행한다.

- landing/login/register가 깨지지 않는다.
- PRO 보호 라우트가 비구독자에게 노출되지 않는다.
- 메인 조건검색 카드가 로딩, empty, stale, partial 상태를 표시한다.
- 섹션 상세가 `/api/conditions/{section}` 응답을 정상 렌더링한다.
- 관리자 화면을 변경한 경우 run timeline, system status, refresh action을 확인한다.

## API Smoke

로컬 서버를 띄운 작업에서는 다음을 확인한다.

- `/api/health`는 `status=ok`, `service=justbuy-api`를 반환한다.
- `/api/main`은 비밀값 없이 조건검색 섹션과 sourceStatus를 반환한다.
- `/api/conditions/{section}`은 5xx 없이 empty/stale/partial 상태를 명확히 반환한다.
- `/api/analysis/live`와 `/api/analysis/job/{jobId}`를 변경한 경우 인증/PRO 게이트와 job timeout 처리가 유지된다.
- `/api/admin/*`를 변경한 경우 일반 사용자 인증 우회가 없어야 한다.

## 조건검색 전용 하네스

1. `sourceStatus`가 실제 데이터 상태와 일치해야 한다.
2. LLM verifier 실패 시 `AI_UNAVAILABLE` 또는 `PARTIAL`로 표시해야 한다.
3. 종목코드가 6자리 숫자가 아니면 publish 금지.
4. 표준 종목명 검증 실패, 비상장, ETF/ETN/스팩성 상품은 publish 금지.
5. 현재가가 없으면 목표가/손절가를 생성하지 않는다.
6. 목표가는 현재가보다 높고 손절가는 현재가보다 낮아야 한다.
7. 같은 run에서 동일 종목 중복 publish를 금지한다.
8. 알림은 published signal만 소비한다.
9. 사용자 opt-in 없는 개인 알림은 금지한다.
10. 대량 알림 또는 새 문구 템플릿은 human approval 없이는 발송하지 않는다.

## 실패 시 중단 기준

- 테스트가 실패했는데 원인을 설명하거나 격리하지 못한 경우.
- schema validation 없이 LLM 결과를 저장/게시해야 하는 경우.
- 비밀 파일 열람이 필요해 보이는 경우.
- PRO 구독 상태나 회원 데이터를 변경해야 하는 경우.
- 운영 DB 원본에 직접 쓰기가 필요한 경우.
- 알림 발송 범위가 불명확한 경우.
- 금융 자문/수익 보장처럼 보이는 문구가 필요해지는 경우.

## 배포 전 게이트

MiniPC 배포는 로컬 하네스 통과 후에만 진행한다.

```powershell
cd C:\bitman_justbuy_project
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-minipc.ps1
```

배포 스크립트는 H2 DB 백업, JAR 교체, Task Scheduler 재시작, health check, 실패 시 롤백을 보장해야 한다. 배포 후에는 로컬 MiniPC health와 공개 API health를 모두 확인한다.

배포 작업은 이 문서만으로 진행하지 않는다. 반드시 `bitman-service-ops` 절차를 함께 적용한다.

중단 기준:

- 사용자가 명시하지 않은 `-SkipTests`.
- 백업 없는 JAR 교체 또는 DB 덮어쓰기.
- `C:\bitman_justbuy`와 다른 서비스 루트가 섞이는 경로 혼동.
- `api.bit-man.net -> localhost:8080` 경로 확인 실패.
- MiniPC 로컬 health 또는 공개 health 실패.
