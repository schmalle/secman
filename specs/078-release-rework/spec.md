# Feature Specification: Release Rework

**Feature Branch**: `078-release-rework`
**Created**: 2026-02-06
**Status**: Draft
**Input**: Rework the release status model, add release context switching with UI elements, validate release-aware exports, and provide a full E2E test suite.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Release Context Switching (Priority: P1)

A user navigates secman and wants to view requirements as they existed in a specific release. By default, secman shows requirements from the ACTIVE release. The user selects a different release (e.g., a PREPARATION release) from a release selector in the UI. All requirement views, lists, and details immediately reflect the snapshot of requirements captured in that release. When the user switches back to the ACTIVE release, the original snapshot is restored.

**Why this priority**: This is the core behavioral change. Without release context switching, the updated status model and exports have no visible effect. Every other feature depends on users being able to select and view requirements in the context of a specific release.

**Independent Test**: Can be fully tested by creating two releases with different requirement snapshots, switching between them, and verifying the displayed requirements match the expected snapshot for each release.

**Acceptance Scenarios**:

1. **Given** two releases exist (Release 1.0 ACTIVE with Requirement A v1, Release 2.0 PREPARATION with Requirement A v2), **When** the user opens secman, **Then** requirements from Release 1.0 (ACTIVE) are displayed by default.
2. **Given** the user is viewing Release 1.0 (ACTIVE), **When** the user selects Release 2.0 (PREPARATION) from the release selector, **Then** the requirements list updates to show Requirement A v2 from Release 2.0's snapshot.
3. **Given** the user is viewing Release 2.0 (PREPARATION), **When** the user switches back to Release 1.0 (ACTIVE), **Then** the requirements list restores to show Requirement A v1.
4. **Given** only one release exists and it is ACTIVE, **When** the user opens secman, **Then** that release's requirements are shown and the selector indicates the active release.

---

### User Story 2 - Updated Release Status Model (Priority: P1)

An administrator manages release lifecycle using four clearly defined statuses. When a new release is created, it enters PREPARATION status. After requirements are updated and alignment begins, the release moves to ALIGNMENT. When a release is activated, it becomes ACTIVE (only one can be ACTIVE at a time). Previously ACTIVE releases move to ARCHIVED. The UI and all APIs reflect these four statuses consistently.

**Why this priority**: The updated status model underpins the release lifecycle. Without it, context switching and exports cannot distinguish release states correctly. This is co-equal with P1 because it defines the data model that all other stories rely on.

**Independent Test**: Can be tested by creating a release (verify it is PREPARATION), triggering alignment (verify it moves to ALIGNMENT), activating it (verify it becomes ACTIVE and the previously ACTIVE release becomes ARCHIVED), and confirming ARCHIVED releases are still accessible for viewing.

**Acceptance Scenarios**:

1. **Given** a user with ADMIN or RELEASE_MANAGER role, **When** they create a new release, **Then** the release is created with status PREPARATION.
2. **Given** a release in PREPARATION status, **When** the alignment process is started, **Then** the release status changes to ALIGNMENT.
3. **Given** a release in ALIGNMENT or PREPARATION status, **When** the admin activates the release, **Then** it becomes ACTIVE and any previously ACTIVE release becomes ARCHIVED.
4. **Given** an ARCHIVED or ACTIVE release, **When** a user selects it in the release selector, **Then** they can view its requirements snapshot but editing controls are hidden or disabled.
5. **Given** a PREPARATION or ALIGNMENT release, **When** a user selects it and edits a requirement, **Then** the edit modifies the live requirement and the release snapshot will reflect the change on next snapshot capture.
6. **Given** a release in ACTIVE status, **When** an admin attempts to delete it, **Then** the system prevents deletion and shows an appropriate error.

---

### User Story 3 - Release-Aware Requirement Export (Priority: P2)

A user selects a release and exports requirements. The export (Word or Excel) contains exactly the requirements as captured in that release's snapshot, not the current live requirements. This applies to all export formats (full export, filtered by use case, translated).

