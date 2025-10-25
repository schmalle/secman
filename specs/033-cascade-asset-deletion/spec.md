# Feature Specification: Cascade Asset Deletion with Related Data

**Feature Branch**: `033-cascade-asset-deletion`
**Created**: 2025-10-24
**Status**: Draft
**Input**: User description: "when deleting an asset all related vulnerabilies and exceptions for this assets must be deleted"

## Clarifications

### Session 2025-10-24

- Q: How should the system prevent concurrent deletion attempts on the same asset? → A: Pessimistic row-level lock - Lock asset record during deletion, second request waits or times out
- Q: What level of detail should audit logs capture for cascade deletion operations? → A: Summary counts + identifiers - Asset ID, timestamp, user, count per entity type (vulns, exceptions, requests), plus list of deleted entity IDs
- Q: When an asset has so many related records that deletion would exceed the 60-second transaction timeout, what should happen? → A: Pre-flight count check with warning - Count related records before deletion, warn user if estimated time >60s, require explicit confirmation
- Q: When a cascade deletion fails and rolls back, what information should be provided to the user? → A: Detailed structured error - Error type, asset name/ID, specific cause (e.g., "locked by user X", "timeout after 60s"), suggested action
- Q: For bulk deletion of 100 assets, how should the operation be executed to balance performance and user feedback? → A: Sequential with progress reporting - Delete assets one-by-one in single transaction, stream progress updates to UI (e.g., "15/100 completed")

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Delete Asset with All Related Data (Priority: P1)

As a system administrator, when I delete an asset, I want all associated vulnerabilities, vulnerability exceptions, and vulnerability exception requests to be automatically removed so that the system maintains data integrity and doesn't leave orphaned records.

**Why this priority**: This is the core functionality that prevents data inconsistency and orphaned records in the database. Without this, the system would accumulate invalid data references that could cause errors or confusion.

**Independent Test**: Can be fully tested by creating an asset with vulnerabilities and exceptions, then deleting the asset and verifying all related records are removed. Delivers immediate value by ensuring database integrity.

**Acceptance Scenarios**:

1. **Given** an asset exists with 5 active vulnerabilities and no exceptions, **When** an administrator deletes the asset, **Then** the asset and all 5 vulnerabilities are removed from the system
2. **Given** an asset exists with vulnerabilities and 3 ASSET-type exceptions referencing it, **When** an administrator deletes the asset, **Then** the asset, vulnerabilities, and all 3 ASSET-type exceptions are removed
3. **Given** an asset exists with vulnerabilities that have pending exception requests, **When** an administrator deletes the asset, **Then** the asset, vulnerabilities, and all related exception requests are removed
4. **Given** an asset exists with vulnerabilities that have approved exception requests, **When** an administrator deletes the asset, **Then** the asset, vulnerabilities, approved exception requests, and corresponding exceptions are all removed

---

### User Story 2 - Complete Deletion of Exception Requests (Priority: P2)

As a compliance officer, when an asset is deleted, I want all exception request records to be completely removed while preserving the immutable audit log so that operational data is cleaned up completely while compliance documentation is maintained.

**Why this priority**: Complete deletion prevents orphaned operational records while the ExceptionRequestAuditLog table already provides the permanent audit trail required for compliance. This balances data cleanup with regulatory requirements.

**Independent Test**: Can be tested by deleting assets with exception requests and verifying that VulnerabilityExceptionRequest records are removed but ExceptionRequestAuditLog entries remain. Delivers value by maintaining clean operational data.

**Acceptance Scenarios**:

1. **Given** an asset with 3 exception requests exists, **When** the asset is deleted, **Then** all 3 VulnerabilityExceptionRequest records are permanently removed from the database
2. **Given** an asset with exception requests that have audit log entries is deleted, **When** the deletion completes, **Then** the exception request records are removed but all ExceptionRequestAuditLog entries are preserved

---

### User Story 3 - Transactional Bulk Asset Deletion (Priority: P2)

As a system administrator performing bulk cleanup operations, when I delete multiple assets at once, I want all assets and their related data to be deleted in a single transaction so that the operation either completes entirely or fails entirely, preventing partial deletions that could leave the system in an inconsistent state.

