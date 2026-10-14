"""Stage 1 — first-principles theses per section, measured on A (discovery) and B (validation).

Holdout C is not touched here.
"""
import numpy as np
import pandas as pd

import harness as H

d = H.load()
x = H.extra(d)
f, c, o, h, l, uni = d.f, d.c, d.o, d.h, d.l, d.universe
liquid = uni & (f["val_avg20"] >= 5e9)                 # 50억 average trading value
up_regime = pd.DataFrame(np.repeat(d.regime.isin(["UP_TREND", "REBOUND"]).to_numpy()[:, None], c.shape[1], axis=1),
                         index=c.index, columns=c.columns)


def btw(frame, lo, hi):
    return (frame >= lo) & (frame <= hi)


def within_group_rank(score: pd.DataFrame, eligible: pd.DataFrame, key: pd.Series) -> pd.DataFrame:
    """rank of score inside each (date, sector) among eligible names, 1 = best"""
    s = score.where(eligible).stack().rename("s").reset_index()
    s.columns = ["date", "code", "s"]
    s["sec"] = s["code"].map(key)
    s = s.dropna(subset=["sec"])
    s["r"] = s.groupby(["date", "sec"])["s"].rank(ascending=False, method="first")
    return s.pivot(index="date", columns="code", values="r").reindex(index=score.index, columns=score.columns)


def run(name, sel, rank, entry, hz, top_n=5, regime=False):
    sel = sel & uni
    chosen = H.picks(sel, rank, top_n)
    res = H.measure(d, chosen, entry, hz, name)
    line = f"  {name:44} [{entry} {hz}d top{top_n}] {H.fmt(res)}"
    if regime:
        line += f"\n      regime(A): {H.by_regime(d, chosen, entry, hz, 'A')}"
    print(line)
    return chosen


# ================================================================== 단타 (day trade)
print("\n### 단타 — next-day trade after a close-based setup (day1 = next open→close, open1 3d)")
base = (f["r1"] >= 0.05) & (f["volr"] >= 2)
for e, hz in (("day1", 1), ("open1", 3)):
    run("baseline: today's gainers w/ volume (current style)", base, f["r1"], e, hz)
pivot = (c > x["hi20_prev"]) & btw(f["volr"], 1.5, 3) & btw(f["r1"], 0.02, 0.08) & (x["atr_contract"] < 0.9) & (c > x["ma60"]) & (f["clv"] >= 0.6)
gaphold = (f["gap_open"] >= 0.02) & (c >= o) & (f["clv"] >= 0.6) & btw(f["volr"], 1.5, 4) & (f["r1"] < 0.10) & (c > x["ma20"])
bounce = (x["rsi2"] < 10) & (c > x["ma120"]) & (x["ma60_slope20"] > 0)
nr7 = x["nr7_prev"] & (c > h.shift(1)) & (c > x["ma60"]) & (f["volr"] >= 1.2) & (f["r1"] < 0.08)
for e, hz in (("day1", 1), ("open1", 3)):
    run("T1 pivot breakout from contraction", pivot & liquid, -f["atr14_pct"], e, hz)
    run("T2 gap and hold", gaphold & liquid, f["clv"], e, hz)
    run("T3 oversold bounce in uptrend (rsi2<10)", bounce & liquid, -x["rsi2"], e, hz)
    run("T4 NR7 breakout", nr7 & liquid, -f["atr14_pct"], e, hz)

# ================================================================== 스윙
print("\n### 스윙 — pullback inside an uptrend (open1, 5d / 10d)")
trend = (c > x["ma60"]) & (x["ma60_slope20"] > 0) & (f["r60"] > 0)
s1 = trend & btw(f["dd20"], -0.12, -0.03) & (x["rsi3"] < 30) & (x["dry5_20"] < 0.9)
s2 = s1.shift(1, fill_value=False) & (c > h.shift(1)) & (f["r1"] > 0)
s3 = trend & (l <= x["ma20"] * 1.01) & (c > x["ma20"]) & (f["r1"] > -0.03)
for e, hz in (("open1", 5), ("open1", 10)):
    run("S1 oversold pullback in uptrend + dry volume", s1 & liquid, x["rs_pct"], e, hz, regime=(hz == 5))
    run("S2 S1 then reversal day (close > prior high)", s2 & liquid, x["rs_pct"], e, hz)
    run("S3 MA20 touch & hold in uptrend", s3 & liquid, x["rs_pct"], e, hz)
    run("S1 gated to UP/REBOUND regime", s1 & liquid & up_regime, x["rs_pct"], e, hz)

