# Feature Specification: MCP List Users Tool

**Feature Branch**: `060-mcp-list-users`
**Created**: 2026-01-04
**Status**: Draft
**Input**: User description: "implement a mcp function to show all users of secman. This function must only be available for admins, meaning in the MCP calls the email address of the authenticated users needs to be passed on"

## Clarifications

### Session 2026-01-04

- Q: Large user count handling - pagination vs full list? → A: Always return full list (no pagination) - rely on performance target

## User Scenarios & Testing *(mandatory)*

### User Story 1 - List All Users via MCP (Priority: P1)

An administrator using an AI assistant (via MCP protocol) needs to retrieve a list of all secman users to understand who has access to the system, their roles, and account status. The AI assistant calls the MCP tool with the admin's email address for authentication, and receives a structured list of all users.

**Why this priority**: This is the core and only functionality requested - listing all users with admin-only access. Without this, the feature has no value.

**Independent Test**: Can be fully tested by calling the MCP `list_users` tool with an admin user's email and verifying all users are returned with their essential information.

**Acceptance Scenarios**:

1. **Given** an MCP session with user delegation enabled for an ADMIN user, **When** the AI calls `list_users` tool, **Then** the tool returns a list of all users containing: id, username, email, roles, authSource, mfaEnabled, lastLogin, createdAt.

2. **Given** an MCP session with user delegation for an ADMIN user, **When** the system has 50 users, **Then** all 50 users are returned in the response.

3. **Given** an MCP session with user delegation enabled, **When** the delegated user email belongs to a user with ADMIN role, **Then** the tool executes successfully and returns user data.

4. **Given** an MCP session without user delegation (service account mode), **When** the AI calls `list_users` tool, **Then** the tool returns an authorization error (ADMIN role check cannot be performed without delegation).

---

### User Story 2 - Denied Access for Non-Admin Users (Priority: P1)

When a non-admin user's email is used in the MCP delegation, the list_users tool must deny access to protect user data privacy.

**Why this priority**: Security is critical - non-admin users must not be able to list all system users. This is equally important as the happy path.

**Independent Test**: Can be tested by calling the MCP tool with a non-admin user's delegated email and verifying an authorization error is returned.

**Acceptance Scenarios**:

1. **Given** an MCP session with user delegation for a USER role user, **When** the AI calls `list_users` tool, **Then** the tool returns an authorization error with code "ADMIN_REQUIRED".

2. **Given** an MCP session with user delegation for a VULN role user (not ADMIN), **When** the AI calls `list_users` tool, **Then** the tool returns an authorization error.

3. **Given** an MCP session with user delegation for a SECCHAMPION role user, **When** the AI calls `list_users` tool, **Then** the tool returns an authorization error (SECCHAMPION does not include ADMIN).

---

### Edge Cases

- What happens when the delegated user email doesn't exist in the system? Tool returns an authentication error before role check.
- What happens when the delegated user account is disabled/locked? The existing MCP delegation mechanism handles account validity checks.
- How does the system handle requests with very large user counts (10,000+ users)? Returns full list; performance target (2s for 1K users) provides adequate constraint.
- What user fields should be excluded from the response for security? Password hash is never exposed; only safe fields returned.

## Requirements *(mandatory)*

### Functional Requirements

**MCP Tool:**

- **FR-001**: MCP MUST provide a `list_users` tool that returns all users in the system
- **FR-002**: MCP `list_users` tool MUST require User Delegation to be enabled (delegated user email must be provided)
- **FR-003**: MCP `list_users` tool MUST verify the delegated user has ADMIN role before executing
- **FR-004**: MCP `list_users` tool MUST return authorization error if delegation is not enabled
- **FR-005**: MCP `list_users` tool MUST return authorization error if delegated user is not ADMIN

**Response Data:**

- **FR-006**: Response MUST include for each user: id, username, email, roles, authSource, mfaEnabled, createdAt
- **FR-007**: Response MUST include lastLogin timestamp (null if user never logged in)
- **FR-008**: Response MUST NOT include password hash or any sensitive authentication data
- **FR-009**: Response MUST include total user count in metadata

**Error Handling:**

- **FR-010**: Tool MUST return error code "ADMIN_REQUIRED" when delegated user lacks ADMIN role
- **FR-011**: Tool MUST return error code "DELEGATION_REQUIRED" when user delegation is not enabled

### Key Entities

- **User**: Core entity with id, username, email, roles (Set<Role>), authSource, mfaEnabled, lastLogin, createdAt - password hash excluded from responses
- **McpExecutionContext**: Existing context object that provides delegatedUserId, delegatedUserEmail, delegatedUserRoles, and isAdmin flag for access control checks
- **McpTool**: Interface for the new `list_users` tool to implement, following existing patterns (GetAssetsTool, GetRequirementsTool)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Admin users can retrieve complete user list in under 2 seconds for systems with up to 1,000 users
- **SC-002**: 100% of non-admin delegation attempts are rejected with clear error message
- **SC-003**: All user data returned matches current database state (no stale data)
- **SC-004**: Tool appears in MCP tool listing with accurate description and input schema
- **SC-005**: Zero sensitive data (password hashes) exposed in any response

## Assumptions

- The existing MCP User Delegation mechanism (McpExecutionContext with delegatedUserEmail, delegatedUserRoles, isAdmin) will be used for authentication
- The existing UserRepository provides methods to retrieve all users efficiently
- The tool will follow the existing pattern used by other admin-only or restricted MCP tools
- No pagination is required for MVP - full user list returned (typical secman installations have < 500 users)
- The tool will be registered in McpToolRegistry following the existing pattern
- Workgroup membership details are NOT included in the response (only user core data)