**Why this priority**: Export is a primary delivery mechanism for compliance and audit. It depends on release context switching (P1) being functional. Once context switching works, exports must respect the selected release.

**Independent Test**: Can be tested by creating two releases with different requirement content, exporting from each, and verifying each export file contains the correct requirement versions.

**Acceptance Scenarios**:

1. **Given** Release 1.0 (ACTIVE) contains Requirement A v1 and Release 2.0 (PREPARATION) contains Requirement A v2, **When** the user selects Release 2.0 and exports requirements as Excel, **Then** the Excel file contains Requirement A v2.
2. **Given** the user selects Release 1.0 and exports requirements as Word, **Then** the Word document contains Requirement A v1.
3. **Given** a release with 168 requirement snapshots, **When** the user exports all requirements, **Then** the export contains exactly 168 requirements matching the snapshot data.
4. **Given** the user selects a release and filters export by a specific use case, **When** they export, **Then** only requirements matching that use case within the release snapshot are included.

---

### User Story 4 - Full E2E Test Suite for Releases (Priority: P2)

An automated test script validates the complete release lifecycle end-to-end against a running secman instance. The test uses environment variables (SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY) for authentication. It exercises release creation, status transitions, requirement context switching, export validation, and cleanup.

**Why this priority**: E2E tests are essential for regression prevention and deployment confidence. They depend on all three prior stories being implemented. They validate the integrated behavior rather than individual components.

**Independent Test**: Can be run standalone with `./tests/release-e2e-test.sh` against any running secman instance with the required environment variables set.

**Acceptance Scenarios**:

1. **Given** SECMAN_USERNAME, SECMAN_PASSWORD, and SECMAN_API_KEY are set as environment variables, **When** the E2E test script is executed, **Then** it authenticates successfully and proceeds with test execution.
2. **Given** a clean test environment, **When** the E2E script runs, **Then** it creates test requirements, creates releases in PREPARATION status, transitions through ALIGNMENT to ACTIVE, verifies context switching returns correct snapshots, and cleans up all test data.
3. **Given** the E2E test creates two releases with different requirement versions, **When** it switches between them via the API, **Then** it verifies the returned requirements match the expected snapshots for each release.
4. **Given** the E2E test creates a release and exports requirements, **When** it downloads the export, **Then** it verifies the export contains the expected number of requirements.
5. **Given** any test step fails, **When** the script exits (success or failure), **Then** all test data (requirements, releases, users) is cleaned up.

---

### Edge Cases

- What happens when the last ACTIVE release is archived and no other release exists to become ACTIVE? The system falls back to showing live (current) requirements with an indicator that no active release is set.
- What happens when a release in ALIGNMENT status is deleted? Alignment sessions and all associated review data must be cleaned up (reviews, snapshots, reviewers, then sessions).
- What happens when a user exports from an ARCHIVED release whose original requirements have since been deleted from the live system? The snapshot data is self-contained and the export should succeed with the captured data.
- What happens when two administrators attempt to activate different releases simultaneously? Only one should succeed; the system should handle concurrent status transitions safely.
- What happens when a release is in PREPARATION and the admin starts alignment without any requirement changes since the last release? The system should allow it (alignment reviews the full requirement set regardless).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST support exactly four release statuses: PREPARATION, ALIGNMENT, ACTIVE, ARCHIVED.
- **FR-002**: System MUST create new releases with PREPARATION status by default.
- **FR-003**: System MUST transition releases from PREPARATION to ALIGNMENT when the alignment process is started.
- **FR-004**: System MUST transition releases from ALIGNMENT or PREPARATION to ACTIVE when activated by an authorized user.
- **FR-005**: System MUST automatically transition the previously ACTIVE release to ARCHIVED when a new release is activated.
- **FR-006**: System MUST enforce that only one release can be in ACTIVE status at any time.
- **FR-007**: System MUST prevent deletion of ACTIVE releases.
- **FR-008**: System MUST provide a release selector UI element that allows users to switch between releases.
- **FR-009**: System MUST default to showing the ACTIVE release when a user opens the application. If no ACTIVE release exists, the system MUST show live (current) requirements with a visible indicator that no active release is set.
- **FR-010**: System MUST update all requirement views to reflect the selected release's snapshot data when the user switches releases.
- **FR-011**: System MUST export requirements from the selected release's snapshot when a release context is active.
- **FR-012**: System MUST support release-aware exports in all existing formats (Word, Excel, filtered by use case, translated).
- **FR-013**: System MUST preserve ARCHIVED release snapshot data indefinitely for audit and compliance purposes.
- **FR-014**: System MUST provide a complete E2E test script that validates the release lifecycle using environment-variable-based authentication (SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY).
- **FR-015**: System MUST persist the user's selected release context within their browser session.
- **FR-016**: System MUST block requirement editing when viewing ARCHIVED or ACTIVE releases (read-only snapshots). Editing MUST be allowed when viewing PREPARATION or ALIGNMENT releases, with edits applied to the live requirement and the release snapshot refreshed on next snapshot capture.

