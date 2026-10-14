"""Research harness for the five section detectors.

Every detector is a pair (selected, rank) of date x code frames built only from data
observable at the signal close. The harness turns it into the picks a member would see
(top_n per day by rank) and measures them against the same day's liquid universe.

Splits (fixed before any detector was written):
  A discovery  2024-06-01 .. 2025-06-30   ideas are formed and tuned here
  B validation 2025-07-01 .. 2025-12-31   chosen designs are checked here
  C holdout    2026-01-01 .. 2026-10-13   touched once, for the final designs only

Entry conventions:
  close      buy at the signal close, sell at close t+h
  open1      buy at the next open, sell at close t+h
  overnight  buy at the signal close, sell at the next open
  day1       buy at the next open, sell at the next close (a pure day trade)
Excess = pick return - same-day liquid-universe mean return over the same window.
Bars whose window spans a corporate-action jump (|ret| > 30%) are dropped.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from condition_engine.engine import Engine          # noqa: E402
from condition_engine.store import BarStore         # noqa: E402

SPLITS = {
    "A": (pd.Timestamp("2024-06-01"), pd.Timestamp("2025-06-30")),
    "B": (pd.Timestamp("2025-07-01"), pd.Timestamp("2025-12-31")),
    "C": (pd.Timestamp("2026-01-01"), pd.Timestamp("2026-12-31")),
}
COST = {"close": 0.0023, "open1": 0.0023, "overnight": 0.0023, "day1": 0.0023}
TRIED: list[str] = []          # every variant measured, for multiple-testing disclosure


@dataclass
class Data:
    eng: Engine
    c: pd.DataFrame
    o: pd.DataFrame
    h: pd.DataFrame
    l: pd.DataFrame
    v: pd.DataFrame
    val: pd.DataFrame
    f: dict
    universe: pd.DataFrame
    regime: pd.Series
    sector: pd.Series
    bench: pd.Series


def load() -> Data:
    eng = Engine.from_store(BarStore())
    p = eng.panel
    return Data(eng, p.close, p.open, p.high, p.low, p.volume, p.value, eng.feats, eng.universe,
                eng.regime, p.meta["sector"].reindex(p.close.columns), p.benchmark["KOSPI200"])


_label_cache: dict = {}


def returns(d: Data, entry: str, h: int) -> pd.DataFrame:
    key = (entry, h)
    if key in _label_cache:
        return _label_cache[key]
    c, o = d.c, d.o
    if entry == "close":
        r = c.shift(-h) / c - 1
        span = (0, h)
    elif entry == "open1":
        r = c.shift(-h) / o.shift(-1) - 1
        span = (1, h)
    elif entry == "overnight":
        r = o.shift(-1) / c - 1
        span = (1, 1)
    elif entry == "day1":
        r = c.shift(-1) / o.shift(-1) - 1
        span = (1, 1)
    else:
        raise ValueError(entry)
    jump = d.eng.panel.ca_jump.astype(float)
    bad = sum(jump.shift(-k).fillna(0) for k in range(max(1, span[0]), span[1] + 1)) > 0
    r = r.mask(bad)
    _label_cache[key] = r
    return r


def excess(d: Data, entry: str, h: int) -> pd.DataFrame:
    r = returns(d, entry, h)
    uni_mean = r.where(d.universe).mean(axis=1)
    return r.sub(uni_mean, axis=0)


def picks(selected: pd.DataFrame, rank: pd.DataFrame | None, top_n: int) -> pd.DataFrame:
    sel = selected.fillna(False).astype(bool)
    if rank is None:
        return sel
    score = rank.where(sel)
    order = score.rank(axis=1, ascending=False, method="first")
    return sel & (order <= top_n)


def measure(d: Data, chosen: pd.DataFrame, entry: str, h: int, name: str = "", splits=("A", "B")) -> dict:
    TRIED.append(f"{name}|{entry}|{h}")
    ex = excess(d, entry, h)
    raw = returns(d, entry, h)
    out = {}
    for s in splits:
        lo, hi = SPLITS[s]
        idx = chosen.index[(chosen.index >= lo) & (chosen.index <= hi)]
        sel = chosen.loc[idx]
        day_ex = ex.loc[idx].where(sel).mean(axis=1).dropna()
        day_raw = raw.loc[idx].where(sel).mean(axis=1).dropna()
        n_picks = int(sel.sum().sum())
        if len(day_ex) < 10:
            out[s] = {"days": len(day_ex), "picks": n_picks}
            continue
        step = max(1, h)
        nonover = day_ex.iloc[::step]
        t = nonover.mean() / (nonover.std(ddof=1) / np.sqrt(len(nonover))) if len(nonover) > 3 else np.nan
        pick_ex = ex.loc[idx].where(sel).stack().dropna()
        out[s] = {
            "days": len(day_ex), "picks": n_picks, "per_day": round(n_picks / max(1, len(day_ex)), 1),
            "exc%": round(day_ex.mean() * 100, 2), "t": round(float(t), 2),
            "net%": round((day_raw.mean() - COST[entry]) * 100, 2),
            "hit%": round((pick_ex > 0).mean() * 100, 1),
        }
    return out


def by_regime(d: Data, chosen: pd.DataFrame, entry: str, h: int, split: str = "A") -> dict:
    ex = excess(d, entry, h)
    lo, hi = SPLITS[split]
    idx = chosen.index[(chosen.index >= lo) & (chosen.index <= hi)]
    day_ex = ex.loc[idx].where(chosen.loc[idx]).mean(axis=1)
    reg = d.regime.reindex(idx)
    return {k: (round(g.dropna().mean() * 100, 2), int(g.notna().sum())) for k, g in day_ex.groupby(reg)}


def fmt(res: dict) -> str:
    parts = []
    for s, r in res.items():
        if "exc%" in r:
            parts.append(f"{s}: {r['exc%']:+.2f}% t{r['t']:+.1f} net{r['net%']:+.2f} hit{r['hit%']:.0f} n{r['picks']}/{r['days']}d")
        else:
            parts.append(f"{s}: n/a ({r.get('days', 0)}d)")
    return " | ".join(parts)


# ----------------------------------------------------------------- extra features
def rsi(c: pd.DataFrame, n: int) -> pd.DataFrame:
    diff = c.diff()
    up = diff.clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    dn = (-diff.clip(upper=0)).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    return 100 - 100 / (1 + up / dn.replace(0, np.nan))


def extra(d: Data) -> dict:
    c, h, l, v, val = d.c, d.h, d.l, d.v, d.val
    x = {}
    ma20, ma60, ma120 = (c.rolling(w, min_periods=w).mean() for w in (20, 60, 120))
    x["ma20"], x["ma60"], x["ma120"] = ma20, ma60, ma120
    x["ma60_slope20"] = ma60 / ma60.shift(20) - 1
    x["rsi2"], x["rsi3"], x["rsi14"] = rsi(c, 2), rsi(c, 3), rsi(c, 14)
    rng = h - l
    x["nr7_prev"] = (rng.shift(1) <= rng.shift(1).rolling(7, min_periods=7).min())
    x["inside_prev"] = (h.shift(1) <= h.shift(2)) & (l.shift(1) >= l.shift(2))
    atr = d.f["atr14_pct"]
    x["atr_contract"] = atr.shift(1) / atr.shift(1).rolling(50, min_periods=40).mean()
    x["hi20_prev"] = h.shift(1).rolling(20, min_periods=20).max()
    x["hi60_prev"] = h.shift(1).rolling(60, min_periods=60).max()
    x["hi250"] = h.rolling(250, min_periods=200).max()
    x["dry5_20"] = v.shift(1).rolling(5, min_periods=5).mean() / v.shift(1).rolling(20, min_periods=20).mean()
    r = c / c.shift(1) - 1
    x["ret"] = r
    # relative strength composite (percentile within the liquid universe)
    rs_raw = 0.4 * d.f["r20"] + 0.3 * d.f["r60"] + 0.3 * (c / c.shift(120) - 1)
    x["rs_pct"] = rs_raw.where(d.universe).rank(axis=1, pct=True)
    x["rs60_pct"] = d.f["r60"].where(d.universe).rank(axis=1, pct=True)
    # sector aggregates over the liquid universe
    x.update(sector_aggregates(d))
    return x


def sector_aggregates(d: Data) -> dict:
    uni = d.universe
    sec = d.sector
    r1 = d.f["r1"].where(uni)
    valr = (d.val / d.f["val_avg20"]).where(uni)
    r60 = d.f["r60"].where(uni)
    groups = sec.dropna().groupby(sec.dropna()).groups
    breadth = pd.DataFrame(np.nan, index=d.c.index, columns=d.c.columns)
    count = breadth.copy(); value_surge = breadth.copy(); sec_r60 = breadth.copy(); sec_r1 = breadth.copy()
    for name, codes in groups.items():
        codes = [c for c in codes if c in d.c.columns]
        if len(codes) < 4:
            continue
        m = uni[codes]
        n = m.sum(axis=1)
        up = (r1[codes] >= 0.03).sum(axis=1)
        b = (up / n).where(n >= 4)
        vs = d.val[codes].where(m).sum(axis=1) / d.f["val_avg20"][codes].where(m).sum(axis=1)
        for col, series in ((breadth, b), (count, n.where(n >= 4)), (value_surge, vs.where(n >= 4)),
                            (sec_r60, r60[codes].median(axis=1).where(n >= 4)), (sec_r1, r1[codes].median(axis=1).where(n >= 4))):
            col.loc[:, codes] = np.repeat(series.to_numpy()[:, None], len(codes), axis=1)
    sec_r60_pct = sec_r60.where(uni).rank(axis=1, pct=True)
    return {"sec_breadth": breadth, "sec_n": count, "sec_value_surge": value_surge,
            "sec_r60": sec_r60, "sec_r60_pct": sec_r60_pct, "sec_r1": sec_r1}
