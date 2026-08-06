# Research: Masscan XML Import

**Feature**: 005-add-funtionality-to
**Date**: 2025-10-04
**Status**: Complete

## Research Questions & Decisions

### 1. Masscan XML Format vs Nmap XML

**Question**: How does Masscan XML format differ from Nmap XML format that we already parse?

**Research Findings**:
- Masscan uses the same root element `<nmaprun>` but with `scanner="masscan"` attribute
- Masscan XML is a simplified subset of Nmap XML format
- Key differences:
  - **No hostname elements**: Masscan doesn't do reverse DNS lookups
  - **No service/version detection**: Masscan only does SYN scans, no service fingerprinting
  - **Different timestamp location**: Uses `endtime` attribute on `<host>` element instead of `start` on root
  - **No OS detection**: Masscan focuses on port discovery only
  - **Simpler port state**: Only includes state, reason, and TTL (no detailed service info)

**Example Masscan XML Structure**:
```xml
<nmaprun scanner="masscan" start="1759560572" version="1.0-BETA">
  <scaninfo type="syn" protocol="tcp" />
  <host endtime="1759560572">
    <address addr="193.99.144.85" addrtype="ipv4"/>
    <ports>
      <port protocol="tcp" portid="80">
        <state state="open" reason="syn-ack" reason_ttl="249"/>
      </port>
    </ports>
  </host>
  <runstats>
    <finished time="1759560583" timestr="2025-10-04 08:49:43" elapsed="11" />
  </runstats>
</nmaprun>
```

**Decision**: Reuse XML parsing infrastructure from NmapParserService, create MasscanParserService with Masscan-specific extraction logic

**Rationale**:
- Avoid duplicating XML security measures (XXE prevention)
- Leverage proven parsing patterns
- Simplify maintenance (both parsers follow same structure)

**Alternatives Considered**:
- **Alternative 1**: Extend NmapParserService to handle both formats
  - Rejected: Would complicate Nmap parser with format detection logic, violates single responsibility
- **Alternative 2**: Generic scan result parser
  - Rejected: Over-engineering for just 2 formats, harder to understand

### 2. Timestamp Handling in Masscan XML

**Question**: How should we extract and convert Masscan timestamps?

**Research Findings**:
- Masscan provides timestamps in Unix epoch seconds
- Location varies: `start` on root (scan start), `endtime` on host (when host was scanned), `time` on finished (scan end)
- For port discovery timestamp, use host's `endtime` attribute (most accurate for when that specific host was scanned)
- Conversion pattern: `LocalDateTime.ofInstant(Instant.ofEpochSecond(endtime), ZoneId.systemDefault())`

**Decision**: Extract `endtime` from `<host>` element and convert to LocalDateTime for ScanResult.discoveredAt

**Rationale**:
- Most accurate timestamp for port discovery
- Consistent with feature requirement: "ensure the correct time stamps are taken care off (the xml file contains the time stamps)"
- Matches ScanResult entity field `discoveredAt: LocalDateTime`

**Alternatives Considered**:
- **Alternative 1**: Use scan start time for all ports
  - Rejected: Less accurate, loses per-host timing information
- **Alternative 2**: Use finished time
  - Rejected: Only tells when scan ended, not when each host was discovered

### 3. Asset Creation with Default Values

**Question**: What default values should be used when auto-creating assets to prevent failures?

**Research Findings from Specification Clarifications**:
- **owner**: "Security Team" (matches vulnerability import pattern from Feature 003)
- **type**: "Scanned Host" (indicates automated discovery source)
- **name**: `null` when no hostname available (IP stored in separate field)
- **description**: `""` (empty string, non-nullable field based on Asset entity)

**Decision**: Use clarified default values for automatic asset creation

**Rationale**:
- Aligns with existing vulnerability import (Feature 003) for consistency
- Prevents constraint violations (all required fields populated)
- "Scanned Host" type distinguishes automated discovery from manual entries
- Null name is acceptable since IP is the primary identifier

