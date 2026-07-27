# 종가매매 추천종목 히스토리 — 설계

- 작성일: 2026-07-27
- 상태: 승인됨

## 목적

홈 화면 종가매매 카드는 당일 TOP3만 보여준다. 과거에 어떤 종목이 추천됐는지,
그리고 그 추천이 실제로 맞았는지 확인할 방법이 없다. 이 두 가지를 함께 제공한다.

## 요구사항

1. 과거 날짜별 종가매매 추천종목 목록 조회
2. 추천의 사후 성과 검증 — **익일 종가 수익률 + 익일 고가 기준 최대수익률**
3. 기간 집계 (승률, 평균 수익률, 목표가/손절가 도달률)
4. 성과 검증은 적용 시점 이후분부터. 과거 아카이브는 목록만 제공하고 `미검증` 표기
5. 전용 페이지로 노출 (`/history/closing-bet`)

## 현행 구조 (변경 전)

- `data/jongga_v2_results_YYYYMMDD.json` — Python 엔진(`engine/generator.py`)이 생성하는 날짜별 아카이브.
  프로덕션(미니PC)에는 20260724까지 존재하며 파이프라인은 정상 동작 중.
  (로컬 `C:\bitman_justbuy_project\data`는 2026-03-03에서 멈춰 있으므로 개발 시 주의)
- `JonggaV2SearchService` — 아카이브 파일 읽기 (`latest()`, `dates()`, `history(date)`, `search()`)
- `JonggaV2Controller` — `/api/kr/jongga-v2/{latest,dates,history/{date},search}`
- `MainConditionService.closingBetSection()` — 아카이브 상위 3건을 홈 카드용 DTO로 변환
- `TrackRecordService` — 단타(`BREAKOUT`) 당일마감 검증, 스윙(`REVERSAL_EDGE`) 1/3/5일 추적.
  **종가매매(`JONGGA_V2`)는 기록 대상이 아니었음**
- `AnalysisTrackRecord` 엔티티 + `TrackRecordRepository`
- `KisApiService.fetchCurrentPrice()` — 현재가·시가·**고가·저가**를 함께 반환

## 채택 방식

**DB 추적 + 아카이브 병합.**

D+1 장마감 후 스케줄 잡이 전일 아카이브를 읽어 `AnalysisTrackRecord(mode="JONGGA_V2")`로
기록하고 KIS로 즉시 검증한다. 조회 시 아카이브(목록)와 DB(성과)를 병합한다.

기존 단타/스윙 추적 체계, 엔티티, 스케줄 패턴을 그대로 재사용하므로 새 개념이 늘지 않는다.

### 탈락한 대안

- **파일 기반** (`data/jongga_v2_performance.json`에 검증 결과 축적) — 마이그레이션은 불필요하지만
  성과 추적이 DB/파일로 이원화되고 집계·백업 부담이 늘어난다.
- **조회 시 실시간 계산** — KIS 현재가 API는 과거 종가/고가를 주지 못한다.
  일봉 API 신규 연동이 강제되며 "성과는 적용 시점 이후부터" 결정과 어긋난다.

## 설계

### 1. 데이터 모델

`AnalysisTrackRecord`를 `mode = "JONGGA_V2"`로 재사용한다.

| 기존 필드 | 종가매매에서의 의미 |
|---|---|
| `analysisDate` | 추천일 (아카이브 `signal_date`) |
| `stockCode` / `stockName` | 종목 |
| `action` | 등급 (S/A/B) |
| `consensusScore` | 점수 `score.total` (17점 만점) |
| `priceAtAnalysis` | 진입가 (`entry_price`, 없으면 `current_price`) |
| `targetPrice` / `stopLoss` | 목표가 / 손절가 |
| `closePrice` / `closeReturn` | **익일 종가 / 익일 종가 수익률** |
| `closeVerifiedAt` | 검증 시각 |
| `hitTarget` / `hitStop` | 익일 고가 ≥ 목표가 / 익일 저가 ≤ 손절가 |
| `status` | 검증 완료 시 `COMPLETED` |

신규 컬럼 2개:

| 컬럼 | 타입 | 용도 |
|---|---|---|
| `high_price1d` | BIGINT | 익일 장중 고가 |
| `max_return1d` | DOUBLE PRECISION | 진입가 → 익일 고가 최대수익률 |

컬럼명은 Hibernate 기본 네이밍(`CamelCaseToUnderscoresNamingStrategy`)을 따른다.
숫자 앞에는 언더스코어가 붙지 않으므로 `highPrice1d` → `high_price1d` 이며,
기존 `price1d` / `return1d` 와 같은 규칙이다. 이름이 어긋나면 prod(`ddl-auto: validate`)
기동이 실패한다.

Flyway 마이그레이션 `V20260727_1200__add_jongga_high_tracking.sql` 추가.
prod 프로파일은 `ddl-auto: validate`이므로 마이그레이션 없이는 기동이 실패한다.

### 2. 기록 + 검증 (스케줄 잡 1개)

`JonggaTrackRecordService.verifyPreviousDayJongga()` — **매 영업일 15:40 KST** (`0 40 15 * * MON-FRI`)

1. 오늘이 영업일이 아니면 스킵
2. 직전 영업일(`KoreanMarketCalendar`로 역산)의 아카이브를 `JonggaV2SearchService.history()`로 읽음
3. 아카이브 `signals` 상위 3건에 대해, DB에 없으면 레코드 생성
   (중복 방지: `existsByModeAndAnalysisDateAndStockCode`)