# ================================================================== 주도주
print("\n### 주도주 — relative-strength leadership (open1, 10d / 20d)")
lead = (x["rs_pct"] >= 0.85) & (c >= 0.9 * x["hi250"]) & (c > x["ma60"]) & (x["ma60"] > x["ma120"]) & (x["sec_r60_pct"] >= 0.7)
lead_calm = lead & (f["gap_ma20"] < 0.15)
sec_top = (x["sec_r60_pct"] >= 0.7) & (c > x["ma60"])
sec_leader_rank = within_group_rank(f["r60"], sec_top & liquid, d.sector)
for e, hz in (("open1", 10), ("open1", 20)):
    run("L1 RS top15% near 52w high in leading sector", lead & liquid, x["rs_pct"], e, hz, regime=(hz == 10))
    run("L2 L1 not extended (gap_ma20<15%), calmest first", lead_calm & liquid, -f["vol20"], e, hz)
    run("L3 strongest stock of each top-30% sector", (sec_leader_rank == 1), x["sec_r60_pct"], e, hz)
    run("L4 L2 gated to UP/REBOUND regime", lead_calm & liquid & up_regime, -f["vol20"], e, hz)

# ================================================================== 테마주
print("\n### 테마주 — sector-wide ignition (breadth + money) and its leader")
ign = (x["sec_breadth"] >= 0.4) & (x["sec_value_surge"] >= 2) & (x["sec_n"] >= 5)
mover = ign & (f["r1"] >= 0.03) & liquid
leader_rank = within_group_rank(f["val_today"], mover, d.sector)
leader = leader_rank == 1
ign_prev = ign.shift(1, fill_value=False)
day2 = ign_prev & (x["sec_r1"] > 0) & (within_group_rank(f["val_today"], ign_prev & liquid, d.sector) == 1)
ign_recent = sum(ign.shift(k, fill_value=False).astype(int) for k in range(2, 6)) > 0
pullback_in_theme = ign_recent & liquid & (f["r1"] < 0) & (f["r5"] > 0.05) & (c > x["ma20"])
laggard = ign & liquid & (f["r1"] < 0.01) & (c > x["ma60"])
for e, hz in (("close", 1), ("open1", 1), ("open1", 5)):
    run("M1 theme ignition: sector leader by money", leader, f["val_today"], e, hz)
    run("M2 day-2: yesterday's leader if theme still up", day2, f["val_today"], e, hz)
    run("M3 pullback 2-5d after ignition (still >MA20)", pullback_in_theme, f["r5"], e, hz)
    run("M4 laggard inside an ignited theme", laggard, -f["atr14_pct"], e, hz)

# ================================================================== 종가매매
print("\n### 종가매매 — buy the close, sell next open / next close / 3d")
jbase = btw(f["r1"], 0.03, 0.299) & (f["val_today"] >= 5e8)
j1 = (f["clv"] >= 0.8) & (f["upper_wick"] <= 0.2) & btw(f["r1"], 0.03, 0.12) & btw(f["volr"], 1.5, 4) & (c >= x["hi20_prev"]) & (f["val_today"] >= 5e9) & (x["ma20"] > x["ma60"])
j2 = j1 & (f["dd120"] >= -0.05)
j3 = btw(f["r1"], 0.01, 0.05) & (f["clv"] >= 0.85) & btw(f["volr"], 1.0, 2.0) & (c > x["ma20"]) & (x["ma20"] > x["ma60"]) & (f["atr14_pct"] <= 0.04)
for e, hz in (("overnight", 1), ("close", 1), ("close", 3)):
    run("baseline: change 3-30% sorted by change (current style)", jbase, f["r1"], e, hz)
    run("J1 strong close at a 20d breakout", j1 & liquid, f["val_today"], e, hz)
    run("J2 J1 within 5% of 120d high", j2 & liquid, f["val_today"], e, hz)
    run("J3 calm strength into the close", j3 & liquid, f["clv"], e, hz)

print(f"\nvariants measured: {len(H.TRIED)}")
