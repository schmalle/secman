"""T023: ExportConfiguration data model."""
from dataclasses import dataclass
from typing import Optional

from src.models.enums import ExportFormat


@dataclass
class ExportConfiguration:
    """Output formatting preferences for vulnerability report export."""

    format: ExportFormat
    output_path: Optional[str] = None
    default_filename_pattern: str = "falcon_vulns_{timestamp}.{ext}"
    timestamp_format: str = "%Y%m%d_%H%M%S"

    def __post_init__(self) -> None:
        """Validate export configuration."""
        # Basic validation
        if not self.default_filename_pattern:
            raise ValueError("default_filename_pattern cannot be empty")

        if not self.timestamp_format:
            raise ValueError("timestamp_format cannot be empty")

    @property
    def column_order(self) -> list[str]:
        """Return the required column ordering for exports."""
        return [
            "Hostname",
            "Local IP",
            "Host groups",
            "Cloud service account ID",
            "Cloud service instance ID",
            "OS version",
            "Active Directory domain",
            "Vulnerability ID",
            "CVSS severity",
            "Vulnerable product versions",
            "Days open",
        ]
