# Data Model: MCP Tools for Security Data

## Overview
This document describes the data structures used by MCP tools to expose security data. All entities already exist in the codebase; this feature adds query interfaces without schema changes.

## Existing Entities (No Changes Required)

### Asset
**Purpose**: Represents infrastructure components in the security inventory

**Attributes**:
- `id`: Long (PK, auto-generated)
- `name`: String (required, max 255, hostname or identifier)
- `type`: String (required, asset classification)
- `ip`: String (nullable, IPv4/IPv6 address)
- `owner`: String (required, max 255, responsible party)
- `description`: String (nullable, max 1024)
- `groups`: String (nullable, max 512, comma-separated group names)
- `cloudAccountId`: String (nullable, max 255)
- `cloudInstanceId`: String (nullable, max 255)
- `adDomain`: String (nullable, max 255, Active Directory domain)
- `osVersion`: String (nullable, max 255, operating system version)
- `lastSeen`: LocalDateTime (nullable, timestamp of last scan)
- `createdAt`: LocalDateTime (auto-generated, immutable)
- `updatedAt`: LocalDateTime (auto-updated)

**Relationships**:
- OneToMany → ScanResult (lazy, cascade all)
- OneToMany → Vulnerability (lazy, cascade all)

**Indexes**:
- Primary key on `id`
- Index on `name` (partial match queries)
- Index on `ip` (exact match queries)
- Index on `type` (filtering)

**Validation Rules**:
- name: NotBlank, Size(max=255)
- type: NotBlank
- owner: NotBlank, Size(max=255)

### Scan
**Purpose**: Metadata about a network scan import event

**Attributes**:
- `id`: Long (PK, auto-generated)
- `scanType`: String (required, max 20, pattern: "nmap|masscan")
- `filename`: String (required, max 255, original upload filename)
- `scanDate`: LocalDateTime (required, when scan was performed)
- `uploadedBy`: String (required, max 255, username from JWT)
- `hostCount`: Int (required, min 0, number of discovered hosts)
- `duration`: Int (nullable, scan duration in seconds)
- `createdAt`: LocalDateTime (auto-generated, immutable)

**Relationships**:
- OneToMany → ScanResult (lazy, cascade all, orphan removal)

**Indexes**:
- Primary key on `id`
- Index on `uploaded_by` (user query filtering)
- Index on `scan_date` (time-based queries)

**Validation Rules**:
- scanType: NotBlank, Pattern("^(nmap|masscan)$")
- filename: NotBlank, Size(max=255)
- uploadedBy: NotBlank
- hostCount: Min(0)

### ScanResult
**Purpose**: Discovery of a specific host during a scan

**Attributes**:
- `id`: Long (PK, auto-generated)
- `ipAddress`: String (required, max 45, IPv4/IPv6)
- `hostname`: String (nullable, max 255, DNS hostname if resolved)
- `discoveredAt`: LocalDateTime (required, discovery timestamp)

**Relationships**:
- ManyToOne → Scan (lazy, required)
- ManyToOne → Asset (lazy, required)
- OneToMany → ScanPort (lazy, cascade all, orphan removal)

**Indexes**:
- Primary key on `id`
- Composite index on `(asset_id, discovered_at)` for history queries
- Index on `scan_id` for scan lookup

**Validation Rules**:
- ipAddress: NotBlank
- Asset and Scan: NotNull

**Methods**:
- `getEffectiveName()`: Returns hostname if present, else ipAddress

### ScanPort
**Purpose**: Port-level discovery data for a host

**Attributes**:
- `id`: Long (PK, auto-generated)
- `portNumber`: Int (required, 1-65535)
- `protocol`: String (required, max 10, pattern: "tcp|udp")
- `state`: String (required, max 20, pattern: "open|filtered|closed")
- `service`: String (nullable, max 100, service name like "http", "ssh")
- `version`: String (nullable, max 255, product version info)

**Relationships**:
- ManyToOne → ScanResult (lazy, required)

**Indexes**:
- Primary key on `id`
- Index on `scan_result_id` for result lookup
- Composite unique index on `(scan_result_id, port_number, protocol)`
- **NEW**: Index on `service` for product discovery queries

**Validation Rules**:
- portNumber: Min(1), Max(65535)
- protocol: NotBlank, Pattern("^(tcp|udp)$")
- state: NotBlank, Pattern("^(open|filtered|closed)$")

**Methods**:
- `toDisplayString()`: Returns formatted string like "80/tcp (open) - http"
- `isOpen()`: Returns true if state == "open"

### Vulnerability
**Purpose**: Security vulnerability discovered during a scan

**Attributes**:
- `id`: Long (PK, auto-generated)
- `vulnerabilityId`: String (nullable, max 255, CVE identifier)
- `cvssSeverity`: String (nullable, max 50, severity level)
- `vulnerableProductVersions`: String (nullable, max 512, affected products)
- `daysOpen`: String (nullable, max 50, days since discovery)
- `scanTimestamp`: LocalDateTime (required, when scan discovered this)
- `createdAt`: LocalDateTime (auto-generated, immutable)

**Relationships**:
- ManyToOne → Asset (lazy, required, JsonIgnore)

**Indexes**:
- Primary key on `id`
- Index on `asset_id` for asset queries
- Composite index on `(asset_id, scan_timestamp)` for history
- **NEW**: Index on `cvss_severity` for severity filtering

**Validation Rules**:
- Asset: NotNull
- scanTimestamp: NotNull

## MCP Tool Response DTOs (New)

### AssetResponse
**Purpose**: Simplified asset representation for MCP responses

**Fields**:
```kotlin
data class AssetResponse(
    val id: Long,
    val name: String,
    val type: String,
    val ip: String?,
    val owner: String,
    val description: String?,
    val groups: List<String>, // parsed from comma-separated
    val cloudAccountId: String?,
    val cloudInstanceId: String?,
    val adDomain: String?,
    val osVersion: String?,
    val lastSeen: String?, // ISO-8601 format
    val createdAt: String,
    val updatedAt: String
)
```

**Mapping**: Asset entity → AssetResponse in tool execute()

### ScanResponse
**Purpose**: Scan metadata for MCP responses

**Fields**:
```kotlin
data class ScanResponse(
    val id: Long,
    val scanType: String,
    val filename: String,
    val scanDate: String, // ISO-8601
    val uploadedBy: String,
    val hostCount: Int,
    val duration: Int?,
    val createdAt: String
)
```

### ScanResultResponse
**Purpose**: Host discovery information

**Fields**:
```kotlin
data class ScanResultResponse(
    val id: Long,
    val scanId: Long,
    val assetId: Long,
    val assetName: String,
    val ipAddress: String,
    val hostname: String?,
    val discoveredAt: String, // ISO-8601
    val ports: List<ScanPortResponse>
)
```

### ScanPortResponse
**Purpose**: Port-level discovery details

**Fields**:
```kotlin
data class ScanPortResponse(
    val id: Long,
    val portNumber: Int,
    val protocol: String,
    val state: String,
    val service: String?,
    val version: String?
)
```

### VulnerabilityResponse
**Purpose**: Vulnerability information

**Fields**:
```kotlin
data class VulnerabilityResponse(
    val id: Long,
    val assetId: Long,
    val assetName: String,
    val vulnerabilityId: String?,
    val cvssSeverity: String?,
    val vulnerableProductVersions: String?,
    val daysOpen: String?,
    val scanTimestamp: String, // ISO-8601
    val createdAt: String
)
```

### ProductResponse
**Purpose**: Discovered product/service information

**Fields**:
```kotlin
data class ProductResponse(
    val service: String,
    val version: String?,
    val protocol: String,
    val portNumber: Int,
    val assetId: Long,
    val assetName: String,
    val ip: String?,
    val state: String
)
```

### AssetProfileResponse
**Purpose**: Comprehensive asset information

**Fields**:
```kotlin
data class AssetProfileResponse(
    val asset: AssetResponse,
    val latestScan: ScanResultResponse?,
    val openPorts: List<ScanPortResponse>,
    val currentVulnerabilities: List<VulnerabilityResponse>,
    val discoveredProducts: List<ProductResponse>,
    val statistics: AssetStatistics
)

data class AssetStatistics(
    val totalScans: Int,
    val totalVulnerabilities: Int,
    val vulnerabilitiesBySeverity: Map<String, Int>,
    val uniqueServices: Int
)
```

## MCP Permission Enum Extension (New)

**Add to `McpPermission` enum**:
```kotlin
enum class McpPermission {
    // ... existing permissions ...
    ASSETS_READ,
    SCANS_READ,
    VULNERABILITIES_READ
}
```

**Permission Mapping**:
- `get_assets`, `get_asset_profile` → ASSETS_READ
- `get_scans`, `get_scan_results`, `get_scan_ports` → SCANS_READ
- `get_vulnerabilities` → VULNERABILITIES_READ
- `search_products` → SCANS_READ (product data comes from scan ports)

## Repository Method Extensions

### AssetRepository (extend existing)
```kotlin
fun findByGroupsContaining(group: String): List<Asset>
fun findByIpContainingIgnoreCase(ip: String): List<Asset>
fun findAll(pageable: Pageable): Page<Asset>
fun findByNameContainingIgnoreCase(name: String, pageable: Pageable): Page<Asset>
```

### ScanRepository (extend existing)
```kotlin
fun findByUploadedByOrderByScanDateDesc(username: String, pageable: Pageable): Page<Scan>
fun findByScanDateBetween(start: LocalDateTime, end: LocalDateTime, pageable: Pageable): Page<Scan>
fun findByScanType(scanType: String, pageable: Pageable): Page<Scan>
```

### ScanResultRepository (extend existing)
```kotlin
fun findByAssetId(assetId: Long, pageable: Pageable): Page<ScanResult>
fun findByAssetIdOrderByDiscoveredAtDesc(assetId: Long, pageable: Pageable): Page<ScanResult>
fun findByScanId(scanId: Long, pageable: Pageable): Page<ScanResult>
```

### ScanPortRepository (extend existing)
```kotlin
fun findByScanResultId(scanResultId: Long): List<ScanPort>
fun findByServiceContainingIgnoreCase(service: String, pageable: Pageable): Page<ScanPort>
fun findByStateAndServiceNotNull(state: String, pageable: Pageable): Page<ScanPort>
```

### VulnerabilityRepository (extend existing)
```kotlin
fun findByAssetId(assetId: Long, pageable: Pageable): Page<Vulnerability>
fun findByVulnerabilityIdContainingIgnoreCase(cveId: String, pageable: Pageable): Page<Vulnerability>
fun findByCvssSeverity(severity: String, pageable: Pageable): Page<Vulnerability>
fun findByCvssSeverityIn(severities: List<String>, pageable: Pageable): Page<Vulnerability>
fun findByScanTimestampBetween(start: LocalDateTime, end: LocalDateTime, pageable: Pageable): Page<Vulnerability>
```

## Data Validation Rules

### Pagination Parameters
- `page`: Min 0, Default 0
- `pageSize`: Min 1, Max 500, Default 100
- Total results per query: Max 50,000 (enforced in tool logic)

### Filter Parameters
- `assetName`: Max length 255
- `ipAddress`: Max length 45
- `service`: Max length 100
- `cveId`: Max length 255
- `severity`: Enum ["Critical", "High", "Medium", "Low", "Informational"]

### Date Range Parameters
- `startDate`: ISO-8601 format, nullable
- `endDate`: ISO-8601 format, nullable
- If both provided: startDate <= endDate

## State Transitions
None required - all entities are read-only for MCP tools (no CREATE/UPDATE/DELETE operations)

## Summary
- **Existing Entities**: 5 (no schema changes)
- **New Response DTOs**: 7
- **New Permissions**: 3
- **New Repository Methods**: ~15
- **New Indexes**: 2 (service, severity)

All data structures follow existing codebase conventions and maintain backward compatibility.
