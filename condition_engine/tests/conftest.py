"""Synthetic market fixtures — no network, deterministic."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from condition_engine import config
from condition_engine.store import BarStore

N_DAYS = 260
SEMIS = [f"{i:05d}0" for i in range(1, 13)]          # 12 names: one sector big enough for peers
OTHERS = [f"{i:05d}0" for i in range(13, 61)]
CHASER = "000010"                                    # planted: +8% on the last day with 3x volume
ALL = SEMIS + OTHERS


def make_bars(n_days: int = N_DAYS, seed: int = 7) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    dates = pd.bdate_range("2025-01-02", periods=n_days)
    rows = []
    for k, code in enumerate(ALL + list(config.BENCHMARKS.values())):
        vol_level = 0.01 + 0.03 * (k % 5) / 4            # mix of calm and noisy names
        rets = rng.normal(0.0004, vol_level, n_days)
        close = 10_000 * np.cumprod(1 + rets)
        volume = rng.integers(200_000, 600_000, n_days).astype(float)
        if code == CHASER:
            close[-1] = close[-2] * 1.08
            volume[-1] = volume[-21:-1].mean() * 3
        open_ = np.r_[close[0], close[:-1]] * (1 + rng.normal(0, 0.002, n_days))
        high = np.maximum(open_, close) * (1 + np.abs(rng.normal(0, vol_level / 2, n_days)))
        low = np.minimum(open_, close) * (1 - np.abs(rng.normal(0, vol_level / 2, n_days)))
        for i, d in enumerate(dates):
            rows.append((code, d, open_[i], high[i], low[i], close[i], volume[i]))
    bars = pd.DataFrame(rows, columns=["code", "date", "open", "high", "low", "close", "volume"])
    bars["as_of"] = (bars["date"] + pd.Timedelta(hours=16)).dt.tz_localize(config.KST)
    bars["final"] = True
    return bars


def make_listing() -> pd.DataFrame:
    rows = [(c, f"반도체{c[-3:]}", "KOSDAQ" if i % 2 else "KOSPI", "반도체 제조업", "메모리 반도체")
            for i, c in enumerate(SEMIS)]
    rows += [(c, f"종목{c[-3:]}", "KOSPI" if i % 3 else "KOSDAQ", f"업종{i % 8}", "기타") for i, c in enumerate(OTHERS)]
    return pd.DataFrame(rows, columns=["code", "name", "market", "sector", "industry"])


@pytest.fixture
def store(tmp_path) -> BarStore:
    s = BarStore(tmp_path / "bars.pkl", tmp_path / "listing.pkl")
    s.upsert(make_bars())
    s.refresh_listing(make_listing)
    s.save_names(pd.DataFrame({"code": ["069500", "229200"], "name": ["KODEX 200", "KODEX 코스닥150"]}))
    return s


@pytest.fixture
def engine(store):
    from condition_engine.engine import Engine
    from condition_engine.panel import build_panel
    panel = build_panel(store.final_bars(), store.load_listing(), store.load_names())
    # The synthetic universe is small, so relax liquidity and seasoning to keep names in it.
    return Engine(panel, min_value_avg20=1e8, min_history=60)
