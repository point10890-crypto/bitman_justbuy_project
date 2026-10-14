"""Engine configuration. Paths are derived from this file, never from the cwd."""
from __future__ import annotations

import os
from pathlib import Path
from zoneinfo import ZoneInfo

PACKAGE_DIR = Path(__file__).resolve().parent
PROJECT_DIR = PACKAGE_DIR.parent
VAR_DIR = Path(os.getenv("CONDITION_ENGINE_VAR", PACKAGE_DIR / "var"))

BARS_FILE = VAR_DIR / "bars_daily.pkl"
LISTING_FILE = VAR_DIR / "listing.pkl"
LEDGER_DB = VAR_DIR / "ledger.db"

LEGACY_CSV_CANDIDATES = [
    Path(r"C:\bitman_marketfloww\data\daily_prices.csv"),
    PROJECT_DIR / "data" / "daily_prices.csv",
]

KST = ZoneInfo("Asia/Seoul")
MARKET_CLOSE_FINAL = (15, 40)        # a session's bar is final only after 15:40 KST

BENCHMARKS = {"KOSPI200": "069500", "KOSDAQ150": "229200"}

ROUND_TRIP_COST = 0.0023
HORIZONS = (1, 5, 20)
DISCOVERY_SHARE = 0.7

UNIVERSE = {
    "min_price": 1_000,
    "min_value_avg20": 1e9,          # 10억
    "min_history": 120,
}

ENGINE_PORT = int(os.getenv("CONDITION_ENGINE_PORT", "8090"))


def ensure_var_dir() -> Path:
    VAR_DIR.mkdir(parents=True, exist_ok=True)
    return VAR_DIR
