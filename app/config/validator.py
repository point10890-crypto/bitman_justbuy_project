"""Configuration validation at startup."""

import os
from typing import List, Tuple


def validate_environment() -> Tuple[bool, List[str]]:
    """Validate all required environment variables at startup."""
    errors = []

    # Required in production
    if os.getenv('RENDER'):  # Production environment
        required = ['SECRET_KEY', 'JWT_SECRET', 'GEMINI_API_KEY']
        for var in required:
            if not os.getenv(var):
                errors.append(f"Missing required env var in production: {var}")

    # Validate format
    if jwt_secret := os.getenv('JWT_SECRET'):
        if len(jwt_secret) < 32:
            errors.append("JWT_SECRET must be at least 32 characters")

    return len(errors) == 0, errors