**Why this priority**: Transactional bulk operations prevent partial deletions that could create data inconsistencies. If one asset fails to delete, rolling back the entire operation ensures the system remains in a known, consistent state.

**Independent Test**: Can be tested by bulk deleting multiple assets where one has a constraint violation, and verifying that no assets are deleted (complete rollback). Delivers value by maintaining data integrity during administrative operations.

**Acceptance Scenarios**:

1. **Given** 10 assets are selected for bulk deletion with varying amounts of vulnerabilities and exceptions, **When** the bulk delete is executed and all assets can be deleted, **Then** all 10 assets and their complete related data trees are removed in a single transaction with real-time progress updates (e.g., "3/10 completed")
2. **Given** a bulk delete operation is in progress, **When** one asset fails to delete due to a constraint violation, **Then** the entire operation rolls back and no assets are deleted, with a detailed error report identifying the problematic asset, error cause, and suggested action

---

### User Story 4 - UI Warning Before Cascade Deletion (Priority: P3)

As a user deleting an asset, I want to be warned about what related data will be deleted so that I can make an informed decision before confirming the deletion.

**Why this priority**: User experience enhancement that prevents accidental data loss. Lower priority because the core deletion functionality is more critical than the warning UI.

**Independent Test**: Can be tested by attempting to delete assets with varying amounts of related data and verifying appropriate warnings are displayed. Delivers value by improving user confidence.

**Acceptance Scenarios**:

1. **Given** an asset has 15 vulnerabilities, 3 exceptions, and 2 pending exception requests, **When** a user initiates asset deletion, **Then** a confirmation dialog shows counts of related data that will be deleted
2. **Given** an asset has no related data, **When** a user initiates asset deletion, **Then** a simple confirmation is shown without warning about related data

---

### Edge Cases

- **Transaction rollback**: If asset deletion fails midway through cascade operations, entire transaction rolls back, no partial deletion occurs, detailed structured error returned to user with specific cause and suggested action
- **IP/PRODUCT exceptions**: Preserved (not deleted) as they apply globally across multiple assets, not tied to specific asset
- **Pending exception requests**: Deleted along with asset and vulnerabilities, recorded in audit log
- **Audit log references**: ExceptionRequestAuditLog entries preserved even when referenced entities are deleted (immutable audit trail)
- **Concurrent deletion during review**: Pessimistic locking prevents deletion while asset is being accessed; reviewer attempting to approve after deletion receives error
- **Active pending requests**: Not prevented - deletion proceeds, requests are cascade deleted and logged
- **Large record sets**: Pre-flight count check warns user if estimated deletion time >60s, requires explicit confirmation before proceeding

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST delete all vulnerabilities associated with an asset when that asset is deleted
- **FR-002**: System MUST delete all ASSET-type vulnerability exceptions (where exception.assetId matches the deleted asset.id) when an asset is deleted
- **FR-003**: System MUST delete all vulnerability exception requests (both pending and approved) that reference vulnerabilities owned by the deleted asset
- **FR-004**: System MUST delete the corresponding VulnerabilityException record when deleting an approved VulnerabilityExceptionRequest that was created from it
- **FR-005**: System MUST execute cascade deletions within a database transaction to ensure atomicity (all-or-nothing)
- **FR-006**: System MUST preserve IP-type and PRODUCT-type exceptions when deleting an asset (as these apply globally, not to specific assets)
- **FR-007**: System MUST display a count of related data (vulnerabilities, exceptions, requests) before confirming asset deletion
- **FR-008**: System MUST handle bulk asset deletion with the same cascade rules as single asset deletion
- **FR-009**: System MUST log all cascade deletion operations for audit purposes including: asset ID, timestamp, user, count per entity type (vulnerabilities, exceptions, requests), and list of all deleted entity IDs
- **FR-010**: System MUST prevent partial cascade deletions - if any related data cannot be deleted, the entire operation should fail and roll back
- **FR-011**: System MUST use pessimistic row-level locking on the asset record during deletion to prevent concurrent deletion attempts (second request waits or times out)
- **FR-012**: System MUST perform a pre-flight count of related records before deletion and warn the user if estimated deletion time exceeds 60 seconds, requiring explicit confirmation to proceed
- **FR-013**: System MUST provide detailed structured error messages on deletion failure including: error type, asset name/ID, specific cause (e.g., "locked by another user", "timeout"), and suggested remediation action
- **FR-014**: System MUST execute bulk deletions sequentially (one asset at a time within single transaction) and stream real-time progress updates to the UI (e.g., "15/100 assets deleted")

