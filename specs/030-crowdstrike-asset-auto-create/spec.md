# Feature Specification: CrowdStrike Asset Auto-Creation

**Feature Branch**: `030-crowdstrike-asset-auto-create`
**Created**: 2025-10-19
**Status**: Draft
**Input**: User description: "extend the existing functionality of the Save to database button in /vulnerabilities/system to store the asset if not existing yet in the database and also store the vulnerability. If the asset is not yet existing assign it to NO workgroup. Assign it to the currently logged in user. Assign it as a server. If you somewhere can find the IP address of the asset in the parenting Crowdstrike scan results, also include this in the vulnerability object. Otherwise use senseful defaults."

## Clarifications

### Session 2025-10-19

- Q: What happens if CrowdStrike returns malformed CVE IDs or invalid severity values? → A: Skip invalid vulnerabilities, save valid ones, report count of skipped items in success message
- Q: When saving to an existing asset, should the IP address be updated if CrowdStrike provides a different one? → A: Update IP address if CrowdStrike provides a different value
- Q: If the same CVE exists for an asset but with a different scan date, should it create a new record or update the existing one? → A: Create new vulnerability record with new scan date (maintain scan history)
- Q: How specific should error messages be? → A: User-friendly message + technical details for admins (role-based error verbosity)
- Q: What events should be logged for operational monitoring and troubleshooting? → A: Log key operations and errors (asset creation/update, vulnerability saves, validation failures, errors)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Save CrowdStrike Vulnerabilities with Auto-Created Assets (Priority: P1)

When a user queries CrowdStrike for system vulnerabilities and clicks "Save to Database", the system automatically creates the asset if it doesn't exist and saves all discovered vulnerabilities linked to that asset. The asset is created with the hostname from the CrowdStrike query, assigned to the current user as owner, and marked as a server type.

**Why this priority**: This is the core functionality that enables users to transition from CrowdStrike query results to persistent vulnerability tracking in the database. Without this, users cannot capture CrowdStrike discoveries for ongoing management.

**Independent Test**: Can be fully tested by querying CrowdStrike for a system not in the database, clicking "Save to Database", and verifying that both the asset and its vulnerabilities are created and properly linked. Delivers immediate value by allowing users to import CrowdStrike findings into the vulnerability management system.

**Acceptance Scenarios**:

1. **Given** a user has queried CrowdStrike and received vulnerability results for hostname "EC2AMAZ-6167U5R" that is not in the database, **When** they click "Save to Database", **Then** a new asset is created with name "EC2AMAZ-6167U5R", type "Server", owner set to the current user's username, no workgroup assignments, and all vulnerabilities from the query are saved and linked to this new asset

2. **Given** a user has queried CrowdStrike and received vulnerability results for a hostname that already exists in the database, **When** they click "Save to Database", **Then** no new asset is created, the vulnerabilities are linked to the existing asset, and the existing asset's IP address is updated if CrowdStrike provides a different value

3. **Given** a user has queried CrowdStrike and received vulnerability results with IP address information available, **When** they click "Save to Database", **Then** the created or updated asset includes the IP address from the CrowdStrike data

4. **Given** a user has queried CrowdStrike and received vulnerability results without IP address information, **When** they click "Save to Database", **Then** the asset is created with no IP address (null/empty value)

---

### User Story 2 - View Saved Assets in Asset Management (Priority: P2)

After saving CrowdStrike vulnerabilities, users can navigate to the Asset Management page and see the newly created assets with their associated vulnerabilities, allowing them to manage these assets alongside manually created ones.

**Why this priority**: This validates that the auto-created assets integrate seamlessly with the existing asset management system and provides users with a unified view of all assets regardless of their source.

**Independent Test**: After completing User Story 1, navigate to /assets and verify the new asset appears in the list with correct metadata (hostname, type=Server, owner=current user, no workgroups). Click into the asset detail view to see associated vulnerabilities.

**Acceptance Scenarios**:

1. **Given** a user has saved CrowdStrike vulnerabilities that created a new asset, **When** they navigate to the Asset Management page, **Then** they see the new asset in the list with hostname as name, type displayed as "Server", owner showing their username, and workgroups column showing empty or "None"

2. **Given** a user views an auto-created asset's detail page, **When** they check the asset's vulnerabilities, **Then** they see all vulnerabilities that were imported from CrowdStrike with correct CVE IDs, severity levels, and scan dates

