# Feature Specification: Prevent Duplicate Vulnerabilities in CrowdStrike Import

**Feature Branch**: `048-prevent-duplicate-vulnerabilities`
**Created**: 2025-11-16
**Status**: Draft
**Input**: User description: "extend the existing crowdstrike API import code, so that no duplicates are created, when importing results from API calls. Meaning, if i have already CVE x on system a identitied, dont create a 2nd entry for it when importing."

## User Scenarios & Testing

### User Story 1 - Prevent Duplicate Vulnerability Entries (Priority: P1)

When security teams run CrowdStrike vulnerability imports multiple times (e.g., scheduled daily imports), they need the system to avoid creating duplicate vulnerability records for the same CVE on the same asset. Currently, the system uses a "delete all + insert new" pattern which works correctly, but we need to verify and document this behavior to ensure it prevents duplicates.

**Why this priority**: This is the core requirement - preventing duplicate vulnerability records is essential for data integrity and accurate vulnerability tracking. Without this, security teams cannot trust their vulnerability counts and reporting.

**Independent Test**: Can be fully tested by running an import twice with the same vulnerability data and verifying that only one vulnerability record exists per CVE per asset.

**Acceptance Scenarios**:

1. **Given** an asset exists with 3 vulnerabilities (CVE-2023-0001, CVE-2023-0002, CVE-2023-0003), **When** a new import runs with the same 3 vulnerabilities plus 2 new ones (CVE-2023-0004, CVE-2023-0005), **Then** the asset should have exactly 5 vulnerabilities total (the original 3 replaced + 2 new), not 8 vulnerabilities
2. **Given** no previous vulnerabilities exist for an asset, **When** an import runs with 5 vulnerabilities, **Then** exactly 5 vulnerability records are created
3. **Given** an asset has 10 vulnerabilities, **When** an import runs with 8 vulnerabilities (2 were remediated), **Then** the asset should have exactly 8 vulnerabilities (old ones removed, new state imported)
4. **Given** two different assets (System A and System B) both have CVE-2023-0001, **When** an import runs, **Then** each asset maintains its own vulnerability record for CVE-2023-0001 (duplicates are prevented per asset, not globally)

---

### User Story 2 - Import Performance with Large Datasets (Priority: P2)

When security teams import large vulnerability datasets (e.g., 94,947 vulnerabilities across 857 servers), the duplicate prevention mechanism must complete within reasonable time limits without degrading system performance.

**Why this priority**: While preventing duplicates is critical, the solution must scale to handle enterprise-sized datasets efficiently. Poor performance would block adoption.

**Independent Test**: Can be tested by importing a large dataset (1000+ assets with 10,000+ vulnerabilities) and measuring import completion time and database load.

**Acceptance Scenarios**:

1. **Given** 1,000 assets with an average of 10 vulnerabilities each, **When** an import runs, **Then** the import completes within 5 minutes
2. **Given** a previous import has completed, **When** a subsequent import runs with mostly unchanged data, **Then** the import completes within similar time as the first import (no performance degradation)
3. **Given** an import is running, **When** database queries are monitored, **Then** no excessive locking or deadlocks occur

---

### User Story 3 - Idempotent Import Operations (Priority: P2)

When automated systems or scheduled jobs run CrowdStrike imports repeatedly, the system must produce identical results regardless of how many times the import runs with the same data, ensuring predictable and reliable behavior.

**Why this priority**: Idempotency is crucial for automated systems and scheduled jobs. It provides confidence that running an import multiple times won't corrupt data or create inconsistent states.

**Independent Test**: Can be tested by running the same import 5 times in succession and verifying that the database state after import 1 is identical to the state after imports 2-5.

**Acceptance Scenarios**:

1. **Given** an import dataset with 100 vulnerabilities across 10 assets, **When** the import runs 3 times consecutively with identical data, **Then** each import reports the same statistics (100 imported, 0 skipped) and the database contains exactly 100 vulnerability records
2. **Given** an import has run successfully, **When** the same import runs again within 1 minute, **Then** the second import replaces the existing data with identical data and reports 100 imported, 0 skipped
3. **Given** an import fails midway through processing, **When** the import runs again, **Then** the system recovers and completes successfully without leaving partial or duplicate data

---

### Edge Cases

