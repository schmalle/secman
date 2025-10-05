# Data Model: Vulnerability Query Tool

## Overview
This document defines the data entities for the CrowdStrike Falcon vulnerability query tool. All entities are implemented as Python dataclasses with type hints for validation and serialization.

## Core Entities

### Vulnerability
Represents a security vulnerability detected on a device in the Falcon platform.

**Attributes**:
- `cve_id`: str - CVE identifier (e.g., "CVE-2021-44228")
- `severity`: str - CVSS severity rating ("CRITICAL", "HIGH", "MEDIUM")
- `cvss_score`: float | None - Numerical CVSS score (0.0-10.0), optional
- `product_versions`: list[str] - Affected software product versions
- `days_open`: int - Number of days since vulnerability was first detected
- `detected_date`: datetime - When the vulnerability was first identified
- `description`: str | None - Optional vulnerability description

**Validation Rules**:
- `severity` must be in {"CRITICAL", "HIGH", "MEDIUM"}
- `days_open` must be >= 0
- `cvss_score` if present must be between 0.0 and 10.0
- `cve_id` must match pattern CVE-YYYY-NNNNN

**Relationships**:
- Associated with one Device via `device_id` foreign key

---

### Device
Represents a managed endpoint in the CrowdStrike Falcon platform.

**Attributes**:
- `device_id`: str - Unique Falcon device identifier (AID)
- `hostname`: str - System hostname
- `local_ip`: str - Local IP address
- `host_groups`: list[str] - Host group memberships
- `cloud_account_id`: str | None - Cloud service account identifier
- `cloud_instance_id`: str | None - Cloud service instance identifier
- `os_version`: str - Operating system version string
- `ad_domain`: str | None - Active Directory domain membership
- `device_type`: str - Classification: "CLIENT" or "SERVER"
- `platform_name`: str - Falcon platform name (e.g., "Windows", "Linux")

**Validation Rules**:
- `device_type` must be in {"CLIENT", "SERVER"}
- `local_ip` must be valid IPv4 or IPv6 address
- `hostname` must not be empty string

**Relationships**:
- One Device has many Vulnerabilities

---

### FilterCriteria
Represents user-specified query parameters for filtering vulnerability results.

**Attributes**:
- `device_type`: str - "CLIENT", "SERVER", or "BOTH"
- `severities`: list[str] - List of severity levels to include (MEDIUM, HIGH, CRITICAL)
- `min_days_open`: int - Minimum number of days a vulnerability must be open
- `ad_domain`: str | None - Optional Active Directory domain filter
- `hostname`: str | None - Optional specific hostname filter

**Validation Rules**:
- `device_type` must be in {"CLIENT", "SERVER", "BOTH"}
- Each severity in `severities` must be in {"MEDIUM", "HIGH", "CRITICAL"}
- `severities` list must not be empty
- `min_days_open` must be >= 0

**Usage**:
Converted to Falcon API filter query string (FQL - Falcon Query Language)

---

### ExportConfiguration
Represents output formatting preferences for vulnerability report export.

**Attributes**:
- `format`: str - "XLSX", "CSV", or "TXT"
- `output_path`: str | None - User-specified file path, None for default naming
- `default_filename_pattern`: str - Template for auto-generated filenames
- `timestamp_format`: str - strftime format for timestamp in filename

**Validation Rules**:
- `format` must be in {"XLSX", "CSV", "TXT"}
- `output_path` if specified must be writable directory path
- `default_filename_pattern` must be "falcon_vulns_{timestamp}.{ext}"
- `timestamp_format` must be "%Y%m%d_%H%M%S"

**Column Ordering** (for structured exports):
1. Hostname
2. Local IP
3. Host groups
4. Cloud service account ID
5. Cloud service instance ID
6. OS version
7. Active Directory domain
8. Vulnerability ID
9. CVSS severity
10. Vulnerable product versions
11. Days open

---

### AuthenticationContext
Represents CrowdStrike Falcon API credentials and connection configuration.

**Attributes**:
- `client_id`: str - Falcon API client ID (from FALCON_CLIENT_ID env var)
- `client_secret`: str - Falcon API client secret (from FALCON_CLIENT_SECRET env var)
- `cloud_region`: str - Falcon cloud region (from FALCON_CLOUD_REGION env var, e.g., "us-1", "eu-1")
- `base_url`: str - Derived API base URL based on region

**Validation Rules**:
- `client_id` must not be empty
- `client_secret` must not be empty
- `cloud_region` must be valid Falcon cloud identifier
- All three environment variables must be present at runtime

**Security Constraints**:
- NEVER log these values at any log level
- NEVER persist to disk
- Sanitize from error messages
- Clear from memory after authentication

---

### VulnerabilityRecord
Composite entity representing a vulnerability instance on a specific device (join of Vulnerability + Device).

**Attributes**:
- All fields from `Vulnerability`
- All fields from `Device`

**Usage**:
- Primary entity for export operations
- Flattened structure matching export column ordering
- Represents one row in XLSX/CSV/TXT output

---

## State Transitions

**None** - All operations are stateless queries. No entity lifecycle management required.

## Data Flow

```
1. User Input (CLI args) → FilterCriteria
2. Environment Variables → AuthenticationContext
3. AuthenticationContext + FilterCriteria → Falcon API Query
4. Falcon API Response → List[VulnerabilityRecord]
5. VulnerabilityRecord[] + ExportConfiguration → Export File
```

## Validation Strategy

- Runtime validation using `dataclass` + custom `__post_init__` methods
- Type checking enforced via `mypy` (strict mode)
- Enum classes for constrained string values (Severity, DeviceType, ExportFormat)
- Pydantic models considered but rejected (minimize dependencies per constitution)

## Error Handling

- Invalid enum values → raise ValueError with clear message, exit code 3
- Missing environment variables → raise EnvironmentError, exit code 1
- Network/API errors → raise appropriate exception, exit codes 2/4
- Export failures → raise IOError, exit code 5
