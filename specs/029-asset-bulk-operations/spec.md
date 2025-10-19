# Feature Specification: Asset Bulk Operations

**Feature Branch**: `029-asset-bulk-operations`
**Created**: 2025-10-19
**Status**: Draft
**Input**: User description: "extend the assets UI with a button \"Delete all assets\" if the current logged in user has the ADMIN role. Also implement all required functionality. For the I/O item (sidebar) add sub items Assets under Import and Export and implement functionality to export and import assets. Ensure that a flow export assets, deletion of assets (via Assets UI) and import is possible."

## Clarifications

### Session 2025-10-19

- Q: Should the asset import update existing assets when duplicates are found, or always skip them? → A: Always skip duplicates (no updates) - Import never modifies existing assets; duplicates are reported in the summary for manual review
- Q: When a user attempts to export assets but there are no assets in the system, what should happen? → A: Show error message only - Display a message "No assets available to export" and prevent download
- Q: When there are no assets in the system, should the "Delete All Assets" button be disabled or hidden? → A: Hide button - The "Delete All Assets" button does not appear when asset count is zero
- Q: If the bulk delete operation fails partway through (database error, connection loss), what should happen to the assets? → A: Rollback all deletions - Use a database transaction; if any deletion fails, rollback all deletions and show error message

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Bulk Delete Assets (Priority: P1 - MVP)

An administrator needs to quickly remove all assets from the system during testing, migration, or system reset scenarios. Currently, assets must be deleted individually which is time-consuming for large datasets.

**Why this priority**: This is the core feature requested - enabling ADMIN users to efficiently clear all assets. Without this, large-scale asset cleanup requires manual deletion of each asset, which is impractical for datasets with hundreds or thousands of assets.

**Independent Test**: Can be fully tested by logging in as an ADMIN user, navigating to the Asset Management page, clicking "Delete All Assets", confirming the action, and verifying all assets are removed from the database and UI.

**Acceptance Scenarios**:

1. **Given** an ADMIN user is logged in and viewing the Asset Management page with 100 assets, **When** they click the "Delete All Assets" button and confirm the action, **Then** all assets are deleted from the system and the asset list shows 0 assets.
2. **Given** a non-ADMIN user (USER, VULN, RELEASE_MANAGER) is logged in and viewing the Asset Management page, **When** they view the page, **Then** the "Delete All Assets" button is not visible.
3. **Given** an ADMIN user is logged in and viewing the Asset Management page with no assets, **When** they view the page, **Then** the "Delete All Assets" button is not shown.
4. **Given** an ADMIN user clicks "Delete All Assets", **When** the confirmation modal appears, **Then** the modal displays a warning about the irreversible nature of the action and requires explicit confirmation.
5. **Given** an ADMIN user initiates bulk delete, **When** the operation completes, **Then** a success message displays showing the number of assets deleted.

---

### User Story 2 - Export Assets to File (Priority: P1 - MVP)

Users need to export their asset inventory to a file for backup, reporting, migration, or external analysis. The export should capture all asset details in a standard format.

**Why this priority**: Exporting assets is essential for data portability, backups before bulk operations, and integration with external systems. This is part of the complete workflow: export → delete → import.

**Independent Test**: Can be tested by navigating to I/O > Export, selecting "Assets" from the export options, clicking export, and verifying that a file downloads containing all current assets with complete field data.

**Acceptance Scenarios**:

1. **Given** a user is logged in and navigates to I/O > Export in the sidebar, **When** they select "Assets" and click "Export", **Then** an Excel file (.xlsx) downloads containing all assets they have permission to view.
2. **Given** a user exports assets, **When** they open the exported file, **Then** it contains columns for all asset fields: name, type, IP address, owner, description, groups, cloudAccountId, cloudInstanceId, osVersion, adDomain, workgroups, createdAt, updatedAt.
3. **Given** a user with workgroup restrictions exports assets, **When** the export completes, **Then** the file contains only assets from their assigned workgroups plus assets they created or uploaded.
4. **Given** an ADMIN user exports assets, **When** the export completes, **Then** the file contains all assets in the system regardless of workgroup assignments.
5. **Given** there are no assets in the system, **When** a user attempts to export, **Then** they receive a message "No assets available to export" and the download is prevented.

