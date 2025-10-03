# Feature Specification: Nmap Scan Import and Management

**Feature Branch**: `002-implement-a-parsing`
**Created**: 2025-10-03
**Status**: Draft
**Input**: User description: "implement a parsing logic for nmap files (see nmap.xml in testdata directory), every found host / ip shall be added as an asset. Import logic must be accissble via the Import side bar entry. Open ports per host / IP may be persisted with the scan time. An overview for when which scan (i want to add later masscan imports too) was performed must be accessible via a new Sidebar Entry (Scans), which must only be accessible for users with role ADMIN. In the asset overview i want to have a button "Show open ports" if scan information is available."

## Execution Flow (main)
```
1. Parse user description from Input
   → Feature identified: Nmap XML import with asset auto-creation
2. Extract key concepts from description
   → Actors: Admin users (for Scans page), all users (for asset view)
   → Actions: Upload nmap XML, parse hosts/IPs/ports, create assets, view scans, view port data
   → Data: Nmap XML files, scan metadata, host/IP/port data, timestamps
   → Constraints: Admin-only Scans page, extensible for masscan
3. For each unclear aspect:
   → Marked with [NEEDS CLARIFICATION] below
4. Fill User Scenarios & Testing section
   → User flow: Upload nmap → Assets created → View scans → Check ports
5. Generate Functional Requirements
   → Requirements FR-001 through FR-016 defined
6. Identify Key Entities
   → Scan, ScanResult, ScanPort, and enhanced Asset entity
7. Run Review Checklist
   → 6 [NEEDS CLARIFICATION] markers present for planning phase
8. Return: SUCCESS (spec ready for planning with clarifications)
```

---

## ⚡ Quick Guidelines
- ✅ Focus on WHAT users need and WHY
- ❌ Avoid HOW to implement (no tech stack, APIs, code structure)
- 👥 Written for business stakeholders, not developers

---

## Clarifications

### Session 2025-10-03

- Q: How to name assets when hostname is missing? → A: Use IP address as asset name
- Q: How to handle duplicate IPs in same scan? → A: Skip duplicates with warning log, keep first occurrence
- Q: What default asset type for network devices? → A: "Network Host"
- Q: How to track multiple scans of same host over time? → A: Maintain point-in-time snapshots in separate ScanResult records
- Q: Acceptable processing time for large files (1000+ hosts)? → A: 60 seconds maximum timeout
- Q: Data model approach for future masscan support? → A: Shared model with scanType discriminator field

---

## User Scenarios & Testing *(mandatory)*

### Primary User Story
A security analyst runs an nmap scan against their network infrastructure and saves the results as an XML file. They want to import this scan into secman to automatically populate the asset inventory with discovered hosts and maintain a historical record of which ports were open at the time of each scan. Administrators need to review scan history to track network changes over time, while all users should be able to see port information for individual assets.

### Acceptance Scenarios

1. **Given** a user has a valid nmap XML file, **When** they navigate to the Import page and upload the file, **Then** the system parses all hosts and creates corresponding assets in the asset inventory with IP addresses and hostnames.

2. **Given** an nmap XML file contains port scan data for multiple hosts, **When** the file is imported, **Then** the system stores each host's open port information along with the scan timestamp.

3. **Given** an admin user wants to review scan history, **When** they access the Scans sidebar entry, **Then** they see a chronological list of all imported scans with metadata (scan date, number of hosts discovered, scan source).

4. **Given** a host appears in multiple scans over time, **When** assets are created from imports, **Then** the system associates all scan results with the existing asset rather than creating duplicates.

5. **Given** an asset has associated scan data, **When** a user views the asset in the asset overview, **Then** they see a "Show open ports" button that displays port scan history.

6. **Given** a regular (non-admin) user tries to access the Scans page, **When** they attempt navigation, **Then** access is denied based on insufficient permissions.

### Edge Cases

- **Host with no hostname (only IP)**: System uses the IP address as the asset name (per FR-012).
- **Duplicate IP addresses in same scan**: System skips duplicate IPs with warning log, keeping first occurrence (per FR-013).
- **Empty scan (zero hosts)**: System accepts the upload, creates Scan record with hostCount=0, provides user feedback.
- **Port state differentiation**: System stores all port states (open/filtered/closed) from nmap XML with state field in ScanPort entity.
- **Same scan file uploaded multiple times**: System treats as separate scan events, creating new Scan and ScanResult records (idempotent operation).
- **Multiple hostnames for single IP**: System uses the first hostname found in nmap XML as asset name, stores additional hostnames in description field if needed.
- **Orphaned scan data when asset deleted**: System cascade deletes associated ScanResult and ScanPort records via database foreign key constraints.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST parse nmap XML files to extract host information including IP addresses, hostnames, and port scan results.