### Key Entities

- **Release**: Represents a defined state of requirements at a point in time. Has a version (semantic), name, status (PREPARATION, ALIGNMENT, ACTIVE, ARCHIVED), and a collection of requirement snapshots. Only one release can be ACTIVE at a time.
- **RequirementSnapshot**: An immutable copy of a requirement's state at the time a release was created. Contains the requirement's internal ID, version number, and all content fields. Linked to exactly one release.
- **Requirement**: A living security requirement with a unique internal ID and version number. Version number increments with each update. Snapshots capture the requirement's state at specific points.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can switch between any two releases and see the correct requirement snapshots within 2 seconds.
- **SC-002**: Exports from a selected release contain 100% of the requirement snapshots for that release, with no data from other releases mixed in.
- **SC-003**: The E2E test suite completes successfully against a clean secman instance with all test steps passing and all test data cleaned up afterward.
- **SC-004**: Release status transitions follow the defined lifecycle (PREPARATION -> ALIGNMENT -> ACTIVE, previous ACTIVE -> ARCHIVED) with zero invalid state transitions permitted.
- **SC-005**: The release selector is accessible from all requirement-related pages and persists the user's selection during the browser session.
- **SC-006**: ARCHIVED releases remain viewable and exportable indefinitely with no data loss from their snapshots.

## Clarifications

### Session 2026-02-06

- Q: How should existing release statuses map to the new model? → A: DRAFT→PREPARATION, IN_REVIEW→ALIGNMENT, ACTIVE→ACTIVE, LEGACY→ARCHIVED, PUBLISHED→ARCHIVED
- Q: Should requirement editing be blocked when viewing a release? → A: Block editing for ARCHIVED and ACTIVE releases only; allow edits when viewing PREPARATION or ALIGNMENT releases
- Q: What happens if no ACTIVE release exists? → A: Show live (current) requirements with an indicator that no active release exists

## Assumptions

- The existing RequirementSnapshot and Release entities provide a sufficient foundation; the primary changes are to the status enum values and transition logic.
- The existing ReleaseSelector frontend component can be extended to support the new status model and made the primary navigation mechanism for release context.
- The existing export endpoints already accept a releaseId parameter; the change is ensuring the UI always passes the selected release context and that exports from snapshots produce complete output.
- The alignment process (existing feature) already handles the PREPARATION to review transition; this will be adapted to use ALIGNMENT as the new status name.
- The E2E test script will follow the established pattern from existing E2E tests (tests/mcp-e2e-workgroup-test.sh) using curl, jq, and MCP API calls.
- The status rename requires a data migration: DRAFT→PREPARATION, IN_REVIEW→ALIGNMENT, ACTIVE→ACTIVE, LEGACY→ARCHIVED, PUBLISHED→ARCHIVED. This is a one-time migration applied during deployment.
