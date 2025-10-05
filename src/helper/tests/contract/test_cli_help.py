"""Contract tests for CLI help functionality."""
import pytest


def test_help_flag_exits_0():
    """T013: Verify --help prints usage text and exits 0."""
    # This test will fail until CLI is implemented
    with pytest.raises(ImportError):
        from src.cli.main import main
    assert False, "CLI main not yet implemented"
