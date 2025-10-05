"""T039: Unit tests for model validation logic."""
import pytest
from datetime import datetime, timezone

from src.models.device import Device
from src.models.enums import DeviceType, Severity
from src.models.vulnerability import Vulnerability
from src.models.filter_criteria import FilterCriteria


class TestVulnerabilityValidation:
    """Test Vulnerability model validation."""

    def test_valid_vulnerability(self):
        """Valid vulnerability should not raise errors."""
        vuln = Vulnerability(
            cve_id="CVE-2021-44228",
            severity=Severity.CRITICAL,
            product_versions=["Log4j 2.14.1"],
            days_open=30,
            detected_date=datetime.now(timezone.utc),
            cvss_score=10.0,
            description="Remote code execution"
        )
        assert vuln.cve_id == "CVE-2021-44228"
        assert vuln.severity == Severity.CRITICAL

    def test_invalid_cve_id(self):
        """CVE ID must start with CVE-."""
        with pytest.raises(ValueError, match="Invalid CVE ID"):
            Vulnerability(
                cve_id="INVALID-2021-12345",
                severity=Severity.HIGH,
                product_versions=["Test"],
                days_open=10,
                detected_date=datetime.now(timezone.utc)
            )

    def test_negative_days_open(self):
        """days_open must be >= 0."""
        with pytest.raises(ValueError, match="days_open must be >= 0"):
            Vulnerability(
                cve_id="CVE-2021-12345",
                severity=Severity.MEDIUM,
                product_versions=["Test"],
                days_open=-1,
                detected_date=datetime.now(timezone.utc)
            )

    def test_invalid_cvss_score_high(self):
        """CVSS score must be 0.0-10.0."""
        with pytest.raises(ValueError, match="CVSS score must be 0.0-10.0"):
            Vulnerability(
                cve_id="CVE-2021-12345",
                severity=Severity.HIGH,
                product_versions=["Test"],
                days_open=5,
                detected_date=datetime.now(timezone.utc),
                cvss_score=11.0
            )

    def test_invalid_cvss_score_low(self):
        """CVSS score must be 0.0-10.0."""
        with pytest.raises(ValueError, match="CVSS score must be 0.0-10.0"):
            Vulnerability(
                cve_id="CVE-2021-12345",
                severity=Severity.HIGH,
                product_versions=["Test"],
                days_open=5,
                detected_date=datetime.now(timezone.utc),
                cvss_score=-1.0
            )


class TestDeviceValidation:
    """Test Device model validation."""

    def test_valid_device(self):
        """Valid device should not raise errors."""
        device = Device(
            device_id="device123",
            hostname="WEB-SERVER-01",
            local_ip="10.0.0.1",
            host_groups=["web-servers"],
            os_version="Windows Server 2019",
            device_type=DeviceType.SERVER,
            platform_name="Windows",
            ad_domain="CORP.LOCAL"
        )
        assert device.hostname == "WEB-SERVER-01"
        assert device.device_type == DeviceType.SERVER

    def test_empty_hostname(self):
        """hostname cannot be empty."""
        with pytest.raises(ValueError, match="hostname cannot be empty"):
            Device(
                device_id="device123",
                hostname="",
                local_ip="10.0.0.1",
                host_groups=[],
                os_version="Windows",
                device_type=DeviceType.CLIENT,
                platform_name="Windows"
            )

    def test_empty_local_ip(self):
        """local_ip cannot be empty."""
        with pytest.raises(ValueError, match="local_ip cannot be empty"):
            Device(
                device_id="device123",
                hostname="TEST-HOST",
                local_ip="",
                host_groups=[],
                os_version="Windows",
                device_type=DeviceType.CLIENT,
                platform_name="Windows"
            )


class TestFilterCriteriaValidation:
    """Test FilterCriteria model validation."""

    def test_valid_filter_criteria(self):
        """Valid filter criteria should not raise errors."""
        criteria = FilterCriteria(
            device_type=DeviceType.SERVER,
            severities=[Severity.CRITICAL, Severity.HIGH],
            min_days_open=30,
            ad_domain="CORP.LOCAL",
            hostname="WEB-01"
        )
        assert criteria.device_type == DeviceType.SERVER
        assert len(criteria.severities) == 2

    def test_empty_severities_list(self):
        """severities list cannot be empty."""
        with pytest.raises(ValueError, match="severities list cannot be empty"):
            FilterCriteria(
                device_type=DeviceType.BOTH,
                severities=[],
                min_days_open=0
            )

    def test_negative_min_days_open(self):
        """min_days_open must be >= 0."""
        with pytest.raises(ValueError, match="min_days_open must be >= 0"):
            FilterCriteria(
                device_type=DeviceType.CLIENT,
                severities=[Severity.HIGH],
                min_days_open=-5
            )


class TestEnumValidation:
    """Test enum value validation."""

    def test_invalid_severity(self):
        """Invalid severity enum value should raise error."""
        with pytest.raises(ValueError):
            Severity("INVALID")

    def test_invalid_device_type(self):
        """Invalid device type enum value should raise error."""
        with pytest.raises(ValueError):
            DeviceType("INVALID")
