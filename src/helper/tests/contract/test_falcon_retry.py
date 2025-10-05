"""Contract tests for Falcon API retry logic."""
import pytest


def test_rate_limit_triggers_retry():
    """T009: Mock 429 response, verify exponential backoff behavior."""
    # This test will fail until retry logic is implemented
    with pytest.raises(ImportError):
        from src.services.falcon_client import FalconClient
    assert False, "Retry logic not yet implemented"
