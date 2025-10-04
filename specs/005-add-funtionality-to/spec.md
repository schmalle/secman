# Feature Specification: Masscan XML Import

**Feature Branch**: `005-add-funtionality-to`
**Created**: 2025-10-04
**Status**: Draft
**Input**: User description: "add funtionality to import masscan xml output files (see testdata/masscan.xml), make the function available in Import UI, ensure that assets will be created, if not existing yet, ensure the currect time stamps are taken care off (the xml file contains the time stamps), use default values so that creating assets will not fail"

## Execution Flow (main)
```
1. Parse user description from Input
   → Feature requires: Masscan XML import, asset creation, timestamp handling, UI integration
2. Extract key concepts from description
   → Actors: Security analysts, administrators
   → Actions: Upload Masscan XML, parse scan data, create/update assets
   → Data: IP addresses, ports, protocols, scan timestamps
   → Constraints: Use XML timestamps, auto-create assets with defaults
3. For each unclear aspect:
   → Asset owner default: "Security Team" (RESOLVED)
   → Asset type default: "Scanned Host" (RESOLVED)
   → Port state filtering: Only "open" ports (RESOLVED)
   → Duplicate port handling: Keep as separate records for historical tracking (RESOLVED)
4. Fill User Scenarios & Testing section
   → Clear user flow: Upload file → Parse → Create assets → Store port data
5. Generate Functional Requirements
   → Each requirement is testable
6. Identify Key Entities
   → Asset, ScanResult
7. Run Review Checklist
   → Multiple [NEEDS CLARIFICATION] items exist
8. Return: WARN "Spec has uncertainties"
```

---

## ⚡ Quick Guidelines
- ✅ Focus on WHAT users need and WHY
- ❌ Avoid HOW to implement (no tech stack, APIs, code structure)
- 👥 Written for business stakeholders, not developers

---

## Clarifications

### Session 2025-10-04
- Q: What default value should be used for the asset owner field when auto-creating assets from Masscan scans? → A: "Security Team" (matches vulnerability import pattern)
- Q: What default value should be used for the asset type field when auto-creating assets from Masscan scans? → A: "Scanned Host" (indicates automated discovery source)
- Q: Should the system import only "open" ports from Masscan XML, or should it import ports with all states (open, closed, filtered)? → A: Only "open" ports (focus on accessible services for security assessment)
- Q: When the same port appears multiple times for the same IP in the XML (e.g., rescans), should these be merged or kept as separate records? → A: Keep as separate records (historical tracking like Nmap import)
- Q: When only an IP address is available (no hostname), how should the asset name field be populated? → A: Leave name empty/null (IP stored in separate field)

---

## User Scenarios & Testing

### Primary User Story
As a security analyst, I want to import Masscan XML scan results so that I can automatically populate the asset inventory with discovered hosts and open ports without manual data entry.

### Acceptance Scenarios
1. **Given** a valid Masscan XML file, **When** I upload it through the Import UI, **Then** the system creates new assets for each discovered IP address
2. **Given** a Masscan XML file with timestamps, **When** the file is processed, **Then** the scan discovery timestamps from the XML are preserved in the system
3. **Given** a Masscan XML file containing an IP that already exists in the asset inventory, **When** the file is imported, **Then** the existing asset is updated with new port information
4. **Given** a Masscan XML file with multiple ports for the same IP, **When** the file is imported, **Then** all discovered ports are stored as separate scan results for historical tracking
5. **Given** an invalid or malformed XML file, **When** I attempt to upload it, **Then** the system rejects the file with a clear error message

### Edge Cases
- What happens when the Masscan XML contains no open ports (only closed or filtered)?
- How does the system handle missing or invalid timestamp values in the XML?
- What happens if the XML file is extremely large (thousands of hosts)?
- When only an IP address is available (no hostname in Masscan XML), the asset name field is left empty/null while the IP is stored in the dedicated IP address field
- When the same port appears multiple times for the same IP (e.g., within one scan or across multiple imports), each occurrence is stored as a separate scan result with its own timestamp for historical tracking

## Requirements

### Functional Requirements
- **FR-001**: System MUST accept Masscan XML files through the Import UI
- **FR-002**: System MUST parse Masscan XML format including nmaprun, host, address, ports, and port elements
- **FR-003**: System MUST extract IP addresses from the address elements with addrtype="ipv4"
- **FR-004**: System MUST extract port information including protocol, portid, and state
- **FR-005**: System MUST extract scan timestamps from the endtime attribute and convert to readable format
- **FR-006**: System MUST automatically create new assets when an IP address is encountered that doesn't exist in the inventory
- **FR-007**: System MUST use default values for asset creation to prevent failures (owner: "Security Team"; type: "Scanned Host"; name: empty/null when no hostname available; description: [NEEDS CLARIFICATION: default for description?])
- **FR-008**: System MUST update existing assets when an IP address already exists in the inventory
- **FR-009**: System MUST store port scan results with their discovery timestamps
- **FR-009a**: System MUST keep duplicate port discoveries as separate historical records (no merging or deduplication)
- **FR-010**: System MUST validate XML file format before processing
- **FR-011**: System MUST provide feedback on import results (number of assets created, updated, ports imported)
- **FR-012**: System MUST handle errors gracefully and continue processing valid entries even if some entries fail
- **FR-013**: System MUST import only ports with state="open" and skip ports with other states (closed, filtered)
- **FR-014**: System MUST handle IPv4 addresses [NEEDS CLARIFICATION: Should IPv6 addresses be supported if present in the XML?]

### Key Entities

- **Asset**: Represents a network host discovered during scanning
  - Identified by IP address (required, stored in dedicated field)
  - Has name (hostname when available, otherwise empty/null), type, owner, description
  - Has last seen timestamp
  - Related to scan results

- **ScanResult**: Represents a discovered port on an asset
  - Links to an Asset
  - Contains port number, protocol, state
  - Contains service, product, version information (if available)
  - Has discovery timestamp
  - Masscan provides: port, protocol, state; service/product/version may be empty

---

## Review & Acceptance Checklist

### Content Quality
- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

### Requirement Completeness
- [ ] No [NEEDS CLARIFICATION] markers remain (2 low-priority items: description default, IPv6 support)
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified
- [x] Core ambiguities resolved (asset defaults, port filtering, duplicate handling, naming)

---

## Execution Status

- [x] User description parsed
- [x] Key concepts extracted
- [x] Ambiguities marked (4 items initially, 5 clarified)
- [x] User scenarios defined
- [x] Requirements generated
- [x] Entities identified
- [x] Major clarifications resolved (5/5 questions answered)
- [ ] Review checklist passed (minor clarifications remain: description default, IPv6 support)

---

## Dependencies and Assumptions

### Dependencies
- Existing Import UI infrastructure (same as used for Nmap XML and Vulnerability Excel imports)
- Existing Asset and ScanResult entities
- File upload validation mechanism

### Assumptions
- Masscan XML format follows the structure shown in testdata/masscan.xml
- System already has a pattern for XML parsing (from Nmap import feature 002)
- Import process is similar to existing Nmap XML import but with Masscan-specific format differences
- Masscan provides less detailed information than Nmap (no service/version detection typically)
