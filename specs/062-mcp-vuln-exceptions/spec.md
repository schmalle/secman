# Feature Specification: MCP Tools for Overdue Vulnerabilities and Exception Handling

**Feature Branch**: `062-mcp-vuln-exceptions`
**Created**: 2026-01-11
**Status**: Draft
**Input**: User description: "add a functionality to show all assets with overdue vulnerabilities for the current user via MCP, add also functionality via MCP to exception handling (request, show, approve, decline), respect here existing RBAC implementations. Add newly create functionality to documentation."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View My Overdue Assets via MCP (Priority: P1)

A security analyst or administrator wants to use an AI assistant to ask "What assets do I have with overdue vulnerabilities?" and receive a list of assets with vulnerabilities that have exceeded their remediation deadline, filtered based on their workgroup access and roles.

**Why this priority**: This provides immediate visibility into the most critical security posture issue - vulnerabilities that have exceeded their allowed remediation time. Direct value for security operations and compliance.

**Independent Test**: Can be tested by invoking the MCP tool and verifying that assets with overdue vulnerabilities are returned, filtered by the user's access rights.

**Acceptance Scenarios**:

1. **Given** a user with ADMIN role connected via MCP with user delegation, **When** they invoke the `get_overdue_assets` tool, **Then** they receive a list of all assets with overdue vulnerabilities in the system.

2. **Given** a user with VULN role connected via MCP with user delegation, **When** they invoke the `get_overdue_assets` tool, **Then** they receive only assets from their assigned workgroups that have overdue vulnerabilities.

3. **Given** a user with no ADMIN or VULN role connected via MCP, **When** they invoke the `get_overdue_assets` tool, **Then** they receive an authorization error.

4. **Given** a user connected via MCP, **When** they invoke `get_overdue_assets` with a severity filter, **Then** only assets with overdue vulnerabilities at or above that severity are returned.

---

### User Story 2 - Request Vulnerability Exception via MCP (Priority: P1)

A user wants to use an AI assistant to create an exception request for a vulnerability they cannot immediately remediate, providing justification and an expiration date. The request should follow the existing approval workflow.

**Why this priority**: Exception requests are critical for managing vulnerabilities that cannot be immediately fixed due to business constraints. This enables security workflows via AI assistants.

**Independent Test**: Can be tested by invoking the MCP tool and verifying an exception request is created in PENDING status (or auto-approved for ADMIN/SECCHAMPION).

**Acceptance Scenarios**:

1. **Given** a regular user connected via MCP with user delegation, **When** they invoke `create_exception_request` with vulnerability ID, reason (50+ characters), and expiration date, **Then** a new exception request is created with PENDING status.

2. **Given** a user with ADMIN or SECCHAMPION role connected via MCP, **When** they invoke `create_exception_request`, **Then** the exception request is auto-approved (status APPROVED immediately).

3. **Given** a user connected via MCP, **When** they invoke `create_exception_request` with a reason less than 50 characters, **Then** they receive a validation error.

4. **Given** a user connected via MCP, **When** they invoke `create_exception_request` with an expiration date in the past, **Then** they receive a validation error.

---

### User Story 3 - View My Exception Requests via MCP (Priority: P2)

A user wants to use an AI assistant to ask "Show me my exception requests" to see the status of their pending and past exception requests.

**Why this priority**: Users need visibility into their own requests to track approval status and manage their workflow.

**Independent Test**: Can be tested by invoking the MCP tool and verifying the user receives only their own exception requests.

**Acceptance Scenarios**:

1. **Given** a user connected via MCP with user delegation, **When** they invoke `get_my_exception_requests`, **Then** they receive a list of their own exception requests.

2. **Given** a user connected via MCP with user delegation, **When** they invoke `get_my_exception_requests` with status filter `PENDING`, **Then** they receive only their pending requests.

3. **Given** a user who has never created exception requests, **When** they invoke `get_my_exception_requests`, **Then** they receive an empty list with count 0.

---

### User Story 4 - View Pending Exception Requests for Approval via MCP (Priority: P2)

An ADMIN or SECCHAMPION wants to use an AI assistant to ask "Show me pending exception requests that need approval" to review and take action on requests awaiting their decision.

**Why this priority**: Approvers need to see the queue of pending requests to perform their approval duties.

