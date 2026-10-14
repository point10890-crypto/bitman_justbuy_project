"""Curated strategy library with research status.

Statuses:
  candidate      두 구간 모두 같은 날 대비 양수, 통계 확정 전
  regime_gated   레짐 조건부로만 노출 (하락장에서 음수였음)
  monitoring     새 조합 — 원장으로 측정 중, 연구 근거 없음
  retired        두 구간 모두 음수 — 실행하지 않고 근거만 보여준다

Evidence quotes research/redesign_20261012 (2024-01-02 ~ 2026-07-31, next-open entry,
5-day hold, excess vs same-day universe; discovery / confirm). The live backtest
endpoint recomputes on current data and is the authoritative number.
"""
from __future__ import annotations

EVIDENCE_BASIS = ("근거 수치: 같은 날 유니버스 대비 5일 초과수익, 탐색 / 확인 구간 "
                  "(2024-01-02~2025-12-09 / 2025-12-10~2026-10-13), 다음날 시가 진입, 확정 봉 기준")

STATUS_LABEL = {
    "candidate": "후보 (확정 전)",
    "regime_gated": "레짐 조건부",
    "monitoring": "측정 중",
    "retired": "검증 탈락",
}

LIBRARY = [
    {
        "id": "lowvol_uptrend", "status": "candidate",
        "title": "저변동 우상향",
        "summary": "60일선 위 · 20일 수익 양수인 종목 중 변동성이 가장 낮은 10종목",
        "when": {"all": [{"f": "gap_ma60", "op": ">", "v": 0}, {"f": "r20", "op": ">", "v": 0}]},
        "rank_by": {"f": "vol20", "desc": False}, "top_n": 10, "guard": {"exclude_risk": []},
        "evidence": "5일 +0.47% / +0.22%(확인 t -0.1), 1일 +0.22% / +0.20%(t 3.9 / 1.5). 비용 차감 5일 +0.13% / -0.35%",
    },
    {
        "id": "closing_bet", "status": "candidate",
        "title": "종가베팅형",
        "summary": "+5~15% 상승, 거래대금 100억↑, 120일 고가 -5% 이내, 고가권 마감·짧은 윗꼬리",
        "when": {"all": [
            {"between": ["r1", 0.05, 0.15]},
            {"f": "val_today", "op": ">=", "v": 1e10},
            {"f": "dd120", "op": ">=", "v": -0.05},
            {"f": "clv", "op": ">=", "v": 0.7},
            {"f": "upper_wick", "op": "<=", "v": 0.3},
        ]},
        "rank_by": {"f": "val_today", "desc": True}, "top_n": 5, "guard": {"exclude_risk": []},
        "evidence": "5일 +1.18% / +1.34%, 비용 차감 +0.80% / +0.49%. 적중률 48~50%로 소수 대박에 기댄 평균, t 0.9 / 1.0 확정 전",
    },
    {
        "id": "quiet_pullback", "status": "regime_gated",
        "title": "저변동 눌림",
        "summary": "120일선 위 · 20일 고가 -2~-8% · ATR 4% 이하, 업종 대비 강도 순 — 지수 60일선 위일 때만",
        "when": {"all": [
            {"f": "gap_ma120", "op": ">", "v": 0},
            {"between": ["dd20", -0.08, -0.02]},
            {"f": "atr14_pct", "op": "<=", "v": 0.04},
        ]},
        "rank_by": {"f": "rs_sector20", "desc": True}, "top_n": 5,
        "guard": {"exclude_risk": [], "regime_in": ["UP_TREND", "PULLBACK"]},
        "evidence": "상승 +0.43%, 눌림 +0.42%, 하락 -0.65%. 레짐 게이트 적용 시 +0.69% / -0.03% — 확인 구간에서 꺾임",
    },
    {
        "id": "pullback_in_trend", "status": "regime_gated",
        "title": "추세 내 눌림 재돌파",
        "summary": "60일선 위 · 중기 모멘텀 양수 · 20일 고가 -3~-10% · 양봉 · 거래량 1.2배 — 지수 60일선 위일 때만",
        "when": {"all": [
            {"f": "gap_ma60", "op": ">", "v": 0},
            {"f": "mom_120_5", "op": ">", "v": 0},
            {"between": ["dd20", -0.10, -0.03]},
            {"f": "r1", "op": ">", "v": 0},
            {"f": "volr", "op": ">=", "v": 1.2},
        ]},
        "rank_by": {"f": "quality_score", "desc": True}, "top_n": 5,
        "guard": {"exclude_risk": [], "regime_in": ["UP_TREND", "PULLBACK"]},
        "evidence": "상승 +0.71%, 하락 -0.98%. 레짐 게이트 적용 시 +0.56% / +0.71%(t 0.2)",
    },
    {
        "id": "engine_default", "status": "monitoring",
        "title": "엔진 기본 — 견고 신호 순",
        "summary": "거래대금 평균 30억↑ · 60일선 위 종목을 '고가 근접 + 낮은 ATR' 점수 순으로. 추격·고변동 자동 제외",
        "when": {"all": [{"f": "val_avg20", "op": ">=", "v": 3e9}, {"f": "gap_ma60", "op": ">", "v": 0}]},
        "rank_by": {"f": "quality_score", "desc": True}, "top_n": 10,
        "guard": {"exclude_risk": ["chase", "high_vol"]},
        "evidence": "5일 +0.70% / +0.22%(t 2.5 / 0.4). 가드 적용 +0.53% vs 미적용 +0.45%. 원장으로 실측 중",
    },
    # ---- retired: kept visible so members can see why
    {
        "id": "chase_gainers", "status": "retired",
        "title": "당일 급등 추격 (구 단타 방식)",
        "summary": "당일 +5% 이상 & 거래량 2배, 등락률 순",
        "when": {"all": [{"f": "r1", "op": ">=", "v": 0.05}, {"f": "volr", "op": ">=", "v": 2.0}]},
        "rank_by": {"f": "r1", "desc": True}, "top_n": 5, "guard": {"exclude_risk": []},
        "evidence": "5일 -2.58% / -4.02%, 적중률 35% / 35%, 비용 차감 -2.92% / -4.60%. 1일 보유도 t -4.4 / -4.9",
    },
    {
        "id": "limit_chase", "status": "retired",
        "title": "상한가 근접 추격",
        "summary": "당일 +20% 이상",
        "when": {"f": "r1", "op": ">=", "v": 0.20},
        "rank_by": {"f": "r1", "desc": True}, "top_n": 5, "guard": {"exclude_risk": []},
        "evidence": "5일 -2.92% / -5.01%, 1일 -1.36% / -2.86%(t -4.4 / -6.1)",
    },
    {
        "id": "breakout_spike", "status": "retired",
        "title": "거래량 폭발 신고가",
        "summary": "120일 신고가 & 거래량 2배",
        "when": {"all": [{"f": "new_high120", "op": "==", "v": 1}, {"f": "volr", "op": ">=", "v": 2.0}]},
        "rank_by": {"f": "volr", "desc": True}, "top_n": 5, "guard": {"exclude_risk": []},
        "evidence": "5일 -1.37% / -3.68%",
    },
    {
        "id": "volume_spike_flat", "status": "retired",
        "title": "가격 정체 속 거래량 폭증",
        "summary": "거래량 3배 & 등락 ±2% 이내",
        "when": {"all": [{"f": "volr", "op": ">=", "v": 3.0}, {"between": ["r1", -0.02, 0.02]}]},
        "rank_by": {"f": "volr", "desc": True}, "top_n": 5, "guard": {"exclude_risk": []},
        "evidence": "5일 -0.82% / -0.56%",
    },
]

BY_ID = {s["id"]: s for s in LIBRARY}


def public_view() -> list[dict]:
    return [{**s, "status_label": STATUS_LABEL[s["status"]]} for s in LIBRARY]
