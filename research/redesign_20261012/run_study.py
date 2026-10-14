"""Redesign research study — which conditions actually predict KRX returns?

Usage:
    PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe research/redesign_20261012/run_study.py

Reads the daily price history read-only and writes results/ next to this file.
"""
from __future__ import annotations

import json
import sys
from dataclasses import asdict
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from rd import dsl  # noqa: E402
from rd.data import load_panel  # noqa: E402
from rd.evaluate import factor_study, split_dates, strategy_study, daily_ic, t_stat_non_overlap  # noqa: E402
from rd.features import build_features, universe_mask  # noqa: E402
from rd.labels import HORIZONS, benchmark_forward, cross_sectional_excess, forward_returns  # noqa: E402
from rd.strategies import ALL_STRATEGIES  # noqa: E402

SOURCE = Path(r"C:\bitman_marketfloww\data\daily_prices.csv")
OUT = HERE / "results"

FACTORS = [
    "app_score_proxy", "r1", "gap_open", "clv", "upper_wick", "up_streak",
    "r5", "r20", "r60", "mom_120_5", "gap_ma20", "gap_ma60", "gap_ma120",
    "dd20", "dd120", "rs20", "volr", "val_avg20", "vol20", "atr14_pct",
]


def app_score_proxy(feats: dict[str, pd.DataFrame], universe: pd.DataFrame) -> pd.DataFrame:
    """Daily approximation of ConditionSignalScoring's short-term rule score.

    18*volume-rank + 18*gainer-rank + min(25, changeRate% * 2.5). Foreign-flow and
    overlap terms need intraday ranking data and are omitted.
    """
    vol_pct = feats["volr"].where(universe).rank(axis=1, pct=True)
    gain_pct = feats["r1"].where(universe).rank(axis=1, pct=True)
    momentum = (feats["r1"].clip(lower=0) * 100 * 2.5).clip(upper=25)
    return 18 * vol_pct + 18 * gain_pct + momentum


def pct_rank(frame: pd.DataFrame, universe: pd.DataFrame) -> pd.DataFrame:
    return frame.where(universe).rank(axis=1, pct=True) - 0.5


