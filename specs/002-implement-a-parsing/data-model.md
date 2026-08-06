# Data Model: Nmap Scan Import

**Feature**: 002-implement-a-parsing
**Date**: 2025-10-03
**Based on**: research.md decisions

## Entity Relationship Diagram

```
┌─────────────────┐
│     Scan        │
│─────────────────│
│ id: Long (PK)   │
│ scanType: String│
│ filename: String│
│ scanDate: DT    │
│ uploadedBy: Str │
│ hostCount: Int  │
│ duration: Int?  │
│ createdAt: DT   │
└────────┬────────┘
         │
         │ 1:N
         ↓
┌─────────────────┐        ┌──────────────────┐
│  ScanResult     │ N:1    │      Asset       │
│─────────────────│────────│──────────────────│
│ id: Long (PK)   │        │ id: Long (PK)    │
│ scanId: FK      │        │ name: String     │
│ assetId: FK     │←───────│ type: String     │
│ ipAddress: Str  │        │ ip: String?      │
│ hostname: Str?  │        │ owner: String    │
│ discoveredAt: DT│        │ description: Str?│
└────────┬────────┘        │ createdAt: DT    │
         │                 │ updatedAt: DT    │
         │ 1:N             └──────────────────┘
         ↓
┌─────────────────┐
│    ScanPort     │
│─────────────────│
│ id: Long (PK)   │
│ scanResultId: FK│
│ portNumber: Int │
│ protocol: String│ (tcp/udp)
│ state: String   │ (open/filtered/closed)
│ service: String?│ (http/ssh/etc)
│ version: String?│ (nginx/OpenSSH 8.0)
└─────────────────┘
```

## Entity Definitions

### 1. Scan (NEW)

**Purpose**: Metadata about a network scan import event.

**Fields**:
- `id`: Auto-generated primary key
- `scanType`: Scan tool type ("nmap", "masscan") - enables future scanner support
- `filename`: Original uploaded file name (e.g., "network-scan-2025-10-03.xml")
- `scanDate`: Timestamp when the scan was performed (from XML metadata)
- `uploadedBy`: Username who uploaded the scan (from JWT/session)
- `hostCount`: Number of hosts discovered in this scan
- `duration`: Scan duration in seconds (optional, nmap provides this)
- `createdAt`: Upload timestamp (auto-populated)

**Validation Rules**:
- `scanType` must be in ["nmap", "masscan"]
- `filename` max 255 characters
- `uploadedBy` references existing user
- `hostCount` ≥ 0
- `scanDate` cannot be in future

