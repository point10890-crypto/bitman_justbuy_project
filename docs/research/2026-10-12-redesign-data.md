# BitMan 재설계 연구: 데이터와 결정론적 조건검색

작성·공식 문서 조회일: 2026-10-12 (Asia/Seoul). 연구 노트이며 구현 완료 또는 운영 성능 검증을 뜻하지 않는다. 제품 코드, 운영 API, 키, 회원 DB, 배포를 변경하지 않았다. 기존 루트 `research/`는 별도 미추적 작업이므로 읽거나 수정하지 않았다.

최종 범위와 용어의 기준은 [통합 설계](../superpowers/specs/2026-10-12-bitman-research-core-design.md)다. 아래 AST·확장 수량·운영 대안은 설계 참고이며 공급자 한도나 실제 달성 성능으로 확정한 값이 아니다.

## 결론

제가 처음부터 설계한다면 **한국 주식의 시점별 근거 저장소 → 결정론적 검색 실행기 → 근거를 읽는 AI 연구팀 → 시간 순서 성과평가** 순서로 만듭니다. API를 사용자 검색마다 모으는 현재 방식에서, 공급자별 수집기가 자료를 미리 축적하고 검색은 같은 버전의 저장된 자료에서 실행하는 방식으로 바꿉니다. 분석력을 키우는 핵심은 많은 모델의 투표보다, 더 넓고 편향이 적은 대상·맞는 시점·정확한 조건식·추적 가능한 근거입니다.

추천은 Spring Boot의 모듈형 단일 서비스와 별도 연구 데이터 저장소입니다. 운영 계정·구독 DB와 연구 자료를 분리하고, KIS를 주 소스로 유지하며 Kiwoom REAL/MOCK의 사실을 독립 보존합니다. MOCK 환경을 가상 숫자로 간주해 차단하지 않으며, 각 필드가 실제 시장 관측인지·지원되는 거래소인지·완성 자료인지 검사합니다. 같은 거래소 시세의 KIS/키움 일치가 서로 독립적인 경제적 전망 두 개를 뜻하지는 않습니다.

## 1. 현재 코드에서 확인한 공백

근거: `docs/ANALYSIS_STRENGTHENING_RND_2026_10_12.md` 및 2026-10-12 현재 소스. 이번 연구는 새로운 라이브 조회를 수행하지 않았습니다.

| 현재 파일·동작 | 재사용할 부분 | 재설계할 부분 |
|---|---|---|
| `KeywordSearchDataService` | Quote·Flow의 공급자 구분, 호출 제한, 단일 실행 중복 제거 | 기본 네이버 시가총액 KOSPI/KOSDAQ 각 200개를 전 시장 universe와 구분. 네이버 모바일 내부 API 의존을 장기 공식 데이터 계약으로 대체 |
| `StockUniverseService` | KIS 공식 마스터, 코드·시장·제외 상태 검증, 완전 스냅샷 교체 | 현재 메모리 24시간 자료는 과거 상장·퇴출 이력을 보존하지 않음. ISIN·유효기간·증권 유형·거래소별 코드·상장/정지/폐지 이벤트 보존 |
| `KisCandidateSourceService` | 30종목 다중 시세, HTS/순위 후보의 실제 코드 확인 | 현재 최대 600개 및 코드순 padding은 전 시장 검색이 아님. 코드순 편향 제거, 순환 스캔과 명시적 대상 분모, 후보 생성 이유 보존 |
| `KeywordConditionSearchService` | 실측값이 없을 때 대신 숫자를 만들지 않음, KIS/키움 관측 독립 유지 | 현재 Boolean 제외 + 경고를 TRUE/FALSE/UNKNOWN과 사유로 승격. cheap 조건 통과·정렬 이후 cross 20개만 깊게 조회하는 선택 순서가 전체 조건의 top K를 보장하지 못함 |
| `KeywordSearchQueryParser` | 명시적 수치·단위·지원 조건·모순 검증 | AND 중심 정규식에서 타입을 가진 AST/DSL로 이행. OR/NOT·기간·대상·시장·가격 기준·순위 적용 순서 명시 |
| `KisRestThrottle` | 기존·확장 KIS REST 공통 spacing | 현재 120ms 상수를 계정/환경/권한별 정책으로 분리. 신규 고객 유량 변경 공지에 대응 가능한 버전 정책 필요 |
| `KiwoomReadOnlyService` | ka10095 복수 시세, 실제 일자/체결시각, bounded transport·독립 환경 | 현재 MOCK 전체 호출을 1.01초 간격으로 직렬화. 공식 제한은 MOCK **TR별** 1초 1회이므로 global 예산과 per-TR 예산을 구분해 검증 후 확대 가능 |
| `DartFinancialEvidenceService` | CFS/OFS·KRW·계정 ID·기간·접수번호·불완전 상태 | 현 5종목/10초 경로를 후보 사전 캐시로 확장하고, 과거 공표/정정 시점과 당시 버전 보존 |
| `DartCorpCodeMapper`·maintenance | 검증·원자적 저장·갱신·이전 유효 자료 보존 | 기업-증권 다대다 유효기간 연결로 확장. DART corp code는 종목 자체의 영구 ID가 아님 |
| `DartDisclosureSearchService` | list.json·접수번호·종목 링크 기반 | 현재 휴면 컴포넌트이며 첫 페이지 100건·제목 태그 중심. 전체 페이지/워터마크·정정/철회 연결·본문 사실 확인 후 활성화 |
| `DeterministicTechnicalEvidence` | 완료 수정 일봉, 엄격한 날짜/이력/산식 | 지표별 feature definition version·부모 fact ID·같은 날짜 벤치마크·시장 폭·조정 버전 보존 |
| `EvidenceFact`·`EvidenceClaimGuard` | 출처·종목·원수치 일치, AI 자유 수치 차단 | 시점/자료의 계보를 canonical fact envelope에 통일. 서버 수치 일치만으로 경제적 사실의 정확성이나 예측력을 보증하지 않음 |
| `AnalysisEvidenceJournalService` | 제한된 공개 자료 감사·비밀 분리 | 30일/1,000파일은 전체 역사 저장소가 아님. 핵심 자료·버전·선택/탈락 후보를 별도 영속 저장 |

