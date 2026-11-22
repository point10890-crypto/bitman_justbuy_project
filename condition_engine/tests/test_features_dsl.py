import numpy as np
import pandas as pd
import pytest

from condition_engine import dsl
from condition_engine.features import REGISTRY, compute_features
from condition_engine.panel import build_panel, is_common_equity
from condition_engine.tests.conftest import CHASER, SEMIS, make_bars, make_listing


def panel_from(bars):
    return build_panel(bars, make_listing())


def test_every_computed_feature_is_registered_and_vice_versa(engine):
    assert set(engine.feats) == set(REGISTRY)


def test_features_at_t_ignore_the_future():
    base = make_bars()
    altered = base.copy()
    cut = sorted(base["date"].unique())[200]
    future = altered["date"] > cut
    for col in ("open", "high", "low", "close", "volume"):
        altered.loc[future, col] *= 3.0
    fa, fb = compute_features(panel_from(base)), compute_features(panel_from(altered))
    for name in fa:
        pd.testing.assert_frame_equal(fa[name].loc[:cut], fb[name].loc[:cut], obj=name)


def test_corporate_action_jump_voids_the_lookback():
    bars = make_bars()
    code = SEMIS[3]
    split_day = sorted(bars["date"].unique())[150]
    after = (bars["code"] == code) & (bars["date"] >= split_day)
    for col in ("open", "high", "low", "close"):
        bars.loc[after, col] *= 0.5
    f = compute_features(panel_from(bars))
    dates = f["r20"].index
    assert np.isnan(f["r20"].at[dates[155], code])
    assert not np.isnan(f["r20"].at[dates[200], code])


def test_ma_crosses_distinguish_a_cross_from_staying_above_or_below():
    bars = make_bars(n_days=80)
    code = SEMIS[0]
    rows = bars["code"] == code
    prices = [10_000] * 74 + [10_500, 10_600, 9_900, 9_800, 9_700, 9_000]
    for col in ("open", "high", "low", "close"):
        bars.loc[rows, col] = prices
    features = compute_features(panel_from(bars))
    assert np.isnan(features["cross_up_ma20"][code].iloc[19])
    assert features["cross_up_ma20"][code].iloc[74:78].tolist() == [1, 0, 0, 0]
    assert features["cross_down_ma20"][code].iloc[74:78].tolist() == [0, 0, 1, 0]


def test_ma_alignment_uses_the_order_of_averages():
    bars = make_bars(n_days=80)
    aligned, reverse = SEMIS[:2]
    # Last close is above all three averages even when the averages are in reverse order.
    prices = {aligned: list(np.linspace(10_000, 12_000, 80)),
              reverse: [12_000] * 60 + [11_000] * 15 + [10_000] * 4 + [12_200]}
    for code, closes in prices.items():
        for col in ("open", "high", "low", "close"):
            bars.loc[bars["code"] == code, col] = closes
    features = compute_features(panel_from(bars))
    assert features["gap_ma5"][reverse].iloc[-1] > 0
    assert features["gap_ma20"][reverse].iloc[-1] > 0
    assert features["gap_ma60"][reverse].iloc[-1] > 0
    assert features["ma_aligned"].iloc[-1][[aligned, reverse]].tolist() == [1, 0]


def test_volume_ratio_requires_the_claimed_twenty_previous_sessions():
    bars = make_bars(n_days=50)
    code = SEMIS[0]
    date_missing = sorted(bars["date"].unique())[-5]
    bars = bars.drop(bars.index[(bars["code"] == code) & (bars["date"] == date_missing)])
    ratio = compute_features(panel_from(bars))["volr"]
    assert np.isnan(ratio[code].iloc[-1])
    assert np.isfinite(ratio[SEMIS[1]].iloc[-1])


