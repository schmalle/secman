# Feature Specification: Asset and Workgroup Criticality Classification

**Feature Branch**: `039-asset-workgroup-criticality`
**Created**: 2025-11-01
**Status**: Draft
**Input**: User description: "implement the attribut criticality for workgroups and assets. Meaning i want a proper design for the UI, data models."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Set Workgroup Baseline Criticality (Priority: P1)

As a security administrator, I want to assign a criticality level to each workgroup so that all assets within that workgroup inherit an appropriate baseline security priority, enabling me to quickly classify large groups of assets based on their organizational importance.

**Why this priority**: This is the foundation of the feature. Setting workgroup-level criticality provides immediate organizational value by classifying assets in bulk, reducing data entry burden and establishing clear security priorities across the organization.

**Independent Test**: Can be fully tested by creating/editing a workgroup, setting its criticality level, and verifying that existing assets display the inherited criticality. Delivers immediate value by providing bulk classification capability.

**Acceptance Scenarios**:

1. **Given** I am an administrator viewing the workgroup management page, **When** I create a new workgroup, **Then** I must select a criticality level (CRITICAL, HIGH, MEDIUM, or LOW) before saving
2. **Given** I have selected MEDIUM as the default criticality for a new workgroup, **When** I save the workgroup, **Then** the workgroup is created with MEDIUM criticality
3. **Given** I am editing an existing workgroup, **When** I change its criticality from MEDIUM to CRITICAL, **Then** all assets in that workgroup without an explicit override now display CRITICAL criticality
4. **Given** I am viewing the workgroup list, **When** I see workgroups with different criticality levels, **Then** each workgroup displays a color-coded badge indicating its criticality (CRITICAL=red, HIGH=orange, MEDIUM=blue, LOW=gray)
5. **Given** I want to focus on high-priority workgroups, **When** I use the criticality filter, **Then** the list shows only workgroups matching the selected criticality level
6. **Given** I want to prioritize my review, **When** I sort workgroups by criticality, **Then** CRITICAL workgroups appear first, followed by HIGH, MEDIUM, and LOW

---

### User Story 2 - Override Asset Criticality (Priority: P2)

As an asset owner or security administrator, I want to override the criticality level of specific assets within a workgroup so that exceptionally critical or non-critical assets can be classified independently of their workgroup's baseline, ensuring accurate risk prioritization.

**Why this priority**: This enables fine-grained control for exceptions. While most assets inherit workgroup criticality (P1), some assets require individual classification (e.g., a CRITICAL database server in a MEDIUM workgroup). This is P2 because it builds on the P1 baseline capability.

**Independent Test**: Can be tested independently by editing an asset's criticality to override its workgroup default and verifying the override appears in asset lists and detail views. Delivers value by enabling exceptions to the bulk classification model.

**Acceptance Scenarios**:

1. **Given** I am viewing an asset within a MEDIUM criticality workgroup, **When** I see the asset's criticality field, **Then** it displays MEDIUM with an indication that this is inherited from the workgroup
2. **Given** I am editing an asset in a MEDIUM workgroup, **When** I set the asset criticality to CRITICAL and save, **Then** the asset displays CRITICAL with an indication that this is an override
3. **Given** an asset has an explicit CRITICAL override, **When** I change the workgroup's criticality from MEDIUM to LOW, **Then** the asset still displays CRITICAL (overrides are independent of workgroup changes)
4. **Given** I want to revert an asset to its workgroup default, **When** I clear the asset's explicit criticality override, **Then** the asset displays its workgroup's criticality with an "inherited" indicator
5. **Given** I am viewing the asset list filtered by CRITICAL, **When** the filter is applied, **Then** I see both assets with CRITICAL overrides and assets inheriting CRITICAL from their workgroups

---

### User Story 3 - Filter and Sort by Criticality (Priority: P3)

As a security team member, I want to filter and sort assets and workgroups by criticality level so that I can focus my attention on the most critical components first, improving efficiency in security reviews and incident response.

**Why this priority**: This enhances usability of the criticality data established in P1 and P2. While classification is essential, filtering/sorting is a productivity feature that maximizes value from the classification system.

**Independent Test**: Can be tested by applying criticality filters and sorts in asset and workgroup list views, verifying correct results. Delivers value by enabling efficient navigation and prioritization workflows.

**Acceptance Scenarios**:

