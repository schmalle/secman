# Feature Specification: Requirement ID.Revision Versioning

**Feature Branch**: `066-requirement-versioning`
**Created**: 2026-01-22
**Status**: Draft
**Input**: User description: "Extend requirements management with ID.Revision versioning format, unique internal IDs, release management UI, and diff table exports showing changed revisions between releases"

## Clarifications

### Session 2026-01-23

- Q: Should existing requirements receive IDs based on database ID order or creation timestamp order? → A: Database ID order (REQ-001 = lowest DB id)

## Overview

This feature extends the existing requirements management system to add explicit versioning with a user-facing "ID.Revision" format. Currently, requirements have database IDs and releases create snapshots, but there is no stable internal requirement identifier that persists across changes. This feature adds:

1. **Requirement Internal ID**: A unique, human-readable identifier (e.g., "REQ-001") that never changes once assigned
2. **Revision Tracking**: Each change to a requirement increments its revision number (e.g., "REQ-001.3")
3. **ID.Revision in Exports**: Both Excel and Word exports include the ID.Revision column
4. **Enhanced Release Management UI**: Admin dashboard to create, view, and switch between releases
5. **Revision Diff Export**: Export table showing which requirements changed between two releases

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Assign and Display Requirement IDs (Priority: P1/MVP)

As a requirement author, I want each requirement to have a unique internal ID that never changes, so I can reference requirements consistently across documents and conversations.

**Why this priority**: This is the foundational feature. Without stable IDs, all other features (revision tracking, diff exports) cannot work. This delivers immediate value by giving requirements traceable identifiers.

**Independent Test**: Can be fully tested by creating a new requirement and verifying it receives a unique ID (e.g., "REQ-001"). Editing the requirement should NOT change its ID.

**Acceptance Scenarios**:

1. **Given** I create a new requirement, **When** the requirement is saved, **Then** it is automatically assigned a unique internal ID in the format "REQ-NNN" (zero-padded to 3 digits minimum)
2. **Given** a requirement with ID "REQ-005" exists, **When** I edit its content, **Then** the ID remains "REQ-005" (unchanged)
3. **Given** multiple requirements exist, **When** I view the requirements list, **Then** I can see each requirement's internal ID displayed
4. **Given** a requirement is deleted, **When** I create a new requirement, **Then** the deleted ID is NOT reused (IDs are never recycled)

---

### User Story 2 - Track Requirement Revisions (Priority: P1/MVP)

As a compliance officer, I want to see which revision of a requirement I'm looking at, so I can track the evolution of requirements over time.

**Why this priority**: Revision tracking is core to the "ID.Revision" format the user requested. Combined with US1, this completes the versioning model.

**Independent Test**: Can be tested by creating a requirement, editing it multiple times, and verifying the revision number increments each time.

**Acceptance Scenarios**:

1. **Given** a new requirement is created, **When** I view it, **Then** its revision number starts at 1 (displayed as "REQ-001.1")
2. **Given** requirement "REQ-001.2" exists, **When** I edit any field (shortreq, details, motivation, example, etc.), **Then** the revision increments to "REQ-001.3"
3. **Given** requirement "REQ-001.3" exists, **When** I view its edit history, **Then** I can see when each revision was created (timestamp of last update)
4. **Given** a requirement in display, **When** hovering over the ID.Revision badge, **Then** a tooltip shows the last modified date

---

### User Story 3 - Export ID.Revision in Excel and Word (Priority: P2)

As a document author, I want the ID.Revision to appear in both Excel and Word exports, so I can reference specific requirement versions in external documents.

**Why this priority**: Export functionality is essential for sharing requirements with stakeholders who don't use the system. This builds on US1 and US2.

**Independent Test**: Can be tested by exporting requirements to Excel and Word, then verifying the ID.Revision column appears with correct values.

**Acceptance Scenarios**:

1. **Given** requirements with various ID.Revisions exist, **When** I export to Excel, **Then** the first column shows "ID.Revision" with values like "REQ-001.3"
2. **Given** requirements with various ID.Revisions exist, **When** I export to Word, **Then** each requirement header includes the ID.Revision (e.g., "REQ-001.3: Short requirement text")
3. **Given** I export a release snapshot to Excel, **When** I open the file, **Then** it shows the ID.Revision as it was at the time of the release (not current values)
4. **Given** I export a release snapshot to Word, **When** I open the file, **Then** requirement headers show the ID.Revision from that release snapshot

---

### User Story 4 - Create and Manage Releases (Priority: P2)

As an admin or release manager, I want to create releases that capture the state of all requirements at a point in time, so I can track how requirements evolved across versions.

**Why this priority**: Release management exists but needs enhancement to store ID.Revision in snapshots. This is prerequisite for diff exports.

**Independent Test**: Can be tested by creating a release, then modifying requirements, then verifying the release snapshot still shows original ID.Revision values.

**Acceptance Scenarios**:

1. **Given** I am on the Release Management page, **When** I create a new release, **Then** all current requirements are captured as snapshots including their current ID.Revision
2. **Given** a release "v1.0" exists with requirement "REQ-001.2", **When** I update that requirement to "REQ-001.3", **Then** viewing release "v1.0" still shows "REQ-001.2"
3. **Given** I view a release, **When** I look at its requirements, **Then** I see the ID.Revision as frozen at release time
4. **Given** releases exist, **When** I select a release from the admin dashboard, **Then** I can view all requirements as they were at that point in time

---

### User Story 5 - Compare Releases and Export Diff Table (Priority: P3)

As a compliance officer, I want to compare two releases and export a table showing which requirements changed, so I can document changes between versions.

**Why this priority**: Diff comparison already exists, but export needs to show ID.Revision changes. This is the final piece requested by the user.

**Independent Test**: Can be tested by creating two releases with different requirement revisions, comparing them, and exporting the diff table.

**Acceptance Scenarios**:

1. **Given** release "v1.0" has "REQ-001.2" and release "v2.0" has "REQ-001.5", **When** I compare these releases, **Then** "REQ-001" appears in the "Modified" list showing revision changed from .2 to .5
2. **Given** I compare two releases with changes, **When** I click "Export Diff to Excel", **Then** an Excel file is downloaded with columns: ID, Old Revision, New Revision, Change Summary
3. **Given** a requirement exists only in the newer release, **When** I compare releases, **Then** it shows as "Added" with its ID.Revision
4. **Given** a requirement was deleted between releases, **When** I compare releases, **Then** it shows as "Deleted" with its last known ID.Revision

---

### Edge Cases

- **EC-001**: What happens when importing requirements via Excel that don't have IDs? System assigns new IDs automatically
- **EC-002**: What happens if a requirement ID column is provided in import but conflicts with existing IDs? System ignores imported IDs and assigns new ones (IDs are system-generated only)
- **EC-003**: What if a requirement is restored after deletion? It receives a NEW ID (old IDs are never reused)
- **EC-004**: What if the ID sequence reaches maximum (999)? System auto-extends to 4 digits (REQ-1000)
- **EC-005**: What if two users edit the same requirement simultaneously? Standard optimistic locking; last save wins but both edits increment revision

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST assign a unique internal ID to each requirement upon creation in format "REQ-NNN" (zero-padded, minimum 3 digits)
- **FR-002**: System MUST never reuse a deleted requirement's internal ID
- **FR-003**: System MUST maintain a revision number for each requirement, starting at 1
- **FR-004**: System MUST increment the revision number on any content change (shortreq, details, motivation, example, norm, chapter, usecase)
- **FR-005**: System MUST NOT increment revision on metadata-only changes (associated usecases/norms relationships)
- **FR-006**: System MUST display ID.Revision in format "REQ-NNN.R" throughout the UI (list views, detail views, edit forms)
- **FR-007**: System MUST include ID.Revision as the first column in Excel exports
- **FR-008**: System MUST include ID.Revision in Word export requirement headers
- **FR-009**: System MUST store ID.Revision in requirement snapshots when a release is created
- **FR-010**: System MUST allow comparison between any two releases
- **FR-011**: System MUST provide diff export functionality showing ID, old revision, new revision, and change summary
- **FR-012**: System MUST persist the internal ID counter to ensure uniqueness across restarts

### Key Entities

- **Requirement** (extends existing):
  - `internalId`: Unique human-readable ID (e.g., "REQ-001"), assigned once, never changes
  - `revision`: Integer starting at 1, increments on content changes
  - Computed property `idRevision`: Returns "REQ-NNN.R" format string

- **RequirementSnapshot** (extends existing):
  - `internalId`: Captured from requirement at snapshot time
  - `revision`: Captured from requirement at snapshot time

- **RequirementIdSequence** (new):
  - Single-row table tracking next available ID number
  - Ensures atomicity and persistence of ID generation

## Assumptions

- The existing `versionNumber` field in `VersionedEntity` will be repurposed as the revision number (it's currently unused)
- Internal IDs use the "REQ-" prefix for clarity (distinguishes from database IDs)
- IDs are zero-padded to 3 digits minimum for readability and sorting
- The system will migrate existing requirements to assign IDs based on database ID order (lowest DB id → REQ-001, next → REQ-002, etc.)
- Relationship changes (adding/removing usecases or norms) do NOT increment revision (only content changes do)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of requirements display their ID.Revision in list and detail views
- **SC-002**: Every content edit to a requirement results in exactly one revision increment
- **SC-003**: Excel and Word exports include ID.Revision for all requirements with 100% accuracy
- **SC-004**: Release snapshots preserve ID.Revision values correctly, verifiable by comparing export before and after subsequent edits
- **SC-005**: Diff export between releases correctly identifies all added, deleted, and modified requirements with their ID.Revisions
- **SC-006**: Data migration assigns IDs to existing requirements without data loss or duplicate IDs
