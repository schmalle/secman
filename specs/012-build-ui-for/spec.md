# Feature Specification: Release Management UI Enhancement

**Feature Branch**: `012-build-ui-for`  
**Created**: 2025-10-07  
**Status**: Draft  
**Input**: User description: "build UI for release management"

## Context

Feature 011 implemented the backend infrastructure for release-based requirement version management, including:
- Backend APIs for creating, listing, and deleting releases
- RequirementSnapshot entity for point-in-time requirement copies
- Release comparison API
- Basic frontend components (ReleaseManagement.tsx, ReleaseSelector.tsx, ReleaseComparison.tsx)
- Pages at `/admin/releases` and `/releases`

This feature focuses on completing and enhancing the user interface to make release management intuitive, accessible, and fully functional for all user roles (ADMIN, RELEASE_MANAGER, and regular users).

## Clarifications

### Session 2025-10-07
- Q: Should the UI provide controls to transition release status, or is status set only during creation and managed through other means? → A: UI provides status transition controls (e.g., "Publish" button on DRAFT releases, "Archive" button on PUBLISHED releases)
- Q: Should RELEASE_MANAGER be able to delete ANY release in the system, or only releases they personally created? → A: RELEASE_MANAGER can only delete releases they created; ADMIN can delete any release
- Q: What format should the comparison report export use? → A: Single Excel sheet with all requirements and a "Change Type" column (Added/Deleted/Modified) with color coding
- Q: For handling large lists (releases and requirement snapshots), which pagination strategy should be implemented? → A: Traditional pagination with page numbers and "Previous/Next" buttons
- Q: What status should a newly created release have by default? → A: Always starts as DRAFT - user must explicitly publish when ready

## User Scenarios & Testing

### User Story 1 - View and Browse Releases (Priority: P1) 🎯 MVP

As a **compliance manager or auditor**, I need to view all releases in the system with clear status indicators and metadata so that I can understand what releases exist and select the appropriate one for review or export.

**Why this priority**: Without a clear release listing view, users cannot discover or access historical requirement snapshots, making the entire release feature unusable.

**Independent Test**: Navigate to `/releases` page, verify all releases display with version, name, status, date, and requirement count. Filter and sort controls work correctly.

**Acceptance Scenarios**:

1. **Given** I navigate to `/releases`, **When** the page loads, **Then** I see a card or table layout displaying all releases with version, name, status badge, release date, requirement count, and created by info

2. **Given** releases exist with different statuses (DRAFT, PUBLISHED, ARCHIVED), **When** viewing the release list, **Then** each status is visually distinguished with color-coded badges (e.g., yellow=DRAFT, green=PUBLISHED, gray=ARCHIVED)

3. **Given** multiple releases exist, **When** I use the status filter dropdown, **Then** only releases matching the selected status are displayed

4. **Given** I am viewing releases, **When** I click on a release card/row, **Then** I navigate to a detailed view showing full release information and its requirement snapshots

5. **Given** I am viewing the release list, **When** I use the search box, **Then** releases are filtered by version or name matching my search term

6. **Given** I have no releases in the system, **When** I view `/releases`, **Then** I see an empty state message with guidance on how to create the first release (if I have permissions)

---

### User Story 2 - Create New Release (Priority: P1) 🎯 MVP

As a **RELEASE_MANAGER or ADMIN**, I need to create a new release by specifying version, name, and description so that I can freeze the current state of all requirements for compliance documentation.

**Why this priority**: Creating releases is the core action that makes the entire release management system valuable. Without an intuitive creation flow, the feature cannot be used.

**Independent Test**: Click "Create Release" button, fill form with valid data (version: 1.0.0, name: "Q4 2024 Audit"), submit, verify release appears in list with DRAFT status and backend confirms requirement snapshots were created.

**Acceptance Scenarios**:

1. **Given** I am an ADMIN or RELEASE_MANAGER on `/releases`, **When** I click the "Create Release" button, **Then** a modal/form appears with fields for version, name, and description

2. **Given** the create release form is open, **When** I enter an invalid version (e.g., "abc" or "1.0"), **Then** I see inline validation error "Version must follow semantic versioning format (MAJOR.MINOR.PATCH)"

3. **Given** I fill in all required fields with valid data, **When** I submit the form, **Then** the release is created with DRAFT status, a success message appears, the modal closes, and the new release appears in the list

4. **Given** I attempt to create a release with a version that already exists, **When** I submit, **Then** I see an error message "Version 1.0.0 already exists"

