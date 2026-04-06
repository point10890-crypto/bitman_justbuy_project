"""Thread-safe cache for yfinance data with TTL."""

import yfinance as yf
import pandas as pd
from datetime import datetime, timedelta
from typing import Dict, Optional
from threading import Lock


class MarketDataCache:
    """Thread-safe cache for yfinance data with TTL."""

    def __init__(self, ttl_seconds: int = 300):
        self._cache: Dict[str, tuple] = {}  # {ticker: (data, timestamp)}
        self._ttl = ttl_seconds
        self._lock = Lock()

    def get_batch(self, tickers: list, period: str = '5d', batchsize: int = 100):
        """Fetch multiple tickers in batch (much faster than individual calls)."""
        missing = []
        cached_data = {}
        now = datetime.now()

        with self._lock:
            for ticker in tickers:
                if ticker in self._cache:
                    data, timestamp = self._cache[ticker]
                    if (now - timestamp).total_seconds() < self._ttl:
                        cached_data[ticker] = data
                    else:
                        missing.append(ticker)
                else:
                    missing.append(ticker)

        if missing:
            try:
                # Batch download (single API call)
                batch_data = yf.download(
                    ' '.join(missing),
                    period=period,
                    progress=False,
                    threads=True,
                    group_by='ticker'
                )

                with self._lock:
                    for ticker in missing:
                        if ticker in batch_data.columns:
                            self._cache[ticker] = (batch_data[ticker], datetime.now())
                            cached_data[ticker] = batch_data[ticker]
            except Exception as e:
                logger.error(f"Batch yfinance fetch failed: {e}")

        return cached_data


# Global cache instance
_market_cache = MarketDataCache(ttl_seconds=300)