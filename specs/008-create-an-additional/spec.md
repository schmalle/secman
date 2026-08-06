# Feature Specification: Workgroup-Based Access Control

**Feature Branch**: `008-create-an-additional`
**Created**: 2025-10-04
**Status**: Draft
**Input**: User description: "create an additional domain class workgroup. Every asset can belong to 0..n workgroups. Every user can belong to 0..n workgroups. Users with ADMIN role have to be treated as belonging to all workgroups. Implement add/edit/delete functions for workgroups in the admin area. Implement UI elements to edit/add workgroups to assets and users. If a user belongs to workgroup X the user can see all assets and vulnerabilities for workgroup X. A user can always see all assets the user created."

## Execution Flow (main)
```
1. Parse user description from Input
   → If empty: ERROR "No feature description provided"
2. Extract key concepts from description
   → Identified: Workgroup entity, many-to-many relationships, ADMIN privilege, CRUD operations, access filtering
3. For each unclear aspect:
   → Mark with [NEEDS CLARIFICATION: specific question]
4. Fill User Scenarios & Testing section
   → User flows identified for workgroup management and access control
5. Generate Functional Requirements
   → Each requirement must be testable
   → Mark ambiguous requirements
6. Identify Key Entities (if data involved)
7. Run Review Checklist
   → If any [NEEDS CLARIFICATION]: WARN "Spec has uncertainties"
   → If implementation details found: ERROR "Remove tech details"
8. Return: SUCCESS (spec ready for planning)
```

---

## ⚡ Quick Guidelines
- ✅ Focus on WHAT users need and WHY
- ❌ Avoid HOW to implement (no tech stack, APIs, code structure)
- 👥 Written for business stakeholders, not developers

---

## Clarifications

### Session 2025-10-04
- Q: Who is considered the "creator" of an asset for personal visibility purposes? → A: Both - assets have dual ownership (manual creator + scan uploader)
- Q: Do workgroups affect access to other entities beyond assets and vulnerabilities? → A: Assets, vulnerabilities, and their scan results
- Q: Can regular (non-admin) users assign workgroups to assets they create? → A: Admin-only - only administrators can assign workgroups
- Q: What constraints should apply to workgroup names? → A: 1-100 chars, alphanumeric + spaces/hyphens, case-insensitive uniqueness
- Q: How should the VULN role interact with workgroup restrictions? → A: VULN role respects workgroup restrictions like regular users

---

## User Scenarios & Testing

### Primary User Story
As a security manager, I need to organize assets and users into workgroups so that team members can only access security information relevant to their organizational unit, while maintaining visibility over assets they personally manage.

### Acceptance Scenarios

#### Scenario 1: Workgroup Creation
1. **Given** I am logged in as an administrator, **When** I navigate to the workgroup management area, **Then** I can create a new workgroup with a unique name and description

#### Scenario 2: User Assignment to Workgroup
2. **Given** I am an administrator with existing workgroups, **When** I edit a user's profile, **Then** I can assign or remove that user from one or more workgroups

#### Scenario 3: Asset Assignment to Workgroup
3. **Given** I am an administrator with existing workgroups, **When** I edit an asset, **Then** I can assign or remove that asset from one or more workgroups

#### Scenario 4: Filtered Asset Visibility
4. **Given** I am a non-admin user assigned to "Engineering Workgroup", **When** I view the asset list, **Then** I see only assets belonging to "Engineering Workgroup" plus any assets I created personally

#### Scenario 5: Filtered Vulnerability Visibility
5. **Given** I am a non-admin user assigned to "DevOps Workgroup", **When** I view vulnerabilities, **Then** I see only vulnerabilities associated with assets in "DevOps Workgroup" or assets I created

#### Scenario 5a: Filtered Scan Results Visibility
5a. **Given** I am a non-admin user assigned to "DevOps Workgroup", **When** I view scan results, **Then** I see only scan results associated with assets in "DevOps Workgroup" or assets I created

#### Scenario 6: Admin Universal Access
6. **Given** I am logged in with ADMIN role, **When** I view assets, vulnerabilities, or scan results, **Then** I see all data regardless of workgroup assignments

#### Scenario 6a: VULN Role Respects Workgroups
6a. **Given** I am logged in with VULN role and assigned to "Security Workgroup", **When** I view vulnerabilities, **Then** I see only vulnerabilities for assets in "Security Workgroup" or assets I created (same filtering as regular users)

#### Scenario 7: Workgroup Deletion
7. **Given** I am an administrator and a workgroup exists, **When** I delete the workgroup, **Then** the workgroup is removed and all associated asset/user memberships are cleared

#### Scenario 8: User Without Workgroups
8. **Given** I am a non-admin user not assigned to any workgroup, **When** I view assets, **Then** I see only the assets I personally created