5. **Given** I am a regular USER (not ADMIN/RELEASE_MANAGER), **When** I view `/releases`, **Then** the "Create Release" button is either hidden or disabled

6. **Given** there are no requirements in the system, **When** I attempt to create a release, **Then** I receive a warning "Cannot create release: no requirements exist to snapshot"

7. **Given** a release is successfully created, **When** I view it in the list, **Then** its status badge shows "DRAFT" in yellow color

---

### User Story 3 - View Release Details and Snapshots (Priority: P2)

As a **compliance manager**, I need to view the detailed information of a specific release including all requirement snapshots so that I can verify what requirements were frozen at that point in time.

**Why this priority**: After discovering releases (P1), users need to drill down into release contents to verify data before using it for exports or comparisons.

**Independent Test**: Click on a release from the list, view the release detail page showing release metadata and a table of all requirement snapshots with their fields.

**Acceptance Scenarios**:

1. **Given** I click on a release from the list, **When** the detail page loads, **Then** I see release metadata (version, name, description, status, release date, created by, created at, requirement count)

2. **Given** I am viewing a release detail page, **When** I scroll down, **Then** I see a table or card layout displaying all requirement snapshots with columns: shortreq, chapter, norm, details preview, motivation preview

3. **Given** I am viewing requirement snapshots, **When** I click on a snapshot row, **Then** a modal or expanded view shows the complete snapshot including all fields (details, motivation, example, usecase, norm IDs, usecase IDs)

4. **Given** a release has many requirement snapshots (e.g., 500+), **When** viewing the detail page, **Then** snapshots are paginated or use infinite scroll for performance

5. **Given** I am viewing a release detail page, **When** I click the "Export" button, **Then** I can download the requirements from this release in Excel or Word format

---

### User Story 4 - Compare Two Releases (Priority: P2)

As a **compliance manager or auditor**, I need to compare two releases side-by-side to see what requirements were added, modified, or deleted between versions so that I can document changes for audit reports.

**Why this priority**: Comparison is essential for understanding requirement evolution and documenting change history, but users can still create and view releases without it.

**Independent Test**: Navigate to `/releases/compare`, select two releases from dropdowns, click "Compare", view side-by-side or unified diff showing added (green), deleted (red), and modified (yellow) requirements with field-level changes.

**Acceptance Scenarios**:

1. **Given** I navigate to `/releases/compare`, **When** the page loads, **Then** I see two dropdown selectors labeled "From Release" and "To Release" with all available releases

2. **Given** I select two different releases (e.g., v1.0.0 and v1.1.0), **When** I click "Compare", **Then** the page displays a comparison showing requirements in three categories: Added, Deleted, and Modified

3. **Given** I am viewing a comparison, **When** I look at the "Added" section, **Then** I see requirements that exist in the "To" release but not in the "From" release, highlighted in green

4. **Given** I am viewing a comparison, **When** I look at the "Deleted" section, **Then** I see requirements that exist in the "From" release but not in the "To" release, highlighted in red

5. **Given** I am viewing a comparison, **When** I look at the "Modified" section, **Then** I see requirements that exist in both but have different content, highlighted in yellow with a field-by-field diff showing old and new values

6. **Given** no changes exist between two releases, **When** I compare them, **Then** I see a message "No differences found between v1.0.0 and v1.1.0"

7. **Given** I am viewing a comparison, **When** I click "Export Comparison Report", **Then** I download an Excel file containing a single sheet with all requirements, a "Change Type" column (Added/Deleted/Modified), and color-coded rows (green for Added, red for Deleted, yellow for Modified)

8. **Given** I am viewing the exported comparison Excel file, **When** I open it, **Then** I see columns for requirement fields (shortreq, chapter, norm, details, etc.) plus a "Change Type" column, with appropriate cell background colors matching the change type

---

### User Story 5 - Manage Release Status Lifecycle (Priority: P2)

As an **ADMIN or RELEASE_MANAGER**, I need to transition release status through its lifecycle (DRAFT → PUBLISHED → ARCHIVED) so that I can indicate which releases are ready for use and which are historical.

**Why this priority**: Status transitions help teams communicate release readiness and organize releases by their current usage state, though the system can function with manual status management.

**Independent Test**: Create a release (starts as DRAFT), click "Publish" button, verify status changes to PUBLISHED. Later click "Archive" button, verify status changes to ARCHIVED.

**Acceptance Scenarios**:

1. **Given** I am viewing a release with DRAFT status, **When** I click the "Publish" button, **Then** a confirmation modal appears asking "Publish release v1.0.0? This will make it available for exports and comparisons."

2. **Given** I confirm publishing a DRAFT release, **When** the action completes, **Then** the release status changes to PUBLISHED, a success message appears, and the status badge updates

3. **Given** I am viewing a release with PUBLISHED status, **When** I click the "Archive" button, **Then** a confirmation modal appears asking "Archive release v1.0.0? This will mark it as historical."

4. **Given** I confirm archiving a PUBLISHED release, **When** the action completes, **Then** the release status changes to ARCHIVED, a success message appears, and the status badge updates

5. **Given** a release is ARCHIVED, **When** I view its detail page, **Then** I see a badge indicating archived status but can still export and compare with it

6. **Given** I am a regular USER (not ADMIN/RELEASE_MANAGER), **When** I view releases, **Then** status transition buttons (Publish, Archive) are not visible

7. **Given** status transitions follow the workflow DRAFT → PUBLISHED → ARCHIVED, **When** viewing releases, **Then** only valid next-state transitions are offered (no "Archive" button on DRAFT, no "Publish" button on ARCHIVED)

---

### User Story 6 - Delete Release (Priority: P3)

As an **ADMIN or RELEASE_MANAGER**, I need to delete obsolete or incorrectly created releases so that I can maintain a clean and accurate release history.

**Why this priority**: Deletion is important for data hygiene but is a less frequent operation than creation and viewing. The system remains functional without deletion.

**Independent Test**: Navigate to a release detail page or list, click "Delete" button, confirm in modal, verify release disappears from list and backend confirms deletion.

**Acceptance Scenarios**:

1. **Given** I am an ADMIN viewing the release list, **When** I click the "Delete" action for any release, **Then** a confirmation modal appears warning "Are you sure you want to delete release v1.0.0? This will remove all requirement snapshots and cannot be undone."

2. **Given** I am a RELEASE_MANAGER viewing the release list, **When** I view a release I created, **Then** I see a "Delete" button/action for that release

3. **Given** I am a RELEASE_MANAGER viewing the release list, **When** I view a release created by another user, **Then** I do NOT see a "Delete" button/action for that release

4. **Given** the delete confirmation modal is open, **When** I click "Confirm Delete", **Then** the release is deleted, a success message appears, and the release is removed from the list

5. **Given** I am a regular USER (not ADMIN/RELEASE_MANAGER), **When** I view releases, **Then** delete buttons/actions are not visible

6. **Given** I am a RELEASE_MANAGER attempting to delete someone else's release via API, **When** the request is processed, **Then** I receive a 403 Forbidden error "You can only delete releases you created"

7. **Given** I attempt to delete a release, **When** the deletion fails (e.g., network error), **Then** I see an error message "Failed to delete release: [error reason]" and the release remains in the list

---

### User Story 7 - Export Requirements with Release Selection (Priority: P2)

As a **compliance manager**, I need to export requirements from a specific release or the current state so that I can provide historical documentation or current requirements to auditors.

**Why this priority**: Export with release selection is the primary use case for the release system, enabling historical compliance documentation.

**Independent Test**: Navigate to export page, select a release from dropdown (or leave as "Current"), click "Export to Excel", verify downloaded file contains requirements from selected release.

**Acceptance Scenarios**:

1. **Given** I am on the export page (`/export` or `/import-export`), **When** the page loads, **Then** I see a "Release" dropdown selector with options: "Current (latest)" and all existing releases

2. **Given** the release selector defaults to "Current (latest)", **When** I click "Export to Excel" without changing the release, **Then** I download an Excel file with the most current requirement versions

3. **Given** I select a specific release (e.g., "v1.0.0 - Q4 2024 Audit") from the dropdown, **When** I click "Export to Excel", **Then** I download an Excel file containing only the requirement snapshots from that release

4. **Given** I select a specific release, **When** I click "Export to Word", **Then** I download a Word document containing only the requirement snapshots from that release

5. **Given** export with release selection works for Excel/Word, **When** I select a release and click "Export Translated", **Then** the translated export also uses the selected release's snapshots

---

### Edge Cases

