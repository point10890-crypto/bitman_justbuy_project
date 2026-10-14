"""Stage 2 — deepen what stage 1 found. A and B only; C still untouched.

* 종가매매: the overnight premium of the day's gainers (close -> next open).
* 단타: an intraday trade decided at the next open, using the opening gap (known at 09:00).
* 스윙 / 주도주 / 테마주: refinements of the surviving theses.
"""
import numpy as np
import pandas as pd

import harness as H

d = H.load()
x = H.extra(d)
f, c, o, h, l, uni = d.f, d.c, d.o, d.h, d.l, d.universe
liquid = uni & (f["val_avg20"] >= 5e9)
up_regime = pd.DataFrame(np.repeat(d.regime.isin(["UP_TREND", "REBOUND"]).to_numpy()[:, None], c.shape[1], axis=1),
                         index=c.index, columns=c.columns)


def btw(frame, lo, hi):
    return (frame >= lo) & (frame <= hi)


def run(name, sel, rank, entry, hz, top_n=5, regime=False):
    chosen = H.picks(sel & uni, rank, top_n)
    res = H.measure(d, chosen, entry, hz, name)
    line = f"  {name:52} [{entry} {hz}d top{top_n}] {H.fmt(res)}"
    if regime:
        line += f"\n      regime(A): {H.by_regime(d, chosen, entry, hz, 'A')}"
    print(line)
    return chosen


# ================================================================== 종가매매: overnight premium
print("\n### 종가매매 — buy the close, sell the next open (overnight)")
not_limit = f["r1"] < 0.28                       # a limit-up close cannot be bought
gain = btw(f["r1"], 0.03, 0.28)
run("all gainers 3-28%, rank by change", gain, f["r1"], "overnight", 1)
for lo, hi in ((0.03, 0.08), (0.08, 0.15), (0.15, 0.28)):
    run(f"change {lo:.0%}-{hi:.0%}, rank by change", btw(f["r1"], lo, hi), f["r1"], "overnight", 1)
run("gainers, rank by close location (clv)", gain, f["clv"], "overnight", 1)
run("gainers, rank by volume ratio", gain, f["volr"], "overnight", 1)
run("gainers, rank by trading value", gain, f["val_today"], "overnight", 1)
run("gainers clv>=0.9 (closed at the high), by change", gain & (f["clv"] >= 0.9), f["r1"], "overnight", 1)
run("gainers clv<0.5 (faded), by change", gain & (f["clv"] < 0.5), f["r1"], "overnight", 1)
run("gainers liquid (avg value>=50억), by change", gain & liquid, f["r1"], "overnight", 1)
run("gainers new 20d high close, by change", gain & (c >= x["hi20_prev"]), f["r1"], "overnight", 1)
run("gainers KOSDAQ only, by change", gain & (f["is_kosdaq"] == 1), f["r1"], "overnight", 1)
run("gainers KOSPI only, by change", gain & (f["is_kospi"] == 1), f["r1"], "overnight", 1)
j = gain & (f["clv"] >= 0.7) & (f["upper_wick"] <= 0.3)
run("J* gainers 3-28% strong close (clv>=.7, wick<=.3), by change", j, f["r1"], "overnight", 1, regime=True)
run("J* top10", j, f["r1"], "overnight", 1, top_n=10)
run("J* gated UP/REBOUND", j & up_regime, f["r1"], "overnight", 1)
run("J* then hold to next close (for contrast)", j, f["r1"], "close", 1)

# ================================================================== 단타: decided at the next open
print("\n### 단타 — intraday trade decided at 09:00 with the opening gap (day1 = open->close)")
gap_next = f["gap_open"].shift(-1)       # the gap of the trade day, known at its open
trend = (c > x["ma60"]) & (x["ma60_slope20"] > 0)
y_gainer = btw(f["r1"], 0.03, 0.28)
run("yesterday's gainers, any gap (contrast)", y_gainer, f["r1"], "day1", 1)
run("yesterday's gainers that gap up >= +3% (chase)", y_gainer & (gap_next >= 0.03), f["r1"], "day1", 1)
run("yesterday's gainers that gap DOWN (-4%..-1%)", y_gainer & btw(gap_next, -0.04, -0.01), f["r1"], "day1", 1)
run("uptrend names gapping down -4%..-1.5% (gap fill)", trend & liquid & btw(gap_next, -0.04, -0.015), -gap_next, "day1", 1, regime=True)
run("uptrend, rsi2<10 yesterday, gap down (capitulation)", trend & liquid & (x["rsi2"] < 10) & (gap_next < -0.005), -x["rsi2"], "day1", 1)
run("uptrend, rsi2<10 yesterday, gap up (relief)", trend & liquid & (x["rsi2"] < 10) & (gap_next > 0.005), -x["rsi2"], "day1", 1)
run("quiet uptrend (low ATR), small gap up 0..+1.5%", trend & liquid & (f["atr14_pct"] < 0.03) & btw(gap_next, 0.0, 0.015), -f["atr14_pct"], "day1", 1)
run("strong close yesterday (clv>=.8) + flat open (-1..+1%)", trend & liquid & (f["clv"] >= 0.8) & btw(f["r1"], 0.01, 0.06) & btw(gap_next, -0.01, 0.01), f["clv"], "day1", 1)