def main() -> None:
    OUT.mkdir(exist_ok=True)
    print("loading panel ...", flush=True)
    panel = load_panel(SOURCE)
    feats = build_features(panel)
    universe = universe_mask(panel, feats)
    feats["app_score_proxy"] = app_score_proxy(feats, universe)

    dates = panel.dates
    disc, conf = split_dates(dates)
    windows = {"discovery": disc, "confirm": conf}

    quality = {
        "source": str(SOURCE),
        "first_date": str(dates[0].date()), "last_date": str(dates[-1].date()),
        "sessions": int(len(dates)),
        "common_equities": int(panel.close.shape[1]),
        "avg_universe_per_day": float(universe.sum(axis=1).mean()),
        "corporate_action_jumps_excluded": int(panel.ca_jump.values.sum()),
        "discovery": [str(disc[0].date()), str(disc[-1].date()), int(len(disc))],
        "confirm": [str(conf[0].date()), str(conf[-1].date()), int(len(conf))],
    }
    (OUT / "data_quality.json").write_text(json.dumps(quality, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(quality, ensure_ascii=False), flush=True)

    labels = {}
    for h in HORIZONS:
        for entry in ("close", "open1"):
            fwd = forward_returns(panel, h, entry)
            labels[(h, entry)] = (fwd, cross_sectional_excess(fwd, universe), benchmark_forward(panel, h, entry))

    # ---- 1) single-factor study --------------------------------------------
    print("factor study ...", flush=True)
    rows = []
    for name in FACTORS:
        for h in HORIZONS:
            _, exc, _ = labels[(h, "close")]
            rows += [asdict(r) for r in factor_study(feats[name], exc, universe, h, name, windows)]
    factor_df = pd.DataFrame(rows)
    factor_df.to_csv(OUT / "factor_ic.csv", index=False, encoding="utf-8-sig")

    # ---- 2) strategy study ---------------------------------------------------
    print("strategy study ...", flush=True)
    names = set(feats)
    srows = []
    for s in ALL_STRATEGIES:
        dsl.validate_strategy(s, names)
        sel = dsl.select(s, feats, universe)
        for h in HORIZONS:
            for entry in ("close", "open1"):
                fwd, exc, bfwd = labels[(h, entry)]
                srows += [asdict(r) for r in strategy_study(sel, fwd, exc, bfwd, h, entry, s["id"], s["title"], windows)]
    strat_df = pd.DataFrame(srows)

    # ---- 3) composite built on discovery only --------------------------------
    print("composite ...", flush=True)
    d5 = factor_df[(factor_df.horizon == 5) & (factor_df.window == "discovery") & (factor_df.feature != "app_score_proxy")]
    chosen = d5[d5.ic_t.abs() >= 2.0]
    weights = {r.feature: float(np.sign(r.mean_ic)) for r in chosen.itertuples()}
    parts = [pct_rank(feats[f], universe) * w for f, w in weights.items()]
    stacked = np.stack([p.to_numpy() for p in parts])
    avail = np.sum(~np.isnan(stacked), axis=0)
    comp = np.nansum(stacked, axis=0) / np.where(avail == 0, np.nan, avail)
    comp[avail < max(2, len(parts) // 2)] = np.nan
    feats["composite"] = pd.DataFrame(comp, index=dates, columns=panel.close.columns)

    comp_rows = []
    for h in HORIZONS:
        _, exc, _ = labels[(h, "close")]
        comp_rows += [asdict(r) for r in factor_study(feats["composite"], exc, universe, h, "composite", windows)]
    comp_strategy = {
        "id": "C1_composite_top5", "title": "탐색구간 학습 합성점수 상위 5 (거래대금 평균 30억↑)",
        "when": {"f": "val_avg20", "op": ">=", "v": 3e9},
        "rank_by": {"f": "composite", "desc": True}, "top_n": 5,
    }
    dsl.validate_strategy(comp_strategy, set(feats))
    sel = dsl.select(comp_strategy, feats, universe)
    for h in HORIZONS:
        for entry in ("close", "open1"):
            fwd, exc, bfwd = labels[(h, entry)]
            srows += [asdict(r) for r in strategy_study(sel, fwd, exc, bfwd, h, entry, comp_strategy["id"], comp_strategy["title"], windows)]
    strat_df = pd.DataFrame(srows)
    strat_df.to_csv(OUT / "strategies.csv", index=False, encoding="utf-8-sig")
    pd.DataFrame(comp_rows).to_csv(OUT / "composite_ic.csv", index=False, encoding="utf-8-sig")
    (OUT / "composite_weights.json").write_text(json.dumps(weights, ensure_ascii=False, indent=2), encoding="utf-8")

    # ---- 4) regime split (market above / below its 60-day average) ----------
    print("regime split ...", flush=True)
    bench = panel.benchmark["KOSPI200"]
    regime = np.where(bench > bench.rolling(60).mean(), "UP", "DOWN")
    regime = pd.Series(regime, index=dates).where(bench.rolling(60).mean().notna())
    rrows = []
    for s in ALL_STRATEGIES + [comp_strategy]:
        sel = dsl.select(s, feats, universe)
        _, exc, _ = labels[(5, "close")]
        day_exc = exc.where(sel).mean(axis=1)
        for reg in ("UP", "DOWN"):
            idx = dates[(regime == reg).to_numpy()]
            x = day_exc.reindex(idx).dropna()
            rrows.append({"strategy": s["id"], "regime": reg, "signal_days": len(x),
                          "excess_mean_5d": float(x.mean()) if len(x) else np.nan,
                          "excess_t": t_stat_non_overlap(x, 5)})
    pd.DataFrame(rrows).to_csv(OUT / "regime.csv", index=False, encoding="utf-8-sig")

    # ---- 5) large-liquid universe: does the verdict survive a mega-cap market? --
    print("large-liquid universe ...", flush=True)
    liq_rank = feats["val_avg20"].where(universe).rank(axis=1, ascending=False)
    large = universe & liq_rank.le(200)
    lrows = []
    for name in ("app_score_proxy", "r1", "atr14_pct", "vol20", "dd20", "dd120", "volr", "mom_120_5", "gap_ma60"):
        for h in HORIZONS:
            fwd, _, _ = labels[(h, "close")]
            exc_large = cross_sectional_excess(fwd, large)
            lrows += [asdict(r) for r in factor_study(feats[name], exc_large, large, h, name, windows)]
    pd.DataFrame(lrows).to_csv(OUT / "factor_ic_large200.csv", index=False, encoding="utf-8-sig")
    lsrows = []
    for s in ALL_STRATEGIES:
        sel = dsl.select(s, feats, large)
        for h in HORIZONS:
            fwd, _, bfwd = labels[(h, "open1")]
            exc_large = cross_sectional_excess(fwd, large)
            lsrows += [asdict(r) for r in strategy_study(sel, fwd, exc_large, bfwd, h, "open1", s["id"], s["title"], windows)]
    pd.DataFrame(lsrows).to_csv(OUT / "strategies_large200.csv", index=False, encoding="utf-8-sig")

    # ---- 6) target / stop calibration in ATR units ---------------------------
    print("risk bands ...", flush=True)
    from rd.riskband import excursions, simulate_bands
    brows, erows = [], []
    by_id = {s["id"]: s for s in ALL_STRATEGIES}
    for sid in ("S0_chase_gainers", "S2_closing_bet", "S3_pullback_in_trend", "S7_lowvol_uptrend", "S9_quiet_pullback"):
        sel = dsl.select(by_id[sid], feats, universe)
        for label, idx in windows.items():
            band = simulate_bands(panel, sel, feats["atr14_pct"], 5, dates=idx)
            band.insert(0, "window", label)
            band.insert(0, "strategy", sid)
            brows.append(band)
        ex = excursions(panel, sel, feats["atr14_pct"], 5)
        q = ex.quantile([0.1, 0.25, 0.5, 0.75, 0.9])
        for qq, r in q.iterrows():
            erows.append({"strategy": sid, "quantile": qq, "mfe_atr": r.mfe_atr, "mae_atr": r.mae_atr, "n": len(ex)})
    pd.concat(brows).to_csv(OUT / "risk_bands.csv", index=False, encoding="utf-8-sig")
    pd.DataFrame(erows).to_csv(OUT / "excursions.csv", index=False, encoding="utf-8-sig")
    print("done ->", OUT, flush=True)


if __name__ == "__main__":
    main()
