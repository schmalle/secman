# Feature Specification: Last Admin Protection

**Feature Branch**: `037-last-admin-protection`
**Created**: 2025-10-31
**Status**: Draft
**Input**: User description: "implement a logic that the last user with role ADMIN cannot be deleted"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Prevent System Lockout by Last Admin Deletion (Priority: P1)

As a system administrator, I need the system to prevent deletion of the last remaining ADMIN user, so that the system doesn't become permanently inaccessible due to having no administrators.

**Why this priority**: This is the core safety requirement. Without this protection, the entire system could become unusable if the last admin is deleted, requiring database-level intervention or system reset.

**Independent Test**: Can be fully tested by creating a single ADMIN user, attempting to delete them, and verifying the deletion is blocked with an appropriate error message. Delivers critical system integrity protection.

**Acceptance Scenarios**:

1. **Given** there is exactly one user with ADMIN role in the system, **When** an administrator attempts to delete that user, **Then** the system blocks the deletion and displays an error message "Cannot delete the last administrator. At least one ADMIN user must remain in the system."
2. **Given** there are multiple users with ADMIN role in the system, **When** an administrator deletes one ADMIN user (but not the last), **Then** the deletion succeeds normally.
3. **Given** there is exactly one user with ADMIN role, **When** that admin user attempts to delete their own account, **Then** the system blocks the deletion with an appropriate error message.

---

### User Story 2 - Clear Feedback for Blocked Deletions (Priority: P2)

As an administrator attempting to delete a user, I need clear, informative error messages when deletion is blocked, so that I understand why the action failed and what steps are needed to proceed.

**Why this priority**: Good user experience requires clear communication. Without proper feedback, administrators may be confused about why the deletion fails or repeatedly attempt the same action.

**Independent Test**: Can be tested independently by triggering the last-admin deletion scenario and verifying the error message is clear, actionable, and appears in the appropriate UI location (toast notification, modal, inline message).

**Acceptance Scenarios**:

1. **Given** a deletion attempt is blocked due to last-admin protection, **When** the error is displayed, **Then** the message explains the reason and suggests adding another admin before deletion.
2. **Given** the blocked deletion occurs via the UI, **When** the error message appears, **Then** it is displayed prominently (e.g., as an error toast or modal) and remains visible until acknowledged.
3. **Given** the blocked deletion occurs via API, **When** the error response is returned, **Then** it includes appropriate HTTP status code (409 Conflict or 403 Forbidden) and structured error details.

---

### User Story 3 - Role Change Protection for Last Admin (Priority: P3)

As a system administrator, I need the system to prevent removing the ADMIN role from the last remaining administrator, so that role modifications don't accidentally lock the system.

**Why this priority**: While less common than direct deletion, role changes can achieve the same problematic outcome. This completes the protection against all paths that could result in zero admins.

**Independent Test**: Can be tested independently by attempting to remove the ADMIN role from the only admin user and verifying it's blocked, delivering protection against indirect admin removal.

**Acceptance Scenarios**:

1. **Given** there is exactly one user with ADMIN role, **When** an administrator attempts to remove the ADMIN role from that user, **Then** the system blocks the change and displays an error message.
2. **Given** there are multiple ADMIN users, **When** an administrator removes the ADMIN role from one (but not the last), **Then** the role change succeeds.
3. **Given** the last ADMIN user has multiple roles (e.g., ADMIN and VULN), **When** an administrator attempts to remove only the ADMIN role, **Then** the system blocks the change even though other roles remain.

---

### Edge Cases

- What happens when an admin user is both the last ADMIN and has other roles (USER, VULN, etc.)? Should deletion be blocked even if other roles exist?
  - **Answer**: Yes, deletion should be blocked. The ADMIN role is the critical factor, not the presence of other roles.

- How does the system handle concurrent deletion attempts of the last two admin users?
  - **Answer**: Database-level transaction isolation or optimistic locking should ensure only one deletion succeeds, leaving at least one admin remaining.

- What happens during bulk deletion operations that include the last admin user?
  - **Answer**: The entire bulk operation should be validated before execution. If it would result in zero admins, the operation is rejected entirely.

- What happens if the last admin is deleted through direct database manipulation?
  - **Answer**: While this bypasses application-level protection, it's considered an administrative action outside normal system operation. Recovery would require database-level intervention.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST prevent deletion of any user who is the sole remaining user with the ADMIN role
- **FR-002**: System MUST perform an accurate count of active ADMIN users before allowing any user deletion
- **FR-003**: System MUST display a clear error message when deletion is blocked, stating "Cannot delete the last administrator. At least one ADMIN user must remain in the system."
- **FR-004**: System MUST apply this protection to all deletion pathways: UI delete button, API delete endpoint, and bulk delete operations
- **FR-005**: System MUST return appropriate error responses for API requests (HTTP 409 Conflict with structured error details)
- **FR-006**: System MUST validate admin count at the service layer before database deletion is attempted
- **FR-007**: System MUST allow deletion of ADMIN users when two or more ADMIN users exist in the system
- **FR-008**: System MUST prevent removing the ADMIN role from the last remaining administrator
- **FR-009**: System MUST apply protection consistently whether the user is deleting themselves or another admin user
- **FR-010**: System MUST validate bulk deletion operations to ensure at least one ADMIN user remains after the operation completes

### Key Entities

- **User**: Represents system users with attributes including username, email, and a collection of roles. The ADMIN role is the critical attribute for this feature.
- **Role**: Represents user permissions. The ADMIN role is special as it provides system administration capabilities. A user can have multiple roles simultaneously.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of attempts to delete the last ADMIN user are blocked successfully
- **SC-002**: Error messages appear within 1 second of deletion attempt and clearly explain the reason for blocking
- **SC-003**: System never enters a state with zero ADMIN users through normal application operations
- **SC-004**: Administrators can successfully delete ADMIN users when two or more admins exist, maintaining normal workflow efficiency
- **SC-005**: Bulk delete operations validate admin count in under 2 seconds for operations affecting up to 100 users
