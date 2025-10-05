"""T036: Logging configuration with credential sanitization."""
import logging
import re
from typing import Any, Optional


class CredentialFilter(logging.Filter):
    """Filter to sanitize credentials and sensitive data from logs."""

    # Patterns to redact
    PATTERNS = [
        # API keys and secrets (various formats)
        (re.compile(r'(client_id["\']?\s*[:=]\s*["\']?)([^"\'}\s]+)(["\']?)', re.IGNORECASE), r'\1***REDACTED***\3'),
        (re.compile(r'(client_secret["\']?\s*[:=]\s*["\']?)([^"\'}\s]+)(["\']?)', re.IGNORECASE), r'\1***REDACTED***\3'),
        (re.compile(r'(api_key["\']?\s*[:=]\s*["\']?)([^"\'}\s]+)(["\']?)', re.IGNORECASE), r'\1***REDACTED***\3'),
        (re.compile(r'(token["\']?\s*[:=]\s*["\']?)([^"\'}\s]+)(["\']?)', re.IGNORECASE), r'\1***REDACTED***\3'),
        (re.compile(r'(password["\']?\s*[:=]\s*["\']?)([^"\'}\s]+)(["\']?)', re.IGNORECASE), r'\1***REDACTED***\3'),
        (re.compile(r'(secret["\']?\s*[:=]\s*["\']?)([^"\'}\s]+)(["\']?)', re.IGNORECASE), r'\1***REDACTED***\3'),
        # Bearer tokens
        (re.compile(r'(Bearer\s+)([A-Za-z0-9\-._~+/]+=*)', re.IGNORECASE), r'\1***REDACTED***'),
        # Authorization headers
        (re.compile(r'(Authorization["\']?\s*[:=]\s*["\']?)([^"\'}\s]+)(["\']?)', re.IGNORECASE), r'\1***REDACTED***\3'),
    ]

    def filter(self, record: logging.LogRecord) -> bool:
        """Sanitize log message by redacting credentials.

        Args:
            record: Log record to filter

        Returns:
            True (always pass the record through, but sanitized)
        """
        # Sanitize the message
        if hasattr(record, 'msg') and isinstance(record.msg, str):
            for pattern, replacement in self.PATTERNS:
                record.msg = pattern.sub(replacement, record.msg)

        # Sanitize args if present
        if hasattr(record, 'args') and record.args:
            sanitized_args: list[Any] = []
            args_list = list(record.args) if isinstance(record.args, (list, tuple)) else [record.args]
            for arg in args_list:
                if isinstance(arg, str):
                    for pattern, replacement in self.PATTERNS:
                        arg = pattern.sub(replacement, arg)
                sanitized_args.append(arg)
            record.args = tuple(sanitized_args)

        return True


def setup_logging(verbose: bool = False) -> None:
    """Configure logging for the application.

    Args:
        verbose: If True, set DEBUG level; otherwise INFO level
    """
    # Determine log level
    level = logging.DEBUG if verbose else logging.INFO

    # Create formatter with timestamp
    formatter = logging.Formatter(
        fmt='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )

    # Configure root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(level)

    # Remove any existing handlers
    for handler in root_logger.handlers[:]:
        root_logger.removeHandler(handler)

    # Create console handler
    console_handler = logging.StreamHandler()
    console_handler.setLevel(level)
    console_handler.setFormatter(formatter)

    # Add credential filter
    credential_filter = CredentialFilter()
    console_handler.addFilter(credential_filter)

    # Add handler to root logger
    root_logger.addHandler(console_handler)

    # Set level for third-party libraries to WARNING to reduce noise
    logging.getLogger('urllib3').setLevel(logging.WARNING)
    logging.getLogger('falconpy').setLevel(logging.WARNING)


def get_logger(name: str) -> logging.Logger:
    """Get a logger instance with the specified name.

    Args:
        name: Logger name (typically __name__)

    Returns:
        Logger instance
    """
    return logging.getLogger(name)
