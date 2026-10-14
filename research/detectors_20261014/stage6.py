"""Stage 6 — avoid-rules chosen on A/B, checked once on C."""
import harness as H
d = H.load(); x = H.extra(d); f = d.f
btw = lambda fr, lo, hi: (fr >= lo) & (fr <= hi)  # noqa: E731
gap_next = f["gap_open"].shift(-1)
rules = {
    "yesterday's gainer gapping up >=3% (buy at open, day1)": (btw(f["r1"], 0.03, 0.28) & (gap_next >= 0.03), f["r1"], "day1", 1),
    "today's gainers +5% & vol 2x bought at close, 5d": ((f["r1"] >= 0.05) & (f["volr"] >= 2), f["r1"], "close", 5),
    "today's gainers +5% & vol 2x, next open->close": ((f["r1"] >= 0.05) & (f["volr"] >= 2), f["r1"], "day1", 1),
}
for name, (sel, rank, e, hz) in rules.items():
    ch = H.picks(sel & d.universe, rank, 5)
    print(f"  {name:56} [{e} {hz}d] {H.fmt(H.measure(d, ch, e, hz, name, ('A','B','C')))}")