1. **Given** I am viewing the asset list, **When** I apply a criticality filter for CRITICAL, **Then** only assets with CRITICAL criticality (inherited or overridden) are displayed
2. **Given** I am viewing the asset list with 100+ assets, **When** I sort by criticality descending, **Then** CRITICAL assets appear first, followed by HIGH, MEDIUM, LOW, maintaining other sort orders (e.g., name) as secondary sort
3. **Given** I am viewing the workgroup list, **When** I sort by criticality, **Then** workgroups are ordered CRITICAL → HIGH → MEDIUM → LOW
4. **Given** I have applied multiple filters (criticality=CRITICAL, type=Server), **When** viewing results, **Then** only assets matching all criteria are shown
5. **Given** I want to review all high-priority assets, **When** I export the filtered asset list, **Then** the export includes only the filtered assets with their criticality levels visible

---

### User Story 4 - Criticality-Based Notifications (Priority: P4)

As an asset owner, I want to receive immediate email notifications when new vulnerabilities are discovered on my CRITICAL assets, while receiving standard notifications for lower-priority assets, so that I can respond urgently to threats against my most important systems.

**Why this priority**: This integrates criticality with the existing notification system (Feature 035). It's P4 because it requires both the classification system (P1-P2) and integration with another feature, making it a nice-to-have enhancement rather than core functionality.

**Independent Test**: Can be tested by creating vulnerabilities on CRITICAL vs MEDIUM assets and verifying notification timing differs. Delivers value by automating risk-based prioritization in security responses.

**Acceptance Scenarios**:

1. **Given** I own an asset with CRITICAL criticality, **When** a new vulnerability is imported for that asset, **Then** I receive an immediate email notification (within 1 hour)
2. **Given** I own an asset with MEDIUM criticality, **When** a new vulnerability is imported for that asset, **Then** I receive a notification using the standard notification schedule
3. **Given** I am configuring my notification preferences, **When** I enable criticality-based notifications, **Then** I can set different notification rules for each criticality level
4. **Given** an asset's criticality is upgraded from MEDIUM to CRITICAL, **When** existing vulnerabilities exist on that asset, **Then** future notifications use the CRITICAL notification rules (no retroactive notifications sent)

---

### User Story 5 - Criticality in Dashboards and Reports (Priority: P5)

As a security manager, I want to see criticality-based statistics in dashboards and reports so that I can understand the security posture of my most critical assets at a glance and report on progress to leadership.

**Why this priority**: This is a reporting enhancement that provides strategic value but isn't essential for day-to-day operations. It's P5 because it requires the classification system (P1-P2) and builds on existing dashboard capabilities (Features 034, 036).

**Independent Test**: Can be tested by verifying criticality statistics appear correctly in dashboard widgets and reports. Delivers value by enabling executive reporting and strategic decision-making.

**Acceptance Scenarios**:

1. **Given** I am viewing the outdated assets dashboard, **When** I see the statistics, **Then** I can filter by criticality to see "X CRITICAL assets with overdue vulnerabilities"
2. **Given** I am viewing the vulnerability statistics dashboard, **When** I see the charts, **Then** I can see vulnerability counts grouped by asset criticality level (based on current criticality values)
3. **Given** I want to report to leadership, **When** I export the outdated assets report, **Then** the export includes a criticality column and summary statistics by criticality
4. **Given** I am viewing the asset list, **When** I see the summary statistics, **Then** I see a count breakdown: "X CRITICAL, Y HIGH, Z MEDIUM, W LOW assets"

---

### Edge Cases

- **What happens when a workgroup is deleted?** Assets lose their workgroup association but retain any explicit criticality overrides. Assets without overrides default to MEDIUM criticality (or system default if configured).

- **What happens when an asset is moved to a different workgroup?** If the asset has an explicit override, it retains that override. If it was inheriting from the old workgroup, it now inherits from the new workgroup's criticality.

- **What happens when an asset belongs to multiple workgroups with different criticalities?** The system uses the highest criticality level among all assigned workgroups (CRITICAL > HIGH > MEDIUM > LOW) as the inherited baseline, unless the asset has an explicit override.

- **What happens when importing assets in bulk?** Imported assets without explicit criticality inherit from their assigned workgroups. If no workgroup is assigned during import, they default to MEDIUM criticality.

- **What happens when a user without admin privileges tries to change criticality?** The system enforces role-based access control: only ADMIN and VULN roles can modify workgroup criticality; only asset owners, ADMIN, and VULN roles can modify asset criticality overrides.

- **What happens to existing assets when this feature is deployed?** All existing workgroups default to MEDIUM criticality. All existing assets have no explicit override, so they inherit MEDIUM from their workgroups. Administrators can then update criticality levels as needed.

- **How does the system handle concurrent criticality updates?** The system uses optimistic locking (version field) to prevent lost updates. If two users try to update the same workgroup/asset criticality simultaneously, the second save fails with a conflict error.

## Requirements *(mandatory)*

### Functional Requirements

#### Data Model Requirements

