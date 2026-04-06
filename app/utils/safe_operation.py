"""Safe operation decorators for consistent error handling."""

import traceback
from typing import Optional, TypeVar, Callable
from functools import wraps

T = TypeVar('T')


def safe_operation(logger, operation_name: str, default_value: Optional[T] = None):
    """Decorator for safe error handling with proper logging."""
    def decorator(func: Callable[..., T]) -> Callable[..., T]:
        @wraps(func)
        def wrapper(*args, **kwargs) -> T:
            try:
                return func(*args, **kwargs)
            except (FileNotFoundError, IOError) as e:
                logger.error(f"File error in {operation_name}: {e}", exc_info=True)
                return default_value
            except ValueError as e:
                logger.error(f"Invalid value in {operation_name}: {e}", exc_info=True)
                return default_value
            except KeyError as e:
                logger.error(f"Missing key in {operation_name}: {e}", exc_info=True)
                return default_value
            except (pd.errors.ParserError, pd.errors.EmptyDataError) as e:
                logger.error(f"DataFrame error in {operation_name}: {e}", exc_info=True)
                return default_value
            except Exception as e:
                logger.critical(f"UNEXPECTED error in {operation_name}: {type(e).__name__}: {e}",
                               exc_info=True)
                # Re-raise unexpected errors to prevent silent failures
                raise
        return wrapper
    return decorator