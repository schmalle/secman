# Feature Specification: Future User Mapping Support

**Feature Branch**: `042-future-user-mappings`
**Created**: 2025-11-07
**Status**: Draft
**Input**: User description: "The existing mapping upload must be extended / validated, so that mappings can also be stored for currently not existing users. If this users gets active, the mapping must be applied."

## Clarifications

### Session 2025-11-07

- Q: What uniqueness constraint should apply to email addresses in the mapping table when users don't exist yet? → A: Email must be unique across all mappings (future and active) - prevents duplicate mappings for same email
- Q: What happens if a pre-existing future user mapping conflicts with a new mapping assignment during user creation? → A: Pre-existing mapping wins - user creation proceeds, pre-configured mapping is applied, any conflicting new mapping is rejected/ignored
- Q: What level of detail should audit logging capture for future user mapping operations? → A: Minimal logging - only log successful mapping application (timestamp + email)
- Q: What happens to the mapping record after it has been successfully applied to a newly created user? → A: Keep mapping record - retain with appliedAt timestamp set, marking it as applied (historical record)
- Q: How should the UI display mappings in different states (future/active/applied historical)? → A: Separate tabs - "Current Mappings" tab (future + active) and "Applied History" tab (applied mappings with appliedAt timestamp)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Upload Mappings for Future Users (Priority: P1)

An administrator needs to prepare user mappings for employees who will join the company next month. They want to upload a mapping file that includes email addresses for these future users along with their AWS account IDs and domain assignments. When these users are eventually created in the system (either manually or through auto-provisioning), their mappings should automatically apply.

**Why this priority**: This is the core functionality that enables proactive user management. Without this, administrators must manually update mappings after each new user is created, creating administrative overhead and potential security gaps during the transition period.

**Independent Test**: Can be fully tested by uploading a mapping file with non-existent user emails, creating those users later, and verifying the mappings are automatically applied and grant correct asset access.

**Acceptance Scenarios**:

1. **Given** I am an administrator, **When** I upload an Excel/CSV file containing mappings for users that don't exist yet, **Then** the system accepts the file and stores the mappings without errors
2. **Given** a mapping exists for a non-existent user email, **When** that user is created in the system (manually or via auto-provisioning), **Then** the pre-configured mappings are automatically applied to the new user
3. **Given** I have uploaded mappings for future users, **When** I view the "Current Mappings" tab in the user mapping management page, **Then** I can see mappings for both existing and future users, with clear indication of which users don't exist yet
4. **Given** a mapping exists for a future user, **When** the user is eventually created, **Then** they immediately have access to assets based on their AWS account ID and domain mappings, and the mapping appears in the "Applied History" tab with an appliedAt timestamp

---

### User Story 2 - Update/Override Future User Mappings (Priority: P2)

An administrator realizes that a mapping file they uploaded yesterday contains an incorrect AWS account ID for a future employee. They need to upload a corrected mapping file before the employee's account is created, ensuring the correct mapping is applied when the user account is eventually provisioned.

**Why this priority**: This handles the common scenario of correcting mistakes in advance, preventing security issues or access problems when users are eventually onboarded.

**Independent Test**: Can be tested by uploading a mapping for a future user, then uploading a corrected mapping with different values, creating the user, and verifying the latest mapping is applied.

**Acceptance Scenarios**:

1. **Given** a mapping already exists for a future user email, **When** I upload a new mapping file with updated values for the same email, **Then** the system updates the existing mapping with the new values
2. **Given** I have corrected a future user's mapping before their account exists, **When** the user account is created, **Then** the corrected mapping is applied (not the original incorrect one)

---

### User Story 3 - Delete Future User Mappings (Priority: P3)

An administrator learns that a planned employee hire has been cancelled. They need to remove the mapping that was created for that future user so it doesn't accidentally get applied if someone else with the same email is created later.

**Why this priority**: This is important for data hygiene and security, but less critical than creating and updating mappings since it handles edge cases.

**Independent Test**: Can be tested by creating a future user mapping, deleting it via the management interface, then creating a user with that email and verifying no mapping is applied.

**Acceptance Scenarios**:

1. **Given** a mapping exists for a future user, **When** I delete the mapping from the user mapping management page, **Then** the mapping is removed and will not be applied when a user with that email is created
2. **Given** I have deleted a future user mapping, **When** I later upload a file with that same email, **Then** a new mapping can be created normally

---

### Edge Cases