### top K 의미가 특히 중요합니다

사용자 문장 `상승률 상위 20 PER 100 이하`는 일반적으로 **PER 조건을 통과한 종목 중 상승률 상위 20**을 뜻합니다. 상승률 상위 20부터 뽑아 그중 PER 통과 종목만 남기는 것은 다른 조건식입니다. 현재 cross 20개만 조사하여 2개가 남았다면, 다음 순위의 종목이 PER 조건에 맞는지 아직 모릅니다. 결과 2개를 오류로 단정하지는 않되, 전체 대상에서 가장 좋은 2개 또는 조건 일치가 총 2개라고 표시하면 안 됩니다.

순위 기준의 cheap metric이 모든 대상에서 완전하고 정렬되어 있다면, 상위부터 추가 사실을 채워 K개의 TRUE를 얻고 **그보다 순위가 높은 UNKNOWN이 하나도 없을 때** top K를 확정할 수 있습니다. 호출 예산이 끝나거나 순위 자체의 자료가 누락되면 `PROVISIONAL_TOP_K`입니다. 이 구분이 후보 확장과 비용 절약을 함께 가능하게 합니다.

## 2. 세 가지 대안과 선택

| 대안 | 이득 | 한계·비용 | 결정 |
|---|---|---|---|
| A. 현재 요청 시 조회·JSON 캐시를 더 크게 확장 | 이행 빠름, 기존 운영과 호환 | 사용자 요청마다 호출 지연·부분 상태, 동시 요청 경쟁, 과거 재현/선택편향 해결 어려움 | 단기 호환 어댑터로만 사용 |
| B. 공급자 수집기 + 시점별 연구 DB + 로컬 검색/feature 엔진 | 저비용·API 재사용·동일 snapshot 재현, 오류 격리, 조회 수와 UI 요청 분리 | schema/수집 상태/시점 정책 구축 필요, 유료 이용·저장 권한 확인 필요 | **추천** |
| C. 상업용 전 시장/기업행위/뉴스 feed와 별도 warehouse/stream platform | 계약된 전 시장·정정·폐지 이력, 장중 coverage 확대에 유리 | 데이터 비용과 운영 복잡도, 계약 및 공급자 의존 | B의 평가로 필요성이 입증된 뒤 특정 공백만 구입 |

지금 규모에는 Kafka/벡터 DB/복수 마이크로서비스를 초기 필수 조건으로 삼지 않습니다. 수집 job, 연구 DB, Spring query API, 원본 object/file 저장을 명확히 나누면 공급자 교체와 이후 확장이 가능합니다. 영속 연구 DB는 새 namespace/저장소로 시작하고 회원 DB는 그대로 둡니다. 정확한 엔진 선택은 백업 복구·쓰기 경쟁·용량 측정을 거쳐 정하며, Postgres가 필요한 병렬 쓰기/운영 편의가 확인되면 채택하고 단일 writer 저비용 구성도 비교합니다.

