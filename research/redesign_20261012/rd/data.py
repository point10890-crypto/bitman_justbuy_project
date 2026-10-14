"""Point-in-time KRX daily panel.

Every matrix is [date x ticker]. Features are built only from rows up to and
including date t; labels only from rows strictly after t. Corporate-action
jumps (beyond the KRX +/-30% daily limit) are never treated as returns.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

ETF_BRANDS = re.compile(
    r"^(KODEX|TIGER|KBSTAR|ACE|SOL|HANARO|ARIRANG|KOSEF|RISE|PLUS|TIMEFOLIO|"
    r"KIWOOM|BNK|WOORI|마이다스|에셋플러스|파워|히어로즈|TRUSTON|UNICORN|1Q|KoAct|VITA|FOCUS|DAISHIN343|ITF|TREX)\b"
)
EXCLUDE_WORDS = re.compile(r"(스팩|ETN|리츠|인프라|선물|레버리지|인버스|합성|채권)")
BENCHMARKS = {"KOSPI200": "069500", "KOSDAQ150": "229200"}
CORPORATE_ACTION_JUMP = 0.30


@dataclass
class Panel:
    close: pd.DataFrame
    open: pd.DataFrame
    high: pd.DataFrame
    low: pd.DataFrame
    volume: pd.DataFrame
    value: pd.DataFrame          # close * volume (KRW traded, approximation)
    names: pd.Series
    benchmark: pd.DataFrame      # date x {KOSPI200, KOSDAQ150}
    ca_jump: pd.DataFrame        # True where close(t)/close(t-1) breaks the daily limit

    @property
    def dates(self) -> pd.DatetimeIndex:
        return self.close.index


def is_common_equity(ticker: str, name: str) -> bool:
    """Ordinary common shares only: 6-digit code ending in 0, not a fund/SPAC."""
    if not re.fullmatch(r"\d{6}", ticker or ""):
        return False
    if not ticker.endswith("0"):          # preferred shares end in 5/7/9/K...
        return False
    name = str(name or "")
    if ETF_BRANDS.search(name) or EXCLUDE_WORDS.search(name):
        return False
    return True


def load_panel(csv_path: str | Path) -> Panel:
    raw = pd.read_csv(
        csv_path,
        dtype={"ticker": str},
        usecols=["ticker", "date", "name", "current_price", "high", "low", "open", "volume"],
        encoding="utf-8-sig",
    )
    raw["date"] = pd.to_datetime(raw["date"])
    # Several collection runs appended the same session twice; the later row wins.
    raw = raw.drop_duplicates(["ticker", "date"], keep="last")

    bench = (
        raw[raw["ticker"].isin(BENCHMARKS.values())]
        .pivot(index="date", columns="ticker", values="current_price")
        .rename(columns={v: k for k, v in BENCHMARKS.items()})
        .sort_index()
    )

    names = raw.drop_duplicates("ticker", keep="last").set_index("ticker")["name"]
    keep = [t for t, n in names.items() if is_common_equity(t, n)]
    eq = raw[raw["ticker"].isin(keep)]

    def wide(col: str) -> pd.DataFrame:
        return eq.pivot(index="date", columns="ticker", values=col).sort_index().astype(float)

    close = wide("current_price")
    open_ = wide("open")
    high = wide("high")
    low = wide("low")
    volume = wide("volume")

    # A halted session (zero volume) carries a stale price; treat it as missing.
    halted = volume <= 0
    for frame in (close, open_, high, low):
        frame[halted] = np.nan
    volume[halted] = np.nan
    # Some sources leave OHLC at 0 when only a close exists.
    for frame in (open_, high, low):
        frame[frame <= 0] = np.nan

    prev = close.ffill().shift(1)
    ca_jump = ((close / prev - 1).abs() > CORPORATE_ACTION_JUMP).fillna(False)

    return Panel(
        close=close, open=open_, high=high, low=low, volume=volume,
        value=close * volume, names=names.loc[close.columns],
        benchmark=bench.reindex(close.index), ca_jump=ca_jump,
    )


def clean_lookback(panel: Panel, window: int) -> pd.DataFrame:
    """True where no corporate-action jump occurred in the last `window` sessions (inclusive)."""
    return ~panel.ca_jump.astype(float).rolling(window, min_periods=1).max().astype(bool)
