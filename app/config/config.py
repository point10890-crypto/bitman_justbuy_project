"""Application configuration."""

import os
from urllib.parse import quote_plus
from sqlalchemy.pool import QueuePool


class Config:
    """Base configuration."""

    SECRET_KEY = os.getenv('SECRET_KEY')

    # Database URI
    _db_type = os.getenv('DB_TYPE', 'sqlite').lower()
    if _db_type == 'postgres':
        _username = os.getenv('DB_USER', 'bitman')
        _password = os.getenv('DB_PASSWORD')
        _host = os.getenv('DB_HOST', 'localhost')
        _port = os.getenv('DB_PORT', '5432')
        _database = os.getenv('DB_NAME', 'bitman')

        if not _password:
            raise ValueError("DB_PASSWORD must be set for PostgreSQL")

        # URL encode password to handle special characters
        _encoded_password = quote_plus(_password)
        SQLALCHEMY_DATABASE_URI = f'postgresql://{_username}:{_encoded_password}@{_host}:{_port}/{_database}'

    elif _db_type == 'sqlite':
        import os
        _db_path = os.path.join(os.path.dirname(__file__), '..', '..', 'data', 'users.db')
        os.makedirs(os.path.dirname(_db_path), exist_ok=True)
        SQLALCHEMY_DATABASE_URI = f'sqlite:///{_db_path}'

    else:
        raise ValueError(f"Unsupported DB_TYPE: {_db_type}")

    SQLALCHEMY_TRACK_MODIFICATIONS = False
    SQLALCHEMY_ENGINE_OPTIONS = {
        'poolclass': QueuePool,
        'pool_size': 10,
        'pool_recycle': 3600,  # Recycle connections after 1 hour
        'pool_pre_ping': True,  # Test connections before using
        'max_overflow': 20,
        'echo': False,
    }