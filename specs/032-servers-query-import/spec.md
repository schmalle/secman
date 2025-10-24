# Feature Specification: Servers Query Import

**Feature Branch**: `032-servers-query-import`
**Created**: 2025-10-21
**Status**: Draft
**Input**: User description: "add a query option called servers, which ensures, that the crowdstrike API is called and all HIGH and CRITICAL vulnerabilities, which are longer open than 30 days on servers are queried. Then ensure that all recieved vulnerabilities are being stored in secman same as the assets, if not already existing. If vulnerabilities for the current server are already existing, remove the old vulnerabilities and add only the new vulnerabilities. Please also provide a statistic how many servers and how many vulnerabilities have been imported."

## Clarifications

### Session 2025-10-21

- Q: Should the `secman query servers` command query ALL servers in the entire CrowdStrike tenant, or should it allow targeting specific servers? → A: Support both: query all servers by default, but allow optional hostname filtering via `--hostnames` flag
- Q: When replacing vulnerabilities for a server (delete old + import new), should this operation be transactional per server to prevent inconsistent state if the import fails mid-operation? → A: Yes - Use database transaction per server (atomic delete+import)
- Q: Should this feature create a new backend API endpoint `/api/crowdstrike/vulnerabilities/save`, or use an existing endpoint? → A: Create new endpoint `/api/crowdstrike/vulnerabilities/save` as part of this feature
- Q: When CrowdStrike API returns HTTP 429 (rate limit exceeded), what should the CLI do? → A: Retry with exponential backoff (wait 1s, 2s, 4s, 8s up to 3 retries)
- Q: Should vulnerabilities without a CVE identifier be imported or skipped? → A: Skip vulnerabilities without CVE IDs (log warning, exclude from import)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Query Server Vulnerabilities with Auto-Import (Priority: P1)

A security analyst wants to query CrowdStrike for all HIGH and CRITICAL severity vulnerabilities on servers that have been open for more than 30 days, and automatically import both the servers and vulnerabilities into secman for tracking and remediation planning.

**Why this priority**: This is the core MVP functionality that delivers immediate value by automating the vulnerability discovery and import process for critical server vulnerabilities.

**Independent Test**: Can be fully tested by running `secman query servers` and verifying that both servers and HIGH/CRITICAL vulnerabilities (>30 days open) are created in the database, with import statistics displayed to the user.

**Acceptance Scenarios**:

1. **Given** CrowdStrike API credentials are configured, **When** user runs `secman query servers`, **Then** system queries CrowdStrike API for ALL servers filtering for device type SERVER, severity HIGH|CRITICAL, and vulnerabilities open >30 days
2. **Given** user runs `secman query servers --hostnames prod-web-01,prod-web-02`, **When** query executes, **Then** system queries only the specified hostnames (not all servers)
3. **Given** CrowdStrike returns 5 unique servers with 25 HIGH/CRITICAL vulnerabilities, **When** import completes, **Then** system displays "Imported: 5 servers, 25 vulnerabilities"
4. **Given** servers do not exist in secman database, **When** vulnerabilities are imported, **Then** system automatically creates Asset records for each server with type="SERVER"
5. **Given** a server already exists in secman database, **When** vulnerabilities are imported, **Then** system reuses the existing Asset record without creating duplicates

---

### User Story 2 - Replace Existing Vulnerabilities (Priority: P1)

A security analyst runs the servers query import daily to keep vulnerability data up-to-date. The system should replace old vulnerability records with current data from CrowdStrike to maintain data freshness.

**Why this priority**: Critical for maintaining accurate vulnerability state - without this, the database would accumulate stale vulnerability records and cause confusion about current risk exposure.

**Independent Test**: Can be tested independently by importing vulnerabilities for a server, then re-running the import with different vulnerability data, and verifying that old records are deleted and replaced with new records.

**Acceptance Scenarios**:

1. **Given** server "prod-web-01" has 10 existing vulnerability records from yesterday, **When** user runs `secman query servers` today, **Then** system deletes all 10 old vulnerability records before importing new ones
2. **Given** CrowdStrike returns 15 current vulnerabilities for a server, **When** import completes, **Then** server has exactly 15 vulnerability records (old records removed, new records added)
3. **Given** a vulnerability (CVE-2024-1234) existed yesterday but is not in today's CrowdStrike response, **When** import completes, **Then** the old CVE-2024-1234 record is deleted (not retained)
4. **Given** multiple servers are imported in a single query, **When** vulnerabilities are replaced, **Then** each server's vulnerabilities are replaced independently without affecting other servers' data

---

### User Story 3 - Filter by Device Type, Severity, and Days Open (Priority: P1)

The system must apply precise filters when querying CrowdStrike to ensure only relevant vulnerabilities are imported: device type must be SERVER, severity must be HIGH or CRITICAL, and vulnerabilities must be open for at least 30 days.

