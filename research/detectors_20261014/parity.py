"""Parity: condition_engine.detectors must select exactly what the research harness selected."""
import numpy as np
import harness as H
from condition_engine import detectors as D

d = H.load(); x = H.extra(d); f, c, uni = d.f, d.c, d.universe
liquid = uni & (f["val_avg20"] >= 5e9)
eng = d.eng
dx = D.section_features(eng)

research = {
    "leaders": (x["rs_pct"] >= 0.85) & (c >= 0.9 * x["hi250"]) & (c > x["ma60"]) & (x["ma60"] > x["ma120"]) & (f["gap_ma20"] < 0.15) & liquid & uni,
}
research["swing"] = research["leaders"].shift(5, fill_value=False) & (f["dd20"] >= -0.10) & (f["dd20"] <= -0.03) & (c > x["ma60"]) & liquid & uni
research["short_term"] = (c > x["ma60"]) & (x["ma60_slope20"] > 0) & liquid & (x["rsi2"] < 10) & uni
ign = (x["sec_breadth"] >= 0.4) & (x["sec_value_surge"] >= 2) & (x["sec_n"] >= 5)
research["laggard"] = ign & liquid & (f["r1"] < 0.01) & (c > x["ma60"]) & uni

lead = D.leader_mask(eng, dx)
ours = {"leaders": lead, "swing": D.swing_mask(eng, dx, lead), "short_term": D.short_term_watch(eng, dx), "laggard": D.laggard_mask(eng, dx)}
ok = True
for k in research:
    a = research[k].fillna(False).astype(bool); b = ours[k].fillna(False).astype(bool)
    diff = int((a != b).sum().sum()); n = int(a.sum().sum())
    print(f"{k:11} research picks {n:7d}  mismatches {diff}")
    ok &= diff == 0
print("PARITY", "OK" if ok else "FAILED")