## 3. 공통 데이터 계약

수치를 가진 모든 자료는 bare number 대신 아래 envelope를 가집니다. 조회 시각을 공표 시각이나 거래 시각으로 대체하지 않습니다.

| 필드 | 의미·검사 |
|---|---|
| `instrumentId`, `issuerId` | 내부 영구 증권/법인 식별자. ticker·ISIN·DART corp code는 기간이 있는 외부 식별자 |
| `source`, `sourceEndpointVersion`, `environment` | KIS/KIWOOM/DART 등 공급자와 REAL/MOCK. 환경은 데이터 진위 판정 자체가 아님 |
| `venue`, `session` | KRX/NXT/통합 범위 및 정규장/시간외/종가. 서로 다른 범위를 무단 합산하지 않음 |
| `metricId`, `metricDefinitionVersion` | close, turnover, per, operatingCashFlow 등 정확한 정의 |
| `value`, `unit`, `currency`, `scale` | BigDecimal, shares/KRW/ratio/percent, 원자료 배수. 단위 미확정은 UNKNOWN |
| `asOf`, `periodStart`, `periodEnd`, `periodType` | 가격의 기준 시점 또는 재무/수급 측정 기간. 분기 손익·누적 현금흐름·기말 잔액 분리 |
| `observedAt`, `timestampPrecision`, `timeBasis` | 원 공급자 관측 시각. date-only/second/unknown, provider clock/거래일 출처 |
| `publishedAt`, `availableAt`, `availabilityBasis` | 최초 공표 시각 및 의사결정에 이용 가능했던 시각. 일자만 있는 경우 exact instant를 만들지 않음 |
| `firstSeenAt`, `fetchedAt`, `ingestedAt` | 플랫폼이 처음 본 시각(불변), 이번 받은 시각, 검증 저장 시각. 재조회가 과거 firstSeen을 바꾸지 않음 |
| `adjustmentMode`, `adjustmentVersion` | RAW/SPLIT_ADJUSTED/TOTAL_RETURN/UNCONFIRMED와 조정 이벤트 버전. 소스의 단순 '수정주가'를 총수익률로 간주하지 않음 |
| `qualityState`, `missingReason` | VALID/INVALID/UNKNOWN/STALE/CONFLICT와 근거. null과 0을 구분 |
| `sourceRecordId`, `revisionId`, `supersedes` | 접수번호/row identity/정정 관계. 최신본이 이전본을 삭제하지 않음 |
| `parentFactIds`, `transformVersion`, `payloadHash` | 계산과 입력 자료의 계보·무결성. 해시는 비밀 없는 허용된 payload만 대상으로 함 |
| `licenseProfileId`, `retentionPolicyId` | 표시/회원 제공/Telegram/저장/AI 가공이 허용된 범위를 별도로 판정 |

**Live replay**는 당시 `ingestedAt <= decisionAt`인 자료만 사용합니다. **Historical research**는 신뢰 가능한 공개 시각과 그 당시 revision이 확인된 자료만 사용합니다. 지금 받은 과거 재무 값의 `periodEnd`가 과거라는 이유로 과거 의사결정에 사용할 수 없습니다. historical availability 미확인은 별도 표본으로 제외하거나 보수적 lag를 명시한 민감도 실험에만 사용합니다. DART 접수일만 확인되면 공시 당일 장중 이용 시각을 추정하지 않으며 첫 실제 수신 시각 또는 보수적으로 다음 거래 세션부터 반영합니다.

Feature와 label의 역할도 나눕니다. 미래 주식분할을 포함하여 오늘 공급자가 재조정한 일봉은 경제적 수익률 계산에 도움이 될 수 있지만, 과거 분석 당시의 가격 수준·돌파 신호를 완벽하게 복원했다고 볼 수 없습니다. exact replay는 당시 캡처 또는 기업행위의 as-known 버전이 필요합니다.

## 4. universe와 시장 범위

`UniverseSnapshot`에 기준일·유효시간·소스·총증권 수·시장·증권 유형·상장/폐지/정지·관리 상태·누락 수를 보존합니다. 현재 universe뿐 아니라 새 상장과 폐지를 append-only 이력으로 관리하여 생존편향을 줄입니다. 보통주를 기본 투자 대상으로 하고 우선주/리츠/ETF/ETN/SPAC 등은 별도 집합과 별도 feature 정의로 다룹니다. 거래정지는 전략별 제외 상태이지 영구 identity 삭제가 아닙니다.