**Indexes**:
- Primary key on `id`
- Index on `uploadedBy` (for user's scan history)
- Index on `scanDate` (for chronological queries)

---

### 2. ScanResult (NEW)

**Purpose**: Host-level data discovered in a scan, linked to an Asset.

**Fields**:
- `id`: Auto-generated primary key
- `scanId`: Foreign key to Scan table
- `assetId`: Foreign key to Asset table
- `ipAddress`: IP address discovered (NOT NULL, used for asset lookup/creation)
- `hostname`: DNS hostname if available (nullable, from nmap PTR lookup)
- `discoveredAt`: Timestamp of this host's discovery (scan_date from parent Scan)

**Validation Rules**:
- `ipAddress` must be valid IPv4/IPv6 format
- `hostname` max 255 characters if present
- `scanId` references existing Scan
- `assetId` references existing Asset

**Relationships**:
- Many ScanResults → One Scan
- Many ScanResults → One Asset (enables scan history per asset)
- One ScanResult → Many ScanPorts

**Indexes**:
- Primary key on `id`
- Foreign key on `scanId` with cascading delete
- Foreign key on `assetId` with cascading delete
- Composite index on `(assetId, discoveredAt)` for port history queries

---

### 3. ScanPort (NEW)

**Purpose**: Port-level data for a specific host in a scan.

**Fields**:
- `id`: Auto-generated primary key
- `scanResultId`: Foreign key to ScanResult table
- `portNumber`: Port number (1-65535)
- `protocol`: Protocol type ("tcp" or "udp")
- `state`: Port state ("open", "filtered", "closed")
- `service`: Service name if detected (e.g., "http", "ssh") - nullable
- `version`: Service version if detected (e.g., "nginx 1.18.0") - nullable

**Validation Rules**:
- `portNumber` range [1, 65535]
- `protocol` must be in ["tcp", "udp"]
- `state` must be in ["open", "filtered", "closed"]
- `service` max 100 characters if present
- `version` max 255 characters if present
- `scanResultId` references existing ScanResult

**Relationships**:
- Many ScanPorts → One ScanResult

**Indexes**:
- Primary key on `id`
- Foreign key on `scanResultId` with cascading delete
- Composite index on `(scanResultId, portNumber, protocol)` for uniqueness within scan

---

### 4. Asset (ENHANCED)

**Purpose**: Existing asset entity, enhanced with scan relationship.

**Existing Fields** (from Asset.kt):
- `id`: Primary key
- `name`: Asset name (@NotBlank, max 255)
- `type`: Asset type (@NotBlank)
- `ip`: IP address (nullable)
- `owner`: Owner username (@NotBlank, max 255)
- `description`: Optional description (max 1024)
- `createdAt`: Creation timestamp
- `updatedAt`: Last update timestamp

**Enhancements**: NO SCHEMA CHANGES NEEDED
- Relationship: `Asset.scanResults: List<ScanResult>` (bidirectional)
- Assets created by nmap import will have:
  - `name` = hostname or IP (from research.md Decision 1)
  - `type` = "Network Host" (from research.md Decision 3)
  - `ip` = scanned IP address
  - `owner` = upload user
  - `description` = "Imported from nmap scan on YYYY-MM-DD"

**Asset Lookup Strategy**:
When processing nmap import:
1. Query `Asset` by `ip` field
2. If found: Reuse existing Asset, create new ScanResult
3. If not found: Create new Asset + ScanResult

---

## State Transitions

### Scan Import Flow

```
1. User uploads nmap.xml file
   ↓
2. Create Scan record (metadata)
   ↓
3. For each <host> in XML:
   a. Extract IP, hostname, ports
   b. Lookup Asset by IP
   c. If no Asset exists:
      → Create Asset (name=hostname|IP, type="Network Host")
   d. Create ScanResult (link Scan + Asset)
   e. For each <port>:
      → Create ScanPort (link to ScanResult)
   ↓
4. Return Scan summary (host count, scan ID)
```

### Asset-Scan Relationship Over Time

```
Timeline: Same host scanned multiple times

2025-10-03: First scan
Asset (id=1, ip="192.168.1.10", name="server.local")
  └─ ScanResult (id=1, scanId=1, ports: 22, 80)

2025-10-10: Second scan (new port opened)
Asset (id=1, ip="192.168.1.10", name="server.local")
  ├─ ScanResult (id=1, scanId=1, ports: 22, 80)
  └─ ScanResult (id=2, scanId=2, ports: 22, 80, 443)  ← NEW

2025-10-17: Third scan (port closed)
Asset (id=1, ip="192.168.1.10", name="server.local")
  ├─ ScanResult (id=1, scanId=1, ports: 22, 80)
  ├─ ScanResult (id=2, scanId=2, ports: 22, 80, 443)
  └─ ScanResult (id=3, scanId=3, ports: 22, 443)       ← Port 80 closed

Query: Show port history for Asset #1
→ Returns all ScanResults ordered by discoveredAt DESC
→ UI displays timeline of port changes
```

---

## Database Constraints

### Foreign Key Constraints

```sql
ALTER TABLE scan_result
  ADD CONSTRAINT fk_scan_result_scan
  FOREIGN KEY (scan_id) REFERENCES scan(id) ON DELETE CASCADE;

ALTER TABLE scan_result
  ADD CONSTRAINT fk_scan_result_asset
  FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE;

ALTER TABLE scan_port
  ADD CONSTRAINT fk_scan_port_result
  FOREIGN KEY (scan_result_id) REFERENCES scan_result(id) ON DELETE CASCADE;
```

**Rationale**:
- Cascade delete: When Scan deleted → remove ScanResults and ScanPorts
- Cascade delete: When Asset deleted → remove associated ScanResults (orphan cleanup)
- Prevents orphaned scan data

### Unique Constraints

No unique constraints at database level due to:
- Multiple scans can have same IP (tracked via scanId)
- Multiple ports can have same number (different scans)
- Application enforces logical uniqueness per scan

### Check Constraints

```sql
ALTER TABLE scan_port
  ADD CONSTRAINT chk_port_number CHECK (port_number BETWEEN 1 AND 65535);

ALTER TABLE scan_port
  ADD CONSTRAINT chk_protocol CHECK (protocol IN ('tcp', 'udp'));

ALTER TABLE scan_port
  ADD CONSTRAINT chk_state CHECK (state IN ('open', 'filtered', 'closed'));
```

---

## JPA/Hibernate Mappings

### Scan Entity (Kotlin)

```kotlin
package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.*
import java.time.LocalDateTime

@Entity
@Table(name = "scan")
@Serdeable
data class Scan(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, length = 20)
    @NotBlank
    @Pattern(regexp = "nmap|masscan")
    var scanType: String,

    @Column(nullable = false, length = 255)
    @NotBlank
    @Size(max = 255)
    var filename: String,

    @Column(nullable = false)
    var scanDate: LocalDateTime,

    @Column(nullable = false, length = 255)
    @NotBlank
    var uploadedBy: String,

    @Column(nullable = false)
    @Min(0)
    var hostCount: Int,

    @Column
    var duration: Int? = null,

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @OneToMany(mappedBy = "scan", cascade = [CascadeType.ALL], orphanRemoval = true)
    var results: List<ScanResult> = emptyList()
) {
    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
    }
}
```

### ScanResult Entity (Kotlin)

```kotlin
package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.*
import java.time.LocalDateTime

@Entity
@Table(name = "scan_result", indexes = [
    Index(name = "idx_scan_result_asset_date", columnList = "asset_id,discovered_at")
])
@Serdeable
data class ScanResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    var scan: Scan,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    var asset: Asset,

    @Column(nullable = false, length = 45)
    @NotBlank
    var ipAddress: String,

    @Column(length = 255)
    var hostname: String? = null,

    @Column(nullable = false)
    var discoveredAt: LocalDateTime,

    @OneToMany(mappedBy = "scanResult", cascade = [CascadeType.ALL], orphanRemoval = true)
    var ports: List<ScanPort> = emptyList()
)
```

### ScanPort Entity (Kotlin)

```kotlin
package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.*

@Entity
@Table(name = "scan_port")
@Serdeable
data class ScanPort(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_result_id", nullable = false)
    var scanResult: ScanResult,

    @Column(nullable = false)
    @Min(1)
    @Max(65535)
    var portNumber: Int,

    @Column(nullable = false, length = 10)
    @Pattern(regexp = "tcp|udp")
    var protocol: String,

    @Column(nullable = false, length = 20)
    @Pattern(regexp = "open|filtered|closed")
    var state: String,

    @Column(length = 100)
    var service: String? = null,

    @Column(length = 255)
    var version: String? = null
)
```

---

## Summary

- **3 new entities**: Scan, ScanResult, ScanPort
- **1 enhanced entity**: Asset (no schema changes, relationship via FK)
- **Cascade deletes**: Maintain referential integrity
- **Indexes**: Optimized for scan history queries
- **Validation**: DB constraints + JPA annotations
- **Extensibility**: scanType field supports future scan tools

Ready for API contract design (contracts/).
