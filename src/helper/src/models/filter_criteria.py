"""T022: FilterCriteria data model."""
from dataclasses import dataclass
from typing import Optional

from src.models.enums import DeviceType, Severity


@dataclass
class FilterCriteria:
    """User-specified query parameters for filtering vulnerabilities."""

    device_type: DeviceType
    severities: list[Severity]
    min_days_open: int
    ad_domain: Optional[str] = None
    hostname: Optional[str] = None

    def __post_init__(self) -> None:
        """Validate filter criteria."""
        if not self.severities:
            raise ValueError("severities list cannot be empty")

        if self.min_days_open < 0:
            raise ValueError(f"min_days_open must be >= 0, got {self.min_days_open}")

    def to_fql(self) -> str:
        """Convert filter criteria to Falcon Query Language string."""
        filters = []

        # Device type filter
        if self.device_type == DeviceType.CLIENT:
            filters.append("platform_name:*'Workstation'")
        elif self.device_type == DeviceType.SERVER:
            filters.append("platform_name:*'Server'")
        # BOTH means no device type filter

        # Severity filter (OR logic)
        if self.severities:
            severity_list = [f"'{s.value}'" for s in self.severities]
            filters.append(f"cve.severity:[{','.join(severity_list)}]")

        # AD domain filter
        if self.ad_domain:
            filters.append(f"host.ad_domain:'{self.ad_domain}'")

        # Hostname filter
        if self.hostname:
            filters.append(f"host.hostname:'{self.hostname}'")

        # Combine with AND logic
        return "+".join(filters) if filters else ""