- **Empty State**: What happens when no releases exist? → Display empty state with clear guidance
- **Concurrent Creation**: What happens if two users create a release with the same version simultaneously? → Backend validation prevents duplicates, UI shows error to second user
- **Large Release**: How does UI handle a release with 1000+ requirement snapshots? → Use pagination, lazy loading, or virtual scrolling
- **Deleted Requirements**: What if a requirement referenced in a snapshot no longer exists in the current system? → Snapshots are independent and preserved even if original requirement is deleted
- **Permission Boundaries**: What if a user's role changes while they're on the page? → On next action (create, delete), backend permission check occurs and UI shows appropriate error if unauthorized
- **Network Failures**: What happens if API call fails during release creation? → Show error message, allow retry, don't close modal until success
- **Invalid Comparison**: What happens if user selects the same release for both "From" and "To"? → Show validation error "Cannot compare a release with itself"

## Requirements

### Functional Requirements

**Release Listing & Discovery**:
- **FR-001**: System MUST display all releases in a card or table layout at `/releases` with version, name, status, date, requirement count, and creator
- **FR-002**: System MUST provide status filtering (ALL, DRAFT, PUBLISHED, ARCHIVED) for the release list
- **FR-003**: System MUST provide search functionality to filter releases by version or name
- **FR-004**: System MUST display an empty state when no releases exist with guidance for users with creation permissions
- **FR-005**: System MUST visually distinguish release statuses with color-coded badges (DRAFT=yellow, PUBLISHED=green, ARCHIVED=gray)
- **FR-006**: System MUST provide traditional pagination with page numbers and Previous/Next buttons for release list (default 20 per page)
- **FR-007**: System MUST display pagination controls showing current page, total pages, and page number links

**Release Creation**:
- **FR-008**: System MUST provide a "Create Release" button for ADMIN and RELEASE_MANAGER users (hidden for regular users)
- **FR-009**: System MUST display a modal/form with fields: version (required), name (required), description (optional)
- **FR-010**: System MUST validate version input follows semantic versioning format (MAJOR.MINOR.PATCH) with inline error messages
- **FR-011**: System MUST prevent creation of releases with duplicate versions and display error message
- **FR-012**: System MUST create all new releases with initial status of DRAFT
- **FR-013**: System MUST show success notification after successful release creation and refresh the list
- **FR-014**: System MUST display appropriate error messages for all creation failures (validation, network, server errors)

**Release Detail View**:
- **FR-015**: System MUST display release detail page showing all metadata (version, name, description, status, dates, creator, requirement count)
- **FR-016**: System MUST display all requirement snapshots in a table/card layout with key fields (shortreq, chapter, norm, details preview)
- **FR-017**: System MUST provide traditional pagination with page numbers and Previous/Next buttons for releases with many snapshots (default 50 per page)
- **FR-018**: System MUST display pagination controls showing current page, total pages, and page number links
- **FR-019**: System MUST provide a way to view complete snapshot details (all fields) via modal or expanded view
- **FR-020**: System MUST provide "Export" button on release detail page to download requirements from that release

**Release Comparison**:
- **FR-021**: System MUST provide comparison page at `/releases/compare` with two release selector dropdowns
- **FR-022**: System MUST display comparison results in three categories: Added, Deleted, Modified
- **FR-023**: System MUST highlight added requirements in green, deleted in red, modified in yellow
- **FR-024**: System MUST show field-by-field differences for modified requirements with old and new values
- **FR-025**: System MUST handle empty comparison (no differences) with appropriate message
- **FR-026**: System MUST provide "Export Comparison Report" button that generates an Excel file
- **FR-027**: Comparison Excel export MUST contain a single sheet with all requirements, a "Change Type" column, and color-coded rows (green=Added, red=Deleted, yellow=Modified)
- **FR-028**: Comparison Excel export MUST include all requirement fields (shortreq, chapter, norm, details, motivation, example, usecase) as columns
- **FR-029**: System MUST validate that user cannot compare a release with itself

**Release Status Management**:
- **FR-030**: System MUST provide "Publish" button for DRAFT releases (ADMIN and RELEASE_MANAGER only)
- **FR-031**: System MUST provide "Archive" button for PUBLISHED releases (ADMIN and RELEASE_MANAGER only)
- **FR-032**: System MUST display confirmation modal before status transitions
- **FR-033**: System MUST enforce status workflow: DRAFT → PUBLISHED → ARCHIVED (no reverse transitions)
- **FR-034**: System MUST update status badge and UI immediately after successful status change
- **FR-035**: System MUST hide status transition buttons for users without ADMIN or RELEASE_MANAGER roles
- **FR-036**: System MUST display appropriate error messages for status transition failures

