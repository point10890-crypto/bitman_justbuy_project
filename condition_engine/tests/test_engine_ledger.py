import numpy as np
import pandas as pd
import pytest

from condition_engine import dsl
from condition_engine.evaluation import forward_returns
from condition_engine.ledger import Ledger
from condition_engine.strategies import BY_ID, LIBRARY
from condition_engine.tests.conftest import CHASER

RISING = {"title": "오른 종목", "when": {"f": "r1", "op": ">", "v": 0.0},
          "rank_by": {"f": "r1", "desc": True}, "top_n": 5}


def test_chaser_is_excluded_by_the_default_guard_and_says_why(engine):
    res = engine.screen(RISING)
    published = [r["code"] for r in res["results"]]
    assert CHASER not in published
    assert res["excluded_codes"][CHASER] == ["chase"] or "chase" in res["excluded_codes"][CHASER]
    row = next(r for r in res["excluded"] if r["code"] == CHASER)
    assert row["stage"] == "GUARD_EXCLUDED"
    assert any(f["id"] == "chase" for f in row["risk"]["flags"])


def test_without_guard_the_chaser_ranks_first(engine):
    res = engine.screen({**RISING, "guard": {"exclude_risk": []}})
    assert res["results"][0]["code"] == CHASER


def test_counts_are_consistent(engine):
    res = engine.screen(RISING)
    c = res["counts"]
    assert c["passing"] == c["guard_excluded"] + c["eligible"]
    assert c["published"] == min(5, c["eligible"]) == len(res["results"])
    assert len(res["below_top_n"]) == c["eligible"] - c["published"]


def test_regime_gate_blocks_publication(engine):
    today = engine.regime.dropna().iloc[-1]
    other = [r for r in ("UP_TREND", "PULLBACK", "DOWN_TREND", "REBOUND") if r != today]
    res = engine.screen({**RISING, "guard": {"exclude_risk": [], "regime_in": other}})
    assert res["regime_blocked"] is True and res["results"] == []


def test_screen_as_of_uses_only_that_day(engine):
    day = engine.panel.dates[-30]
    res = engine.screen(RISING, as_of=str(day.date()))
    assert res["as_of"] == str(day.date())
    for r in res["results"]:
        assert r["snapshot"]["r1"] > 0


def test_forward_return_uses_next_open(engine):
    fwd = forward_returns(engine.panel, 5, "open1")
    t, code = engine.panel.dates[100], engine.panel.codes[3]
    expected = engine.panel.close[code].iloc[105] / engine.panel.open[code].iloc[101] - 1
    assert fwd.at[t, code] == pytest.approx(expected)
    assert fwd.iloc[-5:].isna().all().all()


def test_backtest_shape_and_guard_effect(engine):
    res = engine.backtest(RISING)
    assert set(res["horizons"]) == {"1", "5", "20"}
    for h in res["horizons"].values():
        assert {"all", "discovery", "confirm", "verdict"} <= set(h)
    assert res["guard_effect"]["excluded_flags"] == ["chase", "high_vol"]
    assert res["curve"] and {"excess_pct", "strategy_pct", "index_pct"} <= set(res["curve"][-1])
    assert engine.backtest(RISING) is res                       # cached


def test_every_library_strategy_is_valid(engine):
    for s in LIBRARY:
        dsl.validate_strategy(s, engine.features)


def test_risk_check_handles_unknown_codes(engine):
    res = engine.risk_check([CHASER, "999990"])
    known, unknown = res["items"]
    assert known["found"] and known["risk"]["grade"] in ("보통", "높음", "매우 높음")
    assert unknown == {"code": "999990", "found": False}


# ------------------------------------------------------------------ ledger
def test_ledger_records_every_stage_and_replaces_same_day(engine, tmp_path):
    led = Ledger(tmp_path / "l.db")
    res = engine.screen(RISING)
    h = dsl.dsl_hash(dsl.validate_strategy(RISING))
    run = led.record(res, h)
    led.record(res, h)                                          # idempotent per strategy/day
    with led._conn() as c:
        stages = dict(c.execute("SELECT stage, COUNT(*) FROM observations WHERE run_id=? GROUP BY stage", (run,)).fetchall())
        runs = c.execute("SELECT COUNT(*) FROM runs").fetchone()[0]
    assert runs == 1
    assert stages["PUBLISHED"] == res["counts"]["published"]
    assert stages["GUARD_EXCLUDED"] == res["counts"]["guard_excluded"]
    assert stages.get("BELOW_TOP_N", 0) == len(res["below_top_n"])


def test_ledger_labels_old_runs_and_leaves_recent_ones_pending(engine, tmp_path):
    led = Ledger(tmp_path / "l.db")
    h = dsl.dsl_hash(dsl.validate_strategy(RISING))
    old = engine.screen(RISING, as_of=str(engine.panel.dates[-60].date()))
    new = engine.screen(RISING)
    led.record(old, h)
    led.record(new, h)
    report = led.label(engine)
    assert report["done"] > 0 and report["pending"] > 0
    card = led.scorecard(dsl_hash=h)
    five = card["horizons"]["5"]
    assert five["stages"]["PUBLISHED"]["n"] == old["counts"]["published"]
    assert five["pending"] > 0
    pub, below = five["stages"]["PUBLISHED"]["mean_excess_pct"], five["stages"]["BELOW_TOP_N"]["mean_excess_pct"]
    if pub is not None and below is not None:
        assert five["selection_edge_pct"] == pytest.approx(round(pub - below, 2))


def test_curve_starts_on_the_first_signal_day(engine):
    bt = engine.backtest({"title": "x", "when": {"f": "gap_ma20", "op": ">", "v": 0},
                          "rank_by": {"f": "quality_score", "desc": True}, "top_n": 5})
    first = bt["curve"][0]
    assert first["date"] >= bt["period"]["all"][0]
    assert first["strategy_pct"] != 0.0 or first["excess_pct"] != 0.0


def test_a_single_flag_is_never_graded_low():
    from condition_engine.risk import describe_row, grade
    assert grade(0) == "낮음"
    for flag in ("chase", "high_vol", "breakout_spike", "volume_spike_flat"):
        assert describe_row({flag: True}, 0.03, 1000.0)["grade"] != "낮음"
    assert grade(4) == "매우 높음"


def test_curve_matches_the_daily_mean_not_one_calendar_phase():
    import pandas as pd
    from condition_engine.engine import _curve
    days = pd.bdate_range("2025-01-01", periods=50)
    # Big wins only on every 5th day: a one-in-five sample would show +20% x 10, the book +4% x 10.
    exc = pd.Series([0.2 if i % 5 == 0 else 0.0 for i in range(50)], index=days)
    curve = _curve(exc, exc, pd.Series(100.0, index=days), hold=5, step=5)
    assert abs(curve[-1]["excess_pct"] - ((1.04 ** 10) - 1) * 100) < 0.01
    assert curve[-1]["date"] == str(days[-1].date()) and curve[-1]["index_pct"] == 0.0


def test_negative_needs_significance_to_be_called_reproduced():
    from condition_engine.evaluation import verdict
    weak = verdict({"excess_vs_universe_pct": -0.01, "signal_days": 300},
                   {"excess_vs_universe_pct": -0.04, "signal_days": 200, "t_stat": -0.3})
    strong = verdict({"excess_vs_universe_pct": -2.5, "signal_days": 300},
                     {"excess_vs_universe_pct": -4.0, "signal_days": 200, "t_stat": -2.3})
    assert weak["code"] == "negative_unconfirmed" and strong["code"] == "robust_negative"