- **FR-001**: Workgroups MUST have a criticality attribute with exactly four possible values: CRITICAL, HIGH, MEDIUM, LOW
- **FR-002**: The workgroup criticality attribute MUST be mandatory (cannot be null) with a default value of MEDIUM
- **FR-003**: Assets MUST have an optional criticality attribute with the same four values: CRITICAL, HIGH, MEDIUM, LOW
- **FR-004**: The asset criticality attribute MUST be nullable, indicating inheritance from workgroup when null
- **FR-005**: When an asset's criticality is null, the system MUST determine the effective criticality using inheritance rules
- **FR-006**: When an asset belongs to multiple workgroups, the system MUST use the highest criticality among assigned workgroups as the inherited value (CRITICAL > HIGH > MEDIUM > LOW)
- **FR-007**: When an asset has no assigned workgroups and no explicit criticality, the system MUST default to MEDIUM criticality
- **FR-008**: Criticality values MUST be stored as enumerated types (not free text) to ensure data consistency
- **FR-009**: Both workgroup and asset entities MUST include database constraints preventing invalid criticality values
- **FR-009a**: The system MUST store only the current criticality value; no historical audit trail or time-series tracking of criticality changes is required

#### User Interface Requirements

- **FR-010**: The workgroup creation form MUST include a criticality dropdown with all four levels (CRITICAL, HIGH, MEDIUM, LOW)
- **FR-011**: The workgroup edit form MUST allow changing criticality with immediate effect on all inheriting assets
- **FR-011a**: When a workgroup's criticality is changed, the UI MUST display a progress indicator (loading state) until all inheriting asset updates complete
- **FR-012**: The asset creation/edit form MUST include an optional criticality dropdown with options: "Inherit from workgroup", CRITICAL, HIGH, MEDIUM, LOW
- **FR-013**: When displaying an asset's criticality, the UI MUST clearly indicate whether the value is inherited or explicitly set
- **FR-014**: Criticality MUST be displayed using color-coded badges with icon symbols and text labels for accessibility: CRITICAL (red/danger with icon and "CRITICAL" text), HIGH (orange/warning with icon and "HIGH" text), MEDIUM (blue/info with icon and "MEDIUM" text), LOW (gray/secondary with icon and "LOW" text)
- **FR-015**: The workgroup list view MUST display each workgroup's criticality as a colored badge
- **FR-016**: The asset list view MUST display each asset's effective criticality (inherited or overridden) as a colored badge
- **FR-017**: Both workgroup and asset list views MUST provide filter controls for criticality
- **FR-018**: Both workgroup and asset list views MUST support sorting by criticality (CRITICAL > HIGH > MEDIUM > LOW)
- **FR-019**: When an asset is displayed with inherited criticality, the UI MUST show which workgroup the criticality is inherited from (if multiple, show the one providing the highest value)
- **FR-020**: Asset detail views MUST display both the effective criticality and the source (inherited from X workgroup or explicitly set)

#### Access Control Requirements

- **FR-021**: Only users with ADMIN role MUST be able to set or modify workgroup criticality
- **FR-022**: Only users with ADMIN or VULN roles MUST be able to set or modify asset criticality overrides
- **FR-023**: All authenticated users MUST be able to view criticality information on workgroups and assets they have access to
- **FR-024**: The system MUST enforce workgroup-based access control: users can only see and filter assets based on their workgroup memberships

#### Integration Requirements

- **FR-025**: The notification system (Feature 035) MUST consider asset criticality when determining notification timing and urgency
- **FR-026**: CRITICAL asset vulnerabilities MUST trigger immediate notifications (within 1 hour of discovery)
- **FR-027**: HIGH, MEDIUM, and LOW asset vulnerabilities MUST use standard notification schedules
- **FR-028**: The outdated assets dashboard (Feature 034) MUST allow filtering by criticality level
- **FR-029**: The vulnerability statistics dashboard (Feature 036) MUST display vulnerability counts grouped by asset criticality
- **FR-030**: Asset export functionality MUST include criticality in the exported data
- **FR-031**: Bulk asset imports MUST support an optional criticality column; imported assets without explicit values inherit from assigned workgroups

#### Data Migration Requirements

- **FR-032**: Upon deployment, all existing workgroups MUST be assigned MEDIUM criticality as the default
- **FR-033**: Upon deployment, all existing assets MUST have null criticality (inheriting MEDIUM from workgroups)
- **FR-034**: The migration MUST preserve all existing workgroup and asset data without data loss
- **FR-035**: The system MUST continue to function correctly for users who do not immediately set custom criticality values (default MEDIUM is acceptable)

#### Validation and Error Handling