**Release Deletion**:
- **FR-037**: System MUST provide "Delete" action for ADMIN users on all releases
- **FR-038**: System MUST provide "Delete" action for RELEASE_MANAGER users only on releases they created
- **FR-039**: System MUST hide "Delete" action for RELEASE_MANAGER users on releases created by other users
- **FR-040**: System MUST display confirmation modal before deletion with warning about permanence
- **FR-041**: System MUST show success notification after successful deletion and refresh the list
- **FR-042**: System MUST display appropriate error messages for deletion failures
- **FR-043**: System MUST return 403 Forbidden when RELEASE_MANAGER attempts to delete another user's release via API

**Export Integration**:
- **FR-044**: System MUST add release selector dropdown to export pages (`/export`, `/import-export`)
- **FR-045**: System MUST default release selector to "Current (latest)" showing current requirement versions
- **FR-046**: System MUST pass selected release ID to export APIs (Excel, Word, translated exports)
- **FR-047**: System MUST maintain release selection when switching between export formats

**RBAC & Permissions**:
- **FR-048**: System MUST hide/disable "Create Release" button for users without ADMIN or RELEASE_MANAGER roles
- **FR-049**: System MUST show "Delete Release" action for ADMIN on all releases
- **FR-050**: System MUST show "Delete Release" action for RELEASE_MANAGER only on releases they created
- **FR-051**: System MUST hide/disable status transition buttons for users without ADMIN or RELEASE_MANAGER roles
- **FR-052**: System MUST display appropriate error messages when unauthorized users attempt restricted actions
- **FR-053**: UI MUST check current user's username against release.createdBy to determine delete visibility for RELEASE_MANAGER

**Performance & UX**:
- **FR-054**: Release list page MUST load within 2 seconds for up to 100 releases
- **FR-055**: Release detail page MUST load within 3 seconds for releases with up to 1000 snapshots
- **FR-056**: Comparison MUST complete within 3 seconds for releases with up to 1000 requirements each
- **FR-057**: Status transitions MUST complete within 1 second
- **FR-058**: Pagination controls MUST respond instantly (client-side rendering preferred)
- **FR-059**: All forms MUST provide inline validation with immediate feedback
- **FR-060**: All actions MUST provide loading indicators during API calls
- **FR-061**: All success/error states MUST display appropriate toast notifications or alerts

### Key Entities

**Release** (existing backend entity):
- version: string (semantic versioning format)
- name: string
- description: string (optional)
- status: enum (DRAFT, PUBLISHED, ARCHIVED)
- releaseDate: date (nullable)
- createdBy: string (username)
- createdAt: timestamp
- updatedAt: timestamp
- requirementCount: number (computed)

**RequirementSnapshot** (existing backend entity):
- id: number
- releaseId: number (foreign key)
- originalRequirementId: number (logical reference)
- shortreq, chapter, norm, details, motivation, example, usecase: strings (denormalized requirement fields)
- usecaseIdsSnapshot: JSON array
- normIdsSnapshot: JSON array
- snapshotTimestamp: timestamp

**ReleaseComparison** (API response structure):
- fromRelease: Release metadata
- toRelease: Release metadata
- added: RequirementSnapshot[] (in "to" but not "from")
- deleted: RequirementSnapshot[] (in "from" but not "to")
- modified: RequirementChangeSummary[] (in both but with differences)

**RequirementChangeSummary**:
- requirementId: number
- fieldChanges: FieldChange[] (field name, old value, new value)

## Success Criteria

### Measurable Outcomes

- **SC-001**: Users with appropriate roles can create a new release in under 30 seconds (measured from page load to success confirmation)
- **SC-002**: Users can find and navigate to a specific release from the list in under 15 seconds using search or filter
- **SC-003**: Release comparison displays results within 3 seconds for releases containing up to 1000 requirements each
- **SC-004**: Export with release selection correctly generates files containing only snapshots from the selected release (verified by spot-checking 10 random requirements)
- **SC-005**: 90% of test users successfully complete the primary workflows (create release, view details, compare releases) on first attempt without assistance
- **SC-006**: Zero unauthorized actions succeed (non-ADMIN/RELEASE_MANAGER users cannot create or delete releases)
- **SC-007**: All UI interactions provide feedback within 200ms (button clicks show loading states, form validation is immediate)
- **SC-008**: UI correctly handles and displays errors for all failure scenarios (network errors, validation errors, permission errors) with actionable messages

