"""Daily bar store with point-in-time provenance.

Every row keeps `as_of` (when it was fetched) and `final`. A session's bar is
final only once it was fetched after the close (15:40 KST) of that session; a
bar fetched mid-session is provisional. Two rules follow:

  * a provisional bar never overwrites a final one;
  * analysis reads final bars by default, so a half-day candle is never
    mistaken for a closed session.
"""
from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, time, timedelta
from pathlib import Path
from typing import Callable, Iterable

import pandas as pd

from . import config

log = logging.getLogger(__name__)

BAR_COLUMNS = ["code", "date", "open", "high", "low", "close", "volume", "as_of", "final"]
LISTING_COLUMNS = ["code", "name", "market", "sector", "industry"]
# KRX lists its KOSDAQ blue-chip segment as its own "market". Those are ordinary KOSDAQ
# shares; treating the label as a separate market silently dropped 50 names.
MARKET_ALIASES = {"KOSDAQ GLOBAL": "KOSDAQ"}

Fetcher = Callable[[str, date, date], pd.DataFrame]
ListingFetcher = Callable[[], pd.DataFrame]


def now_kst() -> datetime:
    return datetime.now(config.KST)


def is_final(session: date, fetched_at: datetime) -> bool:
    fetched = fetched_at.astimezone(config.KST)
    if session < fetched.date():
        return True
    if session > fetched.date():
        return False
    h, m = config.MARKET_CLOSE_FINAL
    return fetched.time() >= time(h, m)


def final_mask(sessions: pd.Series, fetched: pd.Series) -> pd.Series:
    """Vectorised is_final for aware timestamps."""
    local = fetched.dt.tz_convert(config.KST)
    session_day = pd.to_datetime(sessions).dt.normalize()
    fetched_day = local.dt.tz_localize(None).dt.normalize()
    h, m = config.MARKET_CLOSE_FINAL
    after_close = (local.dt.hour * 60 + local.dt.minute) >= (h * 60 + m)
    return (session_day < fetched_day) | ((session_day == fetched_day) & after_close)


def fdr_fetch(code: str, start: date, end: date) -> pd.DataFrame:
    import FinanceDataReader as fdr

    raw = fdr.DataReader(code, start.isoformat(), end.isoformat())
    if raw is None or raw.empty:
        return pd.DataFrame(columns=["date", "open", "high", "low", "close", "volume"])
    out = raw.rename(columns=str.lower)[["open", "high", "low", "close", "volume"]].copy()
    out["date"] = pd.to_datetime(raw.index).normalize()
    return out.reset_index(drop=True)


def fdr_listing() -> pd.DataFrame:
    import FinanceDataReader as fdr

    raw = fdr.StockListing("KRX-DESC")
    out = raw.rename(columns={"Code": "code", "Name": "name", "Market": "market",
                              "Sector": "sector", "Industry": "industry"})
    return out[LISTING_COLUMNS].copy()


