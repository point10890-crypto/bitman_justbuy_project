# BitMan KIS 소스 연결 점검

공식 KIS 조회 예제는 현재 Spring Boot 어댑터에 연결할 수 있다. 무료·저비용 우선 순서는 기존 응답 검증 보강, 종목 마스터와 다중 시세, HTS 저장 조건검색, 재무·장중 수급, WebSocket이다. 현재 키워드 검색의 시가총액 상위 최대 400종목 제한을 해소하려면 종목 식별 목록과 실제 시세 수집을 함께 확장해야 한다. 마스터만 추가해서 전 종목 실시간 검색이라고 표시해서는 안 된다.

점검 기준은 2026년 10월 12일의 [공식 저장소 커밋 2082791](https://github.com/koreainvestment/open-trading-api/tree/208279102f43fd0c0f15fcf66c9e5a5adda27050)과 현재 앱 소스다. 신규 KIS 기능은 이번 점검에서 활성화하지 않았다. 후보와 수치는 결정론적 조회로 검증하고 AI에는 검증된 근거만 전달한다.

## 현재 연결 범위

`backend/src/main/java/com/bitman/justbuy/service/KisApiService.java`에는 데이터 조회 경로 9개가 구현돼 있다. 기존 7개와 기본 비활성인 분봉·프로그램 2개이며, 인증 `/oauth2/tokenP`는 별도다.

아래 `Q`는 `/uapi/domestic-stock/v1/quotations/`, `R`은 `/uapi/domestic-stock/v1/ranking/`, `F`는 `/uapi/domestic-stock/v1/finance/`다.

| 현재 상태 | 경로 | TR ID | 주요 연결 위치 |
| --- | --- | --- | --- |
| 기존 | Q `inquire-price` | FHKST01010100 | 현재가·AI 근거 |
| 기존 | Q `inquire-daily-itemchartprice` | FHKST03010100 | 일봉 분석 |
| 기존 | Q `inquire-investor` | FHKST01010900 | 일별 수급·키워드 수급 |
| 기존 | Q `inquire-index-price` | FHPUP02100000 | 시장지수 |
| 기존 | Q `volume-rank` | FHPST01710000 | 단타·주도주 후보 |
| 기존 | R `fluctuation` | FHPST01700000 | 상승률 후보 |
| 기존 | Q `foreign-institution-total` | FHPTJ04400000 | 외인·기관 후보 |
| 확장 플래그 필요 | Q `inquire-time-itemchartprice` | FHKST03010200 | 분봉 근거 |
| 확장 플래그 필요 | Q `program-trade-by-stock` | FHPPG04650101 | 프로그램 근거 |

키워드의 시세 후보는 `KeywordSearchDataService`에서 Naver 코스피·코스닥 시총 상위 각 200종목을 수집한다. KIS 수급은 시세 조건 통과 후 최대 20종목에 20초 예산으로 적용하므로 전체 시장 수급 검색이 아니다. `KisProperties`에는 현재 App Key와 App Secret만 있고 HTS ID 설정은 없다.

## 먼저 수정할 데이터 계약

현재가 응답의 `stck_mxpr`와 `stck_llam`은 상한가·하한가다. 기존 코드가 이를 52주 최고·최저로 매핑한 오류를 수정했다. 실제 52주 필드인 `w52_hgpr`와 `w52_lwpr`를 사용하며, 누락 시 당일 제한가격으로 대신 채우지 않는다. [공식 필드 정의](https://github.com/koreainvestment/open-trading-api/blob/208279102f43fd0c0f15fcf66c9e5a5adda27050/examples_llm/domestic_stock/inquire_price/chk_inquire_price.py#L43)

나머지 개선 항목은 확장 구현의 선행 작업으로 남는다.

| 발견 사항 | 영향 | 수정 방향 |
| --- | --- | --- |
| 기존 일봉·지수·문자열 수급 경로 일부가 `rt_cd` 성공 검사를 생략 | 오류 응답을 데이터로 사용할 수 있음 | 성공 코드·종목·시장·필수 실측값을 공통 검증 |
| 일봉 구간·중복 날짜·OHLC 관계·추가 페이지 완전성 검증 부족 | 이평·신고가·거래량 증가 조건의 기간이 불완전할 수 있음 | 거래일·수정주가 정책·충분한 관측 수를 검증한 DTO 제공 |
| 단타·주도주가 수집 실패의 빈 리스트도 EMPTY로 처리 | 정상 0건과 조회 실패가 혼동됨 | AVAILABLE·PARTIAL·UNAVAILABLE·정상 EMPTY 보존 |
| 기존 HTTP 연결 10초·읽기 120초 | 후보 확장 시 긴 대기 발생 | 이미 있는 짧은 KIS 클라이언트와 전체 단계 예산 적용 |
| 순위 DTO에 원 관측 시각·조회 시각·시장 구분 부족 | 실행 시각을 데이터 시각으로 오해할 수 있음 | 거래일·원 관측 시각·조회 시각·거래소·출처 분리 |
| 현재가 응답에서 종목명을 직접 읽으나 기존 서비스에도 부재 설명이 있음 | 공식 이름이 빈 값일 수 있음 | 마스터 또는 종목 기본정보로 이름과 코드를 결합 |

기존 `inquire-investor`의 당일 자료는 장 종료 후 제공된다. 장중 추정 가집계는 별도 API·별도 데이터형으로 다뤄야 한다. [일별 투자자 조회 안내](https://github.com/koreainvestment/open-trading-api/blob/208279102f43fd0c0f15fcf66c9e5a5adda27050/examples_llm/domestic_stock/inquire_investor/inquire_investor.py#L29)

이미 연결된 `foreign-institution-total` 순위도 장중 가집계다. 직원이 정해진 시간에 입력한 수치의 누계이며 안내된 시각은 약 10분 차이가 날 수 있다. 후보 선정용 가집계를 확정된 일별 순매수와 같은 근거로 표시하지 않는다. [가집계 순위 설명](https://github.com/koreainvestment/open-trading-api/blob/208279102f43fd0c0f15fcf66c9e5a5adda27050/examples_llm/domestic_stock/foreign_institution_total/foreign_institution_total.py#L35)

## 우선 연결할 공식 소스

`DS`는 공식 저장소 `examples_llm/domestic_stock/`이며, REST 예제는 `DS/기능/기능.py` 구조다. Python 예제를 앱 프로세스에 통째로 붙이기보다 기존 Java 어댑터에 요청·응답 계약을 옮긴다.

| 순서 | 공식 소스 | 경로와 TR | 앱 효과와 조건 |
| --- | --- | --- | --- |
| 1 | `stocks_info/kis_kospi_code_mst.py`, `kis_kosdaq_code_mst.py` | 공식 종목 마스터 ZIP | 정식명·코드·시장·업종·ETF·우선주·SPAC·관리/정지/경고를 식별. 시세는 별도 수집 |
| 1 | `stocks_info/theme_code.py`, `sector_code.py` | 테마·업종 마스터 ZIP | 근거가 있는 테마·업종 소속 검색. 당일 뉴스 재료와 구분 |
| 1 | DS `intstock_multprice` | Q `intstock-multprice` / FHKST11300006 | 한 번에 최대 30종목 시세. 후보 묶음 조회·캐시로 요청 수 절감 |
| 2 | DS `psearch_title` | Q `psearch-title` / HHKST03900300 | HTS ID의 서버 저장 조건과 `seq` 목록 |
| 2 | DS `psearch_result` | Q `psearch-result` / HHKST03900400 | 저장 조건에 맞는 실제 후보. 조건당 최대 100종목 |
| 3 | DS `inquire_daily_itemchartprice` | Q `inquire-daily-itemchartprice` / FHKST03010100 | 기존 일봉 재사용. 이평 정배열·평균 대비 거래량·기간 돌파·변동성 조건. 호출당 최대 100개 |
| 3 | DS `near_new_highlow` | R `near-new-highlow` / FHPST01870000 | 신고·신저 근접 후보. 기간과 원 가격으로 재검증 |
| 4 | DS `finance_financial_ratio` | F `financial-ratio` / FHKST66430300 | ROE·EPS·BPS·부채·성장 지표. 결산년월과 연간/분기 구분 보존 |
| 4 | DS `finance_growth_ratio`, `finance_stability_ratio` | F `growth-ratio` / FHKST66430800; `stability-ratio` / FHKST66430600 | 실적 성장·재무 안정 조건 |
| 4 | DS `finance_income_statement`, `finance_balance_sheet` | F `income-statement` / FHKST66430200; `balance-sheet` / FHKST66430100 | 재무 원수치 검증. 후보만 조회하고 결산별 캐시 |
| 4 | DS `investor_trade_by_stock_daily` | Q `investor-trade-by-stock-daily` / FHPTJ04160001 | 날짜·시장 지정 일별 수급 |
| 4 | DS `investor_trend_estimate` | Q `investor-trend-estimate` / HHPTJ04160200 | 장중 외인·기관 추정 수급. 확정 수급과 별도 표시 |
| 4 | DS `program_trade_by_stock_daily` | Q `program-trade-by-stock-daily` / FHPPG04650201 | 기존 장중 프로그램 어댑터와 누적 일별 추이 비교 |
| 4 | DS `inquire_index_category_price` | Q `inquire-index-category-price` / FHPUP02140000 | 업종 강도와 시장 상대 강도 |
| 4 | DS `daily_short_sale` | Q `daily-short-sale` / FHPST04830000 | 공매도 체결량·비중. 공매도 잔고와 구분 |
| 4 | DS `daily_loan_trans` | Q `daily-loan-trans` / HHPST074500C0 | 대차 추이, 호출당 최대 100건. 실제 공매도와 구분 |
| 4 | DS `daily_credit_balance` | Q `daily-credit-balance` / FHPST04760000 | 신용잔고, 호출당 최대 30건 |
| 4 | DS `chk_holiday` | Q `chk-holiday` / CTCA0903R | 영업·거래·개장·결제일 캘린더. 공식 권고 가급적 하루 1회 |
| 5 | DS `inquire_time_dailychartprice` | Q `inquire-time-dailychartprice` / FHKST03010230 | 과거 분봉, 실전 최대 120건/호출·최대 1년 범위 |
| 5 | DS `ccnl_krx`, `ccnl_nxt`, `ccnl_total` | WS H0STCNT0·H0NXCNT0·H0UNCNT0 | 체결·거래량·거래대금·체결강도. 대상 후보만 구독 |
| 5 | DS `asking_price_krx`, `asking_price_total` | WS H0STASP0·H0UNASP0 | 호가·잔량. 체결과 구분 |
| 5 | DS `market_status_total`, `program_trade_krx` | WS H0UNMKO0·H0STPGM0 | 장 상태·VI·프로그램 실시간 보완 |

종목 마스터는 `StockUniverseService` 같은 별도 공급자로 일별 검증·교체하고 `KeywordSearchDataService`의 후보 생성에 연결한다. 다중 시세는 `KisApiService`의 구조화 응답으로 연결한다. HTS 조건 후보는 별도 어댑터에서 가져온 뒤 `KeywordConditionSearchService`에서 가격·시장·제외 조건을 다시 검증한다. 재무·수급·분봉은 `ConditionSearchPipeline`의 증거와 `LeaderConditionEngine`·`ShortTermRealtimeScanner`의 규칙에 연결하고, 지원 근거가 갖춰진 조건만 `KeywordSearchQueryParser`에 추가한다.

## 샘플을 그대로 복사하면 생기는 문제

마스터 예제에는 TLS 검증을 전역으로 끄는 코드, ZIP 전체 추출, import 즉시 다운로드·엑셀 생성이 있다. 운영 어댑터는 TLS 검증, ZIP 파일명·압축 해제 크기·경로 제한, CP949 고정폭 바이트 파싱, 원본 파일 날짜·행 수·중복 식별자 검증, 검증 완료 후 원자적 교체를 적용해야 한다. `sector_code.py`의 이름 슬라이스와 헤더 구조도 대조가 필요하다. [공식 종목 정보 소스](https://github.com/koreainvestment/open-trading-api/tree/208279102f43fd0c0f15fcf66c9e5a5adda27050/stocks_info)

`investor_trade_by_stock_daily` 예제의 연속조회 재귀 호출에는 선언과 인자 순서가 다른 코드가 있다. 연속조회는 요청 구간·중복 날짜·페이지 상한·시간 예산을 포함해 직접 검증해야 한다. [공식 일별 수급 예제](https://github.com/koreainvestment/open-trading-api/blob/208279102f43fd0c0f15fcf66c9e5a5adda27050/examples_llm/domestic_stock/investor_trade_by_stock_daily/investor_trade_by_stock_daily.py)

공식 WebSocket 클라이언트의 40개 구독 가드는 샘플 구현 제한이며 현재 계정의 계약상 한도라는 근거는 아니다. REST·WS 유량은 현재 계정의 제한을 확인하고 공통 throttle·캐시·유한 재시도를 적용한다. KRX·NXT·통합 시장의 거래 시간과 가격을 섞지 않으며 중복·역순·재접속·스냅샷 복구를 검증한다. [공식 통합 체결 소스](https://github.com/koreainvestment/open-trading-api/blob/208279102f43fd0c0f15fcf66c9e5a5adda27050/examples_llm/domestic_stock/ccnl_total/ccnl_total.py)

이 커밋의 저장소 루트에는 공통 LICENSE가 없으며 MIT LICENSE는 MCP 하위 패키지에만 있다. 저장소 전체를 MIT로 재배포할 수 있다고 단정하지 않는다. API 계약을 참고해 어댑터를 작성하고, 필요한 코드 복제 범위와 시세 제공 권한은 각각 확인한다.

## 사용자가 준비할 운영 조건

1. **유료 회원 서비스에서의 KIS 시세 제공 권한**: 기존 개인 App Key 보유와 유료 앱의 시세 제공 허가는 별개다. 공식 안내는 제3자 서비스 제휴와 거래소 정보이용계약을 설명하며 제휴 대상 제한도 명시한다. BitMan의 현재 사업 형태와 국내주식 시세·수급·파생 지표 제공 범위를 KIS 담당자에게 확인해야 한다. API가 기술적으로 호출된다는 이유로 이용 권한을 확보했다고 판단하지 않는다. [공식 제휴 안내](https://apiportal.koreainvestment.com/provider-info), [제휴 제안 안내](https://apiportal.koreainvestment.com/provider-apply)
2. **HTS 저장 조건**: eFriend Plus [0110]에서 조건을 등록하고 사용자조건 서버저장을 실행한다. HTS ID는 환경설정에 직접 입력하고 채팅에 키를 보내지 않는다. API에는 HTS의 대상변경 설정이 적용되지 않으므로 시장·ETF·관리종목 제외는 앱에서도 검사한다. 조건당 100종목 제한과 문서화된 `MCA05918` 정상 0건 예외를 보존한다. [공식 조건검색 결과 소스](https://github.com/koreainvestment/open-trading-api/blob/208279102f43fd0c0f15fcf66c9e5a5adda27050/examples_llm/domestic_stock/psearch_result/psearch_result.py)
3. **시장 범위**: 기존 앱은 KRX 조회다. NXT 또는 통합 시세로 넓힐 경우 이용 범위·거래 시간·휴장·종가 기준을 먼저 결정한다.

종목·재무·저장조건 확장에 새로운 AI 공급사 키는 우선 필요하지 않다. 조회 캐시와 후보 선별로 요청 수를 줄일 수 있으나, 상업용 시세 정보이용 비용까지 무료라고 보장할 수는 없다. 주문·체결통보·Trading MCP와 전략 주문 실행은 이번 분석 소스 연결 범위에 포함하지 않는다.

## 공식 소스 링크

- [다중 종목 시세와 최대 30종목](https://github.com/koreainvestment/open-trading-api/blob/208279102f43fd0c0f15fcf66c9e5a5adda27050/examples_llm/domestic_stock/intstock_multprice/intstock_multprice.py)
- [재무비율](https://github.com/koreainvestment/open-trading-api/blob/208279102f43fd0c0f15fcf66c9e5a5adda27050/examples_llm/domestic_stock/finance_financial_ratio/finance_financial_ratio.py)
- [장중 추정 수급](https://github.com/koreainvestment/open-trading-api/blob/208279102f43fd0c0f15fcf66c9e5a5adda27050/examples_llm/domestic_stock/investor_trend_estimate/investor_trend_estimate.py)
- [과거 분봉](https://github.com/koreainvestment/open-trading-api/blob/208279102f43fd0c0f15fcf66c9e5a5adda27050/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py)

## 수정 검증

52주 범위 수정의 회귀 테스트 2개는 수정 전 실패하고 수정 후 통과했다. 당일 제한가격과 실제 52주 가격이 다른 응답, 52주 필드가 누락된 응답을 검증한다. 집중 테스트 15개와 백엔드 전체 78개 suite·591개 테스트가 실패·오류·skip 0개로 통과했다. 프론트 TypeScript·Vite 빌드도 통과했고 사용자 화면 번들은 기존 키워드 실데이터 배포와 동일하다. 공식 소스의 경로·TR·조회 제한과 수정 코드는 별도 담당자가 다시 대조했다.

`scripts/deploy-minipc.ps1`로 운영 DB 백업 후 JAR 교체·재시작을 완료했다. MiniPC와 개발 빌드의 JAR SHA256은 `C047EBC0D533088D3864ECC87F268827CEF122232272108388BDDC85945A97E0`으로 일치했고, MiniPC 및 `https://api.bit-man.net/api/health`가 `ok`를 반환했다. 실제 홈페이지와 production 별칭, Spring 정적 홈페이지는 기존 새 키워드 JS/CSS 참조를 유지한다. 회원 DB·환경설정·신규 KIS 활성화 플래그는 보존했다. 이번 오류 수정은 모의 HTTP 회귀 테스트와 배포 JAR 일치로 검증했으며 운영 KIS에 추가 요청을 보내지 않았다.