**Independent Test**: Can be tested by invoking the MCP tool with an ADMIN/SECCHAMPION user and verifying all pending requests system-wide are returned.

**Acceptance Scenarios**:

1. **Given** a user with ADMIN role connected via MCP, **When** they invoke `get_pending_exception_requests`, **Then** they receive all pending exception requests in the system, sorted oldest first.

2. **Given** a user with SECCHAMPION role connected via MCP, **When** they invoke `get_pending_exception_requests`, **Then** they receive all pending exception requests in the system.

3. **Given** a regular user connected via MCP (without ADMIN or SECCHAMPION role), **When** they invoke `get_pending_exception_requests`, **Then** they receive an authorization error.

---

### User Story 5 - Approve Exception Request via MCP (Priority: P2)

An ADMIN or SECCHAMPION wants to use an AI assistant to approve a pending exception request, optionally adding a review comment.

**Why this priority**: Core approval workflow capability needed to process the exception queue.

**Independent Test**: Can be tested by invoking the MCP tool and verifying the request status changes to APPROVED.

**Acceptance Scenarios**:

1. **Given** a user with ADMIN role connected via MCP and a pending exception request exists, **When** they invoke `approve_exception_request` with the request ID, **Then** the request status changes to APPROVED and a VulnerabilityException is created.

2. **Given** a user with SECCHAMPION role connected via MCP, **When** they invoke `approve_exception_request` with an optional comment, **Then** the comment is recorded with the approval.

3. **Given** a regular user connected via MCP, **When** they invoke `approve_exception_request`, **Then** they receive an authorization error.

4. **Given** an ADMIN connected via MCP and a request that is already APPROVED, **When** they invoke `approve_exception_request`, **Then** they receive an error indicating the request cannot be approved (invalid status transition).

---

### User Story 6 - Reject Exception Request via MCP (Priority: P2)

An ADMIN or SECCHAMPION wants to use an AI assistant to reject a pending exception request, providing a mandatory rejection reason.

**Why this priority**: Core approval workflow capability needed to process the exception queue.

**Independent Test**: Can be tested by invoking the MCP tool and verifying the request status changes to REJECTED.

**Acceptance Scenarios**:

1. **Given** a user with ADMIN role connected via MCP and a pending exception request exists, **When** they invoke `reject_exception_request` with the request ID and a comment (10+ characters), **Then** the request status changes to REJECTED.

2. **Given** a user with SECCHAMPION role connected via MCP, **When** they invoke `reject_exception_request` without a comment, **Then** they receive a validation error (comment is required for rejection).

3. **Given** a regular user connected via MCP, **When** they invoke `reject_exception_request`, **Then** they receive an authorization error.

---

### User Story 7 - Cancel My Exception Request via MCP (Priority: P3)

A user wants to use an AI assistant to cancel their own pending exception request that they no longer need.

**Why this priority**: Allows users to manage their own requests, but less critical than core create/approve/reject workflow.

**Independent Test**: Can be tested by invoking the MCP tool and verifying the request status changes to CANCELLED.

**Acceptance Scenarios**:

1. **Given** a user connected via MCP who has a PENDING exception request, **When** they invoke `cancel_exception_request` with their request ID, **Then** the request status changes to CANCELLED.

2. **Given** a user connected via MCP, **When** they try to cancel another user's request, **Then** they receive an authorization error.

3. **Given** a user connected via MCP who has an APPROVED exception request, **When** they invoke `cancel_exception_request`, **Then** they receive an error (only PENDING requests can be cancelled).

---

### Edge Cases

- What happens when a user requests an exception for a vulnerability they don't have access to? → Authorization error returned.
- What happens when two approvers try to approve the same request simultaneously? → First-approver-wins using optimistic locking; second receives conflict error.
- What happens when the delegated user's account is disabled? → MCP request fails with delegation error.
- How does the system handle expired exception requests? → Expired requests remain in EXPIRED status; this tool shows current status.

## Requirements *(mandatory)*

### Functional Requirements

**Overdue Assets Tool:**
- **FR-001**: System MUST provide an MCP tool `get_overdue_assets` that returns assets with overdue vulnerabilities.
- **FR-002**: System MUST filter overdue assets based on the delegated user's workgroup access (VULN role sees only their workgroups, ADMIN sees all).
- **FR-003**: System MUST require ADMIN or VULN role to access the `get_overdue_assets` tool.
- **FR-004**: System MUST support filtering overdue assets by minimum severity level.
- **FR-005**: System MUST support pagination for the overdue assets results.

