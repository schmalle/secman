"""Integration test for Acceptance Scenario 3."""
import pytest


def test_export_csv_specific_hostname():
    """T016: hostname filter + CSV format."""
    # This test will fail until full integration is implemented
    with pytest.raises(ImportError):
        from src.cli.main import main
    assert False, "Integration not yet complete"
