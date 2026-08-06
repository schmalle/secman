# Feature Specification: User Mapping Management in User Edit Interface

**Feature Branch**: `017-user-mapping-management`  
**Created**: 2025-10-13  
**Status**: Draft  
**Input**: User description: "i want to add the possibility to view in the UI where i can edit users also the available mappings for this user identitied by his email adress. Meaning i want to add/delete/edit mappings to domains or AWS accounts. The data is stored in the user_mapping table."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View User's Existing Mappings (Priority: P1)

As an administrator editing a user, I need to see all domain and AWS account mappings associated with that user's email address so I can understand what access they currently have.

**Why this priority**: This is the foundation - admins must be able to see existing mappings before they can manage them. Without visibility, no management is possible.

**Independent Test**: Can be fully tested by opening the user edit dialog for any user and verifying that all mappings from the user_mapping table (matching the user's email) are displayed in a clear, readable format. Delivers immediate value by providing visibility into user access.

**Acceptance Scenarios**:

1. **Given** an administrator is editing a user with email "markus@schmall.io" who has 3 mappings (1 domain-only, 1 AWS-only, 1 both), **When** the edit dialog opens, **Then** all 3 mappings are displayed showing the domain and/or AWS account ID for each
2. **Given** an administrator is editing a user with no mappings, **When** the edit dialog opens, **Then** an empty state message is displayed (e.g., "No mappings configured for this user")
3. **Given** an administrator is editing a user with 50+ mappings, **When** the edit dialog opens, **Then** mappings are displayed with pagination or scrolling to handle the volume

---

### User Story 2 - Add New Mapping (Priority: P2)

As an administrator editing a user, I need to add new domain or AWS account mappings so I can grant the user access to additional resources.

**Why this priority**: After visibility (P1), the most critical management operation is adding new mappings to grant access.

**Independent Test**: Can be tested by clicking "Add Mapping" in the user edit dialog, entering a valid domain or AWS account ID (or both), and verifying the new mapping appears in the list and is persisted in the user_mapping table. Delivers standalone value by enabling access grants.

**Acceptance Scenarios**:

1. **Given** an administrator is editing a user, **When** they click "Add Mapping" and enter domain "example.com" only, **Then** a new mapping is created with email, domain, and null AWS account ID
2. **Given** an administrator is editing a user, **When** they click "Add Mapping" and enter AWS account ID "123456789012" only, **Then** a new mapping is created with email, AWS account ID, and null domain
3. **Given** an administrator is editing a user, **When** they click "Add Mapping" and enter both domain "example.com" and AWS account ID "123456789012", **Then** a new mapping is created with all three values
4. **Given** an administrator tries to add a mapping, **When** they leave both domain and AWS account ID empty, **Then** an error message displays "At least one of Domain or AWS Account ID must be provided"
5. **Given** an administrator tries to add a duplicate mapping (same email, domain, and AWS account ID already exists), **When** they submit the form, **Then** an error message displays "This mapping already exists"

---

### User Story 3 - Delete Existing Mapping (Priority: P2)

As an administrator editing a user, I need to delete existing mappings so I can revoke the user's access to specific domains or AWS accounts.

**Why this priority**: Equally critical to adding - admins must be able to revoke access for security and access control purposes.

**Independent Test**: Can be tested by clicking "Delete" on an existing mapping, confirming the deletion, and verifying the mapping is removed from both the display and the user_mapping table. Delivers standalone value by enabling access revocation.

**Acceptance Scenarios**:

1. **Given** an administrator is viewing a user's mappings, **When** they click "Delete" on a mapping and confirm, **Then** the mapping is removed from the list and deleted from the database
2. **Given** an administrator clicks "Delete" on a mapping, **When** they see the confirmation dialog and click "Cancel", **Then** the mapping is not deleted
3. **Given** an administrator deletes the last mapping for a user, **When** the deletion completes, **Then** the empty state message appears

---

### User Story 4 - Edit Existing Mapping (Priority: P3)

As an administrator editing a user, I need to modify an existing mapping's domain or AWS account ID so I can correct mistakes or update access without deleting and recreating.

**Why this priority**: While useful, editing can be accomplished through delete + add operations. This is a convenience feature that comes after core add/delete functionality.

**Independent Test**: Can be tested by clicking "Edit" on an existing mapping, changing the domain or AWS account ID, and verifying the mapping is updated in both the display and database. Delivers incremental value by streamlining corrections.

**Acceptance Scenarios**:

1. **Given** an administrator is viewing a user's mappings, **When** they click "Edit" on a mapping, change the domain from "old.com" to "new.com", and save, **Then** the mapping is updated with the new domain
2. **Given** an administrator is editing a mapping, **When** they change the AWS account ID from "111111111111" to "222222222222" and save, **Then** the mapping is updated with the new AWS account ID
3. **Given** an administrator tries to edit a mapping to create a duplicate, **When** they submit the form, **Then** an error message displays "This mapping already exists"
4. **Given** an administrator is editing a mapping, **When** they clear both domain and AWS account ID fields, **Then** an error message displays "At least one of Domain or AWS Account ID must be provided"

---

### Edge Cases

- What happens when a user has 100+ mappings (large volume)?
  - System should paginate or provide search/filter capability
  - Performance should remain acceptable (load within 2 seconds)
- What happens when two administrators edit the same user's mappings simultaneously?
  - System should handle concurrent edits gracefully (optimistic locking or last-write-wins with notification)
- What happens when an administrator tries to add a mapping for a deleted/invalid email?
  - System validates that the email matches the user being edited
- How does the system handle special characters or edge case formats in domains/AWS IDs?
  - Domain validation: lowercase letters, numbers, dots, hyphens only
  - AWS Account ID validation: exactly 12 numeric digits
  - Display clear validation messages for invalid inputs
- What happens if the user_mapping table is unavailable during load?
  - Display error message: "Unable to load mappings at this time. Please try again."
  - Allow user to close dialog without breaking the page

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST display all existing mappings from the user_mapping table that match the edited user's email address within the user edit dialog
- **FR-002**: System MUST show each mapping with its domain, AWS account ID, or both (whichever are populated)
- **FR-003**: System MUST provide an "Add Mapping" action that allows administrators to create new mappings for the user
- **FR-004**: System MUST validate that at least one of domain or AWS account ID is provided when adding or editing a mapping
- **FR-005**: System MUST validate AWS account IDs as exactly 12 numeric digits when provided
- **FR-006**: System MUST validate domains to contain only lowercase letters, numbers, dots, and hyphens when provided
- **FR-007**: System MUST prevent creation of duplicate mappings (same combination of email, domain, and AWS account ID)
- **FR-008**: System MUST provide a "Delete" action for each mapping with confirmation before deletion
- **FR-009**: System MUST provide an "Edit" action for each mapping that allows changing the domain and/or AWS account ID
- **FR-010**: System MUST normalize email addresses and domains to lowercase before saving
- **FR-011**: System MUST display clear error messages when validation fails or operations cannot complete
- **FR-012**: System MUST show an empty state message when a user has no mappings
- **FR-013**: System MUST persist all mapping changes (add/edit/delete) to the user_mapping table immediately
- **FR-014**: System MUST restrict mapping management to administrators only (users with ADMIN role)

### Key Entities

- **User**: The application user being edited, identified by username and email address
- **UserMapping**: A record linking a user's email address to either a domain, an AWS account ID, or both; represents access grants
  - Contains: id, email, awsAccountId (optional), domain (optional), createdAt, updatedAt
  - Unique constraint: email + awsAccountId + domain combination must be unique
  - At least one of awsAccountId or domain must be non-null

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can view all mappings for a user within 2 seconds of opening the edit dialog
- **SC-002**: Administrators can successfully add a new mapping in under 30 seconds including validation
- **SC-003**: Administrators can successfully delete a mapping in under 15 seconds including confirmation
- **SC-004**: 100% of invalid mapping entries (missing both domain and AWS ID, or invalid formats) are rejected with clear error messages
- **SC-005**: System prevents 100% of duplicate mapping creation attempts
- **SC-006**: Administrators can manage mappings for users with up to 100 mappings without performance degradation
- **SC-007**: 95% of administrators successfully complete their first mapping add/delete operation without requiring support or documentation

### Assumptions

- The existing user edit dialog is accessible only to administrators
- The user_mapping table structure remains unchanged (email, awsAccountId, domain, id, createdAt, updatedAt)
- The application already has appropriate backend endpoints for CRUD operations on user mappings, or these will be created as part of implementation
- Email addresses in the User table and user_mapping table use the same format and can be reliably matched
- The UI framework supports inline editing or modal dialogs for managing list items
- Network latency for database operations is within normal web application parameters (< 500ms per operation)
