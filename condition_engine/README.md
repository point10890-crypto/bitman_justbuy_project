# condition_engine — 조건검색 엔진

홈 5개 섹션은 기존 운영 원천을 유지한다. `live_screeners.py`는 그림자 수집만 수행하며
과거 평균 거래대금은 종가×거래량 추정값이다. 통합 범위와 한계는
`docs/SECTION_INTEGRATION_2026_10_14.md`를 참조한다. `detectors.py`와 `SectionScanner`의
홈 연결 방식은 사용자 반려로 비활성화됐으며 재사용 기준이 아니다.

`docs/REDESIGN_AI_CONDITION_SEARCH_2026_10_12.md` 의 설계를 구현한 별도 서비스.
회원의 자연어 요청을 **검증된 조건식(DSL)** 으로 바꾸고, 그 조건으로 오늘 종목을 찾고, 같은 날 다른 종목과 비교한 과거 성과를 보여준다.
종목을 추천하거나 목표가를 만들지 않는다.

운영에서는 홈페이지 검색 화면과 Spring Boot의 PRO 접근 제어 프록시(`/api/engine/*`)로 연결된다. 엔진은 MiniPC의 `127.0.0.1:8090`에서 별도 실행하며 기존 회원·구독 DB와 별개인 저장소를 사용한다. 기존 종목 순위를 자동으로 교체하거나 주문을 실행하지 않는다.

## 구성

| 모듈 | 역할 |
|---|---|
| `store.py` | 일봉 저장소. 행마다 `as_of`(수집 시각)·`final`(15:40 이후 수집 여부). 잠정 봉은 확정 봉을 덮지 못하고, 다음 갱신 때 다시 받아 교체한다 |
| `panel.py` | 확정 봉만으로 종목×날짜 패널. 보통주만, ±30% 점프(기업행위)는 피처·라벨에서 무효 |
| `features.py` | 화이트리스트 피처 (`REGISTRY`). 각 피처의 설명·근거·예시 포함 |
| `dsl.py` | 조건식 검증·평가. LLM 과 엔진 사이의 계약 |
| `risk.py` | 연구로 검증된 위험 신호 4개, 위험 등급, 5일 변동 범위, 시장 국면 |
| `engine.py` | 검색(단계별: 통과 → 위험 제외 → 후보 → 표시)과 백테스트 |
| `evaluation.py` | 라벨, 탐색 70% / 확인 30% 분할, h일 간격 t값, 판정 |
| `strategies.py` | 큐레이션 전략과 상태(후보 / 레짐 조건부 / 측정 중 / 검증 탈락) |
| `agent.py` | 자연어 → DSL. OpenAI → Gemini → 규칙 번역 순서로 시도하고, 검증을 통과한 답만 쓴다 |
| `ledger.py` | SQLite 원장. 모든 실행의 단계별 종목, 이후 시세 기준 연구 성과, LLM 호출 기록 |
| `api.py` | FastAPI (`127.0.0.1:8090`, `/engine/*`) |

데이터는 `condition_engine/var/` 에 있다 (git 제외): `bars_daily.pkl`, `listing.pkl`, `names.pkl`, `ledger.db`.

## 실행

모든 명령은 프로젝트 루트에서 실행한다.

```bash
# 최초 1회: 기존 daily_prices.csv 가져오기 (수집 시각으로 확정 여부 판정)
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m condition_engine.cli import-legacy

# 시세 갱신 (FinanceDataReader, 약 4~5분). 잠정 봉이 남아 있으면 그 날짜부터 다시 받는다
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m condition_engine.cli update

# 데이터 품질
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m condition_engine.cli quality

# 전략 검색 / 백테스트 / 원장 성과 채우기
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m condition_engine.cli screen engine_default
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m condition_engine.cli backtest lowvol_uptrend
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m condition_engine.cli label

# API 서버 (.claude/launch.json 의 condition-engine 과 같음)
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m condition_engine.cli serve --port 8090
```

화면: 프론트 dev 서버(`npm run dev`, 3000)에서 `http://localhost:3000/lab`.
Vite 가 `/engine` 을 8090 으로 프록시한다. 독립 `/lab` 라우트는 dev 빌드에만 등록한다. 운영 홈페이지에는 같은 조건검색 화면을 제공하며, `/api/engine/*` 요청은 Spring Boot의 회원·구독 검사를 거쳐 엔진으로 전달된다.

## LLM 번역기

`CONDITION_ENGINE_LLM` = `auto`(기본) | `openai` | `gemini` | `none`.
키는 `.env`, `backend/.env` 에서 읽는다. 모델은 `CONDITION_ENGINE_OPENAI_MODEL`(기본 `gpt-5.5`, Spring Boot `ChatGptAgent` 와 같은 모델), `CONDITION_ENGINE_GEMINI_MODEL`(기본 `gemini-2.5-flash`).
LLM 이 없는 피처를 만들거나 호출이 실패하면 규칙 번역으로 넘어가고, 그 사유를 응답의 `fallback_reason` 에 남긴다.
호출 기록은 `GET /engine/usage` 로 본다.

2026-10-14 기준 이 PC 의 상태: OpenAI 키는 `gpt-5.5` 만 접근 가능(`gpt-4o` 는 403). `.env` 의 `GEMINI_API_KEY` 와 `backend/.env` 의 `GOOGLE_API_KEY` 는 둘 다 `API_KEY_INVALID` 다.

## 테스트

```bash
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m pytest condition_engine/tests -q
cd frontend && npx vitest run src/pages/ConditionLabPage.test.tsx
```

## 원칙

- 분석은 확정 봉만 쓴다. 오늘 장중 봉은 화면에 "계산에서 뺐다"고 표시한다.
- 성과는 같은 날 유니버스 대비 초과수익과 지수 대비를 함께 보인다. 판정은 탐색·확인 두 구간이 같은 방향이고 확인 구간 |t| ≥ 2 일 때만 "재현"이다.
- 검증에서 탈락한 전략은 오늘 검색을 하지 않는다 (API 409). 백테스트로 근거만 보여준다.
- 기본 위험 제외는 급등 추격(`chase`)과 고변동(`high_vol`)이다. 회원이 명시적으로 급등 조건을 넣으면 제외를 풀되 근거 수치와 함께 경고한다.
- 설명 문장의 숫자는 엔진이 계산한 값만 쓴다 (`explain.numbers_are_grounded`).
