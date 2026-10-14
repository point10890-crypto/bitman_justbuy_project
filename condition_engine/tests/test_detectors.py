from datetime import datetime, timedelta

import pandas as pd
import pytest
from fastapi.testclient import TestClient

from condition_engine import config, detectors as D
from condition_engine.agent import RuleTranslator
from condition_engine.api import EngineService, create_app
from condition_engine.ledger import Ledger


class FakeLive:
    """Today's partial bars, chosen per code."""

    def __init__(self, bars):
        self.bars, self.asked = bars, []

    def get(self, codes, today):
        self.asked.append(list(codes))
        return {c: self.bars[c] for c in codes if c in self.bars}


def clock_at(engine, hh, mm, days_after=1):
    last = engine.panel.dates[-1]
    day = last + timedelta(days=days_after)
    while day.weekday() >= 5:
        day += timedelta(days=1)
    return lambda: datetime(day.year, day.month, day.day, hh, mm, tzinfo=config.KST)


@pytest.fixture(autouse=True)
def low_liquidity_bar(monkeypatch):
    # the synthetic market trades far less than 50억 a day
    monkeypatch.setattr(D, "LIQUID_VALUE", 0)


def test_every_section_carries_its_design_and_status(engine, store):
    det = D.SectionDetectors(engine, store, live=FakeLive({}), clock=clock_at(engine, 20, 0, days_after=0))
    out = det.all_sections()
    assert set(out["sections"]) == {"short_term", "swing", "leaders", "themes", "closing_bet"}
    for key, sec in out["sections"].items():
        assert sec["thesis"] and sec["entry"] and sec["exit"] and sec["as_of"] == out["as_of"]
        assert sec["status"] in {"SIGNAL", "WATCHLIST", "TRIGGERED", "NO_TRIGGER", "NEAR_CLOSE"}
    assert out["sections"]["short_term"]["status"] == "WATCHLIST"      # after the close: tomorrow's watchlist
    assert "history" not in out["regime"]


def test_leaders_rows_all_satisfy_the_frozen_rule(engine, store):
    det = D.SectionDetectors(engine, store, live=FakeLive({}))
    t = engine.panel.dates[-1]
    for row in det.leaders_section()["rows"]:
        assert bool(det.leaders.at[t, row["code"]])


def test_short_term_buys_only_when_the_open_gaps_down(engine, store, monkeypatch):
    t = engine.panel.dates[-1]
    codes = list(engine.panel.close.columns[:3])
    forced = pd.DataFrame(False, index=engine.panel.close.index, columns=engine.panel.close.columns)
    forced.loc[t, codes] = True
    monkeypatch.setattr(D, "short_term_watch", lambda eng, x: forced)
    prev = engine.panel.close.loc[t]
    live = FakeLive({codes[0]: {"open": prev[codes[0]] * 0.99, "high": 0, "low": 0, "close": prev[codes[0]], "volume": 1},
                     codes[1]: {"open": prev[codes[1]] * 1.01, "high": 0, "low": 0, "close": prev[codes[1]], "volume": 1},
                     codes[2]: {"open": prev[codes[2]] * 0.998, "high": 0, "low": 0, "close": prev[codes[2]], "volume": 1}})
    det = D.SectionDetectors(engine, store, live=live, clock=clock_at(engine, 9, 5))
    sec = det.short_term_section()
    assert sec["status"] == "TRIGGERED"
    assert [r["code"] for r in sec["rows"]] == [codes[0]]             # -1% opens; +1% and -0.2% do not
    assert sec["rows"][0]["open_gap"] == pytest.approx(-0.01)
    assert sec["watchlist"] == 3


def test_short_term_before_the_open_is_a_watchlist_without_live_calls(engine, store, monkeypatch):
    t = engine.panel.dates[-1]
    forced = pd.DataFrame(False, index=engine.panel.close.index, columns=engine.panel.close.columns)
    forced.loc[t, engine.panel.close.columns[:2]] = True
    monkeypatch.setattr(D, "short_term_watch", lambda eng, x: forced)
    live = FakeLive({})
    det = D.SectionDetectors(engine, store, live=live, clock=clock_at(engine, 8, 40))
    sec = det.short_term_section()
    assert sec["status"] == "WATCHLIST" and len(sec["rows"]) == 2 and live.asked == []


def test_near_close_snapshot_replaces_themes_and_closing_bet_for_today(engine, store):
    t = engine.panel.dates[-1]
    prev = engine.panel.close.loc[t]
    bars = {c: {"open": p, "high": p * 1.05, "low": p * 0.99, "close": p * (1.04 if i % 2 else 1.0), "volume": 5e6}
            for i, (c, p) in enumerate(prev.dropna().items())}
    clock = clock_at(engine, 15, 5)
    det = D.SectionDetectors(engine, store, live=FakeLive(bars), clock=clock)
    report = det.compute_near_close()
    assert report["state"] == "done" and report["bars"] == len(bars)
    themes, closing = det.themes_and_closing()
    assert themes["status"] == closing["status"] == "NEAR_CLOSE"
    assert themes["as_of"] == str(clock().date())
    for row in closing["rows"]:
        assert row["role"] == "LAGGARD" and row["r1"] < 0.01


def test_near_close_refuses_a_thin_snapshot(engine, store):
    det = D.SectionDetectors(engine, store, live=FakeLive({}), clock=clock_at(engine, 15, 5))
    assert det.compute_near_close()["state"] == "error"
    assert det.near_close is None


def test_sections_endpoint(engine, store, tmp_path):
    svc = EngineService(store=store, ledger=Ledger(tmp_path / "l.db"), translator=RuleTranslator(), engine=engine)
    svc._detectors = D.SectionDetectors(engine, store, live=FakeLive({}), clock=clock_at(engine, 20, 0, days_after=0))
    c = TestClient(create_app(svc, autoload=False))
    body = c.get("/engine/sections").json()
    assert set(body["sections"]) == {"short_term", "swing", "leaders", "themes", "closing_bet"}
    assert body["near_close"]["state"] == "idle"
