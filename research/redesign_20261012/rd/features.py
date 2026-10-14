"""Point-in-time features at the close of date t.

Rule: a feature at t may read rows <= t only. Any lookback window that spans a
corporate-action jump is invalidated (NaN), never "adjusted" by guesswork.
Each feature documents the trader intuition it encodes, so the study can tell
which intuitions survive contact with data.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .data import Panel, clean_lookback


def _guard(frame: pd.DataFrame, panel: Panel, window: int) -> pd.DataFrame:
    return frame.where(clean_lookback(panel, window))


def build_features(panel: Panel) -> dict[str, pd.DataFrame]:
    c, o, h, l, v, val = panel.close, panel.open, panel.high, panel.low, panel.volume, panel.value
    prev_c = c.shift(1)

    f: dict[str, pd.DataFrame] = {}

    # --- today's move: what the current app's scoring rewards most -------------
    f["r1"] = _guard(c / prev_c - 1, panel, 2)                       # 당일 등락률
    f["gap_open"] = _guard(o / prev_c - 1, panel, 2)                 # 시가 갭
    rng = (h - l).replace(0, np.nan)
    f["clv"] = (c - l) / rng                                          # 종가 위치(1=고가 마감)
    f["upper_wick"] = (h - np.maximum(o, c)) / rng                    # 윗꼬리 비율

    # --- trend / momentum ------------------------------------------------------
    f["r5"] = _guard(c / c.shift(5) - 1, panel, 6)
    f["r20"] = _guard(c / c.shift(20) - 1, panel, 21)
    f["r60"] = _guard(c / c.shift(60) - 1, panel, 61)
    f["mom_120_5"] = _guard(c.shift(5) / c.shift(120) - 1, panel, 121)   # 중기 모멘텀(최근 1주 제외)
    for w in (20, 60, 120):
        ma = c.rolling(w, min_periods=w).mean()
        f[f"gap_ma{w}"] = _guard(c / ma - 1, panel, w)
    hi20 = h.rolling(20, min_periods=20).max()
    hi120 = h.rolling(120, min_periods=120).max()
    f["dd20"] = _guard(c / hi20 - 1, panel, 20)                       # 20일 고가 대비(눌림 깊이)
    f["dd120"] = _guard(c / hi120 - 1, panel, 120)                    # 120일 고가 근접도
    prior_hi120 = c.shift(1).rolling(119, min_periods=119).max()
    f["new_high120"] = _guard((c >= prior_hi120).astype(float).where(prior_hi120.notna()), panel, 120)

    up = (c > prev_c).astype(float).where(c.notna() & prev_c.notna())
    streak = up.copy() * 0
    run = pd.Series(0.0, index=c.columns)
    for i, idx in enumerate(c.index):
        row = up.iloc[i]
        run = (run + 1).where(row == 1, 0.0)
        streak.iloc[i] = run.where(row.notna())
    f["up_streak"] = streak.clip(upper=10)                            # 연속 상승일

    # --- participation / liquidity --------------------------------------------
    vol_avg_prior = v.shift(1).rolling(20, min_periods=15).mean()
    f["volr"] = v / vol_avg_prior                                     # 거래량 배수(당일 제외 20일 평균 대비)
    f["val_today"] = val
    f["val_avg20"] = val.rolling(20, min_periods=15).mean()           # 유동성

    # --- risk -------------------------------------------------------------------
    ret = c / prev_c - 1
    f["vol20"] = _guard(ret.rolling(20, min_periods=15).std(), panel, 21)
    tr = pd.concat([(h - l), (h - prev_c).abs(), (l - prev_c).abs()]).groupby(level=0).max()
    tr = tr.reindex(c.index)
    f["atr14_pct"] = _guard(tr.rolling(14, min_periods=14).mean() / c, panel, 15)

    # --- relative strength vs own market proxy ----------------------------------
    bench = panel.benchmark["KOSPI200"]
    bench_r20 = bench / bench.shift(20) - 1
    f["rs20"] = f["r20"].sub(bench_r20, axis=0)

    return f


def universe_mask(panel: Panel, feats: dict[str, pd.DataFrame],
                  min_price: float = 1_000, min_value_avg20: float = 1e9,
                  min_history: int = 120) -> pd.DataFrame:
    """Tradable names at the close of t: priced, liquid, seasoned, not halted."""
    seasoned = panel.close.notna().cumsum() >= min_history
    return (
        panel.close.ge(min_price)
        & feats["val_avg20"].ge(min_value_avg20)
        & seasoned
        & panel.volume.gt(0)
    )