- What happens when a mapping file contains both existing and non-existent user emails in the same upload? (System processes both - updates existing user mappings and creates future user mappings)
- How does the system handle duplicate email addresses within a single upload file when some map to existing users and others to future users? (Email uniqueness constraint prevents duplicates - last occurrence in file wins for the update/upsert operation)
- What happens if an admin uploads a mapping for a future user, then deletes it, then uploads it again before the user is created? (Normal create operation - deletion removes the mapping, re-upload creates a new one)
- How does the system behave when a user is created via OAuth auto-provisioning with an email that has a pre-existing future user mapping? (Pre-existing mapping is automatically applied, user gains immediate asset access)
- What happens if a future user mapping exists for email "john@example.com" with AWS account "123456789012", but when the user is created, a different mapping process also tries to assign a different AWS account? (Pre-existing future user mapping takes precedence - conflicting new mapping assignment is rejected/ignored)
- How does case sensitivity work for email addresses in future user mappings (e.g., "John@Example.com" vs "john@example.com")? (Case-insensitive matching per FR-012)
- What happens when a mapping file contains scientific notation for AWS account IDs (existing edge case that must still work)? (System parses scientific notation correctly as per existing validation rules in FR-014)

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST accept user mapping uploads (Excel/CSV) that contain email addresses for users who do not yet exist in the system
- **FR-002**: System MUST store future user mappings separately or mark them distinctly from active user mappings
- **FR-003**: System MUST validate all mapping data (email format, 12-digit AWS account ID, domain format) regardless of whether the user exists
- **FR-004**: System MUST automatically detect when a new user is created whose email matches a stored future user mapping
- **FR-005**: System MUST automatically apply the stored mappings (AWS account ID, domain) to newly created users when their email matches a future user mapping
- **FR-005a**: System MUST give precedence to pre-existing future user mappings over any conflicting new mapping assignments during user creation - pre-existing mapping wins and conflicting new mappings are rejected
- **FR-005b**: System MUST retain mapping records after successful application, marking them with the appliedAt timestamp to maintain historical audit trail
- **FR-006**: System MUST apply future user mappings regardless of how the user is created (manual creation, OAuth auto-provisioning, or any other method)
- **FR-007**: System MUST handle mixed uploads where some email addresses correspond to existing users and others to future users, processing both categories appropriately
- **FR-008**: System MUST display mappings in the user mapping management interface using separate tabs: "Current Mappings" tab showing future and active user mappings with clear indication of user existence status, and "Applied History" tab showing applied mappings with appliedAt timestamps
- **FR-009**: System MUST allow administrators to update/override future user mappings before the user is created
- **FR-010**: System MUST allow administrators to delete future user mappings
- **FR-011**: System MUST handle duplicate email addresses in uploads by updating existing mappings (existing behavior should extend to future users)
- **FR-012**: System MUST perform case-insensitive email matching when applying future user mappings to newly created users
- **FR-013**: System MUST log successful mapping applications to newly created users with timestamp and email address for audit purposes (minimal logging)
- **FR-014**: System MUST maintain all existing validation rules (email format, 12-digit AWS account, domain format, scientific notation handling) for future user mappings
- **FR-015**: System MUST ensure that when a future user mapping is applied to a newly created user, the user immediately gains access to assets according to the unified access control rules (AWS account ID match, domain match)

### Key Entities

- **UserMapping (Extended)**: Represents the association between a user email and their AWS account ID/domain. Must now support entries where the email does not correspond to an existing user. Key attributes: email (required, unique across all mappings), awsAccountId (nullable, 12 digits), domain (nullable), userExists flag (boolean or reference to User entity), createdAt, appliedAt (nullable timestamp when mapping was applied to a user - null indicates future user mapping not yet applied, non-null indicates applied mapping serving as historical record). Uniqueness constraint: email must be unique across all mapping records regardless of whether user exists or not. Lifecycle: Mapping records are retained after application for audit purposes.

- **User**: Existing entity representing system users. No structural changes needed, but user creation process must trigger mapping lookup and application.

### Non-Functional Requirements

- **NFR-001**: Mapping application process must complete within 2 seconds of user creation to ensure immediate asset access
- **NFR-002**: System must support at least 10,000 future user mappings without performance degradation
- **NFR-003**: Future user mapping storage must not significantly increase database size (estimated <1KB per mapping)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can successfully upload mapping files containing emails for non-existent users without errors or warnings
- **SC-002**: When a user is created whose email matches a future user mapping, the mapping is automatically applied within 2 seconds
- **SC-003**: Users who are created with pre-configured mappings immediately have access to assets based on those mappings without requiring manual intervention
- **SC-004**: Administrators can view, update, and delete future user mappings through the existing management interface using separate tabs for current mappings and applied history
- **SC-005**: 100% of future user mappings are successfully applied when users are created, with no manual intervention required
- **SC-006**: Reduce administrative overhead by eliminating the need to manually configure mappings after user creation

### Assumptions

- The existing UserMapping table structure can be extended to support entries without a corresponding user, or a new related table can be created
- User creation process (manual, OAuth auto-provisioning) can be extended to include a mapping lookup and application step
- Email addresses are the unique identifier for matching future users to their mappings
- Email matching should be case-insensitive to handle variations in capitalization
- The existing unified access control system (AWS account ID matching, domain matching) will work correctly once mappings are applied to new users
- Administrators are responsible for ensuring email addresses in future user mappings are correct
- There is no requirement for bulk import of users with simultaneous mapping application (users are created individually or through standard auto-provisioning)

### Dependencies

- Existing UserMapping entity and import functionality (Feature 013/016)
- User creation flows (manual creation, OAuth auto-provisioning from Feature 041)
- Unified Access Control system (AWS account and domain matching)
- Excel/CSV import functionality with validation

### Out of Scope

- Bulk user creation functionality (users are still created individually)
- Email verification or validation that email addresses in future user mappings correspond to real people
- Notification system to alert administrators when future user mappings are applied
- Expiration or time-to-live for future user mappings (mappings persist indefinitely until applied or manually deleted)
- Migration of existing manual mapping workflows (this feature adds capability, doesn't replace existing processes)
- Mapping preview or "what-if" analysis showing which assets a future user would access
