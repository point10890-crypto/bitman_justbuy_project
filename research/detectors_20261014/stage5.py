"""Stage 5 — one more principled thesis family for 스윙 and 종가매매 (A/B first; C only if both pass).
C is a SECOND look for these two sections (their first designs failed C) — disclosed in the report."""
import harness as H
import numpy as np
import pandas as pd

d = H.load(); x = H.extra(d); f, c, h, uni = d.f, d.c, d.h, d.universe
liquid = uni & (f["val_avg20"] >= 5e9)
btw = lambda fr, lo, hi: (fr >= lo) & (fr <= hi)  # noqa: E731
leader = (x["rs_pct"] >= 0.85) & (c >= 0.9 * x["hi250"]) & (c > x["ma60"]) & (x["ma60"] > x["ma120"]) & (f["gap_ma20"] < 0.15) & liquid


def show(name, sel, rank, entry, hz, splits=("A", "B")):
    ch = H.picks(sel & uni, rank, 5)
    r = H.measure(d, ch, entry, hz, name, splits)
    print(f"  {name:52} [{entry} {hz}d] {H.fmt(r)}")
    return r

print("스윙 theses (open1)")
base_hi = h.shift(1).rolling(40, min_periods=40).max()
vcp = (f["dd120"] >= -0.15) & (x["atr_contract"] < 0.8) & (c >= 0.95 * base_hi) & (x["dry5_20"] < 0.9) & (c > x["ma60"]) & liquid
pull60 = (c / x["ma120"] - 1 > 0) & btw(c / x["ma60"] - 1, -0.01, 0.03) & (x["ma60_slope20"] > 0) & (x["rsi14"] < 50) & liquid
lead_pull = leader.shift(5, fill_value=False) & btw(f["dd20"], -0.10, -0.03) & (c > x["ma60"])
res = {}
for hz in (5, 10):
    res[("vcp", hz)] = show("W1 volatility-contraction base near 40d high", vcp, -x["atr_contract"], "open1", hz)
    res[("pull60", hz)] = show("W2 pullback to rising MA60 in long uptrend", pull60, x["rs_pct"], "open1", hz)
    res[("leadpull", hz)] = show("W3 a validated leader pulling back 3-10%", lead_pull & liquid, x["rs_pct"], "open1", hz)

print("종가매매 theses (buy the close)")
lead_close = leader & btw(f["r1"], 0.01, 0.06) & (f["clv"] >= 0.7) & btw(f["volr"], 1.0, 3.0)
quiet_close = leader & btw(f["r1"], -0.01, 0.02) & (f["clv"] >= 0.6)
for e, hz in (("close", 1), ("close", 3), ("overnight", 1)):
    res[("leadclose", e, hz)] = show("K1 validated leader closing strong (+1..6%)", lead_close, f["clv"], e, hz)
    res[("quietclose", e, hz)] = show("K2 validated leader, quiet close (-1..+2%)", quiet_close, -f["vol20"], e, hz)


def passes(r):
    return all(r.get(s, {}).get("exc%", -1) > 0 for s in ("A", "B"))

print("\nsurvivors on A and B -> holdout C (second look for these sections):")
for key, r in res.items():
    if passes(r):
        name = key[0]
        if name == "vcp": sel, rank, e, hz = vcp, -x["atr_contract"], "open1", key[1]
        elif name == "pull60": sel, rank, e, hz = pull60, x["rs_pct"], "open1", key[1]
        elif name == "leadpull": sel, rank, e, hz = lead_pull & liquid, x["rs_pct"], "open1", key[1]
        elif name == "leadclose": sel, rank, e, hz = lead_close, f["clv"], key[1], key[2]
        else: sel, rank, e, hz = quiet_close, -f["vol20"], key[1], key[2]
        show(f"C-check {key}", sel, rank, e, hz, splits=("A", "B", "C"))
