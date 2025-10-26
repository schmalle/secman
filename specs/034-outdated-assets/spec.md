# Feature Specification: Outdated Assets View

**Feature Branch**: `034-outdated-assets`
**Created**: 2025-10-26
**Status**: Draft
**Input**: User description: "i want to get an additional UI as sub item below Vuln management named Outdated assets, which shows all assets, which have vulnerabilities longer open than the day number configured in [Image #1]. I Want to have a performance optimized solution, meaning the UI should query from a precalculated table, but there must be also a button to update the state. At best the precalculated table will be generated as part of the import from the command line, when the cli is asked to save the data. For the view please ensure the same role based access requirements as for the Vuln overview. Take complexity and speed into account. It must be possible to store more than 10000 assets."

## Clarifications

### Session 2025-10-26

- Q: Should the materialized view refresh happen synchronously (CLI import waits) or asynchronously (CLI import completes immediately, refresh happens in background)? → A: Asynchronous - CLI import triggers refresh job in background and returns immediately for better performance
- Q: What observability signals should the system expose for monitoring materialized view health and performance? → A: Basic metrics + logs - Expose refresh duration, success/failure rate, queue depth, last refresh timestamp with structured logs for debugging
- Q: Should users see detailed progress during long-running refresh operations? → A: Progress percentage - Show "Refreshing... 35%" based on assets processed for better user feedback

## User Scenarios & Testing

### User Story 1 - View Outdated Assets (Priority: P1)

As a security manager, I need to quickly identify all assets that have vulnerabilities exceeding the configured overdue threshold (e.g., 30 days), so I can prioritize remediation efforts and understand the overall security posture.

**Why this priority**: This is the core value proposition - providing a fast, dedicated view of problematic assets. Without this, users must manually filter through all vulnerabilities to find overdue items.

**Independent Test**: Can be fully tested by navigating to "Vuln Management > Outdated Assets" page and verifying the list shows only assets with vulnerabilities older than the configured threshold. Delivers immediate value by consolidating critical assets in one view.

**Acceptance Scenarios**:

1. **Given** I am an authenticated user with ADMIN or VULN role, **When** I navigate to Vuln Management menu, **Then** I see an "Outdated Assets" submenu item
2. **Given** I click on "Outdated Assets", **When** the page loads, **Then** I see a table listing assets that have at least one vulnerability older than the configured threshold
3. **Given** the configured threshold is 30 days and an asset has vulnerabilities at 25 days and 35 days old, **When** I view Outdated Assets, **Then** that asset appears in the list (because it has at least one vulnerability > 30 days)
4. **Given** an asset has only vulnerabilities under the threshold (e.g., all < 30 days), **When** I view Outdated Assets, **Then** that asset does NOT appear in the list
5. **Given** there are 100 assets with outdated vulnerabilities, **When** I load the page, **Then** the page loads in under 2 seconds (performance requirement)

---

### User Story 2 - View Asset Details and Vulnerabilities (Priority: P1)

As a security analyst, I need to see how many overdue vulnerabilities each asset has and their severity distribution, so I can understand which assets pose the highest risk and require immediate attention.

**Why this priority**: Raw asset list is insufficient - users need context about WHY an asset is outdated (number of issues, severity) to make informed decisions.

**Independent Test**: Can be tested by clicking on any asset in the Outdated Assets list and verifying detailed vulnerability information is displayed. Delivers value by enabling informed prioritization.

**Acceptance Scenarios**:

1. **Given** I am viewing the Outdated Assets list, **When** I look at an asset row, **Then** I see the asset name, total number of overdue vulnerabilities, and oldest vulnerability age
2. **Given** an asset has 5 Critical, 10 High, and 3 Medium overdue vulnerabilities, **When** I view the asset row, **Then** I see the severity breakdown (e.g., "5 Critical, 10 High, 3 Medium")
3. **Given** an asset's oldest vulnerability is 180 days old, **When** I view the asset row, **Then** I see "180 days" in the "Oldest Vulnerability" column
4. **Given** I click on an asset in the Outdated Assets list, **When** the detail view loads, **Then** I see all overdue vulnerabilities for that asset with their CVE IDs, severity, and days open

