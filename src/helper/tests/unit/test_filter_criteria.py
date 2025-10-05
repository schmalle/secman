"""T040: Unit tests for FQL conversion logic."""
import pytest

from src.models.enums import DeviceType, Severity
from src.models.filter_criteria import FilterCriteria


class TestFQLConversion:
    """Test FilterCriteria.to_fql() method."""

    def test_server_device_type(self):
        """SERVER device type should generate platform_name filter."""
        criteria = FilterCriteria(
            device_type=DeviceType.SERVER,
            severities=[Severity.CRITICAL],
            min_days_open=0
        )
        fql = criteria.to_fql()
        assert "platform_name:*'Server'" in fql

    def test_client_device_type(self):
        """CLIENT device type should generate Workstation filter."""
        criteria = FilterCriteria(
            device_type=DeviceType.CLIENT,
            severities=[Severity.HIGH],
            min_days_open=0
        )
        fql = criteria.to_fql()
        assert "platform_name:*'Workstation'" in fql

    def test_both_device_type(self):
        """BOTH device type should not add platform filter."""
        criteria = FilterCriteria(
            device_type=DeviceType.BOTH,
            severities=[Severity.MEDIUM],
            min_days_open=0
        )
        fql = criteria.to_fql()
        assert "platform_name" not in fql

    def test_single_severity(self):
        """Single severity should generate correct filter."""
        criteria = FilterCriteria(
            device_type=DeviceType.BOTH,
            severities=[Severity.CRITICAL],
            min_days_open=0
        )
        fql = criteria.to_fql()
        assert "cve.severity:['CRITICAL']" in fql

    def test_multiple_severities(self):
        """Multiple severities should use OR logic."""
        criteria = FilterCriteria(
            device_type=DeviceType.BOTH,
            severities=[Severity.CRITICAL, Severity.HIGH],
            min_days_open=0
        )
        fql = criteria.to_fql()
        assert "cve.severity:[" in fql
        assert "'CRITICAL'" in fql
        assert "'HIGH'" in fql

    def test_ad_domain_filter(self):
        """AD domain should be included in filter."""
        criteria = FilterCriteria(
            device_type=DeviceType.SERVER,
            severities=[Severity.HIGH],
            min_days_open=0,
            ad_domain="CORP.LOCAL"
        )
        fql = criteria.to_fql()
        assert "host.ad_domain:'CORP.LOCAL'" in fql

    def test_hostname_filter(self):
        """Hostname should be included in filter."""
        criteria = FilterCriteria(
            device_type=DeviceType.BOTH,
            severities=[Severity.MEDIUM],
            min_days_open=0,
            hostname="WEB-SERVER-01"
        )
        fql = criteria.to_fql()
        assert "host.hostname:'WEB-SERVER-01'" in fql

    def test_combined_filters(self):
        """All filters should be combined with + (AND logic)."""
        criteria = FilterCriteria(
            device_type=DeviceType.SERVER,
            severities=[Severity.CRITICAL, Severity.HIGH],
            min_days_open=0,
            ad_domain="CORP.LOCAL",
            hostname="WEB-01"
        )
        fql = criteria.to_fql()
        # Check all components present
        assert "platform_name:*'Server'" in fql
        assert "cve.severity:" in fql
        assert "host.ad_domain:'CORP.LOCAL'" in fql
        assert "host.hostname:'WEB-01'" in fql
        # Check AND operator used
        assert "+" in fql

    def test_minimal_filter(self):
        """Filter with only required fields."""
        criteria = FilterCriteria(
            device_type=DeviceType.BOTH,
            severities=[Severity.MEDIUM],
            min_days_open=0
        )
        fql = criteria.to_fql()
        # Only severity filter should be present
        assert "cve.severity:['MEDIUM']" in fql
        assert "host.ad_domain" not in fql
        assert "host.hostname" not in fql
        assert "platform_name" not in fql
