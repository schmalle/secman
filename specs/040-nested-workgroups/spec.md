# Feature Specification: Nested Workgroups

**Feature Branch**: `040-nested-workgroups`
**Created**: 2025-11-02
**Status**: Draft
**Input**: User description: "workgroups are currently flat, meaning workgroups cannot contain other workgroups. Within this feature i want to implement exactly this , a possibility for nested workgroups, including UI, adding / deleting, of workgroups."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Create Child Workgroup (Priority: P1)

An administrator needs to organize teams hierarchically by creating workgroups within parent workgroups (e.g., "Engineering" parent with "Frontend" and "Backend" children).

**Why this priority**: This is the core capability - without the ability to create nested structures, the feature provides no value. This is the minimum viable product.

**Independent Test**: Can be fully tested by creating a parent workgroup, then creating a child workgroup under it, and verifying the parent-child relationship is established. Delivers immediate organizational value.

**Acceptance Scenarios**:

1. **Given** an administrator is viewing a workgroup detail page, **When** they click "Add Child Workgroup", **Then** they see a form to create a new workgroup as a child of the current one
2. **Given** an administrator fills out the child workgroup form with valid data, **When** they submit, **Then** the child workgroup is created and appears under the parent in the hierarchy
3. **Given** a child workgroup has been created, **When** viewing the parent workgroup, **Then** the child workgroup is listed as a sub-group with visual indication of the hierarchy
4. **Given** a workgroup is at level 5 (maximum depth), **When** an administrator attempts to add a child workgroup, **Then** the system prevents creation and displays a message indicating maximum depth has been reached
5. **Given** an administrator attempts to create a child workgroup with a name that already exists among siblings, **When** they submit the form, **Then** the system rejects creation with a clear error message indicating the name conflict

---

### User Story 2 - View Workgroup Hierarchy (Priority: P2)

An administrator needs to visualize the entire organizational structure to understand team relationships and asset distribution across nested workgroups.

**Why this priority**: While less critical than creation, visibility is essential for managing complex hierarchies. Users need to see what they've built before they can manage it effectively.

**Independent Test**: Can be tested by creating a multi-level hierarchy (parent > child > grandchild) and verifying that the UI displays all levels with clear parent-child relationships. Delivers organizational clarity.

**Acceptance Scenarios**:

1. **Given** multiple levels of nested workgroups exist, **When** viewing the workgroups list, **Then** the hierarchy is displayed with visual indentation or tree structure
2. **Given** an administrator views a workgroup with children, **When** they expand/collapse the node, **Then** child workgroups are shown/hidden accordingly
3. **Given** a workgroup has multiple ancestors, **When** viewing its detail page, **Then** the breadcrumb trail shows the full path from root to current workgroup

---

### User Story 3 - Delete Workgroup with Children (Priority: P3)

An administrator needs to remove obsolete workgroups while ensuring child workgroups and their assets are properly handled.

**Why this priority**: Deletion is less frequent than creation and viewing, but necessary for maintenance. Can be deferred until basic CRUD operations are stable.

**Independent Test**: Can be tested by creating a parent with children, attempting deletion, and verifying the system either prevents deletion or handles cascading appropriately. Delivers data integrity.

**Acceptance Scenarios**:

1. **Given** a workgroup has child workgroups, **When** administrator attempts to delete it, **Then** system displays a confirmation dialog explaining that children will be promoted to the parent's level
2. **Given** administrator confirms deletion of a parent with children, **When** deletion proceeds, **Then** children are moved to the deleted workgroup's parent (promoted up one level), or become root-level workgroups if the deleted parent was root-level
3. **Given** a workgroup has no children, **When** administrator deletes it, **Then** deletion proceeds immediately after standard confirmation
4. **Given** a root-level workgroup with children is deleted, **When** deletion proceeds, **Then** all direct children become new root-level workgroups maintaining their own child hierarchies

---

### User Story 4 - Move Workgroup in Hierarchy (Priority: P4)

An administrator needs to reorganize the hierarchy by moving workgroups from one parent to another as organizational structures evolve.

**Why this priority**: While valuable for flexibility, this is an enhancement that can be added after basic create/read/delete operations are stable. Not required for MVP.