### Key Entities

- **Asset**: The primary entity being deleted, owns vulnerabilities
- **Vulnerability**: Related records that must be cascade deleted when their owning asset is deleted
- **VulnerabilityException**: Exception rules that may reference assets either through assetId (ASSET-type) or indirectly through vulnerabilities (IP/PRODUCT-type)
- **VulnerabilityExceptionRequest**: Request records that reference vulnerabilities which are owned by assets
- **ExceptionRequestAuditLog**: Immutable audit trail that references exception requests (may need special handling for deleted asset references)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: When an asset is deleted, zero orphaned vulnerability records remain in the database (100% cascade completion)
- **SC-002**: When an asset with ASSET-type exceptions is deleted, zero orphaned exception records with matching assetId remain in the database
- **SC-003**: Database integrity constraints pass validation after any asset deletion operation (no foreign key violations, no orphaned records)
- **SC-004**: Bulk deletion of 100 assets completes in under 30 seconds while maintaining complete cascade deletion for all related data, with real-time progress updates streamed to UI
- **SC-005**: Users receive deletion confirmation within 2 seconds showing accurate counts of affected records (vulnerabilities, exceptions, requests)
- **SC-006**: Audit logs capture 100% of cascade deletion operations with complete details (asset ID, timestamp, user, count per entity type, list of all deleted entity IDs)
- **SC-007**: Zero instances of partial cascade deletions occur (all operations are atomic - complete success or complete rollback)

## Data Integrity Rules

- Asset deletion is transactional - all cascade operations succeed or entire operation rolls back
- ASSET-type exceptions are always deleted with their referenced asset
- IP-type and PRODUCT-type exceptions are preserved (not asset-specific)
- Exception requests linked to deleted vulnerabilities are removed
- Audit logs preserve historical references even when source entities are deleted
- Concurrent deletion attempts are serialized using pessimistic row-level locking (second request waits or times out)

## Assumptions

1. **Cascade Order**: Deletions will occur in dependency order: VulnerabilityExceptionRequests → VulnerabilityExceptions → Vulnerabilities → Asset
2. **Transaction Scope**: All cascade deletions for a single asset occur within one database transaction
3. **Audit Preservation**: ExceptionRequestAuditLog records are never deleted (immutable audit trail) even when their referenced entities are deleted
4. **Bulk Operations**: Existing bulk delete functionality (AssetBulkDeleteService) will be enhanced to include the new cascade rules
5. **Performance**: For assets with large numbers of related records (>1000 vulnerabilities), cascade deletion may take several seconds but must complete atomically
6. **User Permissions**: Asset deletion permissions follow existing RBAC rules (ADMIN role required for bulk operations)
7. **IP/PRODUCT Exceptions**: These exception types are intentionally NOT deleted when assets are removed, as they apply globally across multiple assets
8. **Exception Request Handling**: When deleting approved exception requests during asset cascade, their corresponding VulnerabilityException records must also be deleted

## Constraints

- Database transaction timeout limits (default: 60 seconds) may affect deletion of assets with very large numbers of related records
- Concurrent asset access during deletion must be prevented to avoid race conditions
- Foreign key constraints must be properly configured to support cascade deletion
- Existing API endpoints for asset deletion must maintain backward compatibility
- UI changes must work across all supported browsers (Chrome, Firefox, Safari, Edge)

## Out of Scope

- Soft deletion (marking as deleted rather than physical deletion)
- Undo functionality after asset deletion
- Batch deletion scheduling or queuing
- Deletion of requirements, norms, or other entities unrelated to vulnerability management
- Email notifications for asset deletion events
- Deletion of scan results (already handled by existing cascade rules)
- Import file cleanup when asset created from import is deleted