**Implementation Pattern**:
```kotlin
// Lookup existing asset by IP
val existingAsset = assetRepository.findByIp(ipAddress).orElse(null)

if (existingAsset == null) {
    // Create new asset with defaults
    val newAsset = Asset(
        name = null,  // No hostname from Masscan
        ip = ipAddress,
        type = "Scanned Host",
        owner = "Security Team",
        description = "",
        lastSeen = scanTimestamp
    )
    assetRepository.save(newAsset)
} else {
    // Update last seen
    existingAsset.lastSeen = scanTimestamp
    assetRepository.save(existingAsset)
}
```

### 4. Port State Filtering

**Question**: Should we import all port states (open, closed, filtered) or only specific ones?

**Research Findings from Specification Clarifications**:
- Masscan provides state attribute on port elements: "open", "closed", "filtered"
- Clarification answer: "Only 'open' ports (focus on accessible services for security assessment)"
- Aligns with security assessment use case (only accessible services matter)

**Decision**: Filter ports to import only those with `state="open"`

**Rationale**:
- Reduces noise (closed ports not relevant for vulnerability assessment)
- Aligns with feature purpose: identify accessible attack surface
- Matches security analyst workflow (scan for open ports)

**Implementation**:
```kotlin
// In extractPorts()
val state = portElement.getElementsByTagName("state")
    .item(0) as Element
if (state.getAttribute("state") != "open") {
    logger.debug("Skipping port {} with state: {}", portNumber, state)
    continue  // Skip non-open ports
}
```

**Alternatives Considered**:
- **Alternative 1**: Import all states
  - Rejected: Creates unnecessary data, not useful for security analysis
- **Alternative 2**: Configurable filter
  - Rejected: Over-engineering, requirement is clear

### 5. Historical Tracking vs Deduplication

**Question**: Should duplicate port discoveries be merged or kept as separate records?

**Research Findings from Specification Clarifications**:
- Clarification answer: "Keep as separate records (historical tracking like Nmap import)"
- Matches Feature 002 (Nmap) behavior: each scan creates new ScanResult entries
- Enables audit trail: see when ports were discovered across multiple scans
- ScanResult has `discoveredAt` timestamp for this purpose

**Decision**: Create new ScanResult for each port discovery, even if same IP/port combination exists

**Rationale**:
- Provides historical scan data (valuable for change detection)
- Consistent with Nmap import (Feature 002) pattern
- Supports use case: "track when services appear/disappear"
- Database handles duplicates (no unique constraint on IP+port)

**Implementation**:
```kotlin
// Always create new ScanResult, never update existing
for (port in host.ports) {
    val scanResult = ScanResult(
        asset = asset,
        port = port.portNumber,
        protocol = port.protocol,
        state = port.state,
        service = null,  // Masscan doesn't provide
        product = null,  // Masscan doesn't provide
        version = null,  // Masscan doesn't provide
        discoveredAt = host.timestamp
    )
    scanResultRepository.save(scanResult)
}
```

**Alternatives Considered**:
- **Alternative 1**: Update existing port records
  - Rejected: Loses historical data, violates clarification
- **Alternative 2**: Deduplicate within same import
  - Rejected: Masscan may emit duplicate entries per design, keep for accuracy

### 6. File Validation Requirements

**Question**: What validation is needed for Masscan XML files?

**Research Findings from Existing Import Patterns**:
- ImportController already validates: file size (<10MB), extension (.xml), content type, non-empty
- XML parsing requires: XXE attack prevention, malformed XML handling, encoding detection
- Masscan-specific: validate root element is `<nmaprun>` with `scanner="masscan"` attribute

**Decision**: Reuse existing file validation, add Masscan-specific XML validation

**Rationale**:
- Leverage proven security measures from Nmap import
- Fail fast with clear error messages
- Prevent processing of non-Masscan XML files

