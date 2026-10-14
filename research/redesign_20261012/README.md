# 재설계 연구 하네스 — KRX 조건 검증 (2026-10-12)

"어떤 조건검색이 실제로 수익을 예측하는가"를 일봉 데이터로 검증하는 프로토타입.
재설계 문서 `docs/REDESIGN_AI_CONDITION_SEARCH_2026_10_12.md` 의 근거 데이터를 만든다.

구조 자체가 재설계의 축소판이다.

```
rd/data.py        point-in-time 패널 (중복 제거, 보통주만, 거래정지·기업행위 처리)
rd/features.py    t일 종가까지만 쓰는 피처 20개 (look-ahead 차단)
rd/dsl.py         조건 DSL — LLM이 번역해 넘기는 계약. 화이트리스트 검증 후에만 실행
rd/strategies.py  현행 앱 모드 근사 3개 + 대안 가설 7개 (모두 DSL)
rd/labels.py      진입 관례 2종(종가 / 익일 시가), t+1 이후만 사용
rd/evaluate.py    횡단면 IC, 분위 초과수익, 전략 성과, 탐색 70% / 확인 30%
rd/riskband.py    ATR 배수별 목표·손절 시뮬레이션 (같은 날 둘 다 닿으면 손절 우선)
tests/            look-ahead·라벨 창·기업행위·DSL 가드·적중률·손절 우선 — 12개
```

## 재현

```bash
PROJECT="/c/bitman_justbuy_project"
cd "$PROJECT"
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m pytest research/redesign_20261012/tests -q
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe research/redesign_20261012/run_study.py   # 약 3분
```

입력: `C:\bitman_marketfloww\data\daily_prices.csv` (읽기 전용). 출력: `results/`.

## 하네스 자체 검증

- 테스트 12개 통과.
- 테스트가 결함을 실제로 잡는지 **변이 테스트**로 확인했다: 피처에 미래 참조(`shift(-1)`)를 심으면 look-ahead 테스트가, 목표·손절 판정 순서를 뒤집으면 손절 우선 테스트가 각각 실패한다.
- 연구 중 발견한 하네스 버그 1건: pandas 3.0 에서 `stack()` 이 NaN 을 남겨 적중률 분모가 희석됐다(0.02 vs 0.5). 실패 테스트로 재현 후 수정.

## 결과 파일

| 파일 | 내용 |
|---|---|
| `data_quality.json` | 기간·종목 수·제외된 기업행위 점프·구간 분할 |
| `factor_ic.csv` | 피처 20개 × 보유기간 3개 × 구간 2개의 IC·t·분위 초과수익 |
| `factor_ic_large200.csv` | 거래대금 상위 200종목 내부에서 같은 분석 |
| `strategies.csv` / `strategies_large200.csv` | 전략 11개 × 보유기간 × 진입 관례 × 구간 |
| `composite_ic.csv`, `composite_weights.json` | 탐색 구간만으로 만든 합성 점수와 확인 구간 성적 |
| `regime.csv` | KOSPI200이 60일선 위/아래일 때 전략별 5일 초과수익 |
| `risk_bands.csv`, `excursions.csv` | ATR 배수별 목표·손절 기대값, MFE/MAE 분포 |
