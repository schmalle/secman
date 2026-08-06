# Feature Specification: CrowdStrike Import Cleanup

**Feature Branch**: `053-crowdstrike-import-cleanup`
**Created**: 2025-12-08
**Status**: Draft (Bug Investigation)
**Input**: User description: "if i do an import from crowdstrike data and e.g. import data for servers x,y,z i want to ensure, that the old findings / vulnerabilities for this servers will be deleted before the import"

## Observed Problem

**Symptom**: Domain vulnerability view shows massively inflated numbers, indicating duplicate vulnerabilities are accumulating instead of being replaced during CrowdStrike imports.

**Affected Import Path**: CLI scheduled import (CrowdStrike API polling) - the automated vulnerability sync from CrowdStrike Falcon.

**Expected Behavior**: Feature 048's transactional replace pattern should delete existing vulnerabilities for a server before inserting new ones, preventing duplicates.

**Investigation Goal**: Identify why the delete-before-insert pattern is not working correctly in the CLI import path and fix the root cause.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Clean Import for Multiple Servers (Priority: P1)

As a security administrator, when I import CrowdStrike vulnerability data for a set of servers (e.g., servers X, Y, Z), I want the system to automatically delete all existing vulnerabilities for those specific servers before importing the new data, so that I always have an accurate and current view of vulnerabilities without duplicates or stale findings.

**Why this priority**: This is the core functionality requested. Without this, users would accumulate outdated vulnerability records, leading to inaccurate security posture assessments and confusion about which vulnerabilities are still active.

**Independent Test**: Can be fully tested by importing vulnerability data for a known server, then re-importing updated data for the same server and verifying only the new vulnerabilities exist.

**Acceptance Scenarios**:

1. **Given** server X has 10 existing vulnerabilities in the system, **When** I import new CrowdStrike data containing 5 vulnerabilities for server X, **Then** server X should have exactly 5 vulnerabilities (the newly imported ones) and the 10 old vulnerabilities should be removed.

2. **Given** servers X, Y, Z each have existing vulnerabilities, **When** I import CrowdStrike data that includes updated vulnerability data for all three servers, **Then** each server should only contain the vulnerabilities from the new import.

3. **Given** server X has existing vulnerabilities and server W has existing vulnerabilities, **When** I import CrowdStrike data for server X only, **Then** server X's old vulnerabilities are removed and replaced with new ones, while server W's vulnerabilities remain unchanged.

---

### User Story 2 - Remediation Tracking Continuity (Priority: P2)

As a security administrator, I want the cleanup process to maintain the ability to track remediation progress over time, so that I can identify which vulnerabilities were remediated between import cycles.

**Why this priority**: While accurate current state is most important, being able to understand remediation trends and progress is valuable for reporting and demonstrating security improvements.

**Independent Test**: Can be tested by comparing import results over multiple cycles and verifying remediation metrics are calculable.

**Acceptance Scenarios**:

1. **Given** server X had CVE-2024-001 in the previous import, **When** a new import for server X does not include CVE-2024-001, **Then** the system correctly reflects that this vulnerability was remediated (no longer present).

2. **Given** server X had 10 vulnerabilities in the previous import, **When** a new import shows 7 vulnerabilities, **Then** the delta (3 remediated) should be identifiable through the system's data.

---

### User Story 3 - Import Atomicity (Priority: P3)

As a security administrator, I want the delete-and-import operation to be atomic (all-or-nothing), so that if the import fails partway through, I don't end up with missing data.

**Why this priority**: Data integrity is important, but this is an edge case that occurs less frequently than normal import operations.

**Independent Test**: Can be tested by simulating an import failure and verifying no data was lost.

**Acceptance Scenarios**:

1. **Given** server X has 10 existing vulnerabilities, **When** an import for server X fails after deletion but before new data is fully imported, **Then** the system should rollback and server X should still have its original 10 vulnerabilities.

2. **Given** a batch import for servers X, Y, Z, **When** the import succeeds for X and Y but fails for Z, **Then** X and Y should have their new vulnerabilities, and Z should retain its original vulnerabilities (transaction isolation per server).

---

### Edge Cases

- What happens when importing data for a server that has no existing vulnerabilities? The import should proceed normally, simply adding the new vulnerabilities.
- What happens when importing empty vulnerability data for a server (server exists but has zero vulnerabilities in the new data)? All existing vulnerabilities for that server should be deleted, leaving the server with zero vulnerabilities.
- How does the system handle a server that doesn't exist in the system yet? The server (asset) should be created and vulnerabilities imported normally.
- What happens if the same vulnerability (same CVE) appears multiple times in the import data for the same server? The system should handle deduplication appropriately.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST delete all existing vulnerabilities for a server before importing new vulnerability data for that server from CrowdStrike.
- **FR-002**: System MUST only delete vulnerabilities for servers that are included in the current import batch, leaving other servers' data unchanged.
- **FR-003**: System MUST perform the delete-and-import operation atomically per server (either all changes commit or none do).
- **FR-004**: System MUST support importing data for multiple servers in a single batch operation.
- **FR-005**: System MUST create new server assets automatically if they don't exist when importing vulnerability data for them.
- **FR-006**: System MUST handle the case where new import data contains zero vulnerabilities for a server (resulting in deletion of all existing vulnerabilities with no new ones added).
- **FR-007**: System MUST log the import operation including the number of vulnerabilities deleted and imported per server for audit purposes.

### Key Entities

- **Asset (Server)**: Represents a server/host that can have vulnerabilities. Key attributes: hostname, IP address, cloud account, domain. Acts as the parent entity for vulnerabilities.
- **Vulnerability**: Represents a security vulnerability finding. Key attributes: CVE identifier, severity/criticality, detection date, affected asset. Vulnerabilities are scoped to a specific asset.
- **Import Batch**: Represents a single CrowdStrike data import operation. Contains vulnerability data for one or more servers/assets.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After any CrowdStrike import operation, each affected server contains exactly the vulnerabilities from the latest import with zero duplicates or stale entries.
- **SC-002**: Servers not included in an import batch retain 100% of their existing vulnerability data unchanged.
- **SC-003**: Import operations complete successfully or roll back completely with no partial state visible to users.
- **SC-004**: Security administrators can determine vulnerability remediation progress by comparing vulnerability counts between import cycles.
- **SC-005**: All import operations are logged with sufficient detail to reconstruct what was deleted and imported for compliance and troubleshooting.

## Clarifications

### Session 2025-12-08

- Q: Is this feature about applying the existing cleanup pattern to additional import paths, or a request for new behavior? → A: Verify existing pattern works (bug investigation)
- Q: What symptom are you seeing that suggests the cleanup pattern isn't working correctly? → A: Massive/inflated numbers in domain vulnerability view (duplicates accumulating)
- Q: Which CrowdStrike import method are you primarily using when you see the duplicates? → A: CLI scheduled import (CrowdStrike API polling)

## Assumptions

- The existing CrowdStrike import mechanism already identifies servers by a unique identifier (hostname or similar) that can be used to match against existing assets.
- **Feature 048 already implemented the transactional replace pattern** - this feature is a verification/bug investigation to ensure the pattern is working correctly across all CrowdStrike import paths.
- Vulnerabilities are uniquely associated with a single asset/server (not shared across multiple assets).
- The import process has sufficient permissions to delete and create vulnerability records.
- Users performing imports have appropriate authorization (e.g., ADMIN or VULN role).