4. 아직 `closePrice`가 비어 있는 레코드에 대해 `kisApiService.fetchCurrentPrice()` 1회 호출 →
   현재가(= 당일 종가), 고가, 저가 확보
5. 계산 후 저장
   - `closeReturn` = (종가 − 진입가) / 진입가 × 100
   - `maxReturn1d` = (고가 − 진입가) / 진입가 × 100
   - `hitTarget` = 고가 ≥ 목표가, `hitStop` = 저가 ≤ 손절가
   - `status = COMPLETED`

기록과 검증을 한 잡으로 묶는 이유: 아카이브가 날짜별 파일이라 추천 당일에 미리 스냅샷을
뜰 필요가 없고, 잡이 하나면 실패 지점도 하나다.

**기록 대상은 홈 카드에 노출되는 상위 3종목만.** 사용자에게 실제로 보여준 추천의 성과여야
승률 통계가 정직해진다.

수동 재실행용 진입점 `verifyJonggaFor(LocalDate recommendedDate)`를 public으로 열어둔다
(운영 중 잡 실패 시 복구, 테스트에서 사용).

### 3. 조회 API

`GET /api/kr/jongga-v2/performance?from=YYYY-MM-DD&to=YYYY-MM-DD`

- 기본 범위: `to` = 오늘, `from` = 오늘 − 30일
- 응답 `JonggaPerformanceResponse`
  - `from`, `to`, `mode`, `title`
  - 집계: `totalSignals`, `verifiedCount`, `wins`/`losses`/`flats`,
    `avgCloseReturnPct`, `avgMaxReturnPct`, `winRate`, `targetHitRate`, `stopHitRate`
  - `days[]` — 날짜별 그룹
    - `date`, `verified`(그날 전 종목 검증 완료 여부), `rows[]`
    - `rows[]` — `rank`, `stockName`, `stockCode`, `grade`, `score`,
      `entryPrice`, `targetPrice`, `stopLoss`,
      `closePrice`, `closeReturnPct`, `maxReturnPct`, `hitTarget`, `hitStop`, `result`
  - `result` — `승` / `패` / `보합` / `미검증`
  - `note` — 상태 설명 문구

병합 규칙: 아카이브에서 날짜별 상위 3건 목록을 만들고, DB 레코드를 `(analysisDate, stockCode)`로
조인해 성과 필드를 채운다. DB에 없으면 성과 필드는 `-`, `result`는 `미검증`.

기존 `/dates`, `/history/{date}`, `/search`, `/latest`는 그대로 둔다.

### 4. 프론트엔드

- `frontend/src/api/conditionApi.ts` — `JonggaPerformanceResponse` 타입 + `fetchClosingBetPerformance(from, to, token)`
- `frontend/src/pages/ClosingBetHistoryPage.tsx` — 신규 페이지
  - 기간 프리셋 7일 / 30일 / 90일
  - 상단 집계 카드 (검증 건수, 승률, 평균 종가 수익률, 평균 최대 수익률, 목표가 도달률)
  - 날짜별 섹션 + 종목 행 (진입가 → 익일 종가, 수익률, 최대수익률, 결과 배지)
  - 비구독자는 홈과 동일한 `maskValue` 규칙 적용
- `frontend/src/main.tsx` — `/history/closing-bet` 라우트를 `SubscribedRoute` + `AppLayout` 하위에 추가
- `frontend/src/pages/HomePage.tsx` — 종가매매 카드에 "히스토리" 버튼 → 해당 페이지로 이동

### 5. 에러 처리

- 아카이브 파일 없음 → 해당 날짜는 목록에서 제외 (예외 아님)
- KIS 호출 실패/미설정 → 해당 종목은 미검증으로 남는다.
  재시도는 **같은 평가일 안에서만** 한다 (15:40 1차 / 16:10 2차).
  다음 영업일에 재시도하면 다른 날 종가를 그날 값으로 기록하게 되므로 하지 않는다.
  그날 확정하지 못한 레코드는 영구 미검증으로 표시된다
- 진입가가 0 이하 → 수익률 계산 불가이므로 검증 스킵
- 프론트 조회 실패 → 페이지에 오류 문구, 홈은 영향 없음

### 6. 테스트

- `JonggaTrackRecordServiceTest`
  - 승/패/보합 판정
  - 익일 고가 ≥ 목표가 → `hitTarget`, 익일 저가 ≤ 손절가 → `hitStop`
  - 중복 기록 방지 (같은 날짜·종목 재실행 시 레코드 1건)
  - KIS 응답 비어 있을 때 미검증으로 남는지
  - 상위 3건만 기록되는지
- `JonggaV2ControllerTest` / `JonggaPerformanceServiceTest`
  - DB 레코드가 없는 과거 날짜가 `미검증`으로 병합되는지
  - 기간 집계 계산 (승률, 평균)
- 기존 `MainConditionServiceTest`, `JonggaV2SearchServiceTest` 회귀 확인

## 범위 밖

- 과거 아카이브 소급 검증 (KIS 일봉 API 신규 연동 필요)
- 단타/스윙 성과 페이지 (이미 API는 있으나 화면 미구현 — 별건)
- 종가매매 TOP3 밖 종목의 성과 추적