전 시장 identity가 있다고 전 시장의 실시간 가격·PER·일봉이 있는 것은 아닙니다. UI/관리 화면에는 네 개의 coverage를 구분합니다.

1. **Identity coverage**: 대상 증권 identity를 확보한 수 / universe 목표 수.
2. **Quote coverage**: 필요한 거래소/세션의 허용된 시점 가격을 확보한 수 / 검색 대상 수.
3. **Predicate coverage**: 조건식 전체를 TRUE/FALSE로 판정 가능한 수 / 검색 대상 수.
4. **AI review coverage**: 조건 통과 후보 중 근거를 AI가 확인한 수 / AI 검토 대상 수.

순위 API·HTS 조건검색은 효율적인 **후보 소스**이지 전 시장 누락이 없다는 증명이 아닙니다. KIS/키움 조건식 이름만 같아도 연산·봉·시장·제외 기준이 다를 수 있으므로 `ProviderConditionMapping`에 버전·HTS 원식·범위·의미·검증 fixtures를 보존합니다. app DSL과 동치성이 증명되지 않으면 그 결과는 seed이며, 조건 일치는 app 실행기가 다시 판정합니다.

코드순 padding 대신 (a) 조건/순위/공시의 event-driven 후보, (b) 유동성·시장·업종을 고려한 strata rotation, (c) 나머지 대상의 순환 스캔을 병행합니다. 모든 선택 이유·조회 못 한 이유를 기록합니다. 특정 모드의 9건이 동일 4종목이면 9개 독립 후보로 보여 주지 않으며, 종목/업종/이벤트 중복을 별도 관리합니다.

장중 시장 폭은 같은 거래소·유효시점·전체 대상분모가 있을 때만 계산합니다. 대형주 400개나 가용600개에서 계산한 상승 종목 비율은 해당 집합의 breadth입니다. ETF를 벤치마크로 사용하면 ETF proxy라고 표기하고 공식 지수라고 부르지 않습니다. 후보와 benchmark의 수익률 기간·거래일·조정 모드가 같아야 상대강도를 계산합니다.

## 5. 호출 예산, 캐시, 운영 흐름

### 공식 확인한 공급자 제약