**Implementation**:
```kotlin
// In MasscanParserService
private fun validateMasscanXml(document: Document) {
    val root = document.documentElement
    if (root.nodeName != "nmaprun") {
        throw MasscanParseException("Invalid root element: ${root.nodeName}")
    }

    val scanner = root.getAttribute("scanner")
    if (scanner != "masscan") {
        throw MasscanParseException("Not a Masscan XML file (scanner=$scanner)")
    }
}
```

### 7. Error Handling Strategy

**Question**: How should parsing errors be handled during import?

**Research Findings from Existing Patterns**:
- ImportController pattern: continue processing on row/entry failures, report counts
- NmapParserService: skip invalid hosts, log warnings, continue with valid data
- ImportResponse includes: assetsCreated, assetsUpdated, portsImported (success metrics)

**Decision**: Continue processing on individual host/port failures, report success/failure counts

**Rationale**:
- Maximizes data import success (don't fail entire file for one bad entry)
- Provides visibility (counts show what succeeded)
- Matches established pattern across all import features

**Implementation**:
```kotlin
// In import service
var assetsCreated = 0
var portsImported = 0

for (host in scanData.hosts) {
    try {
        val asset = findOrCreateAsset(host.ipAddress, host.timestamp)
        if (asset.id == null) assetsCreated++

        for (port in host.ports) {
            try {
                createScanResult(asset, port, host.timestamp)
                portsImported++
            } catch (e: Exception) {
                logger.warn("Failed to import port: ${e.message}")
            }
        }
    } catch (e: Exception) {
        logger.warn("Failed to process host: ${e.message}")
    }
}

return ImportResponse(
    message = "Imported $portsImported ports across $assetsCreated new assets",
    assetsCreated = assetsCreated,
    portsImported = portsImported
)
```

## Summary of Decisions

| Area | Decision | Rationale |
|------|----------|-----------|
| **XML Parsing** | Dedicated MasscanParserService, reuse security measures | Separation of concerns, proven security |
| **Timestamps** | Extract endtime from host element | Most accurate for port discovery time |
| **Asset Defaults** | owner="Security Team", type="Scanned Host", name=null, description="" | Clarified in spec, consistent with Feature 003 |
| **Port Filtering** | Import only state="open" ports | Security assessment focus, clarified in spec |
| **Deduplication** | Keep all port discoveries as separate records | Historical tracking, clarified in spec |
| **Validation** | File validation + Masscan-specific XML validation | Security-first, fail fast with clear errors |
| **Error Handling** | Continue on failures, report counts | Maximize import success, visibility |

## Architecture Patterns Applied

1. **Parser Service Pattern** (from NmapParserService):
   - XML parsing with XXE prevention
   - Data class results (MasscanScanData, MasscanHost, MasscanPort)
   - Exception types (MasscanParseException)
   - Extraction methods (extractHosts, extractPorts, etc.)

2. **Import Controller Pattern** (from existing imports):
   - Multipart file upload endpoint
   - File validation before processing
   - Transactional processing
   - Standardized response format

3. **Asset Management Pattern** (from Feature 003):
   - Find-or-create by IP address
   - Default values for auto-creation
   - Update lastSeen on existing assets

4. **Historical Tracking Pattern** (from Feature 002):
   - Create ScanResult per port/scan
   - Timestamp each discovery
   - No deduplication

## Dependencies & Prerequisites

**Existing Components to Reuse**:
- `Asset` entity (domain model)
- `ScanResult` entity (domain model)
- `AssetRepository.findByIp()` method
- `ScanResultRepository.save()` method
- `ImportController` file validation logic
- XML parsing security configuration (XXE prevention)

**New Components to Create**:
- `MasscanParserService` (parser logic)
- `ImportController.uploadMasscanXml()` endpoint
- Test files (contract, unit, integration, E2E)

**No Schema Changes Required**: Reuses existing Asset and ScanResult entities

---
*Research complete - ready for Phase 1: Design & Contracts*