class BarStore:
    def __init__(self, bars_file: Path | None = None, listing_file: Path | None = None):
        self.bars_file = Path(bars_file or config.BARS_FILE)
        self.listing_file = Path(listing_file or config.LISTING_FILE)
        self._bars: pd.DataFrame | None = None

    # ---------------------------------------------------------------- bars
    def load(self) -> pd.DataFrame:
        if self._bars is None:
            if self.bars_file.exists():
                self._bars = pd.read_pickle(self.bars_file)
            else:
                self._bars = _coerce(pd.DataFrame({c: [] for c in BAR_COLUMNS}))
        return self._bars

    def save(self, bars: pd.DataFrame) -> None:
        self.bars_file.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.bars_file.with_suffix(".tmp")
        bars.to_pickle(tmp)
        tmp.replace(self.bars_file)
        self._bars = bars

    def upsert(self, new_rows: pd.DataFrame) -> dict:
        existing = self.load()
        before = len(existing)
        new_rows = _normalize(new_rows)
        merged = pd.concat([existing, new_rows], ignore_index=True)
        # final rows sort after provisional ones, and later fetches after earlier ones,
        # so keep="last" applies both rules at once.
        # Concatenating onto an empty store leaves object columns: `~` on Python bools
        # is bitwise (-1/-2) and a pivot on object dates is not a DatetimeIndex.
        merged = _coerce(merged)
        merged = (merged.sort_values(["code", "date", "final", "as_of"])
                        .drop_duplicates(["code", "date"], keep="last")
                        .reset_index(drop=True))
        self.save(merged)
        return {"rows_before": before, "rows_after": len(merged),
                "received": len(new_rows), "provisional": int((~new_rows["final"]).sum())}

    def final_bars(self) -> pd.DataFrame:
        bars = self.load()
        return bars[bars["final"]]

    def last_final_date(self) -> date | None:
        bars = self.final_bars()
        return None if bars.empty else pd.Timestamp(bars["date"].max()).date()

    def import_legacy_csv(self, csv_path: Path) -> dict:
        raw = pd.read_csv(csv_path, dtype={"ticker": str}, encoding="utf-8-sig",
                          usecols=["ticker", "date", "name", "current_price", "high", "low", "open", "volume", "update_time"])
        # Keep every name ever seen. The live listing only knows today's listings, and a
        # delisted fund cannot be filtered out of history without its name.
        names = raw.drop_duplicates("ticker", keep="last")[["ticker", "name"]].rename(columns={"ticker": "code"})
        self.save_names(names)
        rows = pd.DataFrame({
            "code": raw["ticker"], "date": pd.to_datetime(raw["date"]),
            "open": raw["open"], "high": raw["high"], "low": raw["low"],
            "close": raw["current_price"], "volume": raw["volume"],
        })
        stamped = pd.to_datetime(raw["update_time"], errors="coerce")
        # Missing collection time: assume an after-close batch run (16:00).
        naive = stamped.fillna(rows["date"] + pd.Timedelta(hours=16))
        rows["as_of"] = naive.dt.tz_localize(config.KST)     # KST has no DST, so no ambiguity
        rows["final"] = final_mask(rows["date"], rows["as_of"])
        return self.upsert(rows)

    def update(self, fetcher: Fetcher = fdr_fetch, end: date | None = None,
               codes: Iterable[str] | None = None, threads: int = 8,
               refetch_days: int = 5, clock: Callable[[], datetime] = now_kst) -> dict:
        fetched_at = clock()
        end = end or fetched_at.date()
        last = self.last_final_date()
        start = (last - timedelta(days=refetch_days)) if last else end - timedelta(days=400)
        # Provisional history (e.g. pre-close snapshots saved as daily bars) is refetched
        # on every run until a final bar replaces it.
        bars = self.load()
        provisional = bars.loc[~bars["final"], "date"]
        if not provisional.empty:
            start = min(start, pd.Timestamp(provisional.min()).date())
        if codes is None:
            codes = sorted(set(self.tradable_codes()) | set(config.BENCHMARKS.values()))
        codes = list(codes)

        frames, failed = [], []

        def one(code: str):
            last_error = None
            for _ in range(3):
                try:
                    df = fetcher(code, start, end)
                    df = df.copy()
                    df["code"] = code
                    return df
                except Exception as exc:  # network hiccups are retried, then reported
                    last_error = exc
            raise last_error

        with ThreadPoolExecutor(max_workers=max(1, threads)) as pool:
            futures = {pool.submit(one, c): c for c in codes}
            for fut in as_completed(futures):
                code = futures[fut]
                try:
                    frames.append(fut.result())
                except Exception as exc:
                    failed.append({"code": code, "error": f"{type(exc).__name__}: {str(exc)[:120]}"})

        if frames:
            rows = pd.concat(frames, ignore_index=True)
            rows["as_of"] = pd.Timestamp(fetched_at).tz_convert(config.KST)
            rows["final"] = final_mask(rows["date"], rows["as_of"])
            stats = self.upsert(rows)
        else:
            stats = {"received": 0, "provisional": 0}
        return {"start": start.isoformat(), "end": end.isoformat(), "codes": len(codes),
                "failed": failed, "last_final_date": str(self.last_final_date()), **stats}

    # ------------------------------------------------------------- names
    @property
    def names_file(self) -> Path:
        return self.bars_file.with_name("names.pkl")

    def load_names(self) -> pd.DataFrame:
        if self.names_file.exists():
            return pd.read_pickle(self.names_file)
        return pd.DataFrame(columns=["code", "name"])

    def save_names(self, names: pd.DataFrame) -> None:
        merged = pd.concat([self.load_names(), names], ignore_index=True)
        merged["code"] = merged["code"].astype(str).str.zfill(6)
        merged = merged.dropna(subset=["name"]).drop_duplicates("code", keep="last")
        self.names_file.parent.mkdir(parents=True, exist_ok=True)
        merged.to_pickle(self.names_file)

    # ------------------------------------------------------------- listing
    def load_listing(self) -> pd.DataFrame:
        if self.listing_file.exists():
            return _normalize_listing(pd.read_pickle(self.listing_file))
        return pd.DataFrame(columns=LISTING_COLUMNS)

    def refresh_listing(self, fetcher: ListingFetcher = fdr_listing) -> int:
        listing = _normalize_listing(fetcher())
        listing = listing.dropna(subset=["code"]).drop_duplicates("code", keep="last")
        self.listing_file.parent.mkdir(parents=True, exist_ok=True)
        listing.to_pickle(self.listing_file)
        return len(listing)

    def tradable_codes(self) -> list[str]:
        listing = self.load_listing()
        if listing.empty:
            return sorted(self.load()["code"].unique())
        ok = listing["market"].isin(["KOSPI", "KOSDAQ"])
        return sorted(listing.loc[ok, "code"])

    # ------------------------------------------------------------- quality
    def quality_report(self) -> dict:
        bars = self.load()
        if bars.empty:
            return {"rows": 0}
        final = bars[bars["final"]]
        provisional = bars[~bars["final"]]
        # Rows outside today's KOSPI/KOSDAQ listing (KONEX, delisted) are never refetched,
        # so their provisional bars stay provisional for good. Report them apart from the
        # ones the next update will settle.
        listed = set(self.tradable_codes()) | set(config.BENCHMARKS.values())
        stuck = provisional[~provisional["code"].isin(listed)]
        provisional = provisional[provisional["code"].isin(listed)]
        recent_dates = sorted(final["date"].unique())[-10:]
        coverage = final[final["date"].isin(recent_dates)].groupby("date")["code"].nunique()
        return {
            "rows": int(len(bars)),
            "codes": int(bars["code"].nunique()),
            "first_date": str(pd.Timestamp(bars["date"].min()).date()),
            "last_final_date": str(self.last_final_date()),
            "provisional_rows": int(len(provisional)),
            "provisional_dates": sorted({str(pd.Timestamp(d).date()) for d in provisional["date"].unique()}),
            "unrepairable_provisional": {"rows": int(len(stuck)), "codes": int(stuck["code"].nunique())},
            "duplicates": int(bars.duplicated(["code", "date"]).sum()),
            "recent_coverage": {str(pd.Timestamp(k).date()): int(v) for k, v in coverage.items()},
            "listing_rows": int(len(self.load_listing())),
        }


