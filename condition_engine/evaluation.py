"""Forward labels and scoring statistics.

Entry conventions are explicit. `open1` (default) buys the next session's open,
which is what an end-of-day scan can honestly do; `close` buys the signal close
(closing-bet practice). Labels never cross a corporate-action jump, and missing
sessions stay missing.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import config
from .panel import Panel

MIN_NAMES_PER_DAY = 30


def future_jump(panel: Panel, h: int) -> pd.DataFrame:
    jump = panel.ca_jump.astype(float)
    return jump[::-1].rolling(h, min_periods=1).max()[::-1].shift(-1).fillna(1.0).astype(bool)


def forward_returns(panel: Panel, h: int, entry: str = "open1") -> pd.DataFrame:
    exit_px = panel.close.shift(-h)
    if entry == "close":
        entry_px = panel.close
    elif entry == "open1":
        entry_px = panel.open.shift(-1)
    else:
        raise ValueError(f"unknown entry {entry!r}")
    return (exit_px / entry_px - 1).where(~future_jump(panel, h))


def excess_vs_universe(fwd: pd.DataFrame, universe: pd.DataFrame) -> pd.DataFrame:
    inside = fwd.where(universe)
    return inside.sub(inside.mean(axis=1), axis=0)


def benchmark_forward(panel: Panel, h: int, which: str = "KOSPI200") -> pd.Series:
    b = panel.benchmark[which]
    return b.shift(-h) / b - 1


def daily_ic(feature: pd.DataFrame, target: pd.DataFrame) -> pd.Series:
    m = feature.notna() & target.notna()
    fr = feature.where(m).rank(axis=1)
    yr = target.where(m).rank(axis=1)
    fr = fr.sub(fr.mean(axis=1), axis=0)
    yr = yr.sub(yr.mean(axis=1), axis=0)
    num = (fr * yr).sum(axis=1)
    den = np.sqrt((fr ** 2).sum(axis=1) * (yr ** 2).sum(axis=1))
    return (num / den.replace(0, np.nan)).where(m.sum(axis=1) >= MIN_NAMES_PER_DAY)


def t_stat_non_overlap(series: pd.Series, h: int) -> float:
    s = series.dropna().iloc[::max(h, 1)]
    if len(s) < 5 or s.std(ddof=1) == 0:
        return float("nan")
    return float(s.mean() / s.std(ddof=1) * np.sqrt(len(s)))


def split_windows(index: pd.DatetimeIndex, share: float = config.DISCOVERY_SHARE) -> dict[str, pd.DatetimeIndex]:
    cut = int(len(index) * share)
    return {"all": index, "discovery": index[:cut], "confirm": index[cut:]}


def window_stats(selected: pd.DataFrame, fwd: pd.DataFrame, excess: pd.DataFrame,
                 bench_fwd: pd.Series, h: int, idx: pd.DatetimeIndex) -> dict:
    sel = selected.reindex(idx)
    picks = sel.sum(axis=1)
    days = idx[(picks > 0).to_numpy()]
    if len(days) == 0:
        return {"signal_days": 0, "picks": 0}
    raw_day = fwd.where(selected).mean(axis=1).reindex(days)
    exc_day = excess.where(selected).mean(axis=1).reindex(days)
    idx_day = raw_day - bench_fwd.reindex(days)
    pick_excess = excess.where(selected).reindex(days).stack().dropna()
    valid_days = int(exc_day.notna().sum())
    return {
        "signal_days": valid_days,
        "picks": int(len(pick_excess)),
        "avg_picks_per_day": _r(picks.reindex(days).mean(), 2),
        "mean_return_pct": _pct(raw_day.mean()),
        "net_return_pct": _pct(raw_day.mean() - config.ROUND_TRIP_COST),
        "excess_vs_universe_pct": _pct(exc_day.mean()),
        "excess_vs_index_pct": _pct(idx_day.mean()),
        "hit_rate_pct": _pct((pick_excess > 0).mean()) if len(pick_excess) else None,
        "t_stat": _r(t_stat_non_overlap(exc_day, h), 2),
    }


def verdict(discovery: dict, confirm: dict) -> dict:
    """Plain-language verdict from the two windows. Conservative by design."""
    d, c = discovery.get("excess_vs_universe_pct"), confirm.get("excess_vs_universe_pct")
    n = min(discovery.get("signal_days", 0), confirm.get("signal_days", 0))
    if d is None or c is None or n < 20:
        return {"code": "insufficient", "label": "표본 부족", "detail": "두 구간 모두 신호일이 20일 이상이어야 판단합니다"}
    t = confirm.get("t_stat") or 0
    if d > 0 and c > 0 and t >= 2:
        return {"code": "robust_positive", "label": "재현된 양의 성과", "detail": "탐색·확인 두 구간 모두 같은 날 다른 종목보다 나았고 확인 구간 t ≥ 2"}
    if d > 0 and c > 0:
        return {"code": "positive_unconfirmed", "label": "양의 성과(통계적 확정 전)", "detail": "두 구간 모두 양수지만 확인 구간 유의성이 낮습니다"}
    if d < 0 and c < 0 and t <= -2:
        return {"code": "robust_negative", "label": "재현된 음의 성과", "detail": "탐색·확인 두 구간 모두 같은 날 다른 종목보다 나빴고 확인 구간 t ≤ -2"}
    if d < 0 and c < 0:
        return {"code": "negative_unconfirmed", "label": "음의 성과(통계적 확정 전)", "detail": "두 구간 모두 음수지만 확인 구간 유의성이 낮습니다"}
    return {"code": "unstable", "label": "구간마다 달라짐", "detail": "한 구간에서만 양수 — 우연일 가능성이 큽니다"}


def _pct(x) -> float | None:
    return None if x is None or not np.isfinite(x) else round(float(x) * 100, 2)


def _r(x, nd) -> float | None:
    return None if x is None or not np.isfinite(x) else round(float(x), nd)
