"""Integration test for Acceptance Scenario 5."""
import pytest


def test_missing_credentials_error():
    """T018: clear error message, exit code 1."""
    # This test will fail until full integration is implemented
    with pytest.raises(ImportError):
        from src.cli.main import main
    assert False, "Integration not yet complete"
