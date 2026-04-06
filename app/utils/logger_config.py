"""Centralized logging configuration."""

import logging
import os
import sys
from logging.handlers import RotatingFileHandler
from typing import Optional


def setup_logger(name: str, log_level: Optional[str] = None) -> logging.Logger:
    """Configure logger with consistent format across app."""
    log_level_str = log_level or os.getenv('LOG_LEVEL', 'INFO')
    log_level = getattr(logging, log_level_str)

    logger = logging.getLogger(name)
    logger.setLevel(log_level)

    # Remove existing handlers to avoid duplicates
    logger.handlers.clear()

    # Format: timestamp | level | logger_name | message
    formatter = logging.Formatter(
        '%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )

    # Console handler (always to stderr, never stdout)
    console_handler = logging.StreamHandler(sys.stderr)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)

    # File handler (if log directory exists)
    log_dir = os.getenv('LOG_DIR', 'logs')
    if log_dir:
        os.makedirs(log_dir, exist_ok=True)
        file_handler = RotatingFileHandler(
            os.path.join(log_dir, 'app.log'),
            maxBytes=10 * 1024 * 1024,  # 10MB
            backupCount=5,
            encoding='utf-8'
        )
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)

    return logger