**Exception Request Creation:**
- **FR-006**: System MUST provide an MCP tool `create_exception_request` to create new vulnerability exception requests.
- **FR-007**: System MUST require vulnerability ID, reason (50-2048 characters), and future expiration date as input. Scope parameter is optional (default: SINGLE_VULNERABILITY; also accepts CVE_PATTERN).
- **FR-008**: System MUST auto-approve exception requests created by users with ADMIN or SECCHAMPION role.
- **FR-009**: System MUST create requests in PENDING status for regular users.
- **FR-010**: System MUST prevent duplicate active exception requests for the same vulnerability by the same user.

**View Exception Requests:**
- **FR-011**: System MUST provide an MCP tool `get_my_exception_requests` for users to view their own exception requests.
- **FR-012**: System MUST support filtering by status (PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED).
- **FR-013**: System MUST support pagination for exception request results.

**Pending Requests for Approval:**
- **FR-014**: System MUST provide an MCP tool `get_pending_exception_requests` for ADMIN/SECCHAMPION to view all pending requests.
- **FR-015**: System MUST sort pending requests by creation date (oldest first) for fair processing.
- **FR-016**: System MUST require ADMIN or SECCHAMPION role to access pending requests.

**Approve/Reject Workflow:**
- **FR-017**: System MUST provide an MCP tool `approve_exception_request` for ADMIN/SECCHAMPION to approve requests.
- **FR-018**: System MUST provide an MCP tool `reject_exception_request` for ADMIN/SECCHAMPION to reject requests.
- **FR-019**: System MUST require a comment (10-1024 characters) for rejection.
- **FR-020**: System MUST use optimistic locking to handle concurrent approval/rejection (first-approver-wins).
- **FR-021**: System MUST create a VulnerabilityException entity upon approval.

**Cancel Request:**
- **FR-022**: System MUST provide an MCP tool `cancel_exception_request` for users to cancel their own PENDING requests.
- **FR-023**: System MUST only allow the original requester to cancel a request.
- **FR-024**: System MUST only allow cancellation of PENDING requests.

**Documentation:**
- **FR-025**: System MUST update MCP documentation to include all new tools with parameters and examples.

### Key Entities

- **VulnerabilityExceptionRequest**: Represents a user's request for an exception, with status workflow (PENDING → APPROVED/REJECTED/CANCELLED, APPROVED → EXPIRED).
- **VulnerabilityException**: The actual exception rule created upon approval that suppresses vulnerability reporting.
- **OutdatedAssetMaterializedView**: Pre-computed view of assets with overdue vulnerabilities used for efficient querying.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can retrieve their overdue assets list via MCP in under 3 seconds.
- **SC-002**: Exception request creation via MCP completes in under 2 seconds.
- **SC-003**: ADMIN/SECCHAMPION users can process (approve/reject) exception requests via MCP with immediate effect.
- **SC-004**: All new MCP tools respect the same RBAC rules as the existing REST API endpoints.
- **SC-005**: Users can successfully create, view, and cancel exception requests entirely via AI assistant interaction.
- **SC-006**: Approval workflow via MCP maintains audit trail identical to REST API usage.
- **SC-007**: Documentation includes clear examples for all new MCP tools enabling immediate user adoption.

## Clarifications

### Session 2026-01-11

- Q: Should the `create_exception_request` MCP tool require users to specify an exception scope? → A: Default to SINGLE_VULNERABILITY scope; scope parameter is optional.

## Assumptions

1. **User Delegation Required**: All MCP tools in this feature require User Delegation to be enabled and `X-MCP-User-Email` header to be provided to identify the acting user.
2. **Existing Materialized View**: The `OutdatedAssetMaterializedView` already exists and is refreshed periodically; no new view creation needed.
3. **Existing Service Layer**: The `VulnerabilityExceptionRequestService` and `OutdatedAssetService` contain all necessary business logic; MCP tools will delegate to these services.
4. **McpPermission Extension**: New MCP permissions may be added for exception handling if the existing permission set is insufficient.
5. **Scope Parameter**: Exception requests support both SINGLE_VULNERABILITY and CVE_PATTERN scopes as per existing implementation.
