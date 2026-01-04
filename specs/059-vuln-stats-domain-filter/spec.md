# Feature Specification: Vulnerability Statistics Domain Filter

**Feature Branch**: `059-vuln-stats-domain-filter`
**Created**: 2026-01-04
**Status**: Draft
**Input**: User description: "Please add a domain selector in the /vulnerability-statistics UI and implement the necessary functions."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Filter Statistics by Domain (Priority: P1)

As a security analyst, I want to filter the vulnerability statistics page by Active Directory domain so that I can focus on vulnerabilities affecting specific parts of our infrastructure and make domain-specific security decisions.

**Why this priority**: This is the core functionality requested. Without domain filtering, users cannot segment their vulnerability data by organizational unit, making it difficult to prioritize remediation efforts for specific domains.

**Independent Test**: Can be fully tested by selecting a domain from the dropdown and verifying that all statistics (Top 10 Vulnerabilities, Top 10 Products, Severity Distribution) update to show only data from assets belonging to that domain.

**Acceptance Scenarios**:

1. **Given** a user is on the vulnerability statistics page, **When** the page loads, **Then** a domain selector dropdown is visible in the page header area showing "All Domains" as the default selection.

2. **Given** a user has access to assets in multiple domains, **When** the user clicks the domain selector, **Then** they see a list of all domains they have access to (based on their domain mappings and workgroup assignments).

3. **Given** a user selects a specific domain from the dropdown, **When** the selection is made, **Then** all three statistics components (Most Common Vulnerabilities, Most Vulnerable Products, Severity Distribution) refresh to show only data from assets belonging to that domain.

4. **Given** a user has filtered by a domain, **When** they select "All Domains", **Then** the statistics return to showing aggregated data across all accessible domains.

---

### User Story 2 - Persist Domain Selection (Priority: P2)

As a security analyst who regularly monitors a specific domain, I want my domain selection to persist during my session so that I don't have to re-select it when navigating away and returning to the statistics page.

**Why this priority**: Improves user experience for analysts who focus on specific domains, but the feature is still valuable without persistence.

**Independent Test**: Can be tested by selecting a domain, navigating to another page, returning to vulnerability statistics, and verifying the domain selection is maintained.

**Acceptance Scenarios**:

1. **Given** a user has selected a specific domain, **When** they navigate away from the vulnerability statistics page and return, **Then** the previously selected domain is still selected.

2. **Given** a user's session ends (logout or timeout), **When** they log back in and visit vulnerability statistics, **Then** the domain selector defaults to "All Domains".

---

### User Story 3 - Display Domain Context (Priority: P3)

As a user viewing filtered statistics, I want clear visual indication of the active filter so that I understand the scope of the data I'm viewing.

**Why this priority**: Nice-to-have UX improvement that helps prevent confusion but is not essential for core functionality.

**Independent Test**: Can be tested by selecting a domain and verifying visual indicators appear showing the active filter.

**Acceptance Scenarios**:

1. **Given** a user has selected a specific domain, **When** viewing the statistics, **Then** a visual indicator (badge or subtitle) shows which domain is currently selected.

2. **Given** "All Domains" is selected, **When** viewing the statistics, **Then** no additional filter indicator is shown (default state).

---

### Edge Cases

- What happens when a user has access to only one domain? The selector still appears but with only one option plus "All Domains" (which would show the same data).
- How does the system handle a user with no domain access? The selector shows only "All Domains" and data comes from workgroup-assigned and personally owned assets only.
- What happens when an asset belongs to multiple domains? Assets belong to one AD domain at most (based on the adDomain field); this is not a multi-domain scenario.
- How does the filter interact with existing access control? Domain filtering is applied as an additional filter on top of existing access control (user still only sees data they're authorized to access).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST display a domain selector dropdown on the vulnerability statistics page header.
- **FR-002**: System MUST populate the domain selector with all unique AD domains from assets the user has access to.
- **FR-003**: System MUST include an "All Domains" option as the default selection that shows aggregated statistics.
- **FR-004**: System MUST update all three statistics components (Most Common Vulnerabilities, Most Vulnerable Products, Severity Distribution) when a domain is selected.
- **FR-005**: System MUST preserve the user's existing access control when applying domain filters (users cannot see data from domains they don't have access to).
- **FR-006**: System MUST persist the domain selection in the browser session storage so it survives page navigation within the same session.
- **FR-007**: System MUST reset the domain selection to "All Domains" when a new user session begins.
- **FR-008**: System MUST display the total count of assets in the selected domain context (or all domains) somewhere visible.
- **FR-009**: System MUST load the domain selector independently from statistics components, showing "Loading domains..." placeholder while fetching the domain list.
- **FR-010**: System MUST allow users to view unfiltered statistics if the domain list fails to load, displaying an appropriate error message in the selector.

### Key Entities

- **Domain Filter**: The currently selected domain value (string or null for "All Domains")
- **Available Domains**: List of unique AD domain values extracted from accessible assets
- **Filtered Statistics**: The vulnerability statistics data scoped to the selected domain

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can filter vulnerability statistics by domain in under 3 seconds (time from selection to data display).
- **SC-002**: 100% of displayed statistics accurately reflect only the selected domain's data when a filter is applied.
- **SC-003**: Users can identify their current filter context within 2 seconds of viewing the page.
- **SC-004**: Domain selection persists across navigation within a session 100% of the time.
- **SC-005**: Users with access to 10+ domains can locate and select their target domain in under 5 seconds.

## Clarifications

### Session 2026-01-04

- Q: How should the UI behave when domain filter data is loading or fails to load? → A: Domain selector loads independently; show "Loading domains..." placeholder in dropdown

## Assumptions

- The existing vulnerability statistics page already respects user access control (ADMIN sees all, others see workgroup-assigned and owned assets).
- Assets have an `adDomain` field that contains the Active Directory domain name (may be null for non-domain-joined assets).
- The existing statistics components can accept a domain filter parameter and will handle the filtering appropriately.
- Session storage is available in all supported browsers for persisting the selection.
- The domain selector will use standard dropdown patterns consistent with other selectors in the application.