---

### User Story 3 - Manual Refresh of Outdated Assets (Priority: P2)

As a security manager, I need to manually refresh the outdated assets list after importing new vulnerability data, so I can see the most current state without waiting for automatic background updates.

**Why this priority**: While automatic updates are ideal, manual refresh provides immediate control when needed (e.g., after a major import or when making critical decisions).

**Independent Test**: Can be tested by clicking the "Refresh" button and verifying the list updates with current data. Delivers value by giving users control over data freshness.

**Acceptance Scenarios**:

1. **Given** I am viewing the Outdated Assets page, **When** I look at the top of the page, **Then** I see a "Refresh" button
2. **Given** I click the "Refresh" button, **When** the refresh is in progress, **Then** I see a progress indicator showing percentage completed (e.g., "Refreshing... 35%") and the refresh button is disabled
3. **Given** new vulnerability data was imported via CLI that creates new outdated assets, **When** I click "Refresh" and wait for completion, **Then** the newly outdated assets appear in the list
4. **Given** vulnerabilities were remediated making an asset no longer outdated, **When** I click "Refresh", **Then** that asset is removed from the list
5. **Given** I click "Refresh" while a previous refresh is still processing, **When** the button is clicked, **Then** the button is disabled with message "Updating..." until refresh completes

---

### User Story 4 - Filter and Search Outdated Assets (Priority: P3)

As a security analyst managing a large infrastructure, I need to filter outdated assets by severity, search by name, and sort by various columns, so I can quickly find specific assets or focus on the most critical issues.

**Why this priority**: Essential for usability at scale (10,000+ assets) but can be added incrementally after core viewing functionality works.

**Independent Test**: Can be tested by using filter/search controls and verifying results match criteria. Delivers value by improving navigation in large datasets.

**Acceptance Scenarios**:

1. **Given** I am viewing Outdated Assets, **When** I type an asset name in the search box, **Then** the list filters to show only matching assets
2. **Given** I select "Critical only" from severity filter, **When** the filter applies, **Then** I see only assets with Critical overdue vulnerabilities
3. **Given** I click on the "Oldest Vulnerability" column header, **When** the sort applies, **Then** assets are sorted by oldest vulnerability age (descending)
4. **Given** there are 5000 outdated assets displayed, **When** I apply any filter or search, **Then** the results update in under 1 second

---

### User Story 5 - Workgroup-Based Access Control (Priority: P2)

As a workgroup member with VULN role, I need to see only outdated assets from my assigned workgroups, so I have appropriate access restrictions consistent with the existing vulnerability management system.

**Why this priority**: Critical for security compliance but dependent on existing RBAC infrastructure. Must be implemented before production use in multi-tenant environments.

**Independent Test**: Can be tested by logging in as different users with different workgroup assignments and verifying each sees only their authorized assets.

**Acceptance Scenarios**:

1. **Given** I am a VULN user assigned to Workgroup A, **When** I view Outdated Assets, **Then** I see only assets from Workgroup A that are outdated
2. **Given** I am an ADMIN user, **When** I view Outdated Assets, **Then** I see ALL outdated assets regardless of workgroup
3. **Given** an asset belongs to multiple workgroups including one I'm assigned to, **When** I view Outdated Assets, **Then** I see that asset if it's outdated
4. **Given** I am not assigned to any workgroups and I'm not an ADMIN, **When** I view Outdated Assets, **Then** I see only assets I personally created/own that are outdated

---

### Edge Cases

