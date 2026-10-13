# Profit Research Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax for tracking.

**Goal:** 실제 공개 종목 자료를 재현 가능한 조건검색·성과 평가·개선안 비교 기록으로 연결하는 실행 가능한 연구 핵심을 만든다.

**Architecture:** Java 21 순수 모듈과 별도 H2 연구 원장을 사용한다. 기존 Spring 서비스·회원 저장소·알림을 자동 변경하지 않으며 명시적으로 실행하는 CLI가 실제 공개 저장본을 읽어 결과를 만든다. 개선은 같은 시점의 비용 확인 결과를 비교해 연구 제안으로 보존한다.

**Tech Stack:** Java 21, existing Jackson/H2, JUnit 5, Gradle. 추가 유료 모델/데이터 호출과 라이브러리 설치 없음.

**Spec:** `docs/superpowers/specs/2026-10-12-bitman-research-core-design.md`, 특히 A단계. 2026-10-14 사용자 요청을 구현 진행 승인으로 받아 실행 계획·검증을 계속한다.

## Global Constraints

- 실제 근거가 없는 종목·가격·성과를 생성하지 않는다. REAL/MOCK는 provenance이며 동일성·시점·기간을 검증한다.
- 새로운 주문·회원 DB·운영 알림 경로 없음. 원본 프로젝트의 미추적 연구 자료·다른 프로젝트 CSV는 사용하지 않는다.
- 조건 판정은 TRUE/FALSE/UNKNOWN이며 null·오래된 자료·미래 자료를 0으로 치환하지 않는다.
- 결과·실험의 입력·버전을 고정하고 완료 단계를 재사용한다. 취소·늦은 결과·UNCERTAIN은 성공으로 덮지 않는다.
- 관측 변화와 체결 시뮬레이션은 별도 상태다. 비용/비교 대상 미확인은 net/excess=null이다.
- 공개 저장본의 미래 성과가 없으면 실제 평가 표본은 0건이다. 합성 예외 검사와 실제 성과를 구분한다.

## Review Focus

- 동일 지표의 상충 공급자 값을 임의 선택해 조건을 통과시키지 않는다: Task 1 conflict test.
- 정렬값 UNKNOWN이 결과 순위를 바꿀 수 있으면 전체 순위로 승격하지 않는다: Task 1 rank coverage test.
- 정지·누락 가격을 다음 임의 날짜로 메우거나 같은 봉 목표/손절 순서를 만들지 않는다: Task 2 missing/path tests.
- 취소된 실행과 옛 입력의 늦은 저장이 새 결과를 덮지 않는다: Task 3 reopen/lease tests.
- 표본 누락·겹친 날짜·비용 미확인으로 개선안이 자동 승격되지 않는다: Task 3 paired eligibility tests, Task 4 actual-data test.

## Task 1: 결정론 조건검색

**Files:** `backend/src/main/java/com/bitman/justbuy/research/ResearchConditionEngine.java`, matching test.
**Interface:** nested immutable records `Fact`, `Candidate`, `Snapshot`, `Compare/All/Any/Not`, `Request`, `Result`; `evaluate(Snapshot,Request)`; allowlisted metric/unit/operator; missing/conflict/stale/future reason codes. 전체 범위·검색 순서와 고유 후보 수 보존.

- [x] Write failure cases: unknown NOT, provider conflict, future fact, wrong unit, filter-then-rank vs rank-then-filter, missing rank, partial universe, deterministic ties, malformed/overdeep AST.
- [x] Run targeted test and record missing implementation/expected behavior failure.
- [x] Implement typed validation, 3값 논리, cutoff·freshness, deterministic ranking and coverage.
- [x] Run targeted tests; keep engine free of network/Spring/database.

## Task 2: 실제 이후 관측·가격 시나리오 평가

**Files:** `ResearchOutcomeEvaluator.java`, matching test.
**Interface:** own immutable `Signal`, `SessionBar`, `Policy`, `Outcome`; `evaluate` using explicit ordered session calendar. 첫 다음 KRX 정규 세션 하나가 진입 유효기간, 진입 세션을 1로 세는 horizon. Positive valid OHLC, availability, raw/unresolved corporate action flags, entry eligibility, optional costs and datealigned benchmark.

- [x] Pin 1/3/5/20 anchor, holiday/calendar ordering, missing first entry, pending mature outcome, unresolved action, bad OHLC, cost/benchmark mismatch, simultaneous target/stop and gap.
- [x] Observe failure before implementing.
- [x] Implement exact Decimal return and explicit outcome/path status. No forward fill or realized-order claim.
- [x] Targeted tests; report all missing/ambiguous states.

## Task 3: 영속 재현·개선안 비교