- **FR-002**: System MUST automatically create assets in the asset inventory for each discovered host in an nmap scan.

- **FR-003**: System MUST associate each created/updated asset with its originating scan for traceability.

- **FR-004**: System MUST persist open port information for each host along with the scan timestamp.

- **FR-005**: System MUST provide an upload mechanism for nmap XML files accessible via the existing Import sidebar entry.

- **FR-006**: System MUST support file validation to ensure uploaded files are valid nmap XML format.

- **FR-007**: System MUST create a new "Scans" sidebar entry that displays scan history and metadata.

- **FR-008**: System MUST restrict access to the Scans page to users with ADMIN role only.

- **FR-009**: System MUST display scan metadata including scan date, source filename, number of hosts discovered, and scan duration.

- **FR-010**: System MUST provide a "Show open ports" button in the asset overview for assets that have associated scan data.

- **FR-011**: System MUST display port scan history when users click "Show open ports" showing port numbers, states, services, and scan timestamps.

- **FR-012**: System MUST handle cases where a host has only an IP address (no hostname) by using the IP address as the asset name.

- **FR-013**: System MUST handle duplicate hosts within a single scan by skipping duplicate IP addresses with a warning log, keeping the first occurrence.

- **FR-014**: System MUST determine asset type for network hosts using the default type "Network Host" for all assets created from scan imports.

- **FR-015**: System MUST handle multiple scans of the same host over time by maintaining point-in-time snapshots in separate ScanResult records, preserving historical scan data while reusing the same Asset entity.

- **FR-016**: System MUST be designed to support future import types (masscan) by using a shared data model with a scanType discriminator field to distinguish between nmap, masscan, and other scan types.

### Non-Functional Requirements

- **NFR-001**: System MUST validate nmap XML structure before processing to prevent data corruption.

- **NFR-002**: System MUST provide user feedback during file upload showing progress and results summary.

- **NFR-003**: System MUST log all scan import activities for audit purposes.

- **NFR-004**: System MUST handle large nmap files (1000+ hosts) without timeout, with a maximum processing time limit of 60 seconds.

### Key Entities *(include if feature involves data)*

- **Scan**: Represents a network scan import event with metadata including scan timestamp, source filename, number of hosts discovered, scan type (nmap, masscan-future), uploaded by user, upload timestamp.

- **ScanResult**: Represents discovered host data from a scan including associated scan reference, host IP address, hostname(s), scan timestamp for this specific host.

- **ScanPort**: Represents individual port data for a host including port number, protocol (tcp/udp), state (open/filtered/closed), service name, and version information.

- **Asset** (enhanced): Existing asset entity extended with relationship to ScanResult records enabling port history tracking, association with multiple scans over time.

---

## Review & Acceptance Checklist
*GATE: Automated checks run during main() execution*

### Content Quality
- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

### Requirement Completeness
- [x] No [NEEDS CLARIFICATION] markers remain *(all 6 clarifications resolved)*
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

---

## Execution Status
*Updated by main() during processing*

- [x] User description parsed
- [x] Key concepts extracted
- [x] Ambiguities marked
- [x] User scenarios defined
- [x] Requirements generated
- [x] Entities identified
- [x] Review checklist passed *(all clarifications resolved)*

---

## Notes for Planning Phase

All clarifications have been resolved (see Clarifications section above). The planning phase should proceed with:

1. **Asset Naming**: Use IP address as name when hostname missing (Decision 1)
2. **Duplicate Handling**: Skip duplicates with warning, keep first occurrence (Decision 2)
3. **Asset Type**: Use "Network Host" as default type (Decision 3)
4. **Scan History**: Maintain point-in-time snapshots via separate ScanResult records (Decision 4)
5. **Processing Time**: 60 seconds maximum timeout (Decision 5)
6. **Future Extensibility**: Shared model with scanType discriminator (Decision 6)

These decisions inform the data model design, API contracts, and implementation approach.
