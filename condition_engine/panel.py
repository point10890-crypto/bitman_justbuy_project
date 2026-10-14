"""Wide [date x code] panel built from final bars only."""
from __future__ import annotations

import re
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from . import config

ETF_BRANDS = re.compile(
    r"^(KODEX|TIGER|KBSTAR|ACE|SOL|HANARO|ARIRANG|KOSEF|RISE|PLUS|TIMEFOLIO|KIWOOM|BNK|WOORI|"
    r"마이다스|에셋플러스|파워|히어로즈|TRUSTON|UNICORN|1Q|KoAct|VITA|FOCUS|DAISHIN343|ITF|TREX)\b"
)
EXCLUDE_WORDS = re.compile(r"(스팩|ETN|리츠|인프라|선물|레버리지|인버스|합성|채권)")
CORPORATE_ACTION_JUMP = 0.30


def is_common_equity(code: str, name, market=None) -> bool:
    if not re.fullmatch(r"\d{6}", code or "") or not code.endswith("0"):
        return False
    # Unknown market (delisted, not in today's listing) is not grounds for exclusion:
    # dropping it would bring survivorship bias back into every backtest.
    if isinstance(market, str) and market not in ("KOSPI", "KOSDAQ"):
        return False
    name = name if isinstance(name, str) else ""
    return not (ETF_BRANDS.search(name) or EXCLUDE_WORDS.search(name))


@dataclass
class Panel:
    close: pd.DataFrame
    open: pd.DataFrame
    high: pd.DataFrame
    low: pd.DataFrame
    volume: pd.DataFrame
    value: pd.DataFrame
    ca_jump: pd.DataFrame
    benchmark: pd.DataFrame
    meta: pd.DataFrame = field(default_factory=pd.DataFrame)   # index=code: name, market, sector, industry

    @property
    def dates(self) -> pd.DatetimeIndex:
        return self.close.index

    @property
    def codes(self) -> pd.Index:
        return self.close.columns


def build_panel(bars: pd.DataFrame, listing: pd.DataFrame | None = None,
                names: pd.DataFrame | None = None) -> Panel:
    bars = bars[bars["final"]] if "final" in bars else bars
    bars = bars.drop_duplicates(["code", "date"], keep="last")

    bench = (bars[bars["code"].isin(config.BENCHMARKS.values())]
             .pivot(index="date", columns="code", values="close")
             .rename(columns={v: k for k, v in config.BENCHMARKS.items()}))

    meta = _meta(bars, listing, names)
    benchmarks = set(config.BENCHMARKS.values())
    keep = [c for c, r in meta.iterrows()
            if c not in benchmarks and is_common_equity(c, r["name"], r.get("market"))]
    eq = bars[bars["code"].isin(keep)]

    def wide(col: str) -> pd.DataFrame:
        return eq.pivot(index="date", columns="code", values=col).sort_index().astype("float64")

    close, open_, high, low, volume = (wide(c) for c in ("close", "open", "high", "low", "volume"))
    halted = volume.le(0) | volume.isna()
    for frame in (close, open_, high, low, volume):
        frame[halted] = np.nan
    for frame in (open_, high, low):
        frame[frame <= 0] = np.nan

    prev = close.ffill().shift(1)
    ca_jump = (close / prev - 1).abs().gt(CORPORATE_ACTION_JUMP).fillna(False)

    for col in ("KOSPI200", "KOSDAQ150"):
        if col not in bench:
            bench[col] = np.nan
    return Panel(close=close, open=open_, high=high, low=low, volume=volume,
                 value=close * volume, ca_jump=ca_jump,
                 benchmark=bench.reindex(close.index)[["KOSPI200", "KOSDAQ150"]],
                 meta=meta.reindex(close.columns))


def _meta(bars: pd.DataFrame, listing: pd.DataFrame | None, names: pd.DataFrame | None) -> pd.DataFrame:
    codes = pd.Index(sorted(bars["code"].unique()), name="code")
    meta = pd.DataFrame(index=codes, columns=["name", "market", "sector", "industry"])
    if listing is not None and not listing.empty:
        lst = listing.drop_duplicates("code", keep="last").set_index("code")
        meta.update(lst[["name", "market", "sector", "industry"]])
    if names is not None and not names.empty:
        known = names.drop_duplicates("code", keep="last").set_index("code")["name"]
        meta["name"] = meta["name"].fillna(known.reindex(meta.index))
    return meta


def clean_lookback(panel: Panel, window: int) -> pd.DataFrame:
    """True where no corporate-action jump occurred in the last `window` sessions."""
    return ~panel.ca_jump.astype(float).rolling(window, min_periods=1).max().astype(bool)