---

### User Story 3 - Track Asset Creator for Audit Trail (Priority: P3)

The system records which user imported each asset from CrowdStrike, providing an audit trail for compliance and troubleshooting purposes.

**Why this priority**: While important for audit and accountability, this is not critical for the core functionality. Users can still import and manage vulnerabilities without this tracking, but it enhances governance and allows administrators to identify who added specific assets.

**Independent Test**: After saving CrowdStrike vulnerabilities, query the database or check asset metadata to verify that the "manualCreator" field is set to the current authenticated user. This can be validated independently through the asset detail view or database inspection.

**Acceptance Scenarios**:

1. **Given** a user "adminuser" has saved CrowdStrike vulnerabilities that created a new asset, **When** an administrator views the asset's creation metadata, **Then** they see "adminuser" recorded as the creator

2. **Given** multiple users have imported different systems from CrowdStrike, **When** an administrator reviews asset creation history, **Then** they can identify which user added each CrowdStrike-sourced asset

---

### Edge Cases

- What happens when a CrowdStrike query returns multiple vulnerabilities for the same system? All vulnerabilities should be saved and linked to the same asset.
- What happens if the hostname from CrowdStrike is empty or null? The save operation should fail with a clear error message indicating that asset creation requires a valid hostname.
- What happens if the user's authentication session expires during the save operation? The operation should fail with an authentication error, prompting the user to log in again.
- What happens if a duplicate asset name exists but with different casing (e.g., "EC2AMAZ-6167U5R" vs "ec2amaz-6167u5r")? The system should perform case-insensitive matching to find existing assets and avoid creating duplicates.
- What happens if the CrowdStrike data includes vulnerabilities that already exist for the asset? If the exact combination (asset + CVE ID + scan date) exists, it should be skipped to prevent duplicates. If the same CVE exists but with a different scan date, a new vulnerability record should be created to maintain scan history.
- What happens if the save operation partially completes (asset created but vulnerability save fails)? The operation should be transactional, rolling back asset creation if vulnerability saves fail.
- What happens when the user clicks "Save to Database" multiple times rapidly? The button should be disabled during the save operation to prevent duplicate submissions.
- What happens if CrowdStrike returns malformed or invalid vulnerability data (invalid CVE ID format, unrecognized severity values, negative days open)? Invalid vulnerabilities should be skipped, valid ones saved, and the success message should report both saved and skipped counts.
- What happens when saving to an existing asset that has a different IP address than the CrowdStrike data? The existing asset's IP address should be updated to match the CrowdStrike scan result (most recent data wins).
- What level of detail should error messages provide to users? Error messages should be role-based: regular users see user-friendly messages ("Failed to save vulnerabilities. Please try again."), while ADMIN users see technical details for debugging ("Failed to save vulnerabilities: Foreign key constraint violation on asset_id=123").

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST create a new asset when saving CrowdStrike vulnerabilities if no asset with the queried hostname exists in the database
- **FR-002**: System MUST assign the newly created asset to the currently authenticated user as the owner (using the "owner" field)
- **FR-003**: System MUST set the asset type to "Server" for all auto-created assets from CrowdStrike queries
- **FR-004**: System MUST NOT assign any workgroups to auto-created assets (workgroups field should be empty or null)
- **FR-005**: System MUST record the current authenticated user as the "manualCreator" for audit trail purposes
- **FR-006**: System MUST check for existing assets by hostname using case-insensitive matching before creating a new asset
- **FR-007**: System MUST extract and store the IP address from CrowdStrike scan results if available and include it in the asset record; for existing assets, the IP address MUST be updated if CrowdStrike provides a different value
- **FR-008**: System MUST handle missing IP addresses gracefully by creating the asset with a null/empty IP field
- **FR-009**: System MUST save all vulnerabilities from the CrowdStrike query results and link them to the corresponding asset (new or existing)
- **FR-010**: System MUST populate vulnerability records with CVE ID, severity, affected product, days open, scan date, and exception status from CrowdStrike data
- **FR-011**: System MUST prevent duplicate vulnerability records based on asset + CVE ID + scan date combination; if the same CVE exists with a different scan date, a new vulnerability record MUST be created to maintain scan history
- **FR-012**: System MUST perform asset creation and vulnerability saves within a single transaction to ensure data consistency
- **FR-013**: System MUST disable the "Save to Database" button during the save operation to prevent duplicate submissions
- **FR-014**: System MUST display a success message indicating the number of assets created/updated, vulnerabilities saved, and vulnerabilities skipped due to validation failures
- **FR-015**: System MUST display role-based error messages: user-friendly messages for regular users (e.g., "Failed to save vulnerabilities. Please try again or contact support.") and detailed technical messages for ADMIN users (e.g., "Failed to save vulnerabilities: Foreign key constraint violation on asset_id=123")
- **FR-016**: System MUST use sensible defaults for any optional asset fields not provided by CrowdStrike (e.g., description, cloud account ID, AD domain should be null/empty)
- **FR-017**: System MUST validate vulnerability data from CrowdStrike (CVE ID format, severity values, numeric fields) and skip invalid records while continuing to save valid ones
- **FR-018**: System MUST log key operations including asset creation/update events (with hostname, IP, user), vulnerability save operations (with counts), validation failures (with details), and all errors (with context) for operational monitoring and troubleshooting

### Key Entities

- **Asset**: Represents a system/server discovered via CrowdStrike queries. Key attributes include hostname (name), type (always "Server" for CrowdStrike imports), IP address (if available from scan), owner (current user's username), manualCreator (current authenticated user for audit), workgroups (empty collection for auto-created assets), and standard timestamps.

- **Vulnerability**: Represents a security vulnerability discovered on an asset at a specific point in time. Key attributes include CVE ID, severity level, affected product/version, days open, scan date, exception status, and relationship to the parent Asset entity. The unique constraint (asset + CVE ID + scan date) allows multiple records for the same CVE across different scan dates, maintaining scan history.

- **User**: The authenticated user performing the CrowdStrike query and save operation. Used to populate both the owner field (username string) and manualCreator reference (User entity) on auto-created assets.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can save CrowdStrike vulnerability query results to the database in under 5 seconds for queries returning up to 100 vulnerabilities
- **SC-002**: 100% of CrowdStrike-sourced assets are correctly attributed to the importing user as both owner and creator
- **SC-003**: Zero duplicate assets are created when saving CrowdStrike results for systems already in the database (case-insensitive hostname matching)
- **SC-004**: All saved vulnerabilities from CrowdStrike are viewable and manageable through the existing vulnerability management interface without distinction from manually entered vulnerabilities
- **SC-005**: Save operations either fully succeed or fully fail (no partial saves) in 99.9% of cases through transactional integrity
- **SC-006**: Users receive clear feedback within 1 second of clicking "Save to Database" indicating success or specific failure reasons
- **SC-007**: 100% of save operations (success and failure) are logged with sufficient context (user, hostname, counts, errors) to enable troubleshooting and audit trail review

## Assumptions

- CrowdStrike query responses always include a hostname/system identifier
- The hostname from CrowdStrike matches the naming convention used in the asset database (no transformation required)
- IP addresses in CrowdStrike data, when present, are valid IPv4 or IPv6 addresses
- The authenticated user's username is available and valid for assignment as the asset owner
- The existing Asset and Vulnerability entity structures support the required fields (owner, type, manualCreator, IP, CVE ID, severity, etc.)
- The existing database schema allows null/empty values for optional asset fields (IP, description, workgroups, etc.)
- The user interface already has a "Save to Database" button in the CrowdStrike vulnerability lookup view (/vulnerabilities/system)
- The system supports transactional operations for atomic asset and vulnerability creation

## Dependencies

- Existing CrowdStrike API integration for querying vulnerability data
- Existing authentication system to identify the current user
- Existing Asset and Vulnerability data models and database schemas
- Existing asset management and vulnerability management user interfaces

## Out of Scope

- Bulk import of CrowdStrike data for multiple systems at once
- Scheduled/automated synchronization with CrowdStrike
- Assignment of auto-created assets to workgroups (must be done manually by administrators)
- Modification of asset type after creation (remains "Server")
- Enrichment of asset data beyond what CrowdStrike provides (e.g., cloud account lookups, AD domain resolution)
- Merging or deduplication of vulnerabilities across different scan dates
- Custom mapping rules for hostname transformation or normalization
- Support for CrowdStrike data sources other than the system vulnerability lookup query