def _coerce(df: pd.DataFrame) -> pd.DataFrame:
    """Pin every column to its real dtype, whatever the frame was concatenated from."""
    out = df.copy()
    out["code"] = out["code"].astype(str).str.zfill(6)
    out["date"] = pd.to_datetime(out["date"]).dt.normalize().astype("datetime64[ns]")
    for col in ("open", "high", "low", "close", "volume"):
        out[col] = pd.to_numeric(out[col], errors="coerce").astype("float64")
    out["as_of"] = pd.to_datetime(out["as_of"], utc=True).dt.tz_convert(config.KST)
    out["final"] = out["final"].astype(bool)
    return out[BAR_COLUMNS]


def _normalize_listing(listing: pd.DataFrame) -> pd.DataFrame:
    out = listing.copy()
    out["market"] = out["market"].replace(MARKET_ALIASES)
    return out


def _normalize(rows: pd.DataFrame) -> pd.DataFrame:
    out = rows.copy()
    out["code"] = out["code"].astype(str).str.zfill(6)
    out["date"] = pd.to_datetime(out["date"]).dt.normalize()
    for col in ("open", "high", "low", "close", "volume"):
        out[col] = pd.to_numeric(out[col], errors="coerce").astype("float64")
    out["final"] = out["final"].astype(bool)
    return out[BAR_COLUMNS]
