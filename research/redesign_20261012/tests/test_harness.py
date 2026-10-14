"""The research harness is only worth something if it cannot see the future.

These tests pin the four properties every result depends on:
look-ahead safety of features, label windows, corporate-action handling,
and the DSL guardrails that keep an LLM from injecting free-form logic.
"""
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from rd import dsl  # noqa: E402
from rd.data import Panel  # noqa: E402
from rd.evaluate import daily_ic  # noqa: E402
from rd.features import build_features  # noqa: E402
from rd.labels import forward_returns  # noqa: E402


def make_panel(n_days=200, n_names=40, seed=0, jump_at=None):
    rng = np.random.default_rng(seed)
    dates = pd.bdate_range("2025-01-01", periods=n_days)
    names = [f"{i:05d}0" for i in range(n_names)]
    rets = rng.normal(0.0005, 0.02, size=(n_days, n_names))
    close = pd.DataFrame(10_000 * np.cumprod(1 + rets, axis=0), index=dates, columns=names)
    if jump_at is not None:
        day, col = jump_at
        close.iloc[day:, col] *= 0.5          # a 2:1 split shows up as a -50% "return"
    open_ = close.shift(1).fillna(close) * (1 + rng.normal(0, 0.003, size=close.shape))
    high = np.maximum(open_, close) * 1.01
    low = np.minimum(open_, close) * 0.99
    volume = pd.DataFrame(rng.integers(50_000, 500_000, size=close.shape), index=dates, columns=names).astype(float)
    bench = pd.DataFrame({"KOSPI200": 30_000 * np.cumprod(1 + rng.normal(0.0003, 0.01, n_days)),
                          "KOSDAQ150": 10_000 * np.cumprod(1 + rng.normal(0.0003, 0.012, n_days))}, index=dates)
    ca = ((close / close.shift(1) - 1).abs() > 0.30).fillna(False)
    return Panel(close=close, open=open_, high=high, low=low, volume=volume, value=close * volume,
                 names=pd.Series(names, index=names), benchmark=bench, ca_jump=ca)


def test_features_at_t_do_not_change_when_the_future_changes():
    base = make_panel()
    altered = make_panel()
    t = 150
    for frame in (altered.close, altered.open, altered.high, altered.low, altered.volume):
        frame.iloc[t + 1:] = frame.iloc[t + 1:] * 3.0
    altered.value = altered.close * altered.volume
    fa, fb = build_features(base), build_features(altered)
    for name in fa:
        left = fa[name].iloc[: t + 1]
        right = fb[name].iloc[: t + 1]
        pd.testing.assert_frame_equal(left, right, check_names=False, obj=name)


def test_label_uses_only_sessions_after_t():
    p = make_panel()
    fwd = forward_returns(p, 5, "close")
    t, col = 100, p.close.columns[3]
    expected = p.close[col].iloc[t + 5] / p.close[col].iloc[t] - 1
    assert fwd[col].iloc[t] == pytest.approx(expected)
    assert fwd.iloc[-5:].isna().all().all()          # no future, no label


def test_next_open_entry_uses_tomorrows_open():
    p = make_panel()
    fwd = forward_returns(p, 1, "open1")
    t, col = 50, p.close.columns[0]
    expected = p.close[col].iloc[t + 1] / p.open[col].iloc[t + 1] - 1
    assert fwd[col].iloc[t] == pytest.approx(expected)


def test_corporate_action_inside_label_window_voids_the_label():
    p = make_panel(jump_at=(120, 7))
    col = p.close.columns[7]
    fwd = forward_returns(p, 5, "close")
    assert np.isnan(fwd[col].iloc[117])               # window 118..122 contains the split
    assert not np.isnan(fwd[col].iloc[100])           # an untouched window survives


def test_corporate_action_inside_feature_lookback_voids_the_feature():
    p = make_panel(jump_at=(120, 7))
    col = p.close.columns[7]
    f = build_features(p)
    assert np.isnan(f["r20"][col].iloc[125])          # lookback spans the split
    assert not np.isnan(f["r20"][col].iloc[170])      # far enough after it


