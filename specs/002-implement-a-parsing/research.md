# Research: Nmap Scan Import Technical Decisions

**Feature**: 002-implement-a-parsing
**Date**: 2025-10-03
**Purpose**: Resolve NEEDS CLARIFICATION items from spec.md

## Overview

This document resolves 6 technical decision points identified in the feature specification by researching existing secman patterns, nmap XML structure, and best practices for scan data management.

---

## Decision 1: Asset Naming When Hostname Missing (FR-012)

**Question**: How to name assets when nmap discovers a host with only an IP address (no hostname)?

**Research**:
- Examined Asset.kt: `name` field is `@NotBlank` and required
- Nmap XML example (testdata/nmap.xml): Contains `<hostname name="www.heise.de">` but can be absent
- Real-world scenario: Many IPs have no reverse DNS (PTR records)

**Decision**: **Use IP address as asset name when hostname is missing**

**Rationale**:
- Simple, predictable, user-friendly
- IP address uniquely identifies the asset
- Meets `@NotBlank` constraint requirement
- Users can manually rename asset later if needed
- Hostname can be stored separately in Asset.description or custom field

**Alternatives considered**:
- "Unknown Host 192.168.1.1" - Adds unnecessary prefix noise
- Generated GUID - Not human-readable, poor UX
- Throw error/skip host - Loses valuable scan data

**Implementation**:
```kotlin
val assetName = hostname.firstOrNull()?.name ?: ipAddress
```

---

## Decision 2: Duplicate Host Handling in Single Scan (FR-013)

**Question**: How to handle duplicate IP addresses appearing in the same nmap scan file?

**Research**:
- Nmap XML structure: Each `<host>` element represents one scanned target
- Duplicates occur when: scanning same IP multiple times in single command, virtual hosts, or scan errors
- Frequency: Rare in practice (user error scenario)

**Decision**: **Skip duplicates with warning log, keep first occurrence**

**Rationale**:
- Preserves first-seen data (likely most complete)
- Prevents database constraint violations (assuming IP uniqueness per scan)
- Logs warning for user/admin troubleshooting
- Simple implementation, no complex merge logic needed

**Alternatives considered**:
- Merge port lists - Complex, error-prone, unclear precedence rules
- Create separate assets - Violates uniqueness, confuses users
- Fail entire import - Too harsh, loses good data

**Implementation**:
```kotlin
val seenIPs = mutableSetOf<String>()
hosts.forEach { host ->
    if (host.ip in seenIPs) {
        logger.warn("Duplicate IP ${host.ip} in scan, skipping")
    } else {
        seenIPs.add(host.ip)
        // process host
    }
}
```

---

## Decision 3: Asset Type for Network Devices (FR-014)

**Question**: What default asset type to use for assets created from nmap scans?

**Research**:
- Examined Asset.kt: `type` field is free-form String
- Existing usage: Likely "Server", "Workstation", "Network Device", etc.
- Nmap doesn't provide definitive type classification

**Decision**: **Default type = "Network Host"**

**Rationale**:
- Generic, accurate for any scanned device
- Distinct from manually-created assets
- User can refine type later based on port analysis
- Future enhancement: Infer type from open ports (e.g., port 22+80 → "Server")

**Alternatives considered**:
- "Unknown" - Too vague, not informative
- Derive from ports - Complex heuristics, not always accurate (e.g., firewall rules)
- User-specified per scan - Extra UI friction, delays import

**Implementation**:
```kotlin
val asset = Asset(
    name = hostname ?: ipAddress,
    type = "Network Host",
    ip = ipAddress,
    owner = currentUser.username,
    description = "Imported from nmap scan on ${scanDate}"
)
```

---

## Decision 4: Multiple Scans Over Time - Same Host (FR-015)

**Question**: How to handle the same host appearing in multiple scans over time?

**Research**:
- Requirements: Track port changes over time, show scan history
- Asset table: Single asset should represent one network host
- Data model options:
  1. Update asset in-place (loses history)
  2. Keep scan results separate (preserves timeline)

**Decision**: **Maintain point-in-time snapshots in separate ScanResult table**

**Rationale**:
- Preserves historical record (security audit requirement)
- Enables trend analysis (port opened/closed over time)
- Aligns with "Scans" page showing scan history
- Asset remains single source of truth for current state
- Relationship: Asset ←1:N→ ScanResult ←1:N→ ScanPort

