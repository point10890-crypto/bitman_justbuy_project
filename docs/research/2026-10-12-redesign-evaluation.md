# BitMan 재설계 연구: 검출 종목의 품질과 성과를 증명하는 평가 시스템

작성일: 2026-10-12. 상태: 연구 설계안. 제품 코드·운영 설정·회원 DB를 변경하지 않았으며 신규 시스템의 수익률 실증 결과는 아직 없다.

최종 범위와 용어의 기준은 [통합 설계](../superpowers/specs/2026-10-12-bitman-research-core-design.md)다. 이 노트의 표본 수·최소효과·비율 기준은 실험 정책 초안이며 통합 설계에서 확정한 보편 합격선이 아니다.

## 1. 현재 공개 자료가 보여주는 문제

기존 `docs/ANALYSIS_STRENGTHENING_RND_2026_10_12.md`, 공개 검출 스냅샷·계산 JSON·기존 성적표 JSON을 읽었다. 별도 프로젝트 CSV, 미추적 `research/` 실험, 운영 DB, 키, 새로운 공급자 조회를 사용하지 않았다.

공개 스냅샷을 읽어 Node 내장 SQLite 메모리 테이블에서 원 계산을 다시 수행했다. 파일 쓰기와 API 호출은 없다.

| 확인 항목 | 실제 값 | 평가 설계에 주는 의미 |
|---|---:|---|
| 네 AI 모드 추천 슬롯 | 9 | 카드 수와 고유 후보 수를 분리해야 한다 |
| 고유 종목 | 4 | 같은 주식이 다른 모드에 나오면 신규 독립 관측으로 세지 않는다 |
| 팬오션 / HMM 슬롯 | 4 / 3 | 두 종목이 7/9슬롯이다. 종목·업종·테마 집중도 노출 필요 |
| 표시 목표 상승 폭 < 손절 하락 폭 | 4/9 | 가격 간 거리다. 승률·실현 위험보상·기대수익이 아니다 |
| 슬롯 비중의 HHI | 1/3 | 역수 3은 슬롯 집중 설명값이다. 실제 독립 표본 수나 포트폴리오 위험을 뜻하지 않는다 |
| 기존 최근 30일 평균 표시 수익률 | -0.98%~-1.76% | 서로 다른 보유기간·기준가격이 섞여 신규 버전 효과 비교에 사용할 수 없다 |
| 당시 새 5거래일 완료 실측 | 0건 | 수익률 개선·예측력 향상을 입증할 자료가 없다 |

원 계산은 `SUM((종목별 슬롯수)^2)/(총 슬롯수)^2`와 `(target-current)/(current-stop)`다. DB손해보험 스윙 0.38, 팬오션 스윙 0.71, 삼표시멘트 테마 0.85, HMM 스윙 0.91은 기존 표시값 간 거리다. 단순히 이 비율을 1 이상으로 바꾸면 분석력이 좋아진다고 결론 내릴 수 없다. 목표·손절 수준 생성 방식과 이후 경로를 별도로 검증해야 한다.

## 2. 설계 원칙: 사실 품질·선정 품질·실현 가능 성과를 분리한다

한 종합 AI 점수로 세 가지를 덮지 않는다.

1. **사실 품질**: 종목 식별, 공급자·환경, 공개/관측/수집 시각, 단위, 기간, 정정 이력, 자료 범위가 맞는가. 공급자가 실제 반환한 자료인지 확인하는 것이며, MOCK 환경 자료를 임의 생성으로 단정하지 않는다.
2. **선정 품질**: 검색 조건 일치, 투자 논지에 해당하는 근거, 반증, 유동성·집중도, 비교 기준이 있는가. 같은 입력을 본 여러 모델의 동의는 독립 원천 검증이 아니다.
3. **성과**: 발행 이후 정의된 관측·가상 체결 정책에서 비용 포함 결과가 기준 전략보다 나은가. 실제 회원 거래 수익으로 표현하지 않는다.

앱에는 `근거 충분`, `부분 자료`, `관찰 후보`, `측정 대기`, `측정 불가`, `실증 부족`처럼 이유를 표시한다. 데이터가 부족한 후보를 억지로 Top 3에 채우지 않는 **기권(abstention)**도 정상 결과다. 선택률·기권률·누락률을 수익률과 함께 평가해야 한다.

