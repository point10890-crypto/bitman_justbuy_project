"""Strategy library expressed in the DSL.

The first group approximates what the current app does today, on daily data.
The second group is the set of alternative hypotheses a redesign would consider.
All are scored by the same harness, so they compete on equal terms.
"""

CURRENT_APP_PROXIES = [
    {
        "id": "S0_chase_gainers",
        "title": "현행 단타 근사 — 당일 +5% 이상 & 거래량 2배, 등락률 순",
        "maps_to": "ShortTermRealtimeScanner (changeRate×2.5 + 순위 가점)",
        "when": {"all": [{"f": "r1", "op": ">=", "v": 0.05}, {"f": "volr", "op": ">=", "v": 2.0}]},
        "rank_by": {"f": "r1", "desc": True},
        "top_n": 5,
    },
    {
        "id": "S1_limit_chase",
        "title": "상한가 근접 추격 — 당일 +20% 이상",
        "maps_to": "급등 추격의 극단",
        "when": {"f": "r1", "op": ">=", "v": 0.20},
        "rank_by": {"f": "r1", "desc": True},
        "top_n": 5,
    },
    {
        "id": "S2_closing_bet",
        "title": "종가베팅 V2 근사 — +5~15%, 거래대금 100억↑, 120일 고가 -5% 이내, 고가권 마감",
        "maps_to": "engine 종가베팅 V2 (거래대금·신고가·캔들 가점)",
        "when": {"all": [
            {"between": ["r1", 0.05, 0.15]},
            {"f": "val_today", "op": ">=", "v": 1e10},
            {"f": "dd120", "op": ">=", "v": -0.05},
            {"f": "clv", "op": ">=", "v": 0.7},
            {"f": "upper_wick", "op": "<=", "v": 0.3},
        ]},
        "rank_by": {"f": "val_today", "desc": True},
        "top_n": 5,
    },
]

ALTERNATIVES = [
    {
        "id": "S3_pullback_in_trend",
        "title": "추세 내 눌림 재돌파 — 60일선 위, 중기 모멘텀+, 20일 고가 -3~-10%, 양봉, 거래량 1.2배",
        "when": {"all": [
            {"f": "gap_ma60", "op": ">", "v": 0.0},
            {"f": "mom_120_5", "op": ">", "v": 0.0},
            {"between": ["dd20", -0.10, -0.03]},
            {"f": "r1", "op": ">", "v": 0.0},
            {"f": "volr", "op": ">=", "v": 1.2},
        ]},
        "rank_by": {"f": "mom_120_5", "desc": True},
        "top_n": 5,
    },
    {
        "id": "S4_breakout120",
        "title": "120일 신고가 돌파 + 거래량 2배",
        "when": {"all": [{"f": "new_high120", "op": "==", "v": 1}, {"f": "volr", "op": ">=", "v": 2.0}]},
        "rank_by": {"f": "volr", "desc": True},
        "top_n": 5,
    },
    {
        "id": "S5_oversold_liquid",
        "title": "유동성 상위 단기 과매도 — 5일 -10% 이하, 거래대금 평균 50억↑",
        "when": {"all": [{"f": "r5", "op": "<=", "v": -0.10}, {"f": "val_avg20", "op": ">=", "v": 5e9}]},
        "rank_by": {"f": "r5", "desc": False},
        "top_n": 5,
    },
    {
        "id": "S6_mid_momentum",
        "title": "중기 모멘텀 상위 — 최근 1주 제외 6개월 수익률 상위",
        "when": {"all": [{"f": "mom_120_5", "op": ">", "v": 0.0}, {"f": "val_avg20", "op": ">=", "v": 3e9}]},
        "rank_by": {"f": "mom_120_5", "desc": True},
        "top_n": 10,
    },
    {
        "id": "S7_lowvol_uptrend",
        "title": "저변동 우상향 — 60일선 위, 20일 수익 +, 변동성 낮은 순",
        "when": {"all": [{"f": "gap_ma60", "op": ">", "v": 0.0}, {"f": "r20", "op": ">", "v": 0.0}]},
        "rank_by": {"f": "vol20", "desc": False},
        "top_n": 10,
    },
    {
        "id": "S8_accumulation",
        "title": "가격 정체 속 거래량 급증 — 거래량 3배, 등락 ±2% 이내",
        "when": {"all": [{"f": "volr", "op": ">=", "v": 3.0}, {"between": ["r1", -0.02, 0.02]}]},
        "rank_by": {"f": "volr", "desc": True},
        "top_n": 5,
    },
    {
        "id": "S9_quiet_pullback",
        "title": "저변동 눌림 — 120일선 위, 20일 고가 -2~-8%, ATR 4% 이하, 상대강도 순",
        "when": {"all": [
            {"f": "gap_ma120", "op": ">", "v": 0.0},
            {"between": ["dd20", -0.08, -0.02]},
            {"f": "atr14_pct", "op": "<=", "v": 0.04},
        ]},
        "rank_by": {"f": "rs20", "desc": True},
        "top_n": 5,
    },
]

ALL_STRATEGIES = CURRENT_APP_PROXIES + ALTERNATIVES
