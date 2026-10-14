"""Risk flags, risk grade, expected range — and market regime.

Only flags that the research measured as harmful are used, each with its evidence.
The expected range is a risk statement ("this usually moves this much in 5 days"),
not a target: target/stop multiples did not change outcomes in any of 60 tested
combinations (research §2.6).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

# Evidence numbers: 5-session excess vs the same day's universe, discovery / confirm
# (2024-01-02~2025-12-09 / 2025-12-10~2026-10-13), on final bars after the legacy repair.
FLAG_INFO = {
    "chase": {
        "label": "급등 추격",
        "rule": "당일 +5% 이상 & 거래량 2배 이상, 또는 당일 +15% 이상",
        "evidence": "이 조건에 걸린 종목은 5일 뒤 같은 날 다른 종목 대비 -0.88% / -2.00%, 적중률 40% / 37%",
        "weight": 2,
    },
    "high_vol": {
        "label": "고변동",
        "rule": "ATR 비율이 그날 유니버스 상위 20%",
        "evidence": "5일 -0.64% / -0.48%. ATR 상위 10%는 -0.94% / -0.90%, 하위 10%는 +0.43% / +0.28%",
        "weight": 2,
    },
    "breakout_spike": {
        "label": "거래량 폭발 신고가",
        "rule": "120일 신고가 & 거래량 2배 이상",
        "evidence": "5일 -0.28% / -2.60%",
        "weight": 1,
    },
    "volume_spike_flat": {
        "label": "가격 정체 속 거래량 폭증",
        "rule": "거래량 3배 이상 & 등락 ±2% 이내",
        "evidence": "5일 -0.72% / -0.59%",
        "weight": 1,
    },
}

# Any validated flag is worth at least "보통": every one of them lost to the same day's
# universe in both windows, so showing "낮음" next to a flag would contradict the evidence.
GRADES = [(0, "낮음"), (1, "보통"), (3, "높음"), (4, "매우 높음")]

# Five-session excursion quantiles in ATR multiples, from research/redesign_20261012/results/excursions.csv
BAND_TYPICAL_ATR = 1.1
BAND_WIDE = {"default": (-2.7, 3.5), "chase": (-4.0, 5.3)}


def risk_flags(feats: dict[str, pd.DataFrame], universe: pd.DataFrame) -> dict[str, pd.DataFrame]:
    r1, volr = feats["r1"], feats["volr"]
    atr_pct_rank = feats["atr14_pct"].where(universe).rank(axis=1, pct=True)
    return {
        "chase": ((r1 >= 0.05) & (volr >= 2.0)) | (r1 >= 0.15),
        "high_vol": atr_pct_rank >= 0.8,
        "breakout_spike": (feats["new_high120"] == 1) & (volr >= 2.0),
        "volume_spike_flat": (volr >= 3.0) & (r1.abs() <= 0.02),
    }


def grade(score: int) -> str:
    label = GRADES[0][1]
    for threshold, name in GRADES:
        if score >= threshold:
            label = name
    return label


def describe_row(flags_today: dict[str, bool], atr_pct: float | None, close: float | None) -> dict:
    active = [k for k, v in flags_today.items() if v]
    score = sum(FLAG_INFO[k]["weight"] for k in active)
    out = {
        "flags": [{"id": k, "label": FLAG_INFO[k]["label"], "rule": FLAG_INFO[k]["rule"],
                   "evidence": FLAG_INFO[k]["evidence"]} for k in active],
        "score": score,
        "grade": grade(score),
    }
    if atr_pct is not None and np.isfinite(atr_pct) and close:
        lo, hi = BAND_WIDE["chase" if "chase" in active else "default"]
        out["band"] = {
            "atr_pct": round(float(atr_pct) * 100, 2),
            "typical_pct": round(float(atr_pct) * BAND_TYPICAL_ATR * 100, 1),
            "wide_low_pct": round(float(atr_pct) * lo * 100, 1),
            "wide_high_pct": round(float(atr_pct) * hi * 100, 1),
            "note": "최근 2.5년 비슷한 종목의 5거래일 변동 범위(중앙값·하위/상위 10%). 목표가가 아닙니다.",
        }
    return out


# ------------------------------------------------------------------- regime
REGIME_INFO = {
    "UP_TREND": "지수가 60일선 위에서 20일 상승",
    "PULLBACK": "지수가 60일선 위지만 20일 하락",
    "DOWN_TREND": "지수가 60일선 아래에서 20일 하락",
    "REBOUND": "지수가 60일선 아래지만 20일 상승",
}


def regime_series(benchmark: pd.Series) -> pd.Series:
    """Point-in-time market regime from the index close (uses rows <= t only)."""
    ma60 = benchmark.rolling(60, min_periods=60).mean()
    r20 = benchmark / benchmark.shift(20) - 1
    above = benchmark > ma60
    up = r20 > 0
    out = pd.Series(np.where(above & up, "UP_TREND",
                     np.where(above & ~up, "PULLBACK",
                     np.where(~above & ~up, "DOWN_TREND", "REBOUND"))), index=benchmark.index, dtype=object)
    return out.where(ma60.notna() & r20.notna())
