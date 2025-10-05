"""Contract tests for CLI environment variable validation."""
import pytest


def test_missing_env_vars_exits_1():
    """T012: Verify missing FALCON_CLIENT_ID exits 1 with clear error."""
    # This test will fail until env validation is implemented
    with pytest.raises(ImportError):
        from src.cli.env import validate_env_vars
    assert False, "Environment validation not yet implemented"
