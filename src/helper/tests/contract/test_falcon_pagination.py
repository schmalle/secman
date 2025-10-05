"""Contract tests for Falcon API pagination."""
import pytest


def test_pagination_retrieves_all_records():
    """T008: Mock multi-page response, verify offset increment logic."""
    # This test will fail until pagination is implemented
    with pytest.raises(ImportError):
        from src.services.falcon_client import FalconClient
    assert False, "Pagination not yet implemented"