## Non-Functional Requirements

### Usability
- **NFR-001**: UI MUST follow existing secman design patterns and Bootstrap 5 styling
- **NFR-002**: All interactive elements MUST have appropriate hover states and cursor indicators
- **NFR-003**: All forms MUST support keyboard navigation (tab order, enter to submit, escape to cancel)
- **NFR-004**: Color coding for status and comparison MUST be accessible (include text labels, not rely solely on color)

### Performance
- **NFR-005**: Initial page load for `/releases` MUST be under 2 seconds
- **NFR-006**: Release list MUST support pagination or lazy loading for 1000+ releases
- **NFR-007**: Comparison algorithm MUST scale to releases with 5000+ requirements without UI freeze

### Compatibility
- **NFR-008**: UI MUST work in modern browsers (Chrome, Firefox, Safari, Edge - latest 2 versions)
- **NFR-009**: UI MUST be responsive and functional on tablets (768px+ width)
- **NFR-010**: UI MUST integrate seamlessly with existing Astro/React architecture

### Maintainability
- **NFR-011**: Components MUST be reusable (e.g., ReleaseSelector used in multiple pages)
- **NFR-012**: Code MUST follow existing TypeScript/React patterns in the frontend codebase
- **NFR-013**: Components MUST handle loading, error, and empty states consistently

## Out of Scope

The following items are explicitly **NOT** included in this feature:

- ❌ Backend API changes (all required APIs exist from Feature 011)
- ❌ Release status workflow (DRAFT → PUBLISHED → ARCHIVED transitions) - only display, not manage
- ❌ Release scheduling or automated creation
- ❌ Requirement editing within releases (snapshots are immutable)
- ❌ Release approval workflow or multi-step creation
- ❌ Notification system for release creation/deletion
- ❌ Mobile-specific optimizations (responsive design only down to tablet)
- ❌ Offline functionality or PWA features
- ❌ Bulk operations (delete multiple releases, compare more than 2)
- ❌ Advanced filtering (by date range, creator, requirement count thresholds)
- ❌ Release notes or changelog generation
- ❌ Integration with external version control systems
- ❌ Release duplication or cloning features
- ❌ Custom export templates per release

## Dependencies

### Technical Dependencies
- **Backend APIs** (Feature 011 - already implemented):
  - `POST /api/releases` - Create release
  - `GET /api/releases?status={status}` - List releases with optional filter
  - `GET /api/releases/{id}` - Get release details
  - `DELETE /api/releases/{id}` - Delete release
  - `GET /api/releases/{id}/requirements` - Get release snapshots
  - `GET /api/releases/compare?fromReleaseId={id}&toReleaseId={id}` - Compare releases
  - `GET /api/requirements/export/xlsx?releaseId={id}` - Export with release
  - `GET /api/requirements/export/docx?releaseId={id}` - Export Word with release

- **Frontend Libraries**:
  - Astro 5.14 (routing and page structure)
  - React 19 (component framework)
  - Bootstrap 5.3 (styling)
  - Axios (API client with authentication)

- **Existing Components** (to be enhanced or integrated):
  - `ReleaseManagement.tsx` (currently at `/admin/releases`)
  - `ReleaseSelector.tsx` (dropdown component)
  - `ReleaseComparison.tsx` (comparison display)
  - `authenticatedFetch` utility (src/utils/auth)

### Business Dependencies
- User roles (USER, ADMIN, RELEASE_MANAGER) must be properly assigned in the system
- At least one user with ADMIN or RELEASE_MANAGER role to create first release
- Requirements must exist in the system before releases can be created

## Assumptions

1. Backend APIs from Feature 011 are fully functional and tested
2. JWT authentication is working correctly with role-based claims
3. Existing export functionality works and can accept optional releaseId parameter
4. Users understand semantic versioning format (or will learn from inline validation)
5. Bootstrap 5.3 styles are available and applied to all pages
6. Axios client is configured with authentication headers
7. Layout and navigation components support the new pages
8. Requirement snapshots are created synchronously when a release is created (no async job delays)

## Next Steps

All open questions have been clarified (see Clarifications section above). Ready to proceed with:

1. **Create implementation plan** with phased approach (P1 stories first for MVP)
2. **Design mockups or wireframes** for key pages (optional but recommended)
3. **Break down into granular tasks** following TDD principles
4. **Implement with continuous E2E testing** using Playwright

**Suggested next command**: `/speckit.plan`