**Alternatives considered**:
- Update asset ports in-place - Loses historical data, defeats purpose of scan tracking
- Versioned asset copies - Denormalizes data, complex queries
- Archive old scans - Defeats "show port history" requirement

**Data model**:
```
Asset (id, name, type, ip, owner)
  ↓ 1:N
ScanResult (id, asset_id, scan_id, discovered_at, hostname)
  ↓ 1:N
ScanPort (id, scan_result_id, port_number, protocol, state, service)

Scan (id, filename, scan_date, uploaded_by, host_count)
```

**Implementation**:
- On import: Look up asset by IP
- If exists: Create new ScanResult linked to existing Asset
- If new: Create Asset + ScanResult
- Always create new ScanPort records per scan

---

## Decision 5: Future Masscan Support (FR-016)

**Question**: How to design data model to support future masscan imports alongside nmap?

**Research**:
- Masscan XML format: Similar to nmap (hosts, ports) but less verbose (no service detection by default)
- Key differences: Masscan = fast port scanner, Nmap = detailed service/OS detection
- Overlap: Both output IP, port number, port state

**Decision**: **Shared data model with scan_type discriminator**

**Rationale**:
- Core data (IP, port, state) is identical across scanners
- Scan.scan_type field distinguishes source ("nmap" vs "masscan")
- ScanPort can store optional service/version (empty for masscan)
- Avoids table explosion (nmap_scan, masscan_scan, etc.)
- Parser factory pattern routes to correct XML parser

**Alternatives considered**:
- Separate tables per scanner - Denormalizes data, complex queries across scan types
- Store raw XML - Inefficient, can't query port history easily
- Generic "scan" with no type - Loses important metadata

**Implementation**:
```kotlin
@Entity
data class Scan(
    @Id @GeneratedValue var id: Long? = null,
    var scanType: String, // "nmap", "masscan"
    var filename: String,
    var scanDate: LocalDateTime,
    var uploadedBy: String,
    var hostCount: Int,
    var duration: Int? = null  // nmap provides this, masscan may not
)

// Parser factory
fun getScanParser(scanType: String): ScanParser {
    return when (scanType) {
        "nmap" -> NmapXmlParser()
        "masscan" -> MasscanXmlParser()
        else -> throw IllegalArgumentException("Unsupported scan type")
    }
}
```

---

## Decision 6: Processing Timeout for Large Files (NFR-004)

**Question**: What timeout threshold for processing large nmap files (1000+ hosts)?

**Research**:
- Micronaut default HTTP timeout: 30s (configurable)
- Typical nmap file sizes: 1000 hosts ≈ 5-10MB XML, 5000 hosts ≈ 25-50MB
- Processing steps: Parse XML, validate, create assets, create scan results, create ports
- Performance estimate: ~50ms per host (parsing + DB inserts) = 50s for 1000 hosts

**Decision**: **60-second timeout with async processing for large files**

**Rationale**:
- 60s supports up to ~1200 hosts synchronously
- User feedback: "Processing..." spinner during upload
- Async processing (future enhancement): Return 202 Accepted, poll for completion
- Aligns with constitution <200ms for API responses (scan list, not upload)

**Alternatives considered**:
- 30s default - Too aggressive, fails at ~600 hosts
- 120s+ - Poor UX, user thinks it hung
- Always async - Overengineering for v1, adds complexity

**Implementation**:
```yaml
# application.yml
micronaut:
  server:
    multipart:
      max-file-size: 52428800  # 50MB
    read-timeout: 60s

# Controller
@Post("/upload-nmap", consumes = MediaType.MULTIPART_FORM_DATA)
@ExecuteOn(TaskExecutors.IO)  // Don't block event loop
fun uploadNmap(@Part file: CompletedFileUpload): ScanSummary {
    // Process within 60s
}
```

---

## Summary of Decisions

| Item | Decision | Impact |
|------|----------|--------|
| FR-012 | Use IP as asset name when hostname missing | Simple, meets DB constraints |
| FR-013 | Skip duplicate IPs in same scan, log warning | Prevents errors, preserves first-seen data |
| FR-014 | Default asset type = "Network Host" | Generic, user can refine later |
| FR-015 | Point-in-time snapshots in ScanResult table | Preserves history, enables trend analysis |
| FR-016 | Shared model with scan_type discriminator | Supports masscan without table explosion |
| NFR-004 | 60-second timeout for uploads | Balances performance and UX |

All NEEDS CLARIFICATION items resolved. Ready for Phase 1 design.
