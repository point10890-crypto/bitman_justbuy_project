from datetime import date, datetime

import pandas as pd
import pytest

from condition_engine import config
from condition_engine.store import BarStore, is_final


def kst(*args):
    return datetime(*args, tzinfo=config.KST)


def bar(code, day, close, as_of, final):
    return pd.DataFrame([{"code": code, "date": pd.Timestamp(day), "open": close, "high": close, "low": close,
                          "close": close, "volume": 1000.0, "as_of": pd.Timestamp(as_of), "final": final}])


def test_a_bar_is_final_only_after_the_close():
    assert not is_final(date(2026, 10, 14), kst(2026, 10, 14, 11, 0))      # mid-session
    assert not is_final(date(2026, 10, 14), kst(2026, 10, 14, 15, 39))
    assert is_final(date(2026, 10, 14), kst(2026, 10, 14, 15, 40))
    assert is_final(date(2026, 10, 13), kst(2026, 10, 14, 9, 0))           # yesterday is closed


def test_provisional_bar_never_overwrites_a_final_one(tmp_path):
    s = BarStore(tmp_path / "b.pkl", tmp_path / "l.pkl")
    s.upsert(bar("005930", "2026-10-13", 100.0, kst(2026, 10, 13, 16, 0), True))
    s.upsert(bar("005930", "2026-10-13", 999.0, kst(2026, 10, 14, 10, 0), False))
    assert s.load()["close"].tolist() == [100.0]


def test_final_bar_replaces_the_provisional_one(tmp_path):
    s = BarStore(tmp_path / "b.pkl", tmp_path / "l.pkl")
    s.upsert(bar("005930", "2026-10-14", 101.0, kst(2026, 10, 14, 11, 0), False))
    s.upsert(bar("005930", "2026-10-14", 105.0, kst(2026, 10, 14, 16, 0), True))
    got = s.load()
    assert got["close"].tolist() == [105.0] and got["final"].tolist() == [True]


def test_analysis_reads_final_bars_only(tmp_path):
    s = BarStore(tmp_path / "b.pkl", tmp_path / "l.pkl")
    s.upsert(bar("005930", "2026-10-13", 100.0, kst(2026, 10, 13, 16, 0), True))
    s.upsert(bar("005930", "2026-10-14", 101.0, kst(2026, 10, 14, 11, 0), False))
    assert s.last_final_date() == date(2026, 10, 13)
    assert len(s.final_bars()) == 1
    assert s.quality_report()["provisional_dates"] == ["2026-10-14"]


def test_update_marks_todays_mid_session_bar_provisional_and_reports_failures(tmp_path):
    s = BarStore(tmp_path / "b.pkl", tmp_path / "l.pkl")
    s.upsert(bar("005930", "2026-10-10", 100.0, kst(2026, 10, 10, 16, 0), True))
    calls = {"bad": 0}

    def fetch(code, start, end):
        if code == "999990":
            calls["bad"] += 1
            raise ConnectionError("boom")
        return pd.DataFrame({"date": pd.to_datetime(["2026-10-13", "2026-10-14"]),
                             "open": [1.0, 2.0], "high": [1.0, 2.0], "low": [1.0, 2.0],
                             "close": [110.0, 111.0], "volume": [10.0, 10.0]})

    report = s.update(fetcher=fetch, codes=["005930", "999990"], threads=2,
                      clock=lambda: kst(2026, 10, 14, 11, 30))
    assert [f["code"] for f in report["failed"]] == ["999990"]
    assert calls["bad"] == 3                                   # retried before giving up
    got = s.load().set_index("date")["final"]
    assert bool(got[pd.Timestamp("2026-10-13")]) is True
    assert bool(got[pd.Timestamp("2026-10-14")]) is False
    assert report["last_final_date"] == "2026-10-13"


def test_import_legacy_dedupes_keeps_names_and_judges_finality(tmp_path):
    csv = tmp_path / "daily.csv"
    csv.write_text(
        "ticker,date,name,current_price,change,change_rate,high,low,open,volume,update_time\n"
        "005930,2026-07-30,삼성전자,100,0,0,101,99,100,10,2026-07-30 16:05:00\n"
        "005930,2026-07-30,삼성전자,102,0,0,103,99,100,10,2026-07-30 16:10:00\n"
        "069500,2026-07-31,KODEX 200,50,0,0,51,49,50,10,2026-07-31 11:00:00\n",
        encoding="utf-8")
    s = BarStore(tmp_path / "b.pkl", tmp_path / "l.pkl")
    s.import_legacy_csv(csv)
    bars = s.load().set_index("code")
    assert bars.loc["005930", "close"] == 102.0                  # later collection wins
    assert bool(bars.loc["069500", "final"]) is False            # collected at 11:00 on the same day
    assert dict(s.load_names().values) == {"005930": "삼성전자", "069500": "KODEX 200"}


def test_update_refetches_old_provisional_history_until_it_is_final(tmp_path):
    """Legacy data saved pre-close snapshots as daily bars; update must repair them."""
    s = BarStore(tmp_path / "b.pkl", tmp_path / "l.pkl")
    s.upsert(bar("005930", "2026-03-02", 100.0, kst(2026, 3, 2, 14, 50), False))   # snapshot, not a close
    s.upsert(bar("005930", "2026-10-12", 120.0, kst(2026, 10, 12, 16, 0), True))
    seen = {}

    def fetch(code, start, end):
        seen["start"] = start
        return pd.DataFrame({"date": pd.to_datetime(["2026-03-02", "2026-10-13"]),
                             "open": [1.0, 1.0], "high": [1.0, 1.0], "low": [1.0, 1.0],
                             "close": [101.5, 121.0], "volume": [10.0, 10.0]})

    s.update(fetcher=fetch, codes=["005930"], threads=1, clock=lambda: kst(2026, 10, 14, 18, 0))
    assert seen["start"] <= date(2026, 3, 2)
    repaired = s.load().set_index("date").loc[pd.Timestamp("2026-03-02")]
    assert repaired["close"] == 101.5 and bool(repaired["final"]) is True


def test_kosdaq_global_segment_is_ordinary_kosdaq(tmp_path):
    s = BarStore(tmp_path / "b.pkl", tmp_path / "l.pkl")
    s.refresh_listing(lambda: pd.DataFrame({
        "code": ["196170", "005930", "000001"], "name": ["알테오젠", "삼성전자", "코넥스사"],
        "market": ["KOSDAQ GLOBAL", "KOSPI", "KONEX"], "sector": None, "industry": None}))
    assert s.tradable_codes() == ["005930", "196170"]
    assert s.load_listing().set_index("code").loc["196170", "market"] == "KOSDAQ"


def test_quality_separates_provisional_rows_no_update_can_settle(tmp_path):
    s = BarStore(tmp_path / "b.pkl", tmp_path / "l.pkl")
    s.refresh_listing(lambda: pd.DataFrame({"code": ["005930"], "name": ["삼성전자"], "market": ["KOSPI"],
                                            "sector": None, "industry": None}))
    s.upsert(bar("005930", "2026-10-14", 101.0, kst(2026, 10, 14, 11, 0), False))
    s.upsert(bar("123450", "2026-03-02", 50.0, kst(2026, 3, 2, 14, 0), False))     # delisted since
    q = s.quality_report()
    assert q["provisional_dates"] == ["2026-10-14"]
    assert q["unrepairable_provisional"] == {"rows": 1, "codes": 1}