def test_dsl_rejects_unknown_feature_and_operator():
    feats = {"r1", "volr"}
    with pytest.raises(dsl.DslError):
        dsl.validate({"f": "insider_tip", "op": ">", "v": 1}, feats)
    with pytest.raises(dsl.DslError):
        dsl.validate({"f": "r1", "op": "LIKE", "v": 1}, feats)
    with pytest.raises(dsl.DslError):
        dsl.validate({"f": "r1", "op": ">", "v": "buy now"}, feats)


def test_dsl_not_over_missing_data_is_false():
    idx = pd.bdate_range("2025-01-01", periods=1)
    feats = {"r1": pd.DataFrame([[0.10, np.nan]], index=idx, columns=["A0", "B0"])}
    out = dsl.evaluate({"not": {"f": "r1", "op": ">=", "v": 0.05}}, feats)
    assert out.iloc[0].tolist() == [False, False]     # B has no data, so it is not "not rising"


def test_daily_ic_is_one_for_identical_rankings():
    idx = pd.bdate_range("2025-01-01", periods=3)
    cols = [f"{i:05d}0" for i in range(40)]
    x = pd.DataFrame(np.tile(np.arange(40, dtype=float), (3, 1)), index=idx, columns=cols)
    assert daily_ic(x, x * 2).round(9).eq(1.0).all()


def test_strategy_hit_rate_counts_only_picks():
    """pandas 3 keeps NaN in stack(); unpicked cells must not dilute the hit rate."""
    from rd.evaluate import strategy_study
    idx = pd.bdate_range("2025-01-01", periods=10)
    cols = [f"{i:05d}0" for i in range(50)]
    excess = pd.DataFrame(0.01, index=idx, columns=cols)
    excess.iloc[:, 1] = -0.01
    selected = pd.DataFrame(False, index=idx, columns=cols)
    selected.iloc[:, :2] = True                       # one winner, one loser per day
    res = strategy_study(selected, excess, excess, pd.Series(0.0, index=idx), 1, "close",
                         "T", "t", {"all": idx})[0]
    assert res.hit_rate == pytest.approx(0.5)


def _band_panel(path_high, path_low, close_last):
    idx = pd.bdate_range("2025-01-01", periods=len(path_high) + 2)
    col = ["000000"]
    n = len(idx)
    o = pd.DataFrame(100.0, index=idx, columns=col)
    hi = pd.DataFrame(100.0, index=idx, columns=col)
    lo = pd.DataFrame(100.0, index=idx, columns=col)
    c = pd.DataFrame(100.0, index=idx, columns=col)
    for d, (h_, l_) in enumerate(zip(path_high, path_low), start=1):
        hi.iloc[d, 0], lo.iloc[d, 0] = h_, l_
    c.iloc[len(path_high), 0] = close_last
    p = Panel(close=c, open=o, high=hi, low=lo, volume=o * 0 + 1, value=o,
              names=pd.Series(col, index=col),
              benchmark=pd.DataFrame({"KOSPI200": 1.0, "KOSDAQ150": 1.0}, index=idx),
              ca_jump=pd.DataFrame(False, index=idx, columns=col))
    sel = pd.DataFrame(False, index=idx, columns=col)
    sel.iloc[0, 0] = True
    atr = pd.DataFrame(0.10, index=idx, columns=col)   # 1 ATR = 10%
    return p, sel, atr


def test_band_stop_is_scored_before_a_later_target():
    from rd.riskband import simulate_bands
    p, sel, atr = _band_panel([105, 105, 125], [100, 89, 100], 120)   # stop day 2, target day 3
    r = simulate_bands(p, sel, atr, 3, targets=(2.0,), stops=(1.0,)).iloc[0]
    assert r.stop_hit == 1 and r.target_hit == 0


def test_band_same_day_touch_counts_as_stop():
    from rd.riskband import simulate_bands
    p, sel, atr = _band_panel([125, 100, 100], [89, 100, 100], 100)
    r = simulate_bands(p, sel, atr, 3, targets=(2.0,), stops=(1.0,)).iloc[0]
    assert r.stop_hit == 1 and r.target_hit == 0


def test_band_target_hit_pays_k_atr_minus_cost():
    from rd.riskband import simulate_bands
    p, sel, atr = _band_panel([121, 100, 100], [99, 100, 100], 100)
    r = simulate_bands(p, sel, atr, 3, targets=(2.0,), stops=(1.0,)).iloc[0]
    assert r.target_hit == 1
    assert r.net_mean_pct == pytest.approx((0.20 - 0.0023) * 100)
