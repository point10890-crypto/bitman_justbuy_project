"""Stage 4 — 종가매매: J3 (defined in stage 1, chosen on A/B) frozen, neighbours on A/B, then C once."""
import harness as H
import numpy as np

d = H.load(); x = H.extra(d); f, c, uni = d.f, d.c, d.universe
liquid = uni & (f["val_avg20"] >= 5e9)
btw = lambda fr, lo, hi: (fr >= lo) & (fr <= hi)  # noqa: E731


def j3(r_lo=0.01, r_hi=0.05, clv=0.85, vlo=1.0, vhi=2.0, atr=0.04):
    s = btw(f["r1"], r_lo, r_hi) & (f["clv"] >= clv) & btw(f["volr"], vlo, vhi) & (c > x["ma20"]) & (x["ma20"] > x["ma60"]) & (f["atr14_pct"] <= atr) & liquid
    return s, f["clv"]


def show(name, built, entry, hz, splits=("A", "B")):
    sel, rank = built
    ch = H.picks(sel & uni, rank, 5)
    print(f"  {name:40} [{entry} {hz}d] {H.fmt(H.measure(d, ch, entry, hz, name, splits))}")
    return ch

for kw in ({}, {"r_hi": 0.04}, {"r_hi": 0.07}, {"clv": 0.8}, {"clv": 0.9}, {"vhi": 1.5}, {"vhi": 2.5}, {"atr": 0.035}, {"atr": 0.05}):
    show(f"J3 {kw}", j3(**kw), "close", 3)
for e, hz in (("overnight", 1), ("close", 1), ("close", 5)):
    show("J3 other exits", j3(), e, hz)
print("\nFROZEN 종가매매 = J3, buy the close, hold 3 sessions")
ch = show("J3", j3(), "close", 3, splits=("A", "B", "C"))
print("  regime(C):", H.by_regime(d, ch, "close", 3, "C"))
