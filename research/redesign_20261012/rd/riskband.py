"""Target / stop calibration in ATR units from daily paths.

Daily bars cannot tell which of target and stop came first inside one session,
so a day that touches both is scored as a stop (the conservative reading).
Entry is the open of t+1; the trade is closed at the close of t+h if neither
level was touched. All outcomes are expressed in ATR multiples and in percent.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .data import Panel

ROUND_TRIP_COST = 0.0023


def simulate_bands(panel: Panel, selected: pd.DataFrame, atr_pct: pd.DataFrame, h: int,
                   targets=(1.0, 1.5, 2.0, 3.0), stops=(1.0, 1.5, 2.0),
                   dates: pd.DatetimeIndex | None = None) -> pd.DataFrame:
    o = panel.open.to_numpy()
    hi = panel.high.to_numpy()
    lo = panel.low.to_numpy()
    c = panel.close.to_numpy()
    jump = panel.ca_jump.to_numpy()
    sel = selected.to_numpy()
    atr = atr_pct.to_numpy()
    idx_ok = np.ones(len(panel.dates), dtype=bool) if dates is None else panel.dates.isin(dates)

    trades = []
    n_days = len(panel.dates)
    for t in range(n_days - h - 1):
        if not idx_ok[t]:
            continue
        for j in np.flatnonzero(sel[t]):
            entry = o[t + 1, j]
            a = atr[t, j]
            if not (np.isfinite(entry) and entry > 0 and np.isfinite(a) and a > 0):
                continue
            window = slice(t + 1, t + 1 + h)
            if jump[window, j].any():
                continue
            hs, ls, cl = hi[window, j], lo[window, j], c[t + h, j]
            if not (np.isfinite(hs).all() and np.isfinite(ls).all() and np.isfinite(cl)):
                continue
            trades.append((entry, a, hs, ls, cl))

    rows = []
    for k in targets:
        for m in stops:
            outcomes = []
            hit_t = hit_s = 0
            for entry, a, hs, ls, cl in trades:
                tgt = entry * (1 + k * a)
                stp = entry * (1 - m * a)
                result = cl / entry - 1
                for day in range(len(hs)):
                    if ls[day] <= stp:                 # stop first when both touch
                        result, hit_s = -m * a, hit_s + 1
                        break
                    if hs[day] >= tgt:
                        result, hit_t = k * a, hit_t + 1
                        break
                outcomes.append(result - ROUND_TRIP_COST)
            n = len(outcomes)
            rows.append({
                "target_atr": k, "stop_atr": m, "trades": n,
                "target_hit": hit_t / n if n else np.nan,
                "stop_hit": hit_s / n if n else np.nan,
                "net_mean_pct": float(np.mean(outcomes) * 100) if n else np.nan,
                "net_median_pct": float(np.median(outcomes) * 100) if n else np.nan,
            })
    return pd.DataFrame(rows)


def excursions(panel: Panel, selected: pd.DataFrame, atr_pct: pd.DataFrame, h: int) -> pd.DataFrame:
    """MFE / MAE of each pick over h sessions, in ATR multiples."""
    o, hi, lo = panel.open.to_numpy(), panel.high.to_numpy(), panel.low.to_numpy()
    jump, sel, atr = panel.ca_jump.to_numpy(), selected.to_numpy(), atr_pct.to_numpy()
    out = []
    for t in range(len(panel.dates) - h - 1):
        for j in np.flatnonzero(sel[t]):
            entry, a = o[t + 1, j], atr[t, j]
            w = slice(t + 1, t + 1 + h)
            if not (np.isfinite(entry) and entry > 0 and np.isfinite(a) and a > 0) or jump[w, j].any():
                continue
            mfe = np.nanmax(hi[w, j]) / entry - 1
            mae = np.nanmin(lo[w, j]) / entry - 1
            out.append((mfe / a, mae / a))
    return pd.DataFrame(out, columns=["mfe_atr", "mae_atr"])
