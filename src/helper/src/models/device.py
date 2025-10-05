"""T021: Device data model."""
from dataclasses import dataclass
from typing import Optional

from src.models.enums import DeviceType


@dataclass
class Device:
    """Represents a managed endpoint in the Falcon platform."""

    device_id: str
    hostname: str
    local_ip: str
    host_groups: list[str]
    os_version: str
    device_type: DeviceType
    platform_name: str
    cloud_account_id: Optional[str] = None
    cloud_instance_id: Optional[str] = None
    ad_domain: Optional[str] = None

    def __post_init__(self) -> None:
        """Validate device data."""
        if not self.hostname:
            raise ValueError("hostname cannot be empty")

        # Basic IP validation (simplified)
        if not self.local_ip:
            raise ValueError("local_ip cannot be empty")