**Independent Test**: Can be tested by creating a hierarchy, moving a workgroup to a different parent, and verifying all relationships (users, assets, sub-children) remain intact. Delivers organizational flexibility.

**Acceptance Scenarios**:

1. **Given** an administrator views a child workgroup, **When** they select "Change Parent", **Then** they see a list of eligible parent workgroups (excluding self and descendants)
2. **Given** administrator selects a new parent and confirms, **When** the move completes, **Then** the workgroup appears under the new parent with all its children and assets intact
3. **Given** a workgroup is moved, **When** users assigned to that workgroup access the system, **Then** their access to assets remains unchanged

---

### User Story 5 - Inherit Asset Access from Parent (Priority: P5)

Users assigned to a parent workgroup need automatic access to assets assigned to any child workgroup, enabling simplified permission management.

**Why this priority**: This is an advanced access control enhancement that adds significant complexity. Should be implemented after core hierarchy mechanics are proven stable.

**Independent Test**: Can be tested by assigning a user to a parent workgroup, assigning an asset to a child workgroup, and verifying the user can access that asset. Delivers permission inheritance.

**Acceptance Scenarios**:

1. **Given** a user is assigned to a parent workgroup, **When** an asset is assigned to a child workgroup, **Then** the user can view and interact with that asset
2. **Given** a user is assigned only to a child workgroup, **When** an asset is assigned to the parent workgroup, **Then** the user cannot access that asset (inheritance is downward only)
3. **Given** a user is removed from a parent workgroup, **When** checking their asset access, **Then** they immediately lose access to all child workgroup assets (unless directly assigned to child)

---

### Edge Cases

- What happens when attempting to create a circular reference (workgroup A → child B → child C → child A)?
- How does the system handle maximum depth limits for nested hierarchies?
- What happens when deleting a middle-tier workgroup (has both parent and children)?
- How are assets distributed when a workgroup with children is deleted?
- What happens when moving a workgroup would create a circular reference?
- When two admins attempt concurrent modifications, optimistic locking detects the conflict and the second operation fails with an error prompting the admin to refresh and retry
- What happens to users assigned to a workgroup when it's moved to a different parent?
- How does the system handle very deep hierarchies (10+ levels) in UI performance?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow administrators to designate a workgroup as a child of another workgroup during creation
- **FR-002**: System MUST prevent circular references in the workgroup hierarchy (workgroup cannot be ancestor of itself)
- **FR-003**: System MUST allow administrators to view workgroup hierarchies with clear visual indication of parent-child relationships
- **FR-004**: System MUST maintain referential integrity when deleting parent workgroups with children
- **FR-005**: System MUST allow administrators to remove a child workgroup without affecting sibling workgroups
- **FR-006**: System MUST display breadcrumb navigation showing the full ancestor path for any workgroup
- **FR-007**: System MUST persist parent-child relationships between workgroups across sessions
- **FR-008**: System MUST validate that a proposed parent workgroup exists before creating a child
- **FR-009**: System MUST allow a workgroup to have multiple children (one-to-many relationship)
- **FR-010**: System MUST allow a workgroup to have at most one parent (many-to-one relationship)
- **FR-011**: System MUST display child workgroups when viewing parent workgroup details
- **FR-012**: System MUST allow workgroups to exist without a parent (root-level workgroups)
- **FR-013**: System MUST prevent deleting a parent workgroup that has children unless administrator explicitly handles child disposition
- **FR-014**: System MUST support up to 5 levels of nesting depth and prevent creation of workgroups beyond this limit
- **FR-015**: System MUST validate that moving a workgroup to a new parent would not create circular references
- **FR-016**: System MUST calculate and validate depth before allowing child workgroup creation
- **FR-017**: System MUST promote child workgroups to their grandparent level when a parent is deleted
- **FR-018**: System MUST convert children to root-level workgroups when a root-level parent is deleted
- **FR-019**: System MUST use optimistic locking to detect concurrent modifications and reject conflicting operations with a clear error message
- **FR-020**: System MUST enforce sibling uniqueness by preventing creation or renaming of workgroups when a sibling with the same name already exists under the same parent
- **FR-021**: System MUST allow workgroups in different branches (different parents) to have the same name
- **FR-022**: System MUST log all hierarchy change operations (create, move, delete) with operation type, timestamp, and user who made the change

