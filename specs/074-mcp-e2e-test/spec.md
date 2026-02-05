# Feature Specification: MCP E2E Test - User-Asset-Workgroup Workflow

**Feature Branch**: `074-mcp-e2e-test`
**Created**: 2026-02-04
**Status**: Draft
**Input**: User description: "MCP E2E test script for complete user-asset-workgroup workflow using 1Password credentials"

## Clarifications

### Session 2026-02-04

- Q: What language/technology should be used for the test script? → A: Bash script with `curl` and `jq` for MCP HTTP calls
- Q: How should the test asset be cleaned up without affecting other assets? → A: Delete specific test asset by ID using targeted deletion
- Addition: New MCP tools must be reflected in the MCP key generation UI for permission selection

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Complete MCP Workflow Validation (Priority: P1)

A developer needs to validate the complete MCP workflow for user access control by running an automated E2E test script. The script creates test data (user, asset, vulnerability, workgroup), verifies access control works correctly, and cleans up after itself.

**Why this priority**: This is the core deliverable - validating that MCP operations work correctly together and that workgroup-based access control functions as expected.

**Independent Test**: Can be fully tested by running the test script against a local secman instance. Success is measured by the script completing without errors and the TEST user seeing exactly one asset.

**Acceptance Scenarios**:

1. **Given** a running secman instance with ADMIN credentials available via environment variables, **When** the test script runs, **Then** a TEST user with VULN role is created successfully
2. **Given** the TEST user exists, **When** an asset is added with a CRITICAL vulnerability (10 days open), **Then** the asset and vulnerability are created and linked correctly
3. **Given** the asset exists, **When** a workgroup is created and the asset is assigned to it, **Then** the workgroup contains the asset
4. **Given** the workgroup exists with the asset, **When** the TEST user is assigned to the workgroup, **Then** the TEST user has access to the asset
5. **Given** the TEST user is assigned to the workgroup, **When** switching to TEST user context and listing assets, **Then** exactly one asset is returned (the test asset)
6. **Given** test data exists, **When** cleanup runs, **Then** all test-created entities are removed (user, asset, workgroup)

---

### User Story 2 - Missing MCP Tool Implementation (Priority: P1)

The test script requires MCP operations that do not currently exist. These missing operations must be implemented before the test can run entirely through MCP.

**Why this priority**: Without these tools, the test script cannot be "all driven via MCP" as required.

**Independent Test**: Each new MCP tool can be tested independently by calling it via the MCP protocol and verifying the operation succeeds.

**Acceptance Scenarios**:

1. **Given** a workgroup name and description, **When** calling `create_workgroup` MCP tool, **Then** a new workgroup is created and its ID returned
2. **Given** a workgroup ID and asset IDs, **When** calling `assign_assets_to_workgroup` MCP tool, **Then** the assets are assigned to the workgroup
3. **Given** a workgroup ID and user IDs, **When** calling `assign_users_to_workgroup` MCP tool, **Then** the users are assigned to the workgroup
4. **Given** a workgroup ID, **When** calling `delete_workgroup` MCP tool, **Then** the workgroup is deleted

---

### User Story 3 - Secure Credential Management (Priority: P2)

The test script uses 1Password CLI to securely retrieve credentials rather than storing them in plain text. Environment variables reference 1Password secret URIs.

**Why this priority**: Security best practice - credentials should never be hardcoded or stored in plain text in test scripts.

**Independent Test**: Can be tested by configuring 1Password URIs and verifying the script retrieves credentials successfully before making API calls.

**Acceptance Scenarios**:

1. **Given** environment variables `SECMAN_USERNAME`, `SECMAN_PASSWORD`, `SECMAN_API_KEY` set with 1Password URIs (e.g., `op://test/secman/SECMAN_USERNAME`), **When** the test script starts, **Then** credentials are resolved using `op run` or `op read`
2. **Given** 1Password credentials are resolved, **When** authenticating to secman, **Then** authentication succeeds and a session token is obtained
3. **Given** 1Password is not available or credentials are missing, **When** the test script starts, **Then** a clear error message is shown explaining the missing prerequisites

---

### User Story 4 - MCP Key Generation UI Update (Priority: P1)

When new MCP tools are added, the MCP key generation UI must be updated to display these tools so users can select appropriate permissions when creating API keys.

**Why this priority**: Without UI updates, users cannot grant permissions for the new workgroup management tools when generating MCP keys, making the tools unusable in practice.

**Independent Test**: Can be tested by navigating to the MCP key generation UI and verifying all new tools appear in the tool selection list.

**Acceptance Scenarios**:

1. **Given** the new MCP tools are implemented, **When** a user navigates to the MCP key generation UI, **Then** the new workgroup tools (`create_workgroup`, `assign_assets_to_workgroup`, `assign_users_to_workgroup`, `delete_workgroup`) are visible in the tool list
2. **Given** the new `delete_asset` tool is implemented, **When** a user navigates to the MCP key generation UI, **Then** the `delete_asset` tool is visible in the tool list
3. **Given** a user selects workgroup management tools, **When** generating an MCP key, **Then** the generated key includes permissions for the selected tools