- **FR-036**: The system MUST validate that criticality values are one of the four allowed values before saving
- **FR-037**: When a user attempts to save an invalid criticality value, the system MUST display a clear error message
- **FR-038**: When a workgroup criticality change affects many inheriting assets, the system MUST update all affected assets without data inconsistency
- **FR-039**: If a concurrent update conflict occurs (optimistic locking failure), the system MUST prompt the user to refresh and retry

### Key Entities

- **Workgroup**: Organizational unit for grouping users and assets. Key attributes include name, description, users (many-to-many), assets (many-to-many), and **criticality** (CRITICAL/HIGH/MEDIUM/LOW, mandatory, default MEDIUM). Represents the baseline security priority for all assets within the group.

- **Asset**: IT asset requiring security monitoring. Key attributes include name, type, IP address, owner, description, vulnerabilities (one-to-many), workgroups (many-to-many), and **criticality** (CRITICAL/HIGH/MEDIUM/LOW, optional/nullable). When null, asset inherits criticality from its workgroup(s). Represents the effective security priority for vulnerability response and resource allocation.

- **Criticality Enum**: Enumerated type with four values representing security priority levels:
  - **CRITICAL**: Mission-critical assets requiring immediate response to any security finding
  - **HIGH**: Important assets requiring prioritized attention
  - **MEDIUM**: Standard assets following normal security procedures
  - **LOW**: Non-critical assets with relaxed security monitoring

- **Inheritance Relationship**: When an asset's criticality is null, the effective criticality is determined by:
  1. If asset has multiple workgroups: use the highest criticality among them
  2. If asset has one workgroup: use that workgroup's criticality
  3. If asset has no workgroups: default to MEDIUM

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Security administrators can classify all assets in their organization by criticality within 30 minutes (assuming average 10-20 workgroups and bulk workgroup classification)

- **SC-002**: 90% of assets are classified by criticality (either explicitly or via workgroup inheritance) within 2 weeks of feature deployment

- **SC-003**: Users can filter asset lists by criticality and see results in under 2 seconds for datasets with up to 10,000 assets

- **SC-004**: Security team members report 40% reduction in time spent prioritizing vulnerability remediation tasks (measured via user survey 4 weeks post-deployment)

- **SC-005**: Criticality-based notification filtering reduces notification volume for low-priority assets by 60% while ensuring CRITICAL assets receive immediate alerts

- **SC-006**: Dashboard and report views load with criticality statistics in under 3 seconds for datasets with 1,000+ assets

- **SC-007**: 100% of workgroup criticality changes propagate to inheriting assets within 5 seconds (no stale data displayed)

- **SC-008**: Zero data inconsistencies between workgroup criticality and inherited asset criticality (verified via automated consistency checks)

- **SC-009**: Asset owners can identify their CRITICAL assets and their current vulnerability status in under 30 seconds from the main dashboard

- **SC-010**: Security managers can generate executive reports showing "X CRITICAL assets with Y overdue vulnerabilities" in under 60 seconds

### Assumptions

- The existing role-based access control system (ADMIN, VULN roles) is appropriate for controlling criticality modifications
- The four-level criticality scale (CRITICAL/HIGH/MEDIUM/LOW) aligns with organizational security classification standards
- The inheritance model (workgroup → asset) reflects typical organizational structures where groups of assets share common criticality
- The color scheme (red/orange/blue/gray) combined with icon symbols and text labels provides accessible and intuitive visual encoding
- MEDIUM is an appropriate default criticality for existing data during migration
- The existing notification system (Feature 035) can be extended to support criticality-based routing
- Performance requirements are based on current system capacity (10,000 assets, 100 workgroups)

### Dependencies

- Feature 035 (Notification System): Required for criticality-based notification routing (User Story 4)
- Feature 034 (Outdated Assets Dashboard): Required for criticality filtering in dashboard views (User Story 5)
- Feature 036 (Vulnerability Statistics): Required for criticality-based reporting and analytics (User Story 5)
- Existing workgroup and asset management infrastructure
- Existing role-based access control system (ADMIN, VULN roles)

## Clarifications

### Session 2025-11-01

- Q: When a criticality level is changed (workgroup or asset override), should the system maintain an audit trail of who changed it, when, and what the previous value was? → A: No audit trail - current value only
- Q: When an administrator changes a workgroup's criticality (affecting potentially hundreds of inheriting assets), what should the user experience be during the propagation? → A: Show progress indicator - display loading state until propagation completes
- Q: The spec defines color-coded badges for criticality (red=CRITICAL, orange=HIGH, blue=MEDIUM, gray=LOW). How should the system accommodate users with color vision deficiencies? → A: Icon + text labels - add icon symbols and text within badges
- Q: Should the system track historical criticality values over time (e.g., "Asset X was CRITICAL from Jan-Mar, then downgraded to HIGH")? → A: No historical tracking - store current criticality only