**Why this priority**: Essential filtering criteria that defines the scope of the feature - without these filters, the system would import irrelevant data (clients, low severity, recently opened vulnerabilities).

**Independent Test**: Can be tested by verifying that the CrowdStrike API query includes filters `device_type:SERVER AND (severity:HIGH OR severity:CRITICAL) AND days_open:>=30`, and that imported data matches these criteria.

**Acceptance Scenarios**:

1. **Given** CrowdStrike API returns vulnerabilities with mixed severity levels, **When** import completes, **Then** only HIGH and CRITICAL severity vulnerabilities are stored in secman
2. **Given** CrowdStrike API returns vulnerabilities open for 5 days, 15 days, and 45 days, **When** import completes, **Then** only vulnerabilities open for 45 days are imported (30+ day threshold)
3. **Given** CrowdStrike API returns both CLIENT and SERVER devices, **When** query is executed, **Then** only SERVER devices are included in the query response
4. **Given** user runs `secman query servers --verbose`, **When** query executes, **Then** system logs the applied filters for transparency

---

### User Story 4 - Display Import Statistics (Priority: P2)

After import completes, the system should display clear statistics showing how many servers and vulnerabilities were imported, helping users understand the scope of changes made to the database.

**Why this priority**: Important for user feedback and audit trail, but the feature is still functional without detailed statistics.

**Independent Test**: Can be tested by running an import and verifying that the output displays counts in the format "Imported: X servers (Y new, Z existing), A vulnerabilities"

**Acceptance Scenarios**:

1. **Given** import creates 5 new server assets and reuses 3 existing assets, **When** import completes, **Then** system displays "Imported: 8 servers (5 new, 3 existing)"
2. **Given** import creates 42 vulnerability records, **When** import completes, **Then** system displays "Imported: 42 vulnerabilities"
3. **Given** import processes no vulnerabilities (CrowdStrike returns empty result), **When** import completes, **Then** system displays "Imported: 0 servers, 0 vulnerabilities"
4. **Given** import encounters errors for 2 servers but succeeds for 6 servers, **When** import completes, **Then** system displays success count and lists errors separately

---

### User Story 5 - Preserve Asset Metadata (Priority: P2)

When creating new Asset records for servers discovered via CrowdStrike, the system should populate metadata fields (groups, cloudAccountId, adDomain, osVersion, cloudInstanceId) from the CrowdStrike API response to provide context for vulnerability management.

**Why this priority**: Enhances asset tracking with valuable context, but the core import functionality works without this metadata.

**Independent Test**: Can be tested by importing a server and verifying that Asset record includes fields like adDomain="MS.HOME", osVersion="Windows Server 2019", groups="Production,DMZ"

**Acceptance Scenarios**:

1. **Given** CrowdStrike returns hostname="prod-web-01" with groups="Production,DMZ", **When** Asset is created, **Then** Asset.groups field contains "Production,DMZ"
2. **Given** CrowdStrike returns cloudAccountId="123456789012", **When** Asset is created, **Then** Asset.cloudAccountId field contains "123456789012"
3. **Given** CrowdStrike returns osVersion="Windows Server 2019", **When** Asset is created, **Then** Asset.osVersion field contains "Windows Server 2019"
4. **Given** CrowdStrike does not provide adDomain (null), **When** Asset is created, **Then** Asset.adDomain is null (no default value)

---

### Edge Cases