### Edge Cases
- What happens when a user belongs to multiple workgroups with overlapping assets? (Should see union of all accessible assets)
- How does the system handle an asset belonging to zero workgroups? (Only visible to creator and admins)
- What happens when deleting a workgroup that has many users and assets? (Memberships removed, but users and assets persist)
- Can a workgroup have zero members or zero assets? (Yes, workgroups can exist without assignments)
- What happens if either owner (manual creator or scan uploader) of an asset is deleted? (Ownership references persist but may be null)
- Can regular users assign workgroups to assets they create? (No, only administrators can assign workgroups to any asset)

## Requirements

### Functional Requirements

#### Workgroup Management
- **FR-001**: System MUST allow administrators to create workgroups with a unique name
- **FR-002**: System MUST allow administrators to edit workgroup names and descriptions
- **FR-003**: System MUST allow administrators to delete workgroups
- **FR-004**: System MUST prevent creation of workgroups with duplicate names
- **FR-005**: System MUST provide a list view of all workgroups in the admin area
- **FR-006**: Workgroup names MUST be 1-100 characters long, contain only alphanumeric characters, spaces, and hyphens, and be unique (case-insensitive)

#### User-Workgroup Relationships
- **FR-007**: System MUST allow administrators to assign users to zero or more workgroups
- **FR-008**: System MUST allow administrators to remove users from workgroups
- **FR-009**: System MUST display all workgroups a user belongs to when viewing/editing the user
- **FR-010**: System MUST treat users with ADMIN role as implicitly belonging to all workgroups for access purposes

#### Asset-Workgroup Relationships
- **FR-011**: System MUST allow administrators to assign assets to zero or more workgroups
- **FR-012**: System MUST allow administrators to remove assets from workgroups
- **FR-013**: System MUST display all workgroups an asset belongs to when viewing/editing the asset
- **FR-014**: System MUST restrict workgroup assignment to administrators only (regular users cannot assign workgroups to assets)

#### Access Control & Filtering
- **FR-015**: System MUST filter asset lists to show only assets from workgroups the user belongs to
- **FR-016**: System MUST always show users the assets they personally created (either manually via UI or via scan upload), regardless of workgroup membership
- **FR-017**: System MUST filter vulnerability lists to show only vulnerabilities for accessible assets (based on FR-015 and FR-016)
- **FR-017a**: System MUST filter scan results to show only scans associated with accessible assets (based on FR-015 and FR-016)
- **FR-018**: System MUST grant ADMIN role users access to all assets, vulnerabilities, and scan results regardless of workgroup assignments
- **FR-018a**: System MUST apply workgroup filtering to users with VULN role in the same manner as regular users (VULN role does not bypass workgroup restrictions)
- **FR-019**: System MUST apply workgroup filtering to assets, vulnerabilities, and scan results only (requirements, norms, and use cases remain globally accessible to all authenticated users)
- **FR-020**: System MUST track dual ownership for assets: both the user who manually created the asset record and the user who uploaded any scan that discovered the asset

#### User Interface
- **FR-021**: System MUST provide a workgroup management interface in the admin area with create, edit, delete, and list functions
- **FR-022**: System MUST provide UI elements to add/remove workgroups when editing a user profile
- **FR-023**: System MUST provide UI elements to add/remove workgroups when editing an asset
- **FR-024**: System MUST display workgroup membership information on user detail views
- **FR-025**: System MUST display workgroup membership information on asset detail views

#### Data Integrity
- **FR-026**: System MUST maintain referential integrity when workgroups are deleted (remove all user and asset associations)
- **FR-027**: System MUST maintain referential integrity when users are deleted (asset ownership references become null but assets persist)
- **FR-028**: System MUST maintain referential integrity when assets are deleted (remove workgroup associations)

### Key Entities

- **Workgroup**: Represents an organizational unit or team grouping. Has a unique name, optional description, and creation metadata. Can have zero or more users assigned and zero or more assets assigned.

- **User**: Extended with workgroup memberships. A user can belong to zero or more workgroups. Users with ADMIN role have implicit access to all workgroups, while users with VULN or regular USER roles respect workgroup restrictions.

- **Asset**: Extended with workgroup memberships and dual ownership tracking. An asset can belong to zero or more workgroups. Each asset tracks both manual creator (user who created the record via UI) and scan uploader (user who uploaded scan that discovered it) for personal visibility rules.

- **Relationships**:
  - User ↔ Workgroup: Many-to-many
  - Asset ↔ Workgroup: Many-to-many
  - User → Asset: One-to-many (ownership/creator)

---

## Review & Acceptance Checklist

### Content Quality
- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

### Requirement Completeness
- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous (where specified)
- [x] Success criteria are measurable
- [x] Scope is clearly bounded
- [ ] Dependencies and assumptions identified

---

## Execution Status

- [x] User description parsed
- [x] Key concepts extracted
- [x] Ambiguities marked (all resolved)
- [x] User scenarios defined
- [x] Requirements generated
- [x] Entities identified
- [x] Review checklist passed

---

## Clarifications Needed

No remaining clarifications.