**Files:** `ResearchRunStore.java`, `ResearchLearningService.java`, matching tests.
**Interface store:** Path-owned `research-ledger` H2 file; versioned input/output SHA256, immutable validated stage reuse, stage reservation/fencing/cancel, close/reopen; generic JSON outputs.
**Interface learning:** own paired `Observation` (instrument/date/horizon/policy/versions/gross/net/benchmark/state), `Policy`, `Review`; deterministic summarize unique complete date groups, paired baseline/challenger delta, missing/cost eligibility, failure reason cohorts, `INSUFFICIENT_EVIDENCE/REVIEW_CANDIDATE/REJECTED`; never automatic production activation.

- [x] Test durable reopen, changed fingerprint, duplicate reservation, cancellation late completion, stale lease, UNCERTAIN reservation, path namespace/symlink boundaries.
- [x] Test missing net, mixed policy/horizon, duplicate instrument/date, fewer independent dates, challenger lower result, unrelated improvements, deterministic report.
- [x] Observe RED; implement separate bounded JDBC transactions and pure comparison with conservative eligibility.
- [x] Targeted tests. Preserve unknown cost and sample limits; a review candidate is not proven profitability.

## Task 4: 실행 CLI와 실제 공개 자료 보고서

**Files:** `ResearchCoreCli.java`, `PublicPickSnapshotAdapter.java`, matching tests; `docs/research/2026-10-14-profit-core-verification.md` and generated public report.
**Interface:** CLI `--snapshot <public snapshot> --workspace <new research dir>`; no default network or provider key. Actual snapshot read-only, bounded JSON, public allowlist, provider/time evidence. CLI outputs aggregate/report; writes only owned research workspace. A단계의 미래 봉 입력은 순수 평가기의 typed API로 검증하며 CLI 범용 `--evaluation-data` 파일 반입은 검증된 B단계 exporter 계약과 함께 도입한다.

- [x] Tests first: real fixture 9slots/4stocks; absent facts UNKNOWN; no future outcome yields 0measured and no profit claim; rerun same inputs reuses stored stage; changed input invalidates; malformed or secret-like unknown fields not echoed.
- [x] Implement adapter/CLI against tested task interfaces, immutable hashing and safe output boundary.
- [x] Run actual saved public snapshot twice and verify digest/reuse; change a copied input to verify new fingerprint. Explain stale and legacy identity limits.
- [x] Run whole backend suite, peer review and scoped repairs. No frontend edits require no browser proof.
- [ ] Commit only task files after passing verification; push feature branch and integrate with current remote main when nonconflicting. No production deployment claim for standalone core.

## Execution record

The current team implements independent tasks with explicit file ownership. Root integrates and orders verification. Interfaces refined before delegation are recorded with the source code and test reports. New APIs, provider collectors and production scheduling are B/C follow-up stages, not simulated as completed by this A module.

| 공유 인터페이스 | 생산/소비 | 실행 판단 |
|---|---|---|
| Task1 → Task4 | immutable Fact/Candidate/Snapshot/Request/Result | 단계·범위·미확인을 같은 API로 사용 |
| Task2 → Task4 | typed Signal/Policy/Outcome | 불완전한 공개 기록은 INVALID_SIGNAL/UNMEASURABLE; 캘린더를 만들어 넣지 않음 |
| Task3 → Task4 | 원장 lease와 검증 완료 출력, 비교 Review | 재사용·취소·비용 불명과 실증 부족 보존 |
| Task1/2/3 | 각각 독립 순수 지표·성과·원장/비교 | 소유 파일 충돌 없음; Gradle 전체 test source 컴파일은 root가 순서 조정 |

Ruling: 사용자의 2026-10-14 구현 요청에 따라 현재 팀으로 진행하며 설계 승인 질문을 반복하지 않는다. 첫 계약은 격리 A단계이고 실제 수익성 증거를 요구하는 B/C 전환과 구분한다.
Ruling: 검증된 공개 이후 봉 exporter가 없으므로 자유 JSON으로 실제 성과라고 선언하는 CLI 반입은 추가하지 않는다. 순수 평가기 테스트와 실제 미측정 자료를 구분한다. 판단이 틀리면 B exporter와 CLI 계약을 확장하는 범위로 복구한다.
Ruling: Windows symlink 권한 검사는 실행 환경의 제한으로 1건 건너뛰며, 권한 없이 만들 수 있는 Windows junction 경계 검사를 실제 실행한다.
Ruling: 공유 보고서 cutoff가 같고 입력이 다르면 시간상 최신을 증명할 수 없어 먼저 커밋한 포인터를 보존하고 각 실행의 보고서를 별도로 보존한다.
