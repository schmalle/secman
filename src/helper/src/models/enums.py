"""T019: Enums for constrained values."""
from enum import Enum, IntEnum


class Severity(str, Enum):
    """Vulnerability severity levels."""
    CRITICAL = "CRITICAL"
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"


class DeviceType(str, Enum):
    """Device type classifications."""
    CLIENT = "CLIENT"
    SERVER = "SERVER"
    BOTH = "BOTH"


class ExportFormat(str, Enum):
    """Export file formats."""
    XLSX = "XLSX"
    CSV = "CSV"
    TXT = "TXT"


class ExitCode(IntEnum):
    """Exit codes for CLI."""
    SUCCESS = 0
    AUTH_ERROR = 1
    NETWORK_ERROR = 2
    INVALID_ARGS = 3
    API_ERROR = 4
    EXPORT_ERROR = 5