- What happens when the vulnerability threshold is changed (e.g., from 30 to 60 days)? The materialized view should be automatically refreshed, and assets that no longer meet the new criteria should disappear from the list.
- What happens when there are no outdated assets? Display a friendly message: "No assets currently have overdue vulnerabilities. Great job!"
- What happens during a refresh operation if it takes longer than 30 seconds? Show a progress indicator and allow users to cancel the operation. Display timeout message if it exceeds 2 minutes.
- What happens if a user without proper permissions (USER role only) tries to access the Outdated Assets page? They should see a 403 Forbidden error with message "You need ADMIN or VULN role to view this page."
- What happens when an asset has 100+ overdue vulnerabilities? The severity breakdown should be displayed, but clicking the asset should show paginated vulnerability details to avoid UI performance issues.
- What happens during concurrent refresh operations (e.g., automatic CLI import triggers refresh while user clicks manual refresh)? Use database locking to ensure only one refresh runs at a time. Queue the second request and execute after the first completes.
- What happens if the materialized view table becomes corrupted or out of sync? Provide an admin tool to rebuild the entire materialized view from scratch using current vulnerability and configuration data.

## Requirements

### Functional Requirements

- **FR-001**: System MUST provide a new menu item "Outdated Assets" as a submenu under "Vuln Management" in the left navigation
- **FR-002**: System MUST display assets that have at least one vulnerability with age (days since scan timestamp) exceeding the configured threshold from vulnerability configuration (reminder_one_days)
- **FR-003**: System MUST show for each outdated asset: asset name, total count of overdue vulnerabilities, severity breakdown (Critical/High/Medium/Low counts), and oldest vulnerability age in days
- **FR-004**: System MUST retrieve outdated assets data from a pre-calculated materialized table for sub-2-second query performance even with 10,000+ assets
- **FR-005**: System MUST automatically trigger asynchronous materialized view refresh whenever vulnerability data is imported via the CrowdStrike CLI save operation (CLI returns immediately, refresh runs in background)
- **FR-006**: System MUST provide a "Refresh" button that allows users to manually trigger recalculation of the materialized view
- **FR-007**: System MUST show a progress indicator with percentage (e.g., "Refreshing... 35%") during materialized view refresh operations and disable the refresh button to prevent duplicate requests
- **FR-008**: System MUST restrict access to Outdated Assets view to users with ADMIN or VULN roles (consistent with vulnerability management access control)
- **FR-009**: System MUST apply workgroup-based access control: VULN users see only assets from their assigned workgroups, while ADMIN users see all assets
- **FR-010**: System MUST recalculate the materialized view whenever the vulnerability threshold configuration (reminder_one_days) is changed
- **FR-011**: System MUST support filtering outdated assets by minimum severity level (Critical, High, Medium, Low)
- **FR-012**: System MUST support text search by asset name with real-time filtering
- **FR-013**: System MUST support sorting by asset name, total overdue vulnerabilities, and oldest vulnerability age
- **FR-014**: System MUST display a user-friendly message when no outdated assets exist
- **FR-015**: System MUST handle materialized view refresh failures gracefully with error messages and logging
- **FR-016**: System MUST store materialized view data in a dedicated database table optimized for fast reads with appropriate indexes
- **FR-017**: System MUST track last refresh timestamp and display it to users (e.g., "Last updated: 2 minutes ago")
- **FR-018**: System MUST limit concurrent refresh operations to one at a time using database-level locking or application-level queuing
- **FR-019**: System MUST allow users to click on an asset to view detailed information about all its overdue vulnerabilities
- **FR-020**: System MUST support pagination for assets list when viewing more than 100 outdated assets per page
- **FR-021**: System MUST expose observability metrics including: refresh operation duration, success/failure rate, current queue depth, and last successful refresh timestamp
- **FR-022**: System MUST emit structured logs for materialized view refresh operations including start time, completion time, number of assets processed, and any error details

### Key Entities