## 3. 시점 계약: 과거에 무엇을 알았는지 재현한다

`eventTime`은 경제적 사건 시각, `publishedAt`은 원천 공표 시각, `providerObservedAt`은 공급자 관측 시각, `receivedAt`은 앱 수집 시각, `knownFrom`은 이 버전을 앱이 처음 사용할 수 있었던 시각이다. 수정 자료는 원행을 덮지 않고 새 `revisionId`와 `knownFrom`을 만든다.

- 실운영 재현 입력은 `knownFrom <= run.dataCutoffAt`이고 `bar.endAt <= dataCutoffAt`인 완료 봉만 사용한다. 장중에 당일 최종 고가·종가·기관 누적 확정값을 미리 넣지 않는다.
- DART 재무는 결산일을 이용 가능일로 삼지 않는다. 해당 보고서·정정본이 실제 접수/공개된 시점과 앱 수집 시점을 분리한다. 과거에 당시 최초 수집 시각이 없다면 `HISTORICAL_RECONSTRUCTION`으로 표시하고 정확한 실운영 재생이라고 부르지 않는다.
- 최신 기업명·상장종목만 과거에 붙이지 않는다. ISIN/영구 `instrumentId`, 종목 코드 유효기간, 시장·주식 종류·상장/폐지/정지 상태를 유효일과 알려진 시각으로 관리한다. 코드 끝자리나 현재 이름만으로 보통주·우선주·ETF·SPAC을 분류하지 않는다.
- 최신 LLM에 과거 회사를 질문하면 모델 사전학습에서 이후 사건을 알 수 있다. 과거 재생의 LLM은 검증된 입력을 추출·대조하는 제한 역할로 두며, 무자료 지식으로 미래를 보완하지 못하게 한다. 그래도 사전학습 누출을 완전히 증명해 없앴다고 주장할 수 없으므로 **향후 실시간 shadow 코호트가 AI 순위 개선의 최종 평가**다.
- 모든 외부 데이터의 사용·저장 권한과 범위를 manifest에 기록한다. 다른 프로젝트의 CSV를 우연히 읽어 증거를 채우지 않는다. 공급자 전체 시장이 아닌 395/400개 확인 범위면 그 범위에서만 평가한다.

## 4. 성과 단위와 가격 정책

### 4.1 세 가지 grain

| 테이블 | 고유 키 | 목적 |
|---|---|---|
| 후보 판단 | `runId + instrumentId + strategyId` | 후보 포함/제외/기권과 이유를 보존 |
| 발행 신호 | `signalId`, 중복 그룹 `instrumentId + issuedSession + policyVersion` | 앱·알림 재전송의 중복을 제거하고 모드 관계를 보존 |
| 평가 라벨 | `signalId + horizonSessions + outcomePolicyVersion` | 1/3/5/20거래일 결과를 혼합 없이 계산 |

같은 날짜 여러 모드에서 같은 종목을 선택하면 사용자 전체 후보 목록은 고유 종목으로 합쳐 모드 배지를 붙인다. 모드별 연구는 별도로 가능하지만 날짜·종목 공통 충격을 무시해 표본 수를 부풀리지 않는다. 전체 앱 성과는 실제 사전 정의된 노출 정책으로 일별 바스켓을 구성해야 한다.

### 4.2 공통 1차 평가: 다음 정규 세션 시가 → 정해진 세션 종가

처음의 비교 정책은 `PRIMARY_KRX_REGULAR_NEXT_OPEN_V1`을 제안한다. 모든 비교 버전이 동일 발행 시점·동일 자료 범위에서 판단하고, 발행 다음 KRX 정규 거래 가능 세션의 확인된 시가를 **가상 진입 참조가격**으로 사용한다. 1거래일 결과는 진입 세션의 종가, 3/5/20거래일은 그 세션을 1로 센 3/5/20번째 정규 세션 종가다. 실제 주문·체결이 아니다.