### Key Entities

- **Workgroup**: Organizational unit that can contain users, assets, and child workgroups. Key attributes include name (unique within parent scope), description, parent workgroup reference (nullable for root-level), collection of child workgroups, users collection, assets collection, version field for optimistic locking. Relationships: self-referential many-to-one (parent) and one-to-many (children).

- **Parent-Child Relationship**: Represents the hierarchical link between workgroups. A workgroup can have zero or one parent and zero or many children. The relationship must be acyclic to prevent circular references.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can create a three-level workgroup hierarchy (parent > child > grandchild) in under 5 minutes
- **SC-002**: System displays workgroup hierarchies with up to 100 workgroups per page without page load time exceeding 3 seconds, supporting total system capacity of 500 workgroups
- **SC-003**: 95% of workgroup hierarchy operations (create child, view hierarchy, delete) complete successfully on first attempt
- **SC-004**: Zero circular reference violations occur in production after hierarchy validation is implemented
- **SC-005**: Users can visually identify parent-child relationships in the UI within 10 seconds of viewing the workgroups page
- **SC-006**: Deletion operations that affect child workgroups present clear warnings, reducing accidental data loss by 90% compared to current flat structure
- **SC-007**: Individual hierarchy operations (ancestor queries, descendant queries, circular reference validation) complete within 500ms under normal load

## Clarifications

### Session 2025-11-02

- Q: How should the system handle concurrent modifications to the hierarchy (two admins moving the same workgroup)? → A: Optimistic locking with version field - detect conflicts and reject second operation with clear error message prompting retry
- Q: What performance target should hierarchy operations (ancestor queries, descendant queries, circular reference validation) meet? → A: 500ms for hierarchy operations - fast enough for interactive admin workflows
- Q: What is the expected total system capacity for workgroups to inform database design and indexing strategy? → A: Up to 500 total workgroups - suitable for small-to-medium organizations with minimal optimization needed
- Q: What uniqueness constraints should apply to workgroup names? → A: Unique within parent scope (sibling uniqueness) - children of same parent must have unique names, but different branches can reuse names
- Q: What audit logging requirements apply to hierarchy changes (create, move, delete operations)? → A: Minimal logging - log operation type, timestamp, and user who made the change

## Assumptions

1. **Existing Access Control**: Current workgroup-based access control for assets will be extended to respect nested hierarchies (exact inheritance model to be clarified)
2. **Admin-Only Management**: Only ADMIN role users can create, modify, or delete workgroup hierarchies (same as current flat structure)
3. **Database Schema**: Existing workgroup table can be extended with a parent_id foreign key column without requiring full schema redesign
4. **UI Framework**: Current Astro/React/Bootstrap UI can support tree or hierarchical displays without requiring new component libraries
5. **Performance**: Hierarchy queries will use recursive CTEs or similar database features to efficiently traverse nested structures. System designed to support up to 500 total workgroups with standard indexing strategies.
6. **Deletion Behavior**: When a parent workgroup is deleted, its children are promoted to the grandparent level (or become root-level if the parent was root). This preserves all workgroup data and relationships while removing the parent.
7. **Maximum Depth**: Hierarchy depth is limited to 5 levels to balance organizational flexibility with performance and UI complexity. This supports typical structures (Company > Division > Department > Team > Sub-team).
8. **Asset Access Default**: Initially, assets assigned to a workgroup are accessible only to users directly assigned to that workgroup (no automatic parent/child inheritance until User Story 5 is implemented)

## Dependencies

- Existing Workgroup entity and CRUD operations (Feature 008)
- ADMIN role authorization system
- Astro/React frontend components for workgroup management
- MariaDB database with support for self-referential foreign keys
- Bootstrap UI components for displaying hierarchical data

## Out of Scope

- Bulk operations (moving multiple workgroups simultaneously)
- Workgroup templates or cloning with hierarchy
- Advanced permission inheritance models beyond direct assignment (deferred to User Story 5)
- Multi-parent structures (matrix organization model)
- Workgroup hierarchy versioning or detailed audit history beyond minimal logging (operation, timestamp, user). Specifically excludes: before/after state tracking, rollback capability, and change history snapshots
- Import/export of hierarchical workgroup structures
- Automated hierarchy suggestions based on organizational data
