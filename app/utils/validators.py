"""Input validation utilities for API endpoints."""

from typing import Optional
from datetime import datetime
import re


def validate_date_parameter(date_str: Optional[str], param_name: str = 'date') -> Optional[str]:
    """Validate and sanitize date parameter."""
    if not date_str:
        return None

    # Only allow YYYY-MM-DD format
    if not re.match(r'^\d{4}-\d{2}-\d{2}$', date_str):
        raise ValueError(f"Invalid {param_name} format. Expected YYYY-MM-DD")

    try:
        datetime.strptime(date_str, '%Y-%m-%d')
        return date_str
    except ValueError:
        raise ValueError(f"Invalid {param_name}: {date_str}")


def validate_ticker(ticker: str, max_length: int = 10) -> str:
    """Validate ticker symbol."""
    if not ticker or not isinstance(ticker, str):
        raise ValueError("Ticker must be a non-empty string")

    # Stock tickers are alphanumeric, max 10 chars, no special chars except .
    if not re.match(r'^[A-Z0-9\-\.]{1,10}$', ticker):
        raise ValueError(f"Invalid ticker format: {ticker}")

    return ticker.upper()


def validate_positive_integer(value, param_name: str, min_val: int = 0) -> int:
    """Validate positive integer parameter."""
    try:
        int_val = int(value)
        if int_val < min_val:
            raise ValueError(f"{param_name} must be >= {min_val}")
        return int_val
    except (ValueError, TypeError):
        raise ValueError(f"Invalid {param_name}: must be an integer")


def validate_stripe_customer_id(customer_id: str) -> str:
    """Validate Stripe customer ID format."""
    if not customer_id or not isinstance(customer_id, str):
        raise ValueError("Customer ID must be a non-empty string")

    # Stripe customer IDs start with 'cus_'
    if not re.match(r'^cus_[a-zA-Z0-9]{14,}$', customer_id):
        raise ValueError(f"Invalid Stripe customer ID format: {customer_id}")

    return customer_id