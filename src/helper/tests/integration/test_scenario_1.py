"""Integration test for Acceptance Scenario 1."""
import pytest


def test_query_critical_vulns_servers_ad_domain():
    """T014: SERVER + CRITICAL + 30 days + CORP.LOCAL."""
    # This test will fail until full integration is implemented
    with pytest.raises(ImportError):
        from src.cli.main import main
    assert False, "Integration not yet complete"
