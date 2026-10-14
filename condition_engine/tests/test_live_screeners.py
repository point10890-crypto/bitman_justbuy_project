from datetime import datetime, timedelta

import pandas as pd
import pytest

from condition_engine import config
from condition_engine import live_screeners as L


class FakeFacts:
    def __init__(self, per_code=None):
        self.per_code = per_code or {}

    def enrich(self, codes, today, kinds=None, names=None):
        return {c: self.per_code.get(c, {}) for c in codes}


class FakeDart:
    def __init__(self, today=None, risky=None):
        self._today, self._risky = today or {}, risky or {}

    def today(self, day):
        return self._today

    def risky(self, day):
        return self._risky


def session_clock(engine, hh, mm):
    day = engine.panel.dates[-1] + timedelta(days=1)
    while day.weekday() >= 5:
        day += timedelta(days=1)
    return lambda: datetime(day.year, day.month, day.day, hh, mm, tzinfo=config.KST)


VAL20 = 1e10      # the synthetic market trades far less than KRX names; every stock gets a 100억 average


def snapshot(engine, clock, moves):
    """Every stock flat on normal volume, except `moves`: {code: (change, value multiple of its 20d avg)}."""
    p = engine.panel
    t = p.dates[-1]
    val20 = pd.Series(VAL20, index=p.close.columns)
    share = L.expected_share(clock())
    rows = []
    for code, prev in p.close.loc[t].dropna().items():
        chg, mult = moves.get(code, (0.0, 1.0))
        value = val20.get(code, 0) * share * mult
        rows.append({"code": code, "close": prev * (1 + chg), "change_pct": chg * 100, "value": value,
                     "volume": value / prev, "traded_at": f"{clock().date()}T10:00:00+09:00", "market_status": "OPEN"})
    rows += [{"code": f"9{i:05d}", "close": 1000.0, "change_pct": 0.0, "value": 1.0, "volume": 1.0,
              "traded_at": f"{clock().date()}T10:00:00+09:00", "market_status": "OPEN"} for i in range(600)]
    return pd.DataFrame(rows)


@pytest.fixture
def make(engine, tmp_path):
    def build(clock, moves, facts=None, dart=None):
        ls = L.LiveScreeners(engine, facts=FakeFacts(facts), dart=dart or FakeDart(),
                             snapshot=lambda: snapshot(engine, clock, moves), clock=clock, out_dir=tmp_path)
        ls.daily.frame["val20"] = VAL20
        ls.daily.frame["val5"] = VAL20
        return ls
    return build


def test_each_section_has_its_own_trading_value_pool(engine, make):
    clock = session_clock(engine, 10, 0)
    code = engine.panel.close.columns[0]
    ls = make(clock, {code: (0.05, 4.0)})
    t = ls.tape(snapshot(engine, clock, {code: (0.05, 4.0)}), clock())
    pools = ls.pools(t, clock())
    assert code in pools["short_term"]                 # 30억+, pace 4x, +5%
    assert code in pools["themes"]
    assert pools["closing_bet"] == []                  # 종가매매 pool opens only late in the session
    late = session_clock(engine, 14, 50)
    t2 = ls.tape(snapshot(engine, late, {code: (0.05, 4.0)}), late())
    assert code in ls.pools(t2, late())["closing_bet"]


def test_short_term_needs_a_rising_candle_near_the_high_and_no_risk_filing(engine, make):
    clock = session_clock(engine, 10, 0)
    a, b, c = engine.panel.close.columns[:3]
    prev = engine.panel.close.loc[engine.panel.dates[-1]]
    moves = {a: (0.06, 5.0), b: (0.06, 5.0), c: (0.06, 5.0)}
    facts = {
        a: {"bar": {"open": prev[a], "high": prev[a] * 1.06}},           # 양봉, 고가권 → 포착
        b: {"bar": {"open": prev[b] * 1.08, "high": prev[b] * 1.09}},    # 시가 아래(음봉 진행) → 제외
        c: {"bar": {"open": prev[c], "high": prev[c] * 1.06}},           # 위험 공시 → 제외
    }
    ls = make(clock, moves, facts, FakeDart(risky={c: "관리종목지정"}))
    t = ls.tape(snapshot(engine, clock, moves), clock())
    got = [x.code for x in ls.short_term(t, [a, b, c], facts, {}, {c: "관리종목지정"})]
    assert b not in got and c not in got
    for cand in ls.short_term(t, [a], facts, {a: ["단일판매ㆍ공급계약체결"]}, {}):
        assert any(r.startswith("재료: 당일 공시") for r in cand.reasons)


def short_term_ready(engine, make, clock):
    """A synthetic stock whose +5% today is a breakout above yesterday's high and above its 20-day line."""
    ls = make(clock, {})
    d = ls.daily.frame
    ok = d[(d["prev_close"] * 1.05 > d["prev_high"]) & (d["prev_close"] * 1.05 > d["ma20"])]
    return ok.index[0]


def test_scan_captures_once_and_tracks_the_move_after_capture(engine, make):
    clock = session_clock(engine, 10, 0)
    code = short_term_ready(engine, make, clock)
    prev = engine.panel.close.loc[engine.panel.dates[-1], code]
    facts = {code: {"bar": {"open": prev, "high": prev * 1.2}}}
    now = {"t": clock()}
    ls = make(lambda: now["t"], {code: (0.05, 5.0)}, facts)
    ls.scan()
    entry = ls.book["short_term"][code]
    first_price, first_at = entry["capture_price"], entry["captured_at"]
    now["t"] = now["t"] + timedelta(minutes=3)
    ls.snapshot = lambda: snapshot(engine, lambda: now["t"], {code: (0.09, 6.0)})
    ls.scan()
    row = next(r for r in ls.sections()["short_term"]["rows"] if r["code"] == code)
    assert row["capture_price"] == first_price and row["captured_at"] == first_at
    assert row["max_since_capture"] == pytest.approx((1.09 / 1.05) - 1)