- What happens when an import runs while another import is still in progress for the same asset? (Transaction isolation should handle this)
- How does the system handle a CVE ID that changes format or length between imports? (Database constraints should prevent invalid data)
- What happens if the same asset hostname appears multiple times in a single import batch? (Should process sequentially with the last entry winning)
- How does the system handle vulnerabilities without CVE IDs? (Current logic skips them - should continue this behavior)
- What happens if an asset is renamed between imports? (Creates a new asset - existing behavior is acceptable)
- How does the system handle concurrent imports for different assets? (Should work in parallel without conflicts)
- What happens if network interruption occurs during vulnerability delete operation but before insert? (Transaction rollback should prevent data loss)

## Requirements

### Functional Requirements

- **FR-001**: System MUST prevent duplicate vulnerability records for the same CVE on the same asset across multiple import runs
- **FR-002**: System MUST use the existing transactional replace pattern (delete existing vulnerabilities for an asset before inserting new ones) to ensure data consistency
- **FR-003**: System MUST treat each asset's vulnerabilities independently (CVE-2023-0001 on Asset A is separate from CVE-2023-0001 on Asset B)
- **FR-004**: System MUST complete the duplicate prevention logic within a single database transaction per asset to ensure atomicity
- **FR-005**: System MUST maintain idempotent behavior - running the same import multiple times with identical data must produce identical database states
- **FR-006**: System MUST handle concurrent imports for different assets without creating duplicates or data corruption
- **FR-007**: System MUST skip vulnerabilities without CVE IDs during import (existing behavior to maintain)
- **FR-008**: System MUST preserve the batch processing pattern (chunking servers to avoid 413 errors) while preventing duplicates
- **FR-009**: System MUST log duplicate prevention actions at DEBUG level for troubleshooting without excessive logging

### Key Entities

- **Vulnerability**: Represents a security vulnerability associated with an asset. Key attributes include the asset reference (foreign key), CVE ID, severity, affected products, days open, scan timestamp, and patch publication date. Each vulnerability is uniquely identified by the combination of asset and CVE ID.
- **Asset**: Represents a server or system being monitored. Key attributes include hostname (name), type, IP address, cloud metadata, AD domain, OS version, and last seen timestamp. Assets can have multiple vulnerabilities in a one-to-many relationship.

### Current Implementation Analysis

Based on the code review of `CrowdStrikeVulnerabilityImportService.kt`:

- The system already implements a "transactional replace" pattern at line 168-172: `vulnerabilityRepository.deleteByAssetId(asset.id!!)` deletes all existing vulnerabilities for the asset before inserting new ones
- Each asset is processed in its own transaction (line 161: `@Transactional` on `importVulnerabilitiesForServer()`)
- The batch processing pattern (50 servers per batch by default) is implemented at the CLI layer in `VulnerabilityStorageService.kt` lines 248-296
- Vulnerabilities without CVE IDs are filtered out at line 175: `batch.vulnerabilities.filter { !it.cveId.isNullOrBlank() }`

This means the current implementation already prevents duplicates through the delete-then-insert pattern. The feature request is already satisfied by the existing implementation.

### What Needs to be Done

Since the duplicate prevention mechanism already exists and works correctly, this feature focuses on:

1. **Verification**: Confirm the existing implementation correctly prevents duplicates in all scenarios
2. **Testing**: Add explicit test coverage to verify duplicate prevention behavior
3. **Documentation**: Document the duplicate prevention strategy for future maintainers
4. **Edge Case Handling**: Ensure edge cases (concurrent imports, failed transactions, etc.) are properly handled

## Success Criteria

### Measurable Outcomes

- **SC-001**: When the same vulnerability dataset is imported twice consecutively, the database contains exactly the same number of vulnerability records as after the first import (no duplicates created)
- **SC-002**: When an asset with 10 existing vulnerabilities receives an import with 12 vulnerabilities (8 overlapping, 4 new), the asset ends with exactly 12 vulnerability records
- **SC-003**: Import operations complete within 5 minutes for datasets containing 1,000 assets with 10,000 total vulnerabilities
- **SC-004**: Running the same import 5 times produces identical database states and import statistics each time
- **SC-005**: Concurrent imports for different assets complete successfully without creating duplicate vulnerabilities or transaction conflicts
- **SC-006**: Zero data integrity violations occur during import operations (no orphaned records, no partial transactions)
- **SC-007**: Security teams can confidently schedule automated imports without concern for duplicate vulnerability records accumulating over time
