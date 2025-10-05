"""T042: Unit tests for credential sanitization in logging."""
import logging

import pytest

from src.lib.logging_config import CredentialFilter, setup_logging


class TestCredentialFilter:
    """Test CredentialFilter regex patterns."""

    def test_sanitize_client_id(self):
        """client_id values should be redacted."""
        filter_obj = CredentialFilter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg="client_id=abc123def456",
            args=(),
            exc_info=None
        )
        filter_obj.filter(record)
        assert "abc123def456" not in record.msg
        assert "***REDACTED***" in record.msg

    def test_sanitize_client_secret(self):
        """client_secret values should be redacted."""
        filter_obj = CredentialFilter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg="client_secret='my-secret-key-123'",
            args=(),
            exc_info=None
        )
        filter_obj.filter(record)
        assert "my-secret-key-123" not in record.msg
        assert "***REDACTED***" in record.msg

    def test_sanitize_api_key(self):
        """api_key values should be redacted."""
        filter_obj = CredentialFilter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg='api_key: "sk_test_1234567890"',
            args=(),
            exc_info=None
        )
        filter_obj.filter(record)
        assert "sk_test_1234567890" not in record.msg
        assert "***REDACTED***" in record.msg

    def test_sanitize_token(self):
        """token values should be redacted."""
        filter_obj = CredentialFilter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg="token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
            args=(),
            exc_info=None
        )
        filter_obj.filter(record)
        assert "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" not in record.msg
        assert "***REDACTED***" in record.msg

    def test_sanitize_password(self):
        """password values should be redacted."""
        filter_obj = CredentialFilter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg="password: SuperSecret123!",
            args=(),
            exc_info=None
        )
        filter_obj.filter(record)
        assert "SuperSecret123!" not in record.msg
        assert "***REDACTED***" in record.msg

    def test_sanitize_bearer_token(self):
        """Bearer tokens should be redacted."""
        filter_obj = CredentialFilter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg="Authorization: Bearer abc123xyz789",
            args=(),
            exc_info=None
        )
        filter_obj.filter(record)
        assert "abc123xyz789" not in record.msg
        assert "***REDACTED***" in record.msg

    def test_sanitize_authorization_header(self):
        """Authorization header values should be redacted."""
        filter_obj = CredentialFilter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg='Authorization: "Basic dXNlcjpwYXNz"',
            args=(),
            exc_info=None
        )
        filter_obj.filter(record)
        assert "dXNlcjpwYXNz" not in record.msg
        assert "***REDACTED***" in record.msg

    def test_sanitize_multiple_patterns(self):
        """Multiple credential patterns in same message should all be redacted."""
        filter_obj = CredentialFilter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg="client_id=abc123 client_secret=def456 token=ghi789",
            args=(),
            exc_info=None
        )
        filter_obj.filter(record)
        assert "abc123" not in record.msg
        assert "def456" not in record.msg
        assert "ghi789" not in record.msg
        assert record.msg.count("***REDACTED***") >= 3

    def test_sanitize_json_format(self):
        """Credentials in JSON-like format should be redacted."""
        filter_obj = CredentialFilter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg='{"client_id": "abc123", "client_secret": "xyz789"}',
            args=(),
            exc_info=None
        )
        filter_obj.filter(record)
        assert "abc123" not in record.msg
        assert "xyz789" not in record.msg

    def test_non_credential_text_unchanged(self):
        """Non-credential text should remain unchanged."""
        filter_obj = CredentialFilter()
        original_msg = "Querying vulnerabilities for hostname WEB-SERVER-01"
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg=original_msg,
            args=(),
            exc_info=None
        )
        filter_obj.filter(record)
        assert record.msg == original_msg

    def test_filter_returns_true(self):
        """Filter should always return True to pass records through."""
        filter_obj = CredentialFilter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="",
            lineno=0,
            msg="test message",
            args=(),
            exc_info=None
        )
        result = filter_obj.filter(record)
        assert result is True


class TestLoggingSetup:
    """Test logging configuration setup."""

    def test_setup_logging_info_level(self):
        """Non-verbose mode should set INFO level."""
        setup_logging(verbose=False)
        root_logger = logging.getLogger()
        assert root_logger.level == logging.INFO

    def test_setup_logging_debug_level(self):
        """Verbose mode should set DEBUG level."""
        setup_logging(verbose=True)
        root_logger = logging.getLogger()
        assert root_logger.level == logging.DEBUG

    def test_credential_filter_applied(self):
        """CredentialFilter should be applied to handlers."""
        setup_logging(verbose=False)
        root_logger = logging.getLogger()

        # Check that at least one handler has CredentialFilter
        has_credential_filter = False
        for handler in root_logger.handlers:
            for filter_obj in handler.filters:
                if isinstance(filter_obj, CredentialFilter):
                    has_credential_filter = True
                    break

        assert has_credential_filter, "CredentialFilter not found in handlers"
