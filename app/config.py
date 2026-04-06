"""Application configuration."""

import os
from urllib.parse import quote_plus


class Config:
    """Base configuration."""

    SECRET_KEY = os.getenv('SECRET_KEY')

    @property
    def SQLALCHEMY_DATABASE_URI(self):
        """Build database URI from environment."""
        db_type = os.getenv('DB_TYPE', 'sqlite').lower()

        if db_type == 'postgres':
            username = os.getenv('DB_USER', 'bitman')
            password = os.getenv('DB_PASSWORD')
            host = os.getenv('DB_HOST', 'localhost')
            port = os.getenv('DB_PORT', '5432')
            database = os.getenv('DB_NAME', 'bitman')

            if not password:
                raise ValueError("DB_PASSWORD must be set for PostgreSQL")

            # URL encode password to handle special characters
            encoded_password = quote_plus(password)
            return f'postgresql://{username}:{encoded_password}@{host}:{port}/{database}'

        elif db_type == 'sqlite':
            db_path = os.getenv('SQLITE_PATH', 'data/users.db')
            os.makedirs(os.path.dirname(db_path) or '.', exist_ok=True)
            return f'sqlite:///{db_path}'

        else:
            raise ValueError(f"Unsupported DB_TYPE: {db_type}")

    SQLALCHEMY_TRACK_MODIFICATIONS = False
    SQLALCHEMY_ENGINE_OPTIONS = {
        'poolclass': 'QueuePool',
        'pool_size': 10,
        'pool_recycle': 3600,  # Recycle connections after 1 hour
        'pool_pre_ping': True,  # Test connections before using
        'max_overflow': 20,
        'echo': False,
    }