---

### User Story 3 - Import Assets from File (Priority: P2)

Users need to import assets from a file to populate the system, restore from backup, or migrate from another system. The import should validate data and handle duplicates gracefully.

**Why this priority**: Importing assets completes the full data lifecycle workflow and enables system restoration after bulk deletion. While critical for the complete workflow, export and delete functionality can be tested independently.

**Independent Test**: Can be tested by navigating to I/O > Import, selecting "Assets", uploading a valid asset file, and verifying that assets are created in the database and visible in the Asset Management page.

**Acceptance Scenarios**:

1. **Given** a user is logged in and navigates to I/O > Import in the sidebar, **When** they select "Assets" and upload a valid Excel file (.xlsx), **Then** the system imports all valid assets and displays a summary showing number imported, number skipped, and any errors.
2. **Given** a user uploads an asset import file with duplicate asset names, **When** the import processes, **Then** the system skips duplicates without modifying existing assets, and reports which assets were skipped in the import summary.
3. **Given** a user uploads an asset import file with invalid data (missing required fields, invalid IP format), **When** the import processes, **Then** invalid rows are skipped with detailed error messages, and valid rows are imported successfully.
4. **Given** a user uploads an asset import file, **When** the import completes, **Then** newly imported assets are assigned to the appropriate workgroups based on the workgroup column data or user's default workgroups.
5. **Given** a non-ADMIN user uploads an asset import file, **When** the import completes, **Then** imported assets are associated with the importing user as the creator for access control purposes.

---

### User Story 4 - Sidebar Navigation for Asset I/O (Priority: P2)

Users need easy access to asset import and export functions from the sidebar navigation, organized logically under the existing I/O menu structure.

**Why this priority**: Improved navigation UX makes the feature discoverable and consistent with existing import/export patterns. This enhances usability but doesn't affect core functionality.

**Independent Test**: Can be tested by viewing the sidebar as an authenticated user, expanding the I/O menu, and verifying that Import and Export sub-items both contain an "Assets" option that navigates to the correct pages.

**Acceptance Scenarios**:

1. **Given** a user is logged in and views the sidebar, **When** they expand the I/O menu item, **Then** they see "Import" and "Export" sub-items.
2. **Given** a user expands I/O > Import in the sidebar, **When** the submenu appears, **Then** "Assets" appears as an option alongside existing import types (Requirements, Nmap, Masscan, Vulnerabilities).
3. **Given** a user expands I/O > Export in the sidebar, **When** the submenu appears, **Then** "Assets" appears as an option alongside existing export types (Requirements).
4. **Given** a user clicks I/O > Import > Assets in the sidebar, **When** the page loads, **Then** the Import page opens with the Assets import type pre-selected.
5. **Given** a user clicks I/O > Export > Assets in the sidebar, **When** the page loads, **Then** the Export page opens with the Assets export type pre-selected.

---

### User Story 5 - Complete Workflow: Export, Delete, Import (Priority: P3)

Users need to execute a complete data migration or testing workflow: export current assets, clear the system, and import a new dataset. This workflow should be seamless and reliable.

**Why this priority**: This validates the integration of all three features working together end-to-end. While important for user confidence, each component can function independently.

**Independent Test**: Can be tested by: (1) exporting assets to a file, (2) using bulk delete to remove all assets, (3) importing the previously exported file, and (4) verifying all original assets are restored with complete data.

**Acceptance Scenarios**:

1. **Given** an ADMIN user has exported 500 assets to a file, **When** they delete all assets using the bulk delete function and then import the previously exported file, **Then** all 500 assets are restored with identical data (name, type, IP, owner, description, metadata).
2. **Given** a user exports assets, deletes all assets, and imports the export file, **When** comparing pre-delete and post-import data, **Then** workgroup assignments are preserved correctly.
3. **Given** a user performs export → delete → import workflow, **When** validation occurs, **Then** all asset relationships (workgroups, vulnerabilities, scan results) are maintained correctly.
4. **Given** a user imports an asset file exported from the system, **When** the import completes, **Then** timestamps (createdAt, updatedAt) reflect the import time, not the original timestamps.
5. **Given** a user attempts the workflow during active system use, **When** other users are viewing/editing assets, **Then** appropriate locking or error handling prevents data corruption.

---

### Edge Cases

- What happens when an ADMIN user attempts bulk delete while another user is editing an asset? → The bulk delete proceeds; concurrent edits are not blocked, but the transaction ensures atomicity.
- How does the system handle export of assets with special characters in names or descriptions? → Excel format handles special characters natively; no special encoding required.
- What happens if a user uploads an asset import file in the wrong format (CSV instead of Excel)? → File validation rejects the upload with error message "Invalid file format. Please upload an Excel (.xlsx) file."
- How does the system handle import of assets with workgroup references that no longer exist in the system? → Invalid workgroup names are skipped; assets are imported without workgroup assignment (or with user's default workgroups).
- What happens when bulk delete is triggered but the database connection fails mid-operation? → All deletions are rolled back via database transaction; error message displays "Bulk delete failed. No assets were deleted."
- How does the export handle assets with very long descriptions or metadata fields? → Excel cells support up to 32,767 characters; fields are exported as-is without truncation.
- What happens when importing assets from an export file created by a different version of the system? → Import is best-effort; recognized columns are imported, unknown columns are ignored with warning in summary.
- How does the system handle import of assets with IP addresses in different formats (IPv4 vs IPv6)? → Both formats are accepted as valid strings; format validation ensures correct IPv4/IPv6 syntax.
- What happens when a non-ADMIN user attempts to call the bulk delete API endpoint directly? → API returns 403 Forbidden with message "Insufficient permissions. ADMIN role required."
- How does the system handle concurrent bulk delete requests from multiple ADMIN users? → First request acquires transaction lock; second request waits or fails with "Bulk operation in progress" message.

## Requirements *(mandatory)*

### Functional Requirements

#### Bulk Delete
- **FR-001**: System MUST provide a "Delete All Assets" button on the Asset Management page visible only to users with ADMIN role.
- **FR-002**: System MUST display a confirmation modal when "Delete All Assets" is clicked, warning about irreversible data loss and requiring explicit user confirmation.
- **FR-003**: System MUST delete all assets from the database when the ADMIN user confirms the bulk delete action.
- **FR-004**: System MUST display a success message showing the count of deleted assets after bulk delete completes.
- **FR-005**: System MUST refresh the asset list to show zero assets after successful bulk delete.
- **FR-006**: System MUST prevent non-ADMIN users from accessing the bulk delete functionality through UI or API.
- **FR-007**: System MUST handle cascade deletion of related data (vulnerabilities, scan results) when assets are deleted.
- **FR-008**: System MUST execute bulk delete within a database transaction; if any deletion fails, all deletions are rolled back and an error message is displayed.

#### Asset Export
- **FR-009**: System MUST provide an "Assets" option under I/O > Export in the sidebar navigation.
- **FR-010**: System MUST export assets to Excel (.xlsx) format including all fields: name, type, ip, owner, description, groups, cloudAccountId, cloudInstanceId, osVersion, adDomain, workgroups, createdAt, updatedAt.
- **FR-011**: System MUST apply workgroup-based access control to asset exports - users see only their workgroup assets plus owned assets; ADMIN users see all assets.
- **FR-012**: System MUST format the export file with clear column headers matching database field names.
- **FR-013**: System MUST handle workgroup data in exports by including workgroup names in a readable format.
- **FR-014**: System MUST provide user feedback during export operations with loading indicators and success/error messages.
- **FR-015**: System MUST display an error message "No assets available to export" and prevent file download when no assets are available to the user.

#### Asset Import
- **FR-016**: System MUST provide an "Assets" option under I/O > Import in the sidebar navigation.
- **FR-017**: System MUST accept Excel (.xlsx) files for asset import with validation for file size, format, and required fields.
- **FR-018**: System MUST validate required fields (name, type, owner) and reject rows with missing required data.
- **FR-019**: System MUST validate data formats (IP address format, type values) and skip invalid rows with detailed error messages.
- **FR-020**: System MUST handle duplicate asset names by skipping duplicates without modifying existing assets, and reporting skipped duplicates in the import summary.
- **FR-021**: System MUST associate imported assets with appropriate workgroups based on workgroup column data or importing user's workgroups.
- **FR-022**: System MUST track the importing user as the creator for access control purposes.
- **FR-023**: System MUST provide an import summary displaying: total rows processed, assets imported, assets skipped, and detailed error messages for failed rows.

#### Navigation & UX
- **FR-024**: System MUST organize asset import/export options as sub-items under the existing I/O menu in the sidebar.
- **FR-025**: System MUST pre-select the "Assets" import type when users navigate via I/O > Import > Assets link.
- **FR-026**: System MUST pre-select the "Assets" export type when users navigate via I/O > Export > Assets link.
- **FR-027**: System MUST hide the "Delete All Assets" button when no assets exist in the system (asset count is zero).

### Key Entities *(include if feature involves data)*

- **Asset**: Core entity representing IT assets in the system with fields for name, type, IP address, owner, description, metadata (groups, cloud IDs, OS version, AD domain), workgroup associations, and timestamps.
- **Workgroup**: Represents user groups with access to specific assets; relevant for access control during import/export operations.
- **ImportResult**: Temporary data structure for reporting import outcomes including counts of imported/skipped assets and error details.
- **ExportData**: Temporary data structure containing filtered asset data formatted for Excel export with all fields and workgroup names.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: ADMIN users can delete all assets (10,000+ records) in under 30 seconds with a single button click and confirmation.
- **SC-002**: Users can export their accessible assets (up to 10,000 records) to Excel format in under 15 seconds.
- **SC-003**: Users can import a valid asset file (up to 5,000 records) with a success rate of 95%+ for well-formed data in under 60 seconds.
- **SC-004**: The complete workflow (export → delete → import) maintains 100% data integrity for all asset fields and relationships.
- **SC-005**: Non-ADMIN users attempting to access bulk delete functionality receive appropriate error messages or access denials 100% of the time.
- **SC-006**: Import validation catches and reports 100% of data format errors (invalid IPs, missing required fields) without system errors.
- **SC-007**: Asset navigation in the sidebar reduces clicks to export/import by at least 2 compared to accessing generic I/O pages.
- **SC-008**: System handles concurrent operations (view/edit during bulk delete) without data corruption in 100% of test cases.

## Assumptions

- **A-001**: Export format will be Excel (.xlsx) to match existing import/export patterns in the system (requirements, vulnerabilities, user mappings).
- **A-002**: Import will follow the same file validation and error handling patterns as existing CSV/Excel imports (10MB max file size, skip invalid rows, continue with valid rows).
- **A-003**: Asset exports will respect existing workgroup-based access control rules already implemented in the `GET /api/assets` endpoint.
- **A-004**: Bulk delete will use hard deletion (permanent removal) rather than soft deletion, consistent with individual asset deletion behavior.
- **A-005**: Import duplicate handling will default to "skip duplicates" based on asset name uniqueness, consistent with other entity imports.
- **A-006**: Workgroup associations in import files will be specified by workgroup names (comma-separated) rather than IDs for user-friendliness.
- **A-007**: The "Delete All Assets" button will appear in the Asset Management page header near the existing "Add New Asset" button for logical grouping.
- **A-008**: Import will preserve workgroup assignments if specified in the file; otherwise, assets will be assigned to the importing user's workgroups.
- **A-009**: Export file format will match the import file format to enable seamless export → import workflows.
- **A-010**: Cascade deletion behavior (deleting related vulnerabilities, scan results) already exists for individual asset deletion and will apply to bulk deletion.

## Dependencies

- **D-001**: Existing workgroup-based access control implementation (Feature 008) for filtering assets in exports.
- **D-002**: Existing authentication and role checking utilities (`isAdmin`, `hasRole`) for ADMIN-only UI elements and API endpoints.
- **D-003**: Current `Asset` entity structure and relationships (Workgroup, Vulnerability, ScanResult) must support bulk operations.
- **D-004**: Existing import/export patterns and utilities (Apache POI for Excel, FormData handling, error reporting) used in Requirements and Vulnerability imports.
- **D-005**: Sidebar component's expandable menu structure for I/O navigation already exists and supports sub-items.
- **D-006**: Asset repository methods for bulk deletion (`deleteAll()` or equivalent batch operations).
- **D-007**: File upload infrastructure (multipart/form-data handling, file size limits, MIME type validation).

## Open Questions

None - all requirements are specified with reasonable defaults based on existing system patterns.