- 다음 세션이 휴장이면 달력의 다음 유효 세션으로 이동한다. 정지·가격 제한·유동성 부재이면 `NO_ELIGIBLE_ENTRY`다. 한참 후 재개한 가격을 자동 진입시키지 않고 정책의 진입 유효기간을 지킨다.
- 단타의 발행 다음 분봉/VWAP 정책, 종가매매의 발행 후 경매/종가 정책은 각각 별도 2차 실험으로 등록한다. 마감 이후 나온 신호를 이미 끝난 그날 종가에 진입시켜 비교하지 않는다.
- KRX/NXT/통합 시세와 정규/시간외 세션을 혼합하지 않는다. `venue + sessionType + calendarVersion`을 명시한다. 공식 KRX 코스닥 페이지에 현재 정규 09:00~15:30 외 애프터마켓 16:00~20:00도 표기되므로, 15:30 이후 모든 시장이 닫혔다고 가정하지 않는다. 각 상품·일자에 적용되는 달력이 필요하다. [KRX 시장의 개폐](https://regulation.krx.co.kr/contents/RGL/03/03020401/RGL03020401.jsp)

QuantConnect의 공식 주식 체결 모델도 주문 시각 이전/동일 시각 봉을 배제하고, 갭이 난 stop은 시가와 stop의 관계를 반영한다. 이것은 시간 누출·체결 모델 필요성의 참고 근거이며 미국용 기본 모델을 한국 시장에 그대로 이식하라는 뜻이 아니다. [공식 equity fill model](https://www.quantconnect.com/docs/v2/writing-algorithms/reality-modeling/trade-fills/supported-models/equity-model)

### 4.3 체결·경로·기업행동·비용

- **관측 수익률과 체결 가능한 가상 성과를 분리**한다. 시가가 관측됐다고 원하는 수량이 모두 체결됐다고 주장하지 않는다. 연구의 참조 노출금액, 거래량 참여율, 호가 가용 여부, spread, 지연, 슬리피지 가정을 versioning한다. 호가/거래가능 자료가 부족하면 `OBSERVED_PRICE_RETURN`만 보고한다. 기본 체결 모델도 실제 부분 체결을 자동 재현하지 않는다. [공식 partial/stale fills 설명](https://www.quantconnect.com/docs/v2/writing-algorithms/reality-modeling/trade-fills/key-concepts)
- 하루/한 봉에서 목표와 손절을 모두 넘으면 선후 관계를 알 수 없다. `PATH_AMBIGUOUS`와 best/worst bound를 보존하고 성공으로 세지 않는다. 더 세밀한 원자료가 허용되고 실제 있다면 순서를 확인한다. 일봉에서 `open-high-low-close` 경로를 만들어 판정하지 않는다. 1차 fixed-horizon 종가 평가는 별개로 유지한다.
- 손절가 아래 갭이면 손절가에서 정확히 빠져나왔다고 계산하지 않는다. 사전 등록한 참조 체결 정책 또는 가용 호가로 처리하고 불가하면 미측정이다.
- `raw execution reference prices`, 알려진 기업행동, `split-adjusted feature prices`를 분리한다. 분할·병합·권리락·배당·합병·상장폐지 이력을 사용한다. 30% 넘는 가격 변동만으로 기업행동을 추정·지워서는 안 된다. 알려진 cash entitlement를 outcome에 반영하면서 수정주가에도 배당 반영했다면 이중 계산이 된다.
- 배당 포함 총수익률과 가격수익률은 다른 policy다. 주식·벤치마크의 기준을 맞추며, 기업행동 자료가 없으면 계산 가능 범위를 제한하고 해당 표본을 따로 보고한다. 생존 종목만 남기거나 폐지 종목의 마지막 가격을 계속 `forward-fill`해 수익률을 만들지 않는다. 폐지 후 청산/전환 가치가 확인되지 않으면 `TERMINAL_VALUE_UNKNOWN`으로 남긴다. 기업행동·폐지 처리 필요성은 공식 엔진의 별도 데이터 이벤트 구조에서도 확인된다. [공식 corporate actions](https://www.quantconnect.com/docs/v2/writing-algorithms/securities/asset-classes/us-equity/corporate-actions)
- 수수료·매도세·spread·슬리피지·시장 충격은 `CostPolicy`로 기간·시장·상품별 관리한다. 실제 계좌 조건을 모르면 실제 비용이라고 쓰지 않는다. 기본 및 2배 슬리피지 stress를 함께 보고하며, 세율 숫자는 최신 공식 근거 확인 전 고정하지 않는다. 비용이 미확인인 결과는 `gross`다.
- 고정 보유 기간, stop/target, 포트폴리오 cash/exposure를 별도 측정한다. 겹치는 5일/20일 거래의 평균 수익률을 매일 복리화해 계좌 수익률로 바꾸지 않는다. 계좌형 성과가 필요하면 자금·중복 보유·재진입·비용·현금 배분을 명시한 별도 ledger가 필요하다.

## 5. 벤치마크, 비교군과 보고 지표

**분석력 개선은 단순 상승 종목을 골랐다는 주장보다 같은 자료·시장 환경에서 기준 선택보다 낫다는 주장으로 정의한다.** 시장이 10% 오른 날 종목 5% 상승을 성공으로만 표현하지 않는다.

- 시장 지수/ETF proxy는 같은 진입·종료 날짜, 같은 venue/session 및 가격수익률/총수익률 정책으로 계산한다. ETF는 지수 그 자체가 아니므로 tracking error·비용·상품 특성을 명시한다. 공통 날짜가 없으면 초과수익 `null`이며 가장 가까운 날짜로 몰래 메우지 않는다.
- 사전 등록된 **규칙 단독**, **유동성·시장 맞춤 동일 후보군의 단순 순위**, **같은 pool의 seed 고정 무작위 후보**, **규칙+AI**, **최종 AI+반증+위험 제한**을 paired 비교한다. 무작위 비교의 반복 seed도 실험 수에 포함한다. AI가 전혀 다른 미확인 시장 범위에서 뽑은 결과를 비교하지 않는다.
- 1차 지표는 날짜별 고유 후보 바스켓의 5세션 비용 포함 시장 초과수익과 규칙 단독 대비 차이로 제안한다. 1/3/20일은 2차 지표다. 전략별 주 목표 horizon은 평가 시작 전에 별도로 고정한다.
- 일별 평균/중앙값, 승률의 분모, 하위 5% 수익률, MAE/MFE의 가용 범위, turnover, 진입 불가율, 기권률, 결과 가용률, 종목·업종·테마 집중도를 함께 표시한다. Sharpe·최대낙폭은 별도 실제 가상 바스켓 일별 ledger가 있을 때만 제시한다.
- UI의 하나의 점수는 `데이터 품질`·`조건 적합도` 같은 설명 가능한 평가에 한정한다. `AI confidence 85`를 성공 확률 85%로 바꾸지 않는다. 확률을 도입한다면 목표 사건을 `5세션 net excess > 0`처럼 고정하고, 시간 분리 보정/검증에서 reliability diagram, Brier/log loss, 날짜별 표본 수를 함께 본다. Brier 단독 개선은 보정만 개선했다는 증거가 아니다. [scikit-learn probability calibration](https://scikit-learn.org/stable/modules/calibration.html)

결과 상태는 최소 `PENDING`, `OBSERVED`, `NO_ELIGIBLE_ENTRY`, `DATA_MISSING`, `PATH_AMBIGUOUS`, `CORPORATE_ACTION_UNRESOLVED`, `TERMINAL_VALUE_UNKNOWN`, `COST_UNVERIFIED`로 나눈다. PENDING은 손실도 성공도 아니다. 수익률 계산의 complete-case 분모, 전체 발행 분모, 미측정 이유와 누락 민감도 범위를 같이 보존해 손실 종목 누락으로 승률이 높아지지 않게 한다.

## 6. 연구가 자기 자신을 속이지 않게 만드는 절차

### 6.1 시간 순서·라벨 중첩 통제

무작위 행 분할을 하지 않는다. 날짜 단위로 train → validation/calibration → test를 나누고, feature scaling·winsorization·업종 비교 기준·threshold·retrieval memory를 train 구간에서만 만든다. `TimeSeriesSplit`의 gap은 유용하나 정렬된 주식 패널의 행 수 gap이 실제 거래일/라벨 중첩을 제거하는 것은 아니다. 공식 문서도 시간 순서와 동일 간격 표본 조건을 설명한다. [TimeSeriesSplit](https://scikit-learn.org/stable/modules/generated/sklearn.model_selection.TimeSeriesSplit.html)

제안 계약은 **날짜 그룹 walk-forward + 실제 라벨 구간 purge**다.

1. 학습 라벨의 `labelEndAt`이 다음 평가 데이터 cutoff 전에 완료되어야 한다. 예를 들어 최대 20세션 라벨이면 직전 약 20세션의 미완료 라벨을 학습에서 뺀다. 실제 거래 달력/종목 정지를 반영한 구간을 검사하며 숫자 20행 gap으로 대체하지 않는다.
2. interval 기반 연구 split에서는 `[entryAt, labelEndAt]`이 test 라벨 구간과 겹친 train 표본을 purge한다. 같은 날짜의 서로 다른 종목을 양쪽에 임의 분산하지 않는다.
3. test 뒤의 자료를 train에 쓰는 보조 CPCV 같은 연구 split은 별도 embargo를 사전 정의한다. buffer는 고정된 보편 1%가 아니라 label horizon·데이터 지연·직렬 의존성 분석을 따른다. 후행 표본을 학습에 사용하지 않는 실제 walk-forward와 보조 교차검증을 구분한다.
4. 겹치는 수익률·시장 공통 충격 때문에 500개 신호가 500개 독립 표본은 아니다. 날짜/주 블록으로 paired resampling하거나 일별 바스켓을 분석하며, block length/종목 cluster 민감도를 보고한다. 희박 표본에서 CI를 정밀하다고 표현하지 않는다.

### 6.2 과적합·다중 비교·지속 평가

모델·prompt·가중치·threshold·후보 수·horizon·비용·seed·벤치마크를 변경할 때마다 `experimentId`를 등록하고 실패한 버전까지 남긴다. 성적을 보고 가장 좋은 기간/목표를 선택해 이를 처음부터의 목표라고 표현하지 않는다. 반복 탐색은 우연히 우수한 backtest를 만들 수 있고 일반 holdout만으로 모든 선택 편향을 해결하지 못한다. [Bailey 등 probability of backtest overfitting](https://www.davidhbailey.com/dhbpapers/backtest-prob.pdf)

- 첫 버전은 제한된 사전 등록 비교로 시작한다. 여러 1차 비교를 동시에 한다면 Holm 등의 보정을 사전 등록한다. 탐색 연구는 보정 방법과 탐색으로서의 상태를 표시한다. [statsmodels 공식 multiple-test 구현](https://www.statsmodels.org/stable/_modules/statsmodels/stats/multitest.html)
- DSR/PBO는 충분한 일별 return matrix·시험한 전체 버전·관련 가정을 확보한 뒤 추가 진단으로 사용한다. 데이터가 9카드/4종목이면 숫자를 만들지 않는다. DSR은 trial 수와 비정규 분포, 기간 등에 민감하므로 독립 시험 수를 임의 확정해 보증 점수로 만들지 않는다. [Bailey·López de Prado DSR 원논문](https://www.davidhbailey.com/dhbpapers/deflated-sharpe.pdf)
- 정기 고정 리뷰 주기를 등록한다. 매일 임의로 검정하고 통과한 날만 출시하는 optional stopping을 피한다. 연속 검정이 필요하면 별도 sequential-testing 계약을 먼저 구현한다.
- `champion`은 운영 규칙/버전 고정, `challenger`는 공개되지 않는 shadow 판단이다. 동일 snapshot에서 비교하고 source calls·모델 토큰·p95지연·실패/기권률도 계측한다. 성과가 좋지만 비용·지연이 허용 범위를 넘으면 승격하지 않는다.
- ablation은 기술만 / +수급 / +DART / +AI해석 / +반증 / +다양성 순으로 필요한 조각의 추가 효과를 본다. 모델 수 증가 자체를 분석력 증가로 취급하지 않는다. 독립 기사·공시라도 동일 원문을 재전파했다면 evidence family로 묶는다.
- 자동 학습은 완료·검증된 결과만 사용하며 신호 당시에는 아직 알 수 없었던 outcome이 retrieval memory에 들어오지 않는다. 매일 손실 뒤 prompt를 즉흥적으로 고치지 않고 버전 승격/롤백을 고정 절차로 수행한다.

## 7. 최소 데이터 계약

아래는 구현을 위한 논리 스키마 제안이다. 새 DB/제품 구현 완료를 뜻하지 않는다. 사용자·계좌·주문·원문 질문 정보가 필요하지 않다.

```text
InstrumentVersion
  instrumentId, symbol, isin?, market, venue, instrumentType,
  effectiveFrom, effectiveTo?, knownFrom, knownTo?, sourceVersion

DataSnapshot
  snapshotId, asOf, receivedAt, schemaVersion, payloadHash,
  universeSnapshotId, calendarVersion, licenseProfileId,
  sourceFamilyIds[], missingReasonCounts{}, immutableObjectRef

SourceFact
  factId, snapshotId, instrumentId, metric, value?, unit, currency?,
  eventTime?, publishedAt?, providerObservedAt?, receivedAt, knownFrom,
  periodStart?, periodEnd?, source, environment, venue?, sessionType?,
  reportId?, revisionId?, accountId?, fsType?, status, evidenceFamilyId

ConditionRun
  runId, snapshotId, dataCutoffAt, issuedAt?, strategyVersion,
  featureVersion, modelVersion?, promptHash?, guardVersion,
  candidatePolicyVersion, budgetPolicyVersion, state, expiresAt

CandidateDecision
  runId, instrumentId, strategyId, rank?, conditionMatches{},
  supportingFactIds[], contradictingFactIds[], missingFactIds[],
  decision, reasonCodes[], evidenceQuality, uncertaintyType

PublishedSignal
  signalId, runId, instrumentId, issuedAt, firstVisibleAt,
  modeIds[], dedupGroupId, validUntil, entryPolicyVersion,
  riskScenarioVersion?, targetScenarioId?, stopScenarioId?, status

OutcomeLabel
  signalId, horizonSessions, outcomePolicyVersion, entrySessionId?,
  entryReferencePrice?, entryAt?, exitSessionId?, exitReferencePrice?,
  exitAt?, labelEndAt?, rawPriceSourceIds[], actionVersion?,
  grossReturn?, netReturn?, costPolicyVersion?, benchmarkPolicyVersion?,
  benchmarkReturn?, excessReturn?, entryStatus, outcomeStatus,
  ambiguityLowerBound?, ambiguityUpperBound?, finalizedAt?, revisionId

ExperimentRegistry
  experimentId, hypothesis, primaryMetric, minimumEffect,
  populationVersion, horizons[], splitManifestHash, immutableHoldout,
  comparatorVersions[], trialFamilyId, allTrials[], reviewSchedule,
  multiplicityPolicy, costPolicies[], startAt, endAt, gateVersion, status

EvaluationReport
  reportId, experimentId, codeCommit, snapshotHashes[],
  publishedCount, uniqueStockEvents, uniqueSessions, selectedCount,
  measuredCount, missingCounts{}, confidenceMethod, metrics{},
  confidenceIntervals{}, regimeSlices{}, sourceCoverage{}, limitations[]
```

`status`는 boolean `verified` 한 개로 축약하지 않는다. 제공된 숫자와 기대치가 맞는 것, 보고서 최신성 확인, 파생 계정 계산, 실제 체결성, outcome완료는 다른 상태다. 공개 카드와 관리자 설명은 이 상태들을 평문으로 보여준다.

## 8. 실증 acceptance criteria 제안

다음 숫자는 금융 시장의 보편 법칙이 아니라 **첫 실험을 시작하기 전에 고정할 BitMan 정책 초안**이다. 자료를 보고 문턱을 낮추지 않는다. root 설계 문서의 승인과 최초 실험 등록에서 확정한다.

| 단계 | 통과 조건 | 미통과 시 동작 |
|---|---|---|
| 재현·정보 누출 gate | 동일 snapshot+version에서 deterministic값/판정 100% 동일. 미래 봉·늦은 정정·현재종목마스터·공급자환경 치환·경로 모호성·누락/폐지·잘못된 단위 adversarial fixtures에서 알려진 부적합 발행 0건 | 제품 공개 전에 수정. fixture는 합성 예외 테스트임을 명시하고 실데이터 성과로 세지 않음 |
| live shadow 운영 gate | 최소 20유효 세션 동안 예정 run 상태 100% 기록, 중복 발행 0, cutoff 위반 0, 측정 가능한 자료의 출처/시각/단위 누락 0, 큐·호출·비용 예산 준수 | rule-only/기권으로 유지. 고장 난 단계만 재시도 |
| 1차 성과 주장 가능 최소 관측 | 최소 60고유 발행 세션과 200고유 종목-발행 이벤트, 5세션 완료; 각 시장/모드 결과와 complete-case/전체 분모 공개. 날짜 의존성 때문에 이 수가 통계적 충분성을 보증하지 않음 | `실증 부족`, p값/성공확률 광고·자동 승격 금지 |
| 비교 성과 gate | 동일-date paired 5세션 net excess가 규칙 단독보다 좋고, 사전 최소효과(초안 +0.25%p/5세션)의 하한을 넘는지 평가. 날짜 블록 CI·여러 비교 보정·2배슬리피지 stress에서 결론이 유지. raw상승률만으로 통과 불가 | challenger 유지/폐기. 효과 하한이 불충분하면 관측 연장하며 실험 변경으로 승격하지 않음 |
| coverage·유동성 gate | 비용/기업행동/벤치마크 확인된 성숙 outcome >=95%; 나머지 이유 전부 공개. 불확실/불가 진입은 성공 분모에 편입하지 않음. 차별적 누락·집중도·capacity stress가 결론을 뒤집지 않음 | 해당 집단 성과 주장 유보. 과장된 overall 승률 금지 |
| 20일/장기·regime gate | 20세션은 별도 만기 후 측정. 특정 상승장만 있는 짧은 자료는 그 국면의 결과로 한정. 여러 국면을 관측하기 전 전시장 장기 예측력 주장 금지 | 넓은 범위의 제품 주장은 보류 |
| 승격 gate | 위 품질/운영/성과·비용/지연 기준 모두 통과 후 고정 champion 변경. 최소 20세션 추가 운영 비교 및 rollback가능 | 근거 장애·cutoff오류·예산초과는 즉시 기능별 rulefallback, 성과 열화는 등록된 리뷰 정책으로 판정 |

CI 하한 +0.25%p는 1차 최소 실용 개선 초안이며 비용과 실제 정책 목표를 확인해 실험 시작 전 재설정할 수 있다. 기준 설정에 당시 test 결과를 사용하지 않는다. 95% outcome coverage도 누락 편향을 없애는 보증이 아니며 전체 발행 분모와 민감도 분석을 생략할 근거가 아니다.

처음에는 조건 적합성·근거 설명의 개선과 수익률 개선을 따로 출시 판정한다. 검증된 실제 사실을 더 잘 보여주는 UI/데이터 개선은 수익률 우위를 거짓으로 내세우지 않고도 가치가 있다. AI 순위·확률·목표가 우수성은 향후 shadow의 과학적 비교로 승인한다.

## 9. 가장 먼저 만들 평가 도구

1. append-only 공개 `snapshot + run + candidate decision`을 영구 종목 ID/자료 cutoff/버전으로 묶는 capture 도구. 제한된 기존 audit journal을 완성된 전시장 replay라고 부르지 않는다.
2. KRX 정규 달력과 사실 기반 기업행동을 사용하는 outcome collector. 1/3/5/20세션 상태 머신, 중복 방지, 기다리는 결과와 영구 미측정을 구분한다.
3. 읽기 전용 SQL cohort report. slot/고유stock/event/date grain, 동일날짜 benchmark, gross/net, 누락 이유, 가격 경로 모호성, 집중도, rulebaseline 차이를 한 보고서에 담는다.
4. experiment registry와 shadow comparator. 모델/규칙/token/sourcecall/latency/cost를 동일 입력에서 계측하고 익명의 공개 결과만 저장한다.
5. 근거 필요성 ablation, leak fixtures, 날짜 그룹 walk-forward 도구. 큰 금융 연구 라이브러리를 먼저 도입할 필요 없이 Java/SQL과 독립 분석 실행기를 작은 계약으로 만들 수 있다. 현재 MlFinLab 저장소는 all-rights-reserved와 유료 business/enterprise 경로를 명시하므로 무료 의존성이라고 채택하지 않는다. [maintainer repository](https://github.com/hudson-and-thames/mlfinlab)

이 순서의 산출물은 더 그럴듯한 AI 문장보다, 어떤 자료와 조건으로 후보가 나왔고 같은 조건에서 무엇이 개선됐는지 검증 가능한 체계다. 현재 공개 자료만으로 신규 예측력이 입증되었다고 결론 내리지 않는다.
