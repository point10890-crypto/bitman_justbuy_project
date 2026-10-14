# Claude 검색 통합 엔드포인트 운영 검증 — 2026-10-14

## 반영 대상

- Claude 커밋 `adf37ca`(실데이터 검색과 일봉 엔진 결합), `1b2f446`(홈 검색 버튼).
- 최초 확인 시 두 Pages 주소는 이미 최신 Claude 번들을 제공했고, MiniPC의 `agent.py`도 커밋과 일치했다. 8090 프로세스는 해당 파일 갱신 후 시작된 상태였다.
- 운영 혼합 검색에서 실제로 `object is not iterable` 오류가 발생했다. 엔진의 `excluded_codes` 객체를 프론트가 배열로 취급한 것이 원인이었다.
- 수정·검증·배포 소스: `12c611b14c5c645b6532f509fea503d828e91ac5`.

## 수정 내용

1. 실제 API 계약인 종목 코드 → 제외 사유 배열 객체를 그대로 처리한다.
2. 수급 조회 실패·UNAVAILABLE·UNSUPPORTED를 일봉 단독 종목으로 대체하지 않는다.
3. 교집합을 확정한 뒤 위험 정보를 보강한다. 보강 실패가 교집합을 없애거나 일봉 단독 후보를 노출하지 않는다.
4. 교집합은 엔진 순위와 요청한 `top_n`을 유지한다.
5. `오늘`·`최근 N거래일`은 실시간 수급 검색에 전달한다. 충돌·지원 범위 밖 기간도 삭제하지 않고 기존 수급 파서가 검증한다.
6. 혼합 검색의 과거 성과는 일봉 조건만 검증한 값이며, 수급을 결합한 성과는 미검증이라고 두 화면 경로에서 표시한다.
7. PWA 캐시를 `bitman-v20261014-search-integration-r14`로 갱신했다.

## 로컬 검증

- 원래 프론트 결함 7건의 실패를 확인한 뒤 수정했다. 실데이터 검색이 주도하는 혼합 검색의 성과 표시도 실패를 확인한 뒤 수정했다.
- Python 기간 보존 회귀 5건이 수정 전 실패했다.
- Python 엔진 전체: 80 통과. 기존 Starlette/httpx 경고 1건.
- 프론트 전체: 25 파일, 264 테스트 통과. 타입 검사와 Vite 빌드 통과.
- 백엔드: 124 클래스, 1,190 테스트, 실패 0·오류 0·기존 건너뜀 1. 배포 스크립트에서도 테스트를 재실행했다.
- 별도 읽기 리뷰에서 이번 수정 범위의 남은 출시 차단 결함을 발견하지 않았다.

## 배포 및 보존

- Python `agent.py`만 기존 실행 작업을 통해 교체·재시작했다. 준비 상태와 기존 데이터 버전을 확인하고 실패 시 복원하도록 구성했다.
- Python 백업: `C:/bitman_justbuy/backups/condition-engine-agent-20261014-123937.py`.
- `scripts/deploy-minipc.ps1`로 Spring 정적 화면·JAR을 빌드하고 테스트 후 배포했다. `SkipTests`를 사용하지 않았다.
- 회원 DB 백업: `predeploy-justbuy-db-20261014-124013.mv.db`, 8,544,256 bytes.
- 기존 회원·구독 DB, JWT·API 환경 설정, 엔진 `var` 시세·원장을 덮어쓰지 않았다.
- `npm run deploy`로 main 및 production Pages를 반영했다.

## 운영 증거

- MiniPC Spring `/api/health`: HTTP 200, `ok`, `justbuy-api`.
- 공개 `https://api.bit-man.net/api/health`: HTTP 200, `ok`, `justbuy-api`.
- 엔진 `/engine/health`: HTTP 200, `ready`, `2026-10-13:2591`, 2,591 코드.
- `/engine/regime`, `/engine/strategies`, `/engine/risk`: HTTP 200. 위험 조회 `030000`은 `found=true`, 기준일 2026-10-13.
- 실제 번역 `오늘 기관 순매수 20일선 위 3개`: OpenAI 응답, `realtime_query=오늘 기관 순매수`, `top_n=3`.
- 인증 없는 공개 `/api/engine/health`, `/api/engine/strategies`는 401을 유지했다. 엔진 포트를 외부에 직접 열지 않았다.
- 로그인된 실제 `https://bitman-justbuy.pages.dev/`에서 번역 → 검색 → 과거 성과 표시를 검증했다.
- `60일선 위 저변동 3개`: 분석 대상 868, 통과 87, 위험 제외 1, 실제 DOM 종목 카드 3개(제일기획·HS애드·KT&G).
- 이전에 오류가 났던 `외국인 순매수 60일선 위 저변동 10개`: 반복 오류가 사라졌고 미확인 수급 자료를 표시하며 일봉 단독 후보를 결합 결과로 노출하지 않았다.
- `최근 1거래일 외국인 순매수 20일선 위 3개`: 실제 1거래일 기간 유지, 수급 조건 통과 6개, 확인된 교집합 0개. 자료 일부 확인 중 표시와 일봉만의 과거 성과 안내를 확인했다.

## 파일 대조

| 파일 | SHA256 |
|---|---|
| 운영 JAR / 로컬 빌드 JAR | `e436ef93a509e91a20fa9df75be684cd72af046792f36f92672905e2365a1161` |
| 운영 `agent.py` / 배포 소스 | `2ff1efa37b67ddee91d622486c1aa645088f985556ac62222b87e8acb4c7209f` |
| `assets/index-DgP2LiGi.js` | `d5f267a90bd333f4f923ae4978095d117b1268ad0d1ce9635325eeb4f3e6e562` |
| `assets/index-YeJYe1Tn.css` | `2539ddef50c7942197d50bec64bce12f12641dfc51c70181053410a5b74b1bcc` |
| `sw.js` | `1f3f42d8a35aa8268757324e3f0f776d051417cc474bc5f0e1936419db15cb82` |

`https://bitman-justbuy.pages.dev/`와 `https://production.bitman-justbuy.pages.dev/`의 HTML은 새 JS를 참조하며, 위 JS·CSS·SW의 해시는 로컬 빌드와 각각 일치했다.

화면 증거(로컬 빌드 폴더):
- `C:/bitman_justbuy_project/backend/build/verification/claude-search-endpoint-live-20261014.jpg`
- `C:/bitman_justbuy_project/backend/build/verification/search-flow-partial-20261014.jpg`

## 검증 한계

일봉은 최근 확정 종가인 2026-10-13을 사용한다. 2026-10-14 장중 임시 일봉은 확정 자료로 사용하지 않는다. 수급은 조회 범위와 공급자별 확인 대기가 남아 있어 전체 시장의 수급을 모두 검증한 상태가 아니다. 실제 조회에서 확인된 양의 교집합 사례는 이번 검증에 없었으며, 교집합 순위·개수·장애 처리는 회귀 테스트로 검증했다. 엔드포인트 활성화와 과거 통계 표시가 수익성을 입증하지는 않는다.

주 작업 폴더의 기존 crypto 변경·생성 데이터·미추적 운영 파일은 그대로 보존했다.