---

### Edge Cases

- What happens when the TEST user already exists from a previous failed run? The script should handle cleanup gracefully or detect and remove stale test data at startup.
- What happens when 1Password CLI (`op`) is not installed? The script should fail fast with a clear error message.
- What happens when the secman backend is not reachable? The script should timeout with a meaningful error.
- What happens when a workgroup with the same name exists? The script should use unique names (e.g., with timestamp) or clean up first.
- What happens if cleanup fails midway? Partial cleanup should be handled gracefully with warnings about remaining artifacts.

## Requirements *(mandatory)*

### Functional Requirements

**New MCP Tools:**
- **FR-001**: System MUST provide MCP tool `create_workgroup` that creates a workgroup with specified name and description (ADMIN role required)
- **FR-002**: System MUST provide MCP tool `assign_assets_to_workgroup` that assigns assets to a workgroup by IDs (ADMIN role required)
- **FR-003**: System MUST provide MCP tool `assign_users_to_workgroup` that assigns users to a workgroup by IDs (ADMIN role required)
- **FR-004**: System MUST provide MCP tool `delete_workgroup` that removes a workgroup by ID (ADMIN role required)
- **FR-004a**: System MUST provide MCP tool `delete_asset` that removes a specific asset by ID (ADMIN role required)

**Test Script Workflow:**
- **FR-005**: Test script MUST use 1Password CLI to resolve credentials from environment variable URIs
- **FR-006**: Test script MUST authenticate using provided credentials before performing MCP operations
- **FR-007**: Test script MUST create a user named "TEST" with VULN role via MCP
- **FR-008**: Test script MUST create an asset via MCP (using `add_vulnerability` which auto-creates assets)
- **FR-009**: Test script MUST add a CRITICAL vulnerability to the asset that is 10 days old via MCP
- **FR-010**: Test script MUST create a workgroup and assign the asset to it via MCP
- **FR-011**: Test script MUST assign the TEST user to the workgroup via MCP
- **FR-012**: Test script MUST switch MCP context to TEST user and list visible assets
- **FR-013**: Test script MUST verify TEST user sees exactly one asset (the test asset)
- **FR-014**: Test script MUST clean up all test data after execution by deleting specific entities by ID (user via `delete_user`, asset via `delete_asset`, workgroup via `delete_workgroup`)

**MCP Key Generation UI:**
- **FR-019**: MCP key generation UI MUST display all new workgroup tools (`create_workgroup`, `assign_assets_to_workgroup`, `assign_users_to_workgroup`, `delete_workgroup`) in the tool selection list
- **FR-020**: MCP key generation UI MUST display the `delete_asset` tool in the tool selection list
- **FR-021**: MCP key generation UI MUST allow users to select/deselect individual new tools when configuring key permissions

**Test Script Requirements:**
- **FR-015**: Test script MUST be stored in the `tests` folder
- **FR-016**: Test script MUST exit with code 0 on success, non-zero on failure
- **FR-017**: Test script MUST not log or echo credential values to output
- **FR-018**: Test script MUST be implemented as a Bash script using `curl` for HTTP calls and `jq` for JSON parsing

### Key Entities

- **User**: Test user with VULN role, identified by username and email
- **Asset**: Test asset with a name, type, and associated vulnerability
- **Vulnerability**: CRITICAL severity vulnerability linked to the asset, with detection date 10 days in the past
- **Workgroup**: Container that groups users and assets for access control purposes

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Test script completes full workflow (create, verify, cleanup) in under 2 minutes on localhost
- **SC-002**: All 5 new MCP tools (create_workgroup, assign_assets_to_workgroup, assign_users_to_workgroup, delete_workgroup, delete_asset) are implemented and callable
- **SC-003**: TEST user, when listing assets via MCP, sees exactly 1 asset after workgroup assignment
- **SC-004**: After cleanup, no test artifacts remain in the system (user, asset, workgroup all deleted)
- **SC-005**: Test script works with 1Password-referenced environment variables without exposing credentials in logs
- **SC-006**: Security review confirms no credential leakage, proper authorization checks, and input validation in new MCP tools
- **SC-007**: MCP key generation UI displays all 5 new tools and allows permission selection

## Assumptions

- `curl` and `jq` are installed on the test machine for HTTP calls and JSON parsing
- 1Password CLI (`op`) version 2.x is installed and configured on the test machine
- The secman backend is running and accessible at localhost (default configuration)
- The ADMIN user credentials stored in 1Password have sufficient permissions to create users, assets, and workgroups
- MCP endpoint is available at the standard secman API path
- Test user email will use `test@example.com` format
- Existing `add_user`, `add_vulnerability`, `delete_user`, and `get_assets` MCP tools function correctly
- User delegation via `X-MCP-User-Email` header is the mechanism for switching user context in MCP
