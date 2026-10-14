"""Forward labels strictly after the signal close.

Two entry conventions, both explicit:
  * close  - enter at the close of t (종가 매수). Matches closing-bet practice.
  * open1  - enter at the open of t+1. A delayed signal cannot buy the close it
             was computed from, so this is the honest entry for end-of-day scans.
Exit is always the close of t+h. A label whose window crosses a corporate-action
jump is NaN. Missing sessions stay missing; nothing is forward-filled.
"""
from __future__ import annotations

import pandas as pd

from .data import Panel

HORIZONS = (1, 5, 20)


def _future_jump(panel: Panel, h: int) -> pd.DataFrame:
    jump = panel.ca_jump.astype(float)
    # any jump in sessions t+1 .. t+h
    return jump[::-1].rolling(h, min_periods=1).max()[::-1].shift(-1).fillna(1.0).astype(bool)


def forward_returns(panel: Panel, h: int, entry: str = "close") -> pd.DataFrame:
    exit_px = panel.close.shift(-h)
    if entry == "close":
        entry_px = panel.close
    elif entry == "open1":
        entry_px = panel.open.shift(-1)
    else:
        raise ValueError(entry)
    fwd = exit_px / entry_px - 1
    return fwd.where(~_future_jump(panel, h))


def cross_sectional_excess(fwd: pd.DataFrame, universe: pd.DataFrame) -> pd.DataFrame:
    """Return minus the equal-weight universe average of the same day."""
    inside = fwd.where(universe)
    return inside.sub(inside.mean(axis=1), axis=0)


def benchmark_forward(panel: Panel, h: int, entry: str = "close", which: str = "KOSPI200") -> pd.Series:
    b = panel.benchmark[which]
    if entry == "close":
        return b.shift(-h) / b - 1
    # ETF open is not in the panel; close-to-close from t is the closest available proxy
    return b.shift(-h) / b - 1