- What happens when CrowdStrike API returns 500+ servers (pagination required)?
- How does system handle CrowdStrike API rate limiting (429 responses)? (Answer: Retry with exponential backoff - wait 1s, 2s, 4s, 8s up to 3 retries; fail with error message if all retries exhausted)
- What happens when a server exists in secman but was manually created with different metadata?
- How does system handle vulnerabilities with missing CVE IDs? (Answer: Skip vulnerabilities without CVE IDs, log warning message, exclude from import statistics; ensure skipped count is reported separately)
- What happens when CrowdStrike API returns duplicate vulnerability records for the same server?
- How does system handle authentication failures (expired credentials)?
- What happens when backend API is unreachable during import?
- What happens when partial import failures occur? (Answer: Transaction per server ensures atomicity - if a server's vulnerability import fails, that server's old vulnerabilities are retained via rollback; other servers proceed independently)
- What happens when transaction rollback occurs for a server? (Old vulnerabilities remain unchanged, error logged, server excluded from success statistics)

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST add a new command `secman query servers` to the CLI that queries CrowdStrike API for server vulnerabilities, querying all servers in the CrowdStrike tenant by default
- **FR-001a**: System MUST support optional `--hostnames` flag to filter query to specific comma-separated server hostnames (e.g., `--hostnames server01,server02`)
- **FR-002**: System MUST filter CrowdStrike query by device type SERVER (exclude CLIENT devices)
- **FR-003**: System MUST filter CrowdStrike query by severity HIGH and CRITICAL (exclude MEDIUM, LOW, INFORMATIONAL)
- **FR-004**: System MUST filter CrowdStrike query by days open >=30 (only vulnerabilities open for 30+ days)
- **FR-005**: System MUST automatically create Asset records for servers discovered in CrowdStrike response if they don't exist in secman database
- **FR-006**: System MUST use server hostname as Asset.name for asset creation
- **FR-007**: System MUST set Asset.type="SERVER" for all automatically created server assets
- **FR-008**: System MUST populate Asset metadata fields (groups, cloudAccountId, cloudInstanceId, adDomain, osVersion) from CrowdStrike API response when available
- **FR-009**: System MUST delete all existing Vulnerability records for a server before importing new vulnerabilities (replace, not merge)
- **FR-009a**: System MUST use database transaction per server to ensure atomic delete+import operation (rollback if import fails, preventing inconsistent state)
- **FR-010**: System MUST create Vulnerability records with fields: vulnerabilityId (CVE), cvssSeverity, vulnerableProductVersions, daysOpen, scanTimestamp
- **FR-010a**: System MUST skip vulnerabilities without CVE identifiers (log warning message, exclude from import)
- **FR-010b**: System MUST track skipped vulnerability count separately from imported count in statistics
- **FR-011**: System MUST link each Vulnerability record to its corresponding Asset via asset_id foreign key
- **FR-012**: System MUST set Vulnerability.scanTimestamp to the current import timestamp (when data was retrieved from CrowdStrike)
- **FR-013**: System MUST display import statistics showing count of servers imported (total, new, existing), count of vulnerabilities imported, and count of vulnerabilities skipped (missing CVE ID)
- **FR-014**: System MUST handle CrowdStrike API pagination to retrieve all matching vulnerabilities (not limited to first page)
- **FR-015**: System MUST create new backend API endpoint `POST /api/crowdstrike/vulnerabilities/save` that accepts batch vulnerability data from CLI and persists to database
- **FR-015a**: Backend endpoint MUST accept JSON payload containing server hostname, vulnerabilities array, and metadata fields
- **FR-015b**: Backend endpoint MUST implement transaction-per-server logic (delete old vulnerabilities, create new ones atomically)
- **FR-015c**: Backend endpoint MUST return import statistics (servers processed, vulnerabilities created, errors)
- **FR-016**: System MUST detect existing Asset records by exact hostname match (case-sensitive)
- **FR-017**: System MUST preserve existing Asset metadata when reusing an existing Asset (not overwrite owner, description, workgroups)
- **FR-018**: System MUST handle API errors gracefully (authentication failures, rate limiting, network errors) and display meaningful error messages
- **FR-018a**: System MUST implement exponential backoff retry for CrowdStrike API rate limiting (HTTP 429): retry after 1s, 2s, 4s, 8s delays (maximum 3 retries) before failing
- **FR-019**: System MUST support `--dry-run` flag to query CrowdStrike API without storing results in secman database
- **FR-020**: System MUST support `--verbose` flag to display detailed logging during query and import process
- **FR-021**: System MUST validate CrowdStrike API credentials before executing query (fail fast if credentials invalid)

### Key Entities

- **Asset**: Represents a server in the infrastructure
  - Automatically created from CrowdStrike hostname
  - type="SERVER" for all servers imported via this command
  - Metadata populated from CrowdStrike API: groups, cloudAccountId, cloudInstanceId, adDomain, osVersion
  - owner defaults to "CrowdStrike Import" if not specified
  - Reused if hostname already exists in database

- **Vulnerability**: Represents a security vulnerability discovered on a server
  - Linked to Asset via ManyToOne relationship (asset_id FK)
  - Fields: vulnerabilityId (CVE), cvssSeverity, vulnerableProductVersions, daysOpen, scanTimestamp
  - All existing vulnerabilities for a server are deleted before importing new ones (replace pattern)
  - scanTimestamp records when data was retrieved from CrowdStrike

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Security analyst can execute `secman query servers` and retrieve all HIGH/CRITICAL server vulnerabilities open >30 days in under 60 seconds for queries returning <500 servers
- **SC-002**: System successfully creates Asset and Vulnerability records in secman database for 100% of servers returned by CrowdStrike API (no data loss)
- **SC-003**: System correctly replaces old vulnerability records with new records, ensuring database contains only current vulnerability state (no stale data accumulation)
- **SC-004**: Import statistics display accurate counts matching database changes (servers imported count matches actual Asset records created/updated)
- **SC-005**: System handles CrowdStrike API pagination correctly, retrieving all matching records even when result set exceeds single page limit
- **SC-006**: System fails gracefully with clear error messages for API failures (authentication, rate limiting, network) without corrupting database state
- **SC-007**: `--dry-run` flag allows users to preview import without database changes, displaying what would be imported