# ================================================================== 스윙
print("\n### 스윙 — 5d / 10d (open1)")
trend_s = (c > x["ma60"]) & (x["ma60_slope20"] > 0)
quality = trend_s & (f["atr14_pct"] < 0.035) & (f["dd20"] >= -0.08)
run("Q1 quiet uptrend near 20d high (low ATR), rank low vol", quality & liquid, -f["vol20"], "open1", 5, regime=True)
run("Q1 10d", quality & liquid, -f["vol20"], "open1", 10)
run("Q2 Q1 + 2-5% pullback (dd20 -5%..-2%)", quality & liquid & btw(f["dd20"], -0.05, -0.02), -f["vol20"], "open1", 5)
run("Q3 Q1 + rsi2<20 (short dip in quiet trend)", quality & liquid & (x["rsi2"] < 20), -x["rsi2"], "open1", 5)
run("Q3 10d", quality & liquid & (x["rsi2"] < 20), -x["rsi2"], "open1", 10)
run("Q4 Q1 + sector leading (sec_r60_pct>=.6)", quality & liquid & (x["sec_r60_pct"] >= 0.6), -f["vol20"], "open1", 5)
run("Q4 10d", quality & liquid & (x["sec_r60_pct"] >= 0.6), -f["vol20"], "open1", 10)

# ================================================================== 주도주
print("\n### 주도주 — 10d / 20d (open1)")
lead = (x["rs_pct"] >= 0.85) & (c >= 0.9 * x["hi250"]) & (c > x["ma60"]) & (x["ma60"] > x["ma120"])
for thr in (0.5, 0.7):
    calm = lead & (x["sec_r60_pct"] >= thr) & (f["gap_ma20"] < 0.15) & liquid
    run(f"L2 sector>={thr}, not extended, calmest", calm, -f["vol20"], "open1", 20)
calm = lead & (x["sec_r60_pct"] >= 0.7) & (f["gap_ma20"] < 0.15) & liquid
run("L2 rank by RS instead of calm", calm, x["rs_pct"], "open1", 20)
run("L2 without sector condition", lead & (f["gap_ma20"] < 0.15) & liquid, -f["vol20"], "open1", 20)
run("L2 top10", calm, -f["vol20"], "open1", 20, top_n=10)
run("L2 + big caps only (avg value >= 300억)", calm & (f["val_avg20"] >= 3e10), -f["vol20"], "open1", 20)

# ================================================================== 테마주
print("\n### 테마주")
ign = (x["sec_breadth"] >= 0.4) & (x["sec_value_surge"] >= 2) & (x["sec_n"] >= 5)
lag = ign & liquid & (f["r1"] < 0.01) & (c > x["ma60"])
run("M4 laggard in ignited theme (close 1d)", lag, -f["atr14_pct"], "close", 1)
run("M4 overnight", lag, -f["atr14_pct"], "overnight", 1)
run("M4 open1 5d", lag, -f["atr14_pct"], "open1", 5)
mover = ign & liquid & (f["r1"] >= 0.03)
run("theme movers overnight (rank by change)", mover, f["r1"], "overnight", 1, regime=True)
run("theme movers overnight strong close", mover & (f["clv"] >= 0.7), f["r1"], "overnight", 1)
for b in (0.3, 0.5):
    ig = (x["sec_breadth"] >= b) & (x["sec_value_surge"] >= 1.5) & (x["sec_n"] >= 5)
    run(f"breadth>={b} movers overnight", ig & liquid & (f["r1"] >= 0.03), f["r1"], "overnight", 1)

print(f"\nvariants measured so far (this stage): {len(H.TRIED)}")