- **OutdatedAssetMaterializedView**: Pre-calculated summary table containing:
  - Asset identifier and name
  - Total count of overdue vulnerabilities
  - Count of overdue vulnerabilities by severity (critical, high, medium, low)
  - Oldest vulnerability age in days
  - Last calculation timestamp
  - Workgroup associations (for access control filtering)

- **MaterializedViewRefreshJob**: Tracks refresh operations with:
  - Job ID
  - Start and end timestamps
  - Status (running, completed, failed)
  - Triggered by (CLI import, manual refresh, config change)
  - Error details if failed
  - Number of assets processed
  - Total assets to process (for progress calculation)
  - Current progress percentage (0-100)

## Success Criteria

### Measurable Outcomes

- **SC-001**: Outdated Assets page loads and displays results in under 2 seconds for datasets up to 10,000 assets
- **SC-002**: Manual refresh completes in under 30 seconds for datasets up to 10,000 assets
- **SC-003**: Security managers can identify all assets with overdue vulnerabilities in under 10 seconds (3 clicks: Vuln Management � Outdated Assets � view list)
- **SC-004**: System maintains 99.9% uptime for materialized view refresh operations during CLI imports
- **SC-005**: Users can successfully filter, search, and sort through 10,000+ outdated assets with sub-1-second response times
- **SC-006**: 95% of security team members successfully complete the task "Find all Critical overdue vulnerabilities older than 90 days" in under 1 minute
- **SC-007**: Page load time does not increase by more than 10% when scaling from 1,000 to 10,000 outdated assets
- **SC-008**: Zero unauthorized access incidents to outdated assets data (workgroup access control enforcement = 100%)

## Assumptions

- The existing vulnerability configuration (reminder_one_days) is already implemented and accessible via VulnerabilityConfigService
- The existing RBAC system for ADMIN/VULN roles is already functional and can be reused
- The existing workgroup-based access control logic for vulnerability views can be applied to this new view
- Database supports materialized views or equivalent pre-calculated table patterns with efficient updates
- CLI import operations have a hook or extension point where custom logic can be added to trigger refresh
- The existing Asset and Vulnerability entities have all necessary fields (scan timestamp, severity, etc.) for calculations
- Page load performance requirements (2 seconds) assume reasonable database configuration and indexing
- Users will primarily access this view for monitoring and reporting, not for real-time continuous watching
- Both CLI-triggered and manual refresh operations are asynchronous (users and CLI don't wait for completion before continuing other work)
- The system can handle temporary inconsistency between materialized view and live data (eventual consistency acceptable)

## Constraints

- Must maintain performance with 10,000+ assets (this is a hard requirement affecting architecture decisions)
- Must use role-based access control consistent with existing vulnerability management (ADMIN, VULN, workgroup filtering)
- Materialized view must be automatically updated during CLI imports (no manual intervention required for routine operations)
- Page must be accessible from "Vuln Management" menu as submenu item (navigation hierarchy constraint)
- Must use the same overdue threshold as configured in vulnerability settings (single source of truth)

## Dependencies

- Existing vulnerability configuration system and VulnerabilityConfigService
- Existing RBAC system with ADMIN/VULN roles
- Existing workgroup-based access control infrastructure
- CrowdStrike CLI import service (CrowdStrikeVulnerabilityImportService)
- Existing Asset and Vulnerability domain entities
- Existing navigation menu structure and frontend routing system

## Out of Scope

- Real-time live updates of outdated assets (eventual consistency via refresh is sufficient)
- Custom threshold configuration per user or workgroup (uses global configuration only)
- Automated alerts or notifications when assets become outdated (monitoring/alerting is separate feature)
- Historical trending of outdated assets over time (reporting/analytics is separate feature)
- Export functionality for outdated assets list (can be added later as enhancement)
- Integration with ticketing systems to create remediation tasks (separate integration feature)
- Advanced analytics like "average time to remediation" or "trends by team" (separate analytics feature)
- Asset grouping or tagging specific to outdated view (uses existing asset organization)