- 키움 REST 공식 소개: 국내 조회는 계좌/토큰별 초당 5회, MOCK는 TR별 초당 1회, 토큰별 1개 세션, 실시간 시세 200종목·국내 실시간 조건식 10개·조건결과 최대100종목. OpenAPI+의 분당100/시간1,000 규칙을 REST 규칙으로 이식하지 않습니다. 또한 실전 접속 없이 MOCK만 사용하면 미접속 자동해지 정책에 해당할 수 있습니다. 운영 정책 확인 항목이며 자동 실전 로그인 활성화를 뜻하지 않습니다. [키움 공식 소개](https://openapi.kiwoom.com/intro?dummyVal=0).
- KIS 공식 다중 시세는 최대30종목, 일/주/월/년 기간 시세는 호출당 최대100행을 문서화합니다. 현재 포털에 신규 고객 초당 제한 변경 및 유량 변경 공지가 있으므로 KIS의 계정별 고정 상한을 이번 공개 열람만으로 단정하지 않습니다. 기존 120ms 코드를 공급자 공식 허용량으로 부르지 않습니다. [다중 시세](https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/intstock_multprice.py), [기간 시세](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py), [공식 공지 목록](https://apiportal.koreainvestment.com/community/10000000-0000-0011-0000-000000000001/post/).
- 키움 ka10095는 `|` 구분 여러 종목과 KRX/NXT/SOR 코드, `dt`/`cntr_tm`을 제공합니다. 공식 예제에서 최대 batch 숫자는 확인하지 못했으므로 기존20을 초기 운영 가드로 유지하고 지원 배치 한도와 MOCK 가능 범위를 명세/허용된 probe로 확정해야 합니다. [공식 지정종목 예제](https://raw.githubusercontent.com/Kiwoom-Securities/Kiwoom-REST-API/main/examples/국내주식/종목정보/get_domestic_watchlist_stock_info.py).
- 키움 조건 목록 ka10171·일반조회 ka10172는 REST 포털에 속하지만 실제 전송은 `/api/dostk/websocket` request-once입니다. 일반조회에 continuation이 문서화되어 있습니다. 100 결과 cap과 continuation의 실제 동작·MOCK 지원은 테스트 계약으로 확정하고 조건 결과 전체를 무조건100개라고 가정하지 않습니다. [목록 예제](https://raw.githubusercontent.com/Kiwoom-Securities/Kiwoom-REST-API/main/examples/국내주식/조건검색/list_domestic_condition_searches.py), [일반조회 예제](https://raw.githubusercontent.com/Kiwoom-Securities/Kiwoom-REST-API/main/examples/국내주식/조건검색/request_domestic_condition_search.py).

### 추천 스케줄링 계약

`ProviderBudget`는 공급자 + 환경 + credential profile의 global window, TR별 window, 동시 실행 수, 하루 잔여량, caller budget, retry budget을 합성합니다. token/key는 식별자의 원문으로 기록하지 않습니다. 토큰 획득과 시세 job을 single-flight로 공유하고 여러 JVM을 운영할 때도 한 session owner/수집 주체를 둡니다.

호출이 스로틀되면 가능한 경우 Retry-After를 지키고 jitter/cooldown을 적용합니다. 인증 오류는 토큰 갱신을 무한 반복하지 않으며 quota 오류는 전체 provider circuit breaker로 격리합니다. 완료된 단계를 재사용하고 필요한 필드의 실패한 단계만 재수집합니다. HTTP timeout, 큐 제한, 전체 job deadline, 미완료 응답 cap, cancellation을 독립 검증합니다.

캐시 key에는 source/environment/venue/정규화한 parameters/adjustment/schema version을 포함합니다. 양성/음성/오류 캐시를 구분하고, 데이터 freshness는 TTL만이 아니라 관측 시각과 세션 규칙으로 결정합니다. 오래된 자료는 예전 시각 그대로 보여 줄 수 있지만 오늘 조건에 다시 사용하지 않습니다. 모든 갱신은 완전 검증 후 snapshot pointer를 원자적으로 변경합니다. adapter 장애가 universe·회원 서비스·다른 소스 결과를 덮어쓰지 않습니다.

수집 우선순위는 (1) 현재 사용자 후보의 유효성, (2) 예정 발행의 필수 자료, (3) 전 시장 기본 시세의 순환 갱신, (4) 새 후보의 일봉/수급, (5) 과거 backfill입니다. 굶주림 방지를 위해 각 lane의 최소 budget과 최대 지연을 둡니다. 전 시장 기본 시세는 multi-quote로 스캔하되 순환 1회가 걸린 시간과 max observation skew를 표시합니다. 공식권한/상한을 확인한 뒤 `ceil(N/30)`개의 KIS 호출이 기본 sweep 비용이라는 계획치를 사용하고, 지연·누락·재시도까지 측정하여 실제 완료 시간을 판단합니다.

키움 MOCK에서 서로 다른 TR을 병행할 수 있는 여지는 존재하지만 수집기를 무조건5배 빠르게 만들 수 있다고 주장하지 않습니다. 동일 TR 연속 호출 제한·전체5회·실제 허용 권한·네트워크 지연·자료 개수 모두 만족해야 합니다. 큰 universe의 일봉은 야간 incremental 저장으로 미리 준비하고, 고객 검색 시 모든 종목에 새 일봉 요청을 만들지 않습니다.

## 6. 자연어 검색을 검증된 DSL로 변환

AI는 검색 문장을 표준화하고 의미가 모호한 부분을 설명합니다. 실행하는 조건은 allowlist AST입니다. LLM이 SQL/JavaScript/URL/TR를 출력해 직접 실행하는 경로는 두지 않습니다. 숫자·단위·기간·필드 타입·market/venue·지원 상태·모순·최대 깊이/연산량을 컴파일러가 검사하고 canonical hash를 부여합니다. 입력에 없는 조건을 모델이 조용히 추가하지 못하며 원문 구간→AST 노드 연결과 되돌린 한국어 요약을 보존합니다.

다음은 **실행하지 않은 설계 예시**입니다.

```json
{
  "dslVersion": "bitman-condition-v1",
  "universe": {"markets": ["KOSPI", "KOSDAQ"], "instrumentTypes": ["COMMON_STOCK"], "venue": "KRX"},
  "decisionTime": "RUN_SNAPSHOT",
  "where": {"and": [
    {"compare": {"metric": "valuation.per", "operator": "lte", "value": "100", "unit": "ratio", "basis": "PROVIDER_DEFINED"}},
    {"compare": {"metric": "quote.changePct", "operator": "gt", "value": "0", "unit": "percent", "window": "SESSION"}}
  ]},
  "rank": {"metric": "quote.changePct", "direction": "desc", "limit": 20, "phase": "AFTER_FILTER", "ties": "INSTRUMENT_ID"},
  "missingPolicy": "PRESERVE_UNKNOWN",
  "providerPolicy": "KIS_PRIMARY_KIWOOM_SUPPLEMENT"
}
```

`PER`는 공급자별 TTM/전년도/예상 등 정의가 확정되지 않으면 같다고 볼 수 없습니다. 최초 DSL에는 `PROVIDER_DEFINED`를 명시하고 같은 원천에서만 순위/비교하도록 하며, 정의를 확인하면 TTM 같은 별도 metric을 추가합니다. 흑자/적자/분모0/결측을 분리합니다. `52주 신고가`, `돌파`, `순매수 증가`, `싼 종목`, `좋은 뉴스`는 자동으로 임의 숫자 조건으로 바꾸지 않습니다. 정해진 preset 또는 선택 가능한 정의를 보여 주고 적용된 정의를 노출합니다.

AST를 통과해도 자료가 없는 조건은 지원 실패가 아니라 실행 자료의 UNKNOWN일 수 있습니다. `UNSUPPORTED_SYNTAX`, `AMBIGUOUS_INTENT`, `UNSUPPORTED_METRIC`, `UNAVAILABLE_DATA`, `PARTIAL_RESULT`를 구분합니다. 동일 DSL·snapshot·engine version은 결과와 탈락 사유가 같아야 합니다.

### 3값 논리

조건별 `TRUE`, `FALSE`, `UNKNOWN`과 fact ID/원값/측정 기간/사유를 기록합니다. UNKNOWN은 source timeout, stale, identity conflict, 단위 미확정, 기간 부족, revision 미확정 등을 구분합니다. 0으로 대체하지 않습니다. AND/OR/NOT은 SQL과 같은 3값 연산으로 정의합니다. [PostgreSQL 공식 진리표](https://www.postgresql.org/docs/current/functions-logical.html).

| A | B | A AND B | A OR B |
|---|---|---|---|
| TRUE | UNKNOWN | UNKNOWN | TRUE |
| FALSE | UNKNOWN | FALSE | UNKNOWN |
| UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |

`NOT UNKNOWN = UNKNOWN`입니다. 따라서 데이터가 없는 종목이 `NOT(적자)`라는 조건을 통과하지 않습니다. '공시가 없음'도 전체 기간·전체 페이지를 성공적으로 읽은 경우에만 확인 가능한 부재입니다. timeout의 empty list는 '공시 없음'이 아닙니다. 실행 결과에 TRUE 수, FALSE 수, UNKNOWN 수, 가격자료 없음, identity 제외, 정확한 target 분모를 제공하며 분모 합계를 검사합니다.

## 7. DART·뉴스·기업 이벤트

공식 DART 검색은 corp code가 없을 때 검색기간 최대3개월, 페이지 최대100, `total_page`/`total_count`, `last_reprt_at`, 접수번호/일자/정정·철회 단서를 제공합니다. 재설계는 최신본만 수집하지 않고 원본·정정·철회를 함께 보존합니다. 제목 태그는 retrieval 힌트이며 확정 호재/재무 점수가 아닙니다. [공식 공시검색](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001).

`DisclosureDocument`는 `rcept_no`를 고유 키로 둡니다. `CorporateEvent`는 issuer, event type, 원문으로 확인된 원 이벤트/효력일/대상증권을 기준으로 문서들을 연결합니다. 정정임을 알지만 원문에서 parent를 확인하지 못하면 `revisionLink=UNKNOWN`으로 보존하고 제목 유사성만으로 원 이벤트를 병합하지 않습니다. 결정/신고/이사회 승인/실제 취득/납입/철회는 서로 다른 event state이며, 발표한 자기주식 취득을 취득 완료로 바꾸지 않습니다. 대량보유 증가는 장내 매수뿐 아니라 증여·합병·전환 등 사유가 있으므로 실제 사유 확인을 별도로 둡니다.

`NewsDocument`는 canonical original URL, publisher, 원문 제목/발췌의 허용 범위, publishedAt, firstSeenAt을 보존합니다. 동일 보도자료를 재전송한 여러 매체는 independent evidence count를 늘리지 않고 `EventCluster`로 묶습니다. 상호/과거 회사명/계열 관계의 동형어에 의한 false link를 막기 위해 issuer entity resolution의 확정/가능/미확인을 구분합니다. AI가 어떤 산업 뉴스에 종목 이름을 붙였다는 이유로 관련성을 확정하지 않습니다. [NAVER 공식 뉴스 필드](https://api.ncloud-docs.com/docs/naver-api-hub-search-news).

수집 상태와 사건의 absence를 분리합니다. 완전한 30일 공시검색에서 유상증자0건은 관측 범위 내 부재이며, 전국 검색 첫100행에 없던 종목의 '유상증자 없음'과 다릅니다. event reaction과 기술 feature는 뉴스 수신 이전/이후 같은 venue/time alignment를 유지합니다. 원문 속 명령 문장은 데이터이며 도구 권한·시스템 지침으로 실행하지 않습니다.

## 8. 무료/저비용과 정보이용 권한

현재 유료 회원 앱/Telegram의 KIS·키움 정보 제공·보존·AI 가공 권한은 미확인입니다. 기술 구현과 이용 권한은 별개이며 공급자 capability에 각각 display/cache/research/redistribution/AI processing flag를 둡니다. UI에 표현할 수 있는 것, 자체 계산에 쓸 수 있는 것, 외부로 보낼 수 있는 것을 구분합니다. 원본 URL에 key가 들어가는 DART 요청은 비밀 없는 URI/공개 payload만 기록합니다.

KRX 무료 OPEN API는 이번 조회 기준 비상업 목적·제3자 대가 금지 및 일 요청 제한을 명시합니다. 유료 앱의 무료 전 시장 source로 자동 활성화하지 않습니다. 전 시장·폐지·기업행위 이력이 부족하면 상업 정보계약을 확인하거나 broker 자료로 앞으로 축적하되 과거 coverage 부족을 표시합니다. [KRX 공식 약관](https://openapi.krx.co.kr/contents/OPP/INFO/OPPINFO002.jsp).

뉴스 전문·기사 원문을 무제한 보존한다는 설계를 하지 않습니다. 계약이 허용한 메타데이터/발췌·링크·원문 해시만 저장할 수 있게 분리합니다. 유료 공급자를 늘리는 결정은 '데이터 수'가 아니라 missing predicate coverage·성과 비교·운영 비용을 근거로 내립니다.

## 9. 최소 실증 순서와 통과 기준

아래는 설계 평가를 위한 순서이며 실행 계획 승인이나 이번 턴 구현을 뜻하지 않습니다.

1. **시점/종목/단위 계약을 먼저 검증**: 현재 저장된 공개 스냅샷과 redacted fixtures로 늦게 도착한 정정, 분할, 신규상장, 퇴출, null/0, REAL/MOCK, venue mismatch를 검사합니다. future fact를 과거 run에서 사용하는 사례는 모두 거부해야 합니다.
2. **DSL 의미와 3값 논리를 검증**: 'filter 후 top20'과 'top20 후 filter'가 서로 다른 결과인 dataset, UNKNOWN NOT, OR, 분기/누적 기간, PER 분모 음수, 한글/억/만 단위를 포함합니다. 반복 실행의 digest 동일과 입력 밖 조건 삽입0건을 확인합니다.
3. **공급자 오프라인 replay**: 429/인증/타임아웃/잘린 body/중복/continuation 실패를 fixture로 재현하고, global/perTR 제약과 job deadline·cancellation·중복 요청 제거·이전 snapshot 보존을 확인합니다. 실제 상한은 권한/문서를 확인한 뒤 제한된 runtime probe로 확정합니다.
4. **20→100→명시적 universe 확대**: 조회가 허용된 실제20종목으로 KIS/키움 관측/일봉 alignment를 확인한 후100종목·야간 전 대상 incremental로 확대합니다. 실제 coverage, 필요한 호출수, max observation skew, 완료시간, cooldown 재회복을 측정합니다. 숫자는 확장 단계 제안이지 이미 검증된 성능이 아닙니다.
5. **기존 검색과 shadow 비교**: 같은 snapshot에서 기존 결과/new DSL, candidates selected/omitted, source uncertainty, 중복을 비교합니다. 기존 운영은 유지하고 사용자 노출 전 '자료누락을 조건불일치로 표현한 사례'0건을 확인합니다.
6. **시간 순서 성과 실증**: old/new deterministic 후보와 AI-reviewed 후보를 같은 당시 universe·자료·시점으로 기록하고 1/3/5일, 비용 포함 수익률, benchmark, MFE/MAE, UNKNOWN rate, coverage, 업종/이벤트 concentration을 비교합니다. 앞으로 모은 표본과 보유한 point-in-time 과거 자료를 구분합니다. 근거 충실도 통과만으로 수익률 개선을 주장하지 않습니다.

새 설계가 좋아졌다는 기술 통과 기준은 deterministic reproducibility, 자료 provenance/period/unit 완전성, UNKNOWN 정직성, 목표 universe coverage, 실제 budget 준수, adapter 장애 격리입니다. 분석력의 금융적 통과 기준은 충분한 시간 순서 out-of-sample 관측에서 기존 대비 개선입니다. 둘을 서로 바꾸지 않습니다.

## 10. 미확정 항목과 필요한 사용자 작업

- KIS/키움의 유료 회원·Telegram·AI 가공·역사 저장/재배포 범위 및 계정별 정확한 유량 정책 확인. 키를 채팅으로 보내지 않습니다.
- Kiwoom MOCK의 각 TR/조건검색 지원, real market provenance, continuation, 실제 batch 최대치 확인. 이번 연구는 기존 실측 결과와 코드/공식 명세를 확인했으며 새 운영 호출을 하지 않았습니다.
- 완전한 과거 universe·상장폐지·corporate action과 당시 revision 공급 범위 확인. 확보 전에는 과거 완전 백테스트를 약속하지 않습니다.
- 검색 UI의 default 의미: 기본은 필터 후 순위, 기준 가격은 KRX, 보통주/분리 대상 등 문서로 고정. 사용자가 다른 의미를 선택할 수 있도록 합니다.
- 52주/ATR/분할 같은 feature는 정확한 산식·조정·캘린더·lookback version을 정하며 가중치 자동 최적화는 cohort 실증 이후입니다.

## 공식 문서 열람 목록

모두 2026-10-12에 직접 공개 열람했습니다. GitHub `main` 문서는 변경될 수 있으므로 실제 구현 때 해당 revision/hash를 고정해야 합니다.

- [KIS 다중30종목 공식 예제](https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/intstock_multprice.py)
- [KIS 기간시세 공식 예제](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py)
- [KIS 종목 마스터 공식 예제](https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/stocks_info/kis_kospi_code_mst.py)
- [KIS 서비스/신규 고객 및 유량 공지 목록](https://apiportal.koreainvestment.com/community/10000000-0000-0011-0000-000000000001/post/): 검색 인덱스에서 2026-03-20 신규 고객 및 2026-04-20 유량 갱신 공지 존재 확인. 공개 페이지의 동적 본문은 충분히 열람되지 않아 계정별 숫자는 확정하지 않음.
- [키움 REST 공식 소개·제약](https://openapi.kiwoom.com/intro?dummyVal=0)
- [키움 공식 종목리스트 ka10099](https://raw.githubusercontent.com/Kiwoom-Securities/Kiwoom-REST-API/main/examples/국내주식/종목정보/list_domestic_stocks.py)
- [키움 공식 지정종목 ka10095](https://raw.githubusercontent.com/Kiwoom-Securities/Kiwoom-REST-API/main/examples/국내주식/종목정보/get_domestic_watchlist_stock_info.py)
- [키움 공식 조건 목록 ka10171](https://raw.githubusercontent.com/Kiwoom-Securities/Kiwoom-REST-API/main/examples/국내주식/조건검색/list_domestic_condition_searches.py)
- [키움 공식 일반 조건 ka10172](https://raw.githubusercontent.com/Kiwoom-Securities/Kiwoom-REST-API/main/examples/국내주식/조건검색/request_domestic_condition_search.py)
- [OpenDART 공시검색](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001), [OpenDART 소개](https://opendart.fss.or.kr/intro/main.do)
- [KRX OPEN API 공식 약관](https://openapi.krx.co.kr/contents/OPP/INFO/OPPINFO002.jsp)
- [NAVER API HUB 뉴스 필드](https://api.ncloud-docs.com/docs/naver-api-hub-search-news)
- [SQL 3값 논리 공식 진리표](https://www.postgresql.org/docs/current/functions-logical.html)
- [JSON Schema 2020-12 Core](https://json-schema.org/draft/2020-12/json-schema-core): JSON 구조 계약의 참고 표준, 의미/기간/금융적 정확성 검사는 추가 domain validation이 담당.