def test_sector_relative_strength_needs_enough_peers(engine):
    rs = engine.feats["rs_sector20"].iloc[-1]
    assert rs[SEMIS].notna().all()                          # 12 peers
    assert rs.drop(SEMIS).isna().all()                      # other sectors have 6 names each — too few to compare


def test_benchmark_etfs_and_funds_are_not_equities(engine):
    assert "069500" not in engine.panel.codes and "229200" not in engine.panel.codes
    assert not is_common_equity("123450", "KODEX 반도체", "KOSPI")
    assert not is_common_equity("005935", "삼성전자우", "KOSPI")      # preferred share code
    assert is_common_equity("123450", "어떤회사", float("nan"))       # unknown market is kept (no survivorship)


def test_quality_score_is_bounded(engine):
    q = engine.feats["quality_score"].stack().dropna()
    assert q.between(-1, 1).all()


# ------------------------------------------------------------------ DSL
@pytest.mark.parametrize("cond", [
    {"f": "insider_tip", "op": ">", "v": 1},
    {"f": "r1", "op": "LIKE", "v": 1},
    {"f": "r1", "op": ">", "v": "buy now"},
    {"f": "r1", "op": ">", "v": True},
    {"between": ["dd20", 0.1, -0.1]},
    {"market_in": ["NASDAQ"]},
    {"sector_any": ["반도체; DROP TABLE"]},
    {"all": []},
    {"all": [{"f": "r1", "op": ">", "v": 0}], "not": {"f": "r20", "op": ">", "v": 0}},
    {"f": "r1", "op": ">", "v": 0, "field_ignored": "r20"},
    {"f": ["r1"], "op": ">", "v": 0},
    {"market_in": [{"value": "KOSPI"}]},
])
def test_dsl_rejects_bad_conditions(cond):
    with pytest.raises(dsl.DslError):
        dsl.validate(cond)


def test_dsl_caps_the_number_of_leaves():
    with pytest.raises(dsl.DslError):
        dsl.validate({"all": [{"f": "r1", "op": ">", "v": 0}] * (dsl.MAX_LEAVES + 1)})


def test_default_guard_excludes_chase_and_high_vol():
    s = dsl.validate_strategy({"title": "t", "when": {"f": "r1", "op": ">", "v": 0},
                               "rank_by": {"f": "r1"}, "top_n": 5})
    assert s["guard"]["exclude_risk"] == ["chase", "high_vol"]
    assert s["id"].startswith("adhoc_")


def test_invalid_rank_direction_does_not_coerce_a_string_to_true():
    with pytest.raises(dsl.DslError):
        dsl.validate_strategy({"title": "t", "when": {"f": "r1", "op": ">", "v": 0},
                               "rank_by": {"f": "r1", "desc": "false"}, "top_n": 5})


def test_not_over_missing_data_is_false(engine):
    ctx = engine.ctx
    first = ctx.index[0]                                    # r20 is missing on the first day
    out = dsl.evaluate({"not": {"f": "r20", "op": ">", "v": 0}}, ctx)
    assert not out.loc[first].any()


def test_sector_and_market_conditions(engine):
    last = engine.ctx.index[-1]
    semis = dsl.evaluate({"sector_any": ["반도체"]}, engine.ctx).loc[last]
    assert set(semis[semis].index) == set(SEMIS)
    kosdaq = dsl.evaluate({"market_in": ["KOSDAQ"]}, engine.ctx).loc[last]
    expected = set(make_listing().query("market == 'KOSDAQ'")["code"])
    assert set(kosdaq[kosdaq].index) == expected


def test_describe_reads_like_korean(engine):
    lines = dsl.describe({"all": [{"f": "gap_ma20", "op": ">", "v": 0},
                                  {"between": ["dd20", -0.08, -0.02]},
                                  {"f": "val_today", "op": ">=", "v": 1e10}]})
    assert lines == ["20일선 이격 +0.0% 초과", "20일 고가 대비 -8.0% ~ -2.0%", "당일 거래대금 100억원 이상"]
