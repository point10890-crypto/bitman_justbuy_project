"""Centralized path management for entire application."""

import os
from pathlib import Path


class AppPaths:
    """Centralized path management for entire application."""

    # Determine base directory
    _current_file = Path(__file__).resolve()
    _app_dir = _current_file.parent.parent  # app/
    BASE_DIR = _app_dir.parent  # project root

    # Data directories
    DATA_DIR = BASE_DIR / 'data'
    LOGS_DIR = BASE_DIR / 'logs'
    STATIC_DIR = BASE_DIR / 'frontend' / 'public'

    # External service directories
    US_MARKET_DIR = BASE_DIR / 'us_market'
    US_MARKET_OUTPUT = US_MARKET_DIR / 'output'
    KR_MARKET_DATA = DATA_DIR

    # Ensure all directories exist
    for directory in [DATA_DIR, LOGS_DIR, STATIC_DIR, US_MARKET_OUTPUT]:
        directory.mkdir(parents=True, exist_ok=True)

    @classmethod
    def get_data_file(cls, filename: str) -> Path:
        """Get path to data file with existence check."""
        path = cls.DATA_DIR / filename
        if not path.exists():
            raise FileNotFoundError(f"Data file not found: {filename}")
        return path