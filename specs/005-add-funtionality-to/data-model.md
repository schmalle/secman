# Data Model: Masscan XML Import

**Feature**: 005-add-funtionality-to
**Date**: 2025-10-04
**Status**: Complete

## Overview

This feature **DOES NOT** introduce new entities. It reuses existing entities from the asset management system (Feature 002: Nmap Import).

## Existing Entities (Reused)

### Asset
**Purpose**: Represents a network host/device in the inventory

**Entity Location**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

**Fields Used by Masscan Import**:
| Field | Type | Purpose | Masscan Value |
|-------|------|---------|---------------|
| `id` | Long | Primary key | Auto-generated |
| `name` | String? | Hostname | `null` (Masscan doesn't provide hostname) |
| `ip` | String | IP address | From `<address addr="...">` |
| `type` | String | Asset classification | `"Scanned Host"` (default for Masscan) |
| `owner` | String | Responsible party | `"Security Team"` (default for Masscan) |
| `description` | String | Notes | `""` (empty, default for Masscan) |
| `lastSeen` | LocalDateTime | Last scan timestamp | From `<host endtime="...">` |
| `scanResults` | Set<ScanResult> | Related port scans | One-to-many relationship |

**Key Operations**:
- **Find by IP**: `assetRepository.findByIp(ipAddress): Optional<Asset>`
  - Used to check if asset already exists before creating
- **Create**: `assetRepository.save(asset): Asset`
  - Auto-creates asset with default values if not found
- **Update last seen**: Set `lastSeen` to scan timestamp when processing existing asset

**Business Rules**:
1. IP address is the unique identifier (used for lookup)
2. If asset exists, update `lastSeen` timestamp only (don't overwrite name/type/owner/description)
3. If asset doesn't exist, create with default values
4. Name can be `null` (Masscan doesn't provide hostname)

### ScanResult
**Purpose**: Represents a discovered port from a network scan

**Entity Location**: `src/backendng/src/main/kotlin/com/secman/domain/ScanResult.kt`

**Fields Used by Masscan Import**:
| Field | Type | Purpose | Masscan Value |
|-------|------|---------|---------------|
| `id` | Long | Primary key | Auto-generated |
| `asset` | Asset | Owning asset | FK to Asset (found/created above) |
| `port` | Int | Port number | From `<port portid="...">` |
| `protocol` | String | Protocol type | From `<port protocol="...">` (e.g., "tcp") |
| `state` | String | Port state | From `<state state="...">` (only "open" imported) |
| `service` | String? | Service name | `null` (Masscan doesn't detect service) |
| `product` | String? | Product name | `null` (Masscan doesn't detect product) |
| `version` | String? | Version info | `null` (Masscan doesn't detect version) |
| `discoveredAt` | LocalDateTime | Discovery timestamp | From `<host endtime="...">` |

**Key Operations**:
- **Create**: `scanResultRepository.save(scanResult): ScanResult`
  - Always creates new record (no deduplication)
  - Each scan creates separate ScanResult entries for historical tracking

**Business Rules**:
1. **Filter on import**: Only create ScanResults for ports with `state="open"`
2. **No deduplication**: If same port/IP appears in multiple scans, create separate records
3. **Historical tracking**: Each ScanResult has its own `discoveredAt` timestamp
4. **Nulls allowed**: service, product, version are `null` for Masscan (no service detection)
5. **Cascade**: ScanResults are deleted if parent Asset is deleted

## Data Flow

```
Masscan XML File
    ↓
MasscanParserService.parseMasscanXml()
    ↓
MasscanScanData {
  scanDate: LocalDateTime
  hosts: List<MasscanHost> {
    ipAddress: String
    timestamp: LocalDateTime
    ports: List<MasscanPort> {
      portNumber: Int
      protocol: String
      state: String
    }
  }
}
    ↓
Import Service Logic
    ↓
For each MasscanHost:
  1. Find Asset by IP (assetRepository.findByIp)
  2. If not found → Create Asset with defaults
  3. If found → Update lastSeen
  4. For each MasscanPort where state="open":
     → Create ScanResult linked to Asset
```

## Parser Data Classes

These are intermediate data structures used by MasscanParserService (not persisted entities):

### MasscanScanData
```kotlin
data class MasscanScanData(
    val scanDate: LocalDateTime,   // From <nmaprun start="...">
    val hosts: List<MasscanHost>    // Parsed host entries
)
```

### MasscanHost
```kotlin
data class MasscanHost(
    val ipAddress: String,          // From <address addr="...">
    val timestamp: LocalDateTime,   // From <host endtime="...">
    val ports: List<MasscanPort>    // Parsed port entries
)
```

### MasscanPort
```kotlin
data class MasscanPort(
    val portNumber: Int,     // From <port portid="...">
    val protocol: String,    // From <port protocol="...">
    val state: String        // From <state state="...">
)
```

## Entity Relationship Diagram

```
┌─────────────────┐
│     Asset       │
├─────────────────┤
│ id (PK)         │
│ name (nullable) │◄────┐
│ ip (unique)     │     │
│ type            │     │ One-to-Many
│ owner           │     │
│ description     │     │
│ lastSeen        │     │
└─────────────────┘     │
                        │
                        │
┌─────────────────┐     │
│  ScanResult     │     │
├─────────────────┤     │
│ id (PK)         │     │
│ asset_id (FK)   │─────┘
│ port            │
│ protocol        │
│ state           │
│ service         │
│ product         │
│ version         │
│ discoveredAt    │
└─────────────────┘
```

## Example Data Transformation

**Input XML**:
```xml
<host endtime="1759560572">
  <address addr="193.99.144.85" addrtype="ipv4"/>
  <ports>
    <port protocol="tcp" portid="80">
      <state state="open" reason="syn-ack" reason_ttl="249"/>
    </port>
    <port protocol="tcp" portid="443">
      <state state="open" reason="syn-ack" reason_ttl="249"/>
    </port>
  </ports>
</host>
```

**Result in Database**:

**Asset Table**:
| id | name | ip | type | owner | description | lastSeen |
|----|------|-----|------|-------|-------------|----------|
| 123 | null | 193.99.144.85 | Scanned Host | Security Team | | 2025-10-04 08:49:32 |

**ScanResult Table**:
| id | asset_id | port | protocol | state | service | product | version | discoveredAt |
|----|----------|------|----------|-------|---------|---------|---------|--------------|
| 456 | 123 | 80 | tcp | open | null | null | null | 2025-10-04 08:49:32 |
| 457 | 123 | 443 | tcp | open | null | null | null | 2025-10-04 08:49:32 |

## Field Mappings

### Masscan XML → Asset Entity
| Masscan XML | XPath/Attribute | Asset Field | Transformation |
|-------------|----------------|-------------|----------------|
| IP Address | `//host/address/@addr` | `ip` | Direct string |
| Timestamp | `//host/@endtime` | `lastSeen` | Epoch seconds → LocalDateTime |
| (none) | N/A | `name` | `null` |
| (none) | N/A | `type` | `"Scanned Host"` (constant) |
| (none) | N/A | `owner` | `"Security Team"` (constant) |
| (none) | N/A | `description` | `""` (constant) |

### Masscan XML → ScanResult Entity
| Masscan XML | XPath/Attribute | ScanResult Field | Transformation |
|-------------|----------------|------------------|----------------|
| Port Number | `//port/@portid` | `port` | String → Int |
| Protocol | `//port/@protocol` | `protocol` | Direct string |
| State | `//port/state/@state` | `state` | Direct string (filter: "open" only) |
| Timestamp | `//host/@endtime` | `discoveredAt` | Epoch seconds → LocalDateTime |
| (none) | N/A | `service` | `null` |
| (none) | N/A | `product` | `null` |
| (none) | N/A | `version` | `null` |
| (parent host) | Lookup by IP | `asset` | FK relationship |

## Validation Rules

### Asset Creation Validation
1. **IP address required**: Must be present in `<address addr="...">` element
2. **IP format**: Must be valid IPv4 format (e.g., "193.99.144.85")
3. **Timestamp required**: `endtime` attribute must be valid Unix epoch seconds
4. **Timestamp conversion**: If conversion fails, use current timestamp (log warning)

### ScanResult Creation Validation
1. **Port number**: Must be integer 1-65535
2. **Protocol**: Must be non-empty string (typically "tcp" or "udp")
3. **State filtering**: Only create if `state="open"` (skip "closed", "filtered")
4. **Asset linkage**: Must have valid Asset (created/found in previous step)

## Database Constraints (Inherited)

From existing Asset entity:
- `ip` field has index for fast lookups
- No unique constraint on IP (multiple assets can theoretically share IP)
- `lastSeen` is nullable (handled by Hibernate)

From existing ScanResult entity:
- Foreign key `asset_id` → Asset.id with CASCADE DELETE
- Index on `(asset_id, discoveredAt)` for efficient queries
- No unique constraint on (asset_id, port) - allows duplicates for historical tracking

## Performance Considerations

1. **Batch size**: Process hosts one at a time (no bulk insert) for error isolation
2. **Lookup optimization**: Use `findByIp()` which is indexed
3. **Transaction scope**: Entire import is one transaction (rollback on critical errors)
4. **Memory**: Parser loads entire XML into DOM (10MB file size limit prevents OOM)

## No Schema Migration Required

This feature requires **zero database migrations** because:
- Asset entity already exists (Feature 002)
- ScanResult entity already exists (Feature 002)
- All fields support the required data (nullables where needed)
- Indexes already in place for efficient queries

---
*Data model complete - ready for contract generation*
