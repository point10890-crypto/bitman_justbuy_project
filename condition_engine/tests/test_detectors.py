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
        assert sec["status"] in {"SIGNAL", "WATCHLIST", "TRIGGERED", "NO_TRIGGER"}
    assert out["sections"]["short_term"]["status"] == "WATCHLIST"      # after the close: tomorrow's watchlist
    assert out["sections"]["leaders"]["observed_at"] == f"{out['as_of']}T15:30:00+09:00"
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
    assert sec["rows"][0]["open_price"] == pytest.approx(prev[codes[0]] * 0.99)
    assert sec["rows"][0]["since_open"] == pytest.approx(1 / 0.99 - 1)
    assert sec["watchlist"] == 3
    assert sec["observed_at"] == f"{clock_at(engine, 9, 5)().date()}T09:00:00+09:00"   # decided at today's open


def test_short_term_before_the_open_is_a_watchlist_without_live_calls(engine, store, monkeypatch):
    t = engine.panel.dates[-1]
    forced = pd.DataFrame(False, index=engine.panel.close.index, columns=engine.panel.close.columns)
    forced.loc[t, engine.panel.close.columns[:2]] = True
    monkeypatch.setattr(D, "short_term_watch", lambda eng, x: forced)
    live = FakeLive({})
    det = D.SectionDetectors(engine, store, live=live, clock=clock_at(engine, 8, 40))
    sec = det.short_term_section()
    assert sec["status"] == "WATCHLIST" and len(sec["rows"]) == 2 and live.asked == []


def _snapshot(engine, day, bump=1.0, codes=None):
    t = engine.panel.dates[-1]
    prev = engine.panel.close.loc[t].dropna()
    rows = [{"code": c, "close": p * bump, "change_pct": 0.0, "volume": 5e6, "value": p * 5e6,
             "traded_at": f"{day}T10:00:00+09:00", "market_status": "OPEN"} for c, p in prev.items()]
    # the screener refuses thin snapshots; pad with unknown codes like a real market list
    rows += [{"code": f"9{i:05d}", "close": 1000.0, "change_pct": 0.0, "volume": 1.0, "value": 1000.0,
              "traded_at": f"{day}T10:00:00+09:00", "market_status": "OPEN"} for i in range(600)]
    return pd.DataFrame(rows)


def _force_leaders(monkeypatch, codes_by_call):
    calls = {"n": 0}

    def mask(eng, x):
        m = pd.DataFrame(False, index=eng.panel.close.index, columns=eng.panel.close.columns)
        chosen = codes_by_call[min(calls["n"], len(codes_by_call) - 1)]
        calls["n"] += 1
        m.loc[eng.panel.dates[-1], chosen] = True
        return m
    monkeypatch.setattr(D, "leader_mask", mask)


def test_screener_captures_once_with_time_and_price_then_tracks(engine, store, tmp_path, monkeypatch):
    from condition_engine.scanner import SectionScanner
    codes = list(engine.panel.close.columns[:2])
    _force_leaders(monkeypatch, [codes, codes, codes[:1]])
    first = clock_at(engine, 10, 0)
    day = first().date()
    det = D.SectionDetectors(engine, store, live=FakeLive({}), clock=first)
    snaps = iter([_snapshot(engine, day), _snapshot(engine, day, bump=1.02), _snapshot(engine, day, bump=1.03)])
    times = iter([first(), first() + timedelta(minutes=3), first() + timedelta(minutes=6)])
    now = {"t": first()}
    sc = SectionScanner(store, det, snapshot=lambda: next(snaps), clock=lambda: now["t"], scans_dir=tmp_path)

    now["t"] = next(times); assert sc.scan()["state"] == "done"
    rows = sc.sections()["leaders"]["rows"]
    assert {r["code"] for r in rows} == set(codes) and rows[0]["captured_at"].endswith("10:00:00+09:00")
    p0 = {r["code"]: r["capture_price"] for r in rows}

    now["t"] = next(times); sc.scan()
    row = sc.sections()["leaders"]["rows"][0]
    assert row["captured_at"].endswith("10:00:00+09:00") and row["capture_price"] == p0[row["code"]]  # first capture kept
    assert row["since_capture"] == pytest.approx(0.02)

    now["t"] = next(times); sc.scan()
    sec = sc.sections()["leaders"]
    assert [r["code"] for r in sec["rows"]] == codes[:1]               # the other one left the condition
    assert sec["captured_today"] == 2 and sec["status"] == "LIVE_SCAN"

    again = SectionScanner(store, det, snapshot=lambda: None, clock=lambda: now["t"], scans_dir=tmp_path)
    assert [r["code"] for r in again.sections()["leaders"]["rows"]] == codes[:1]   # survives a restart


def test_screener_refuses_a_stale_or_thin_snapshot(engine, store, tmp_path):
    from condition_engine.scanner import SectionScanner
    clock = clock_at(engine, 10, 0)
    det = D.SectionDetectors(engine, store, live=FakeLive({}), clock=clock)
    yesterday = engine.panel.dates[-1].date()
    sc = SectionScanner(store, det, snapshot=lambda: _snapshot(engine, yesterday), clock=clock, scans_dir=tmp_path)
    assert sc.scan()["state"] == "skipped"
    sc.snapshot = lambda: _snapshot(engine, clock().date()).head(100)
    assert sc.scan()["state"] == "skipped" and sc.day is None


def test_screener_only_runs_in_the_session(engine, store, tmp_path):
    from condition_engine.scanner import SectionScanner
    det = D.SectionDetectors(engine, store, live=FakeLive({}))
    sc = SectionScanner(store, det, snapshot=lambda: None, clock=clock_at(engine, 8, 59), scans_dir=tmp_path)
    assert not sc.in_session()
    assert sc.in_session(clock_at(engine, 9, 0)()) and sc.in_session(clock_at(engine, 15, 30)())
    assert not sc.in_session(clock_at(engine, 15, 31)())


def test_sections_endpoint(engine, store, tmp_path):
    svc = EngineService(store=store, ledger=Ledger(tmp_path / "l.db"), translator=RuleTranslator(), engine=engine)
    svc._detectors = D.SectionDetectors(engine, store, live=FakeLive({}), clock=clock_at(engine, 20, 0, days_after=0))
    c = TestClient(create_app(svc, autoload=False))
    body = c.get("/engine/sections").json()
    assert set(body["sections"]) == {"short_term", "swing", "leaders", "themes", "closing_bet"}
    assert body["scanner"]["state"] == "off"            # tests never start the background screener


def test_served_startup_loads_then_starts_the_screener(store, tmp_path, monkeypatch):
    """The production path: load() with autoscan on must end ready, with the screener running."""
    import condition_engine.api as api_mod
    from condition_engine.engine import Engine
    started = {}

    class FakeScreeners:
        def __init__(self, engine, **kw):
            started["engine"] = engine

        def run_forever(self, stop):
            started["running"] = True

    monkeypatch.setattr(api_mod, "LiveScreeners", FakeScreeners)
    monkeypatch.setattr(Engine, "from_store", classmethod(lambda cls, s: Engine(__import__("condition_engine.panel", fromlist=["build_panel"]).build_panel(s.final_bars(), s.load_listing(), s.load_names()), min_value_avg20=1e8, min_history=60)))
    svc = EngineService(store=store, ledger=Ledger(tmp_path / "l.db"), translator=RuleTranslator())
    svc.autoscan = True
    svc.load()
    assert svc.state == "ready" and svc.error is None
    assert started.get("engine") is svc.engine