def test_news_counts_only_stories_about_the_stock():
    today = datetime(2026, 10, 14).date()
    feed = [{"items": [{"datetime": "202610141000", "titleFull": "HMM·팬오션 동반강세", "body": ""}]},
            {"items": [{"datetime": "202610141010", "titleFull": "삼성SDI ESS 이익 체력", "body": ""}]},
            {"items": [{"datetime": "202610131010", "titleFull": "HMM 어제 기사", "body": ""}]}]
    facts = L.Facts(get=lambda url, params=None: feed)
    assert facts.news("011200", today, "HMM") == {"today": 1, "titles": ["HMM·팬오션 동반강세"]}


def test_swing_excludes_loss_making_companies(engine, make):
    clock = session_clock(engine, 10, 0)
    code = engine.panel.close.columns[0]
    ls = make(clock, {})
    loss = {code: {"quarters": {"op": [("a", -5), ("b", -3), ("c", -1), ("d", -2), ("e", -4)]}}}
    assert ls._fund_part(code, loss)[2] is True
    profit = {code: {"quarters": {"op": [("a", 5), ("b", 3), ("c", 6), ("d", 7), ("e", 9)]}}}
    part, reasons, is_loss = ls._fund_part(code, profit)
    assert not is_loss and part >= 0.8 and "전년 동기 대비 증가" in reasons[0]


def test_expected_share_follows_the_intraday_curve():
    tz = config.KST
    assert L.expected_share(datetime(2026, 10, 14, 9, 0, tzinfo=tz)) == 0.05      # floor at the open
    assert L.expected_share(datetime(2026, 10, 14, 11, 0, tzinfo=tz)) == pytest.approx(0.47)
    assert L.expected_share(datetime(2026, 10, 14, 15, 30, tzinfo=tz)) == 1.0


@pytest.mark.parametrize("hour,minute,short_min,leader_min", [(9, 59, 3e9, 15e9), (10, 0, 5e9, 25e9), (11, 0, 10e9, 50e9)])
def test_pools_apply_approved_liquidity_boundaries(engine, make, hour, minute, short_min, leader_min):
    clock = session_clock(engine, hour, minute)
    ls = make(clock, {})
    row = {"value": short_min - 1, "val20": 20e9, "val5": 10e9, "pace": 4., "velocity": 4.,
           "chg": .05, "close": 100., "hi250": 100.}
    tape = pd.DataFrame([row], index=["005930"])
    assert ls.pools(tape, clock())["short_term"] == []
    assert ls.pools(tape, clock())["themes"] == []
    tape.loc["005930", "value"] = short_min
    assert ls.pools(tape, clock())["short_term"] == ["005930"]
    tape.loc["005930", "value"] = leader_min - 1
    assert ls.pools(tape, clock())["leaders"] == []  # market rank cannot bypass turnover
    tape.loc["005930", "value"] = leader_min
    assert ls.pools(tape, clock())["leaders"] == ["005930"]
    tape.loc["005930", ["val20", "val5"]] = [4.9e9, 2e9]
    assert ls.pools(tape, clock())["swing"] == []


def test_missing_or_partial_financials_do_not_earn_profit_points(engine, make):
    ls = make(session_clock(engine, 10, 0), {})
    for facts in ({}, {"005930": {"quarters": {"op": [("202606", 10)]}}}):
        part, reasons, loss = ls._fund_part("005930", facts)
        assert part == 0
        assert any("미확인" in reason for reason in reasons)


def test_sector_movers_are_all_retained_and_not_called_confirmed_themes(engine, make):
    clock = session_clock(engine, 11, 0)
    ls = make(clock, {})
    codes = list(engine.panel.close.columns[:4])
    ls.daily.sector.loc[codes] = "같은업종"
    tape = pd.DataFrame({"value": [50e9, 40e9, 30e9, 20e9], "val20": [1e9] * 4,
                         "pace": [5.] * 4, "chg": [.05] * 4, "close": [110.] * 4,
                         "prev_high": [100.] * 4}, index=codes)
    candidates = ls.themes(tape, codes, {}, {}, {})
    assert len(candidates) == 4
    assert all(c.info["theme_status"] == "UNKNOWN" for c in candidates)
    assert candidates[2].info["role"] == "동반 상승주"


def test_sampled_performance_is_labeled_not_true_intraday_maximum(engine, make):
    ls = make(session_clock(engine, 10, 0), {})
    assert ls.sections()["short_term"]["performance_basis"] == "SCAN_SAMPLED_AFTER_CAPTURE"
    assert ls.sections()["swing"]["average_turnover_basis"] == "CLOSE_TIMES_VOLUME_ESTIMATE"


def test_individual_previous_day_quotes_never_enter_today_tape(engine, make):
    clock = session_clock(engine, 10, 0)
    ls = make(clock, {})
    snap = snapshot(engine, clock, {})
    code = snap.iloc[0]["code"]
    snap.loc[snap["code"] == code, "traded_at"] = "2020-01-01T10:00:00+09:00"
    assert code not in ls.tape(snap, clock()).index
