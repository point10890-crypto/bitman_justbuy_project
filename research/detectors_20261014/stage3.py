"""Stage 3 — robustness (neighbouring parameters on A and B) and the one-time holdout C.

Designs are frozen in DESIGNS below before C is printed. Neighbours only show whether the
result is a plateau or a spike; they are not used to pick parameters.
"""
import numpy as np
import pandas as pd

import harness as H

d = H.load()
x = H.extra(d)
f, c, o, h, l, uni = d.f, d.c, d.o, d.h, d.l, d.universe
liquid = uni & (f["val_avg20"] >= 5e9)
reg = d.regime


def btw(frame, lo, hi):
    return (frame >= lo) & (frame <= hi)


def gate(sel, allowed):
    ok = pd.DataFrame(np.repeat(reg.isin(allowed).to_numpy()[:, None], c.shape[1], axis=1), index=c.index, columns=c.columns)
    return sel & ok


trend = (c > x["ma60"]) & (x["ma60_slope20"] > 0)
gap_next = f["gap_open"].shift(-1)
ign = lambda b, vs: (x["sec_breadth"] >= b) & (x["sec_value_surge"] >= vs) & (x["sec_n"] >= 5)  # noqa: E731


def danta(rsi_max=10, gap_max=-0.005, atr_max=None):
    s = trend & liquid & (x["rsi2"] < rsi_max) & (gap_next < gap_max)
    if atr_max:
        s &= f["atr14_pct"] < atr_max
    return s, -x["rsi2"]


def swing(atr_max=0.035, dd_min=-0.08, sec_min=0.6):
    s = trend & (f["atr14_pct"] < atr_max) & (f["dd20"] >= dd_min) & liquid & (x["sec_r60_pct"] >= sec_min)
    return s, -f["vol20"]


def leader(rs_min=0.85, near=0.9, ext=0.15, sec_min=None):
    s = (x["rs_pct"] >= rs_min) & (c >= near * x["hi250"]) & (c > x["ma60"]) & (x["ma60"] > x["ma120"]) & (f["gap_ma20"] < ext) & liquid
    if sec_min is not None:
        s &= x["sec_r60_pct"] >= sec_min
    return s, -f["vol20"]


def theme_laggard(b=0.4, vs=2.0, r1_max=0.01):
    return ign(b, vs) & liquid & (f["r1"] < r1_max) & (c > x["ma60"]), -f["atr14_pct"]


def closing(b=0.5, vs=1.5, clv_min=0.7, r_lo=0.03, r_hi=0.28):
    return ign(b, vs) & liquid & btw(f["r1"], r_lo, r_hi) & (f["clv"] >= clv_min), f["r1"]


def show(name, built, entry, hz, top_n=5, splits=("A", "B")):
    sel, rank = built
    chosen = H.picks(sel & uni, rank, top_n)
    print(f"  {name:46} [{entry} {hz}d] {H.fmt(H.measure(d, chosen, entry, hz, name, splits))}")
    return chosen


print("### neighbours (A | B) — a plateau is what we want")
print("단타 (day1)")
for kw in ({}, {"rsi_max": 5}, {"rsi_max": 15}, {"gap_max": -0.01}, {"gap_max": 0.0}, {"atr_max": 0.04}):
    show(f"danta {kw}", danta(**kw), "day1", 1)
print("스윙 (open1 10d)")
for kw in ({}, {"atr_max": 0.03}, {"atr_max": 0.04}, {"dd_min": -0.05}, {"dd_min": -0.12}, {"sec_min": 0.5}, {"sec_min": 0.7}):
    show(f"swing {kw}", swing(**kw), "open1", 10)
print("주도주 (open1 20d)")
for kw in ({}, {"rs_min": 0.8}, {"rs_min": 0.9}, {"near": 0.85}, {"near": 0.95}, {"ext": 0.10}, {"ext": 0.20}, {"sec_min": 0.5}):
    show(f"leader {kw}", leader(**kw), "open1", 20)
print("테마주 laggard (close 1d)")
for kw in ({}, {"b": 0.3}, {"b": 0.5}, {"vs": 1.5}, {"vs": 2.5}, {"r1_max": 0.0}, {"r1_max": 0.02}):
    show(f"theme_laggard {kw}", theme_laggard(**kw), "close", 1)
print("종가매매 theme strong close (overnight)")
for kw in ({}, {"b": 0.4}, {"b": 0.6}, {"vs": 1.2}, {"vs": 2.0}, {"clv_min": 0.6}, {"clv_min": 0.8}, {"r_hi": 0.15}):
    show(f"closing {kw}", closing(**kw), "overnight", 1)

# ------------------------------------------------------------------ frozen designs
DESIGNS = {
    "단타":   (danta(), "day1", 1),
    "스윙":   (swing(), "open1", 10),
    "주도주": (leader(), "open1", 20),
    "테마주": (theme_laggard(), "close", 1),
    "종가매매": (closing(), "overnight", 1),
}
print("\n### FROZEN designs — first and only look at holdout C")
for name, (built, entry, hz) in DESIGNS.items():
    chosen = show(name, built, entry, hz, splits=("A", "B", "C"))
    print(f"      regime(C): {H.by_regime(d, chosen, entry, hz, 'C')}")
print(f"\nvariants measured in stage 3: {len(H.TRIED)}")
