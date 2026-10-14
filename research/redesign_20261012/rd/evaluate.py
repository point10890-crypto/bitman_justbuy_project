"""Scoring rules for features and strategies.

Discipline:
  * Cross-sectional first: compare names on the same day, which removes market
    direction from the verdict.
  * Discovery / confirmation split by date (default 70 / 30). A finding counts
    only if it holds in the confirmation window it never saw.
  * Overlapping multi-day labels inflate naive t-stats, so significance uses
    one observation every h sessions.
  * Costs: 0.23% round trip charged once per trade.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

ROUND_TRIP_COST = 0.0023
MIN_NAMES_PER_DAY = 30


def daily_ic(feature: pd.DataFrame, target: pd.DataFrame) -> pd.Series:
    """Spearman correlation across names, one value per day."""
    m = feature.notna() & target.notna()
    fr = feature.where(m).rank(axis=1)
    yr = target.where(m).rank(axis=1)
    fr = fr.sub(fr.mean(axis=1), axis=0)
    yr = yr.sub(yr.mean(axis=1), axis=0)
    num = (fr * yr).sum(axis=1)
    den = np.sqrt((fr ** 2).sum(axis=1) * (yr ** 2).sum(axis=1))
    ic = num / den.replace(0, np.nan)
    return ic.where(m.sum(axis=1) >= MIN_NAMES_PER_DAY)


def split_dates(index: pd.DatetimeIndex, discovery_share: float = 0.7) -> tuple[pd.DatetimeIndex, pd.DatetimeIndex]:
    cut = int(len(index) * discovery_share)
    return index[:cut], index[cut:]


def t_stat_non_overlap(series: pd.Series, h: int) -> float:
    s = series.dropna().iloc[::max(h, 1)]
    if len(s) < 5 or s.std(ddof=1) == 0:
        return float("nan")
    return float(s.mean() / s.std(ddof=1) * np.sqrt(len(s)))


@dataclass
class FactorResult:
    feature: str
    horizon: int
    window: str
    mean_ic: float
    ic_t: float
    ic_hit: float          # share of days with IC > 0
    top_decile_excess: float
    bottom_decile_excess: float
    days: int


def factor_study(feature: pd.DataFrame, excess: pd.DataFrame, universe: pd.DataFrame,
                 h: int, name: str, windows: dict[str, pd.DatetimeIndex]) -> list[FactorResult]:
    f = feature.where(universe)
    y = excess.where(universe)
    ic = daily_ic(f, y)
    pct = f.rank(axis=1, pct=True)
    top = y.where(pct >= 0.9).mean(axis=1)
    bot = y.where(pct <= 0.1).mean(axis=1)
    out = []
    for label, idx in windows.items():
        ic_w = ic.reindex(idx)
        out.append(FactorResult(
            feature=name, horizon=h, window=label,
            mean_ic=float(ic_w.mean()), ic_t=t_stat_non_overlap(ic_w, h),
            ic_hit=float((ic_w.dropna() > 0).mean()) if ic_w.notna().any() else float("nan"),
            top_decile_excess=float(top.reindex(idx).mean()),
            bottom_decile_excess=float(bot.reindex(idx).mean()),
            days=int(ic_w.notna().sum()),
        ))
    return out


@dataclass
class StrategyResult:
    strategy: str
    title: str
    horizon: int
    entry: str
    window: str
    signal_days: int
    avg_picks: float
    raw_mean: float        # average forward return per pick-day (equal weight within day)
    excess_mean: float     # vs same-day universe average
    bench_excess: float    # vs KOSPI200 over the same window
    net_mean: float        # raw minus round-trip cost
    hit_rate: float        # share of picks with positive excess
    excess_t: float


def strategy_study(selected: pd.DataFrame, fwd: pd.DataFrame, excess: pd.DataFrame,
                   bench_fwd: pd.Series, h: int, entry: str, sid: str, title: str,
                   windows: dict[str, pd.DatetimeIndex]) -> list[StrategyResult]:
    raw_day = fwd.where(selected).mean(axis=1)
    exc_day = excess.where(selected).mean(axis=1)
    bench_day = raw_day - bench_fwd
    picks = selected.sum(axis=1)
    pick_excess = excess.where(selected)
    out = []
    for label, idx in windows.items():
        has = picks.reindex(idx) > 0
        days = idx[has.values]
        pe = pick_excess.reindex(days)
        n_picks = int(pe.notna().sum().sum())
        out.append(StrategyResult(
            strategy=sid, title=title, horizon=h, entry=entry, window=label,
            signal_days=int(len(days)),
            avg_picks=float(picks.reindex(days).mean()) if len(days) else float("nan"),
            raw_mean=float(raw_day.reindex(days).mean()),
            excess_mean=float(exc_day.reindex(days).mean()),
            bench_excess=float(bench_day.reindex(days).mean()),
            net_mean=float(raw_day.reindex(days).mean() - ROUND_TRIP_COST),
            hit_rate=float((pe.stack().dropna() > 0).mean()) if n_picks else float("nan"),
            excess_t=t_stat_non_overlap(exc_day.reindex(days), h),
        ))
    return out
