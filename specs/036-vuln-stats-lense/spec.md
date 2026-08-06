# Feature Specification: Vulnerability Statistics Lense

**Feature Branch**: `036-vuln-stats-lense`
**Created**: 2025-10-27
**Status**: Draft
**Input**: User description: "please implement a subitem called Lense, which shows statistics like Most occurring vulnerability and implement the corresponding backend functionality. Please also add other statistic details to this page."

## Clarifications

### Session 2025-10-27

- Q: What visualization format should be used for temporal trend data? → A: Line chart showing vulnerability count over time with smooth trend line
- Q: What visualization format should be used for severity distribution statistics? → A: Pie chart with percentages and legend showing severity levels with distinct colors
- Q: What caching strategy should be used for statistics calculations? → A: No caching - always calculate statistics fresh on every page load

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View Most Common Vulnerabilities (Priority: P1)

Security administrators and vulnerability managers need to quickly identify which vulnerabilities appear most frequently across their infrastructure to prioritize remediation efforts effectively.

**Why this priority**: Identifying the most common vulnerabilities directly impacts remediation strategy and resource allocation. This is the core value proposition of the statistics view.

**Independent Test**: Can be fully tested by accessing the Lense page and verifying that the top 5-10 most frequently occurring vulnerabilities are displayed with their occurrence counts.

**Acceptance Scenarios**:

1. **Given** the system has vulnerability data from multiple assets, **When** the user navigates to the Vulnerability Management > Lense page, **Then** they see a list of the top vulnerabilities ranked by frequency of occurrence with counts
2. **Given** multiple CVEs exist in the system, **When** viewing the statistics, **Then** each vulnerability shows its CVE identifier, severity level, and total occurrence count
3. **Given** the user is viewing vulnerability statistics, **When** they click on a specific vulnerability entry, **Then** they are shown detailed information including affected assets

---

### User Story 2 - View Severity Distribution Statistics (Priority: P2)

Security teams need to understand the overall security posture by seeing how vulnerabilities are distributed across severity levels (Critical, High, Medium, Low).

**Why this priority**: Severity distribution provides strategic insight into overall risk profile and helps justify resource allocation, but is secondary to knowing which specific vulnerabilities to fix first.

**Independent Test**: Can be fully tested by viewing the Lense page and verifying that a breakdown of vulnerabilities by severity level is displayed with counts and percentages.

**Acceptance Scenarios**:

1. **Given** vulnerabilities exist at different severity levels, **When** the user views the Lense page, **Then** they see a pie chart showing the distribution with percentages and a legend displaying each severity level (Critical, High, Medium, Low) with distinct colors
2. **Given** the severity distribution pie chart is displayed, **When** the user clicks on a severity level segment or legend entry, **Then** they are shown a filtered list of all vulnerabilities at that severity level

---

### User Story 3 - View Asset Vulnerability Statistics (Priority: P3)

Security administrators need to identify which assets or asset types have the most vulnerabilities to understand where security weaknesses are concentrated.

**Why this priority**: Asset-based statistics help identify systemic issues in specific infrastructure areas, but is tertiary to knowing what vulnerabilities exist and their severity.

**Independent Test**: Can be fully tested by viewing statistics showing which assets have the highest vulnerability counts and which asset types are most vulnerable.

**Acceptance Scenarios**:

1. **Given** multiple assets have vulnerabilities, **When** the user views the Lense page, **Then** they see a list of assets with the highest vulnerability counts
2. **Given** different asset types exist (servers, workstations, network devices), **When** viewing asset statistics, **Then** vulnerability counts are grouped by asset type
3. **Given** the user clicks on an asset in the statistics, **When** the selection is made, **Then** they navigate to that asset's detail page showing all its vulnerabilities

---

### User Story 4 - View Temporal Trends (Priority: P4)

Security teams want to understand how vulnerability counts change over time to measure the effectiveness of remediation efforts.

**Why this priority**: Trend analysis provides valuable long-term insights but requires historical data and is less immediately actionable than current vulnerability counts.

**Independent Test**: Can be fully tested by viewing a time-series chart showing how total vulnerability counts have changed over the past 30/60/90 days.

**Acceptance Scenarios**:

1. **Given** vulnerability data exists for the past 90 days, **When** the user views the Lense page, **Then** they see a line chart showing total vulnerability count over time with a smooth trend line
2. **Given** trend data is displayed, **When** the user selects different time ranges (30/60/90 days), **Then** the chart updates to show the selected time period
3. **Given** the line chart is visible, **When** the user hovers over a data point, **Then** they see the exact count and date for that point in a tooltip

---

### Edge Cases

- What happens when no vulnerability data exists in the system?
  - Display empty state with helpful message indicating no vulnerabilities have been scanned/imported
- What happens when an asset is deleted but historical vulnerability data exists?
  - Statistics should still reflect historical data but mark asset as "deleted" or exclude from current asset rankings
- How does the system handle very large datasets (10,000+ vulnerabilities)?
  - Implement pagination and aggregation to ensure page loads within acceptable time
- What happens when severity information is missing for a vulnerability?
  - Categorize as "Unknown" severity and display separately in statistics
- How are duplicate CVEs across different assets counted?
  - Each instance is counted separately (e.g., CVE-2023-1234 on 5 different assets = count of 5)
- What happens when workgroup filtering applies (non-admin users)?
  - Statistics should only reflect vulnerabilities on assets the user has access to through their workgroups

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST display the top 10 most frequently occurring vulnerabilities with their occurrence counts
- **FR-002**: System MUST show each vulnerability's CVE identifier, severity level, and total count across all accessible assets
- **FR-003**: System MUST provide a severity distribution breakdown showing count and percentage for each severity level (Critical, High, Medium, Low) displayed as an interactive pie chart with distinct color coding and legend
- **FR-004**: System MUST display a list of the top 10 assets with the highest vulnerability counts
- **FR-005**: System MUST group vulnerability statistics by asset type and show counts per type
- **FR-006**: System MUST respect workgroup access controls - non-admin users should only see statistics for assets they have access to through their workgroups
- **FR-007**: System MUST provide time-series data showing vulnerability count trends over selectable time periods (30/60/90 days) displayed as a line chart with smooth trend line and interactive tooltips on hover
- **FR-008**: System MUST allow users to click on vulnerabilities in statistics to view detailed information
- **FR-009**: System MUST allow users to click on assets in statistics to navigate to the asset detail page
- **FR-010**: System MUST allow users to click on severity level pie chart segments or legend entries to view filtered lists of vulnerabilities at that level
- **FR-011**: System MUST handle missing or incomplete data gracefully by categorizing as "Unknown" or excluding from specific statistics
- **FR-012**: System MUST display an appropriate empty state message when no vulnerability data exists
- **FR-013**: System MUST calculate statistics dynamically based on current vulnerability data at the time of page load without caching (always fresh calculation)
- **FR-014**: System MUST add the Lense page as a sub-item under Vulnerability Management in the navigation sidebar
- **FR-015**: System MUST ensure statistics page loads within 3 seconds for datasets up to 10,000 vulnerabilities

### Key Entities

- **Vulnerability Statistics Aggregate**: Represents calculated statistics including most common vulnerabilities, severity distribution, asset rankings, and temporal trends; derived from existing Vulnerability and Asset entities
- **Vulnerability Trend Data Point**: Represents a snapshot of vulnerability counts at a specific date for time-series analysis; includes date, total count, and breakdown by severity
- **Top Vulnerability Entry**: Represents a vulnerability ranked by occurrence frequency; includes CVE identifier, severity, total occurrence count, and reference to vulnerability details

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Security administrators can identify the top 10 most common vulnerabilities within 10 seconds of loading the Lense page
- **SC-002**: Users can understand the overall severity distribution of vulnerabilities at a glance (within 5 seconds)
- **SC-003**: The statistics page loads and displays all data within 3 seconds for environments with up to 10,000 vulnerabilities
- **SC-004**: 90% of users can successfully navigate from a statistic to detailed vulnerability or asset information on their first attempt
- **SC-005**: Statistics accurately reflect current vulnerability data with no more than a 1-minute delay from when data is imported
- **SC-006**: Workgroup-based access controls ensure users only see statistics for assets they have permissions to view
- **SC-007**: The Lense feature reduces time spent identifying high-priority vulnerabilities by at least 40% compared to manual analysis of vulnerability lists
- **SC-008**: 95% of security team members can interpret the severity distribution statistics without additional training or documentation

## Assumptions

- Vulnerability data already exists in the system from previous features (vulnerability imports and scanning)
- The existing Asset and Vulnerability entities contain sufficient data (CVE identifiers, severity levels, scan dates) for statistical analysis
- Users have appropriate role-based access (ADMIN or VULN roles) to access the Vulnerability Management section
- Historical vulnerability data is retained for at least 90 days to support trend analysis
- Severity levels follow standard CVSS severity classifications (Critical, High, Medium, Low)
- Asset types are already categorized in the system (e.g., server, workstation, network device)
- The frontend uses the existing React/Astro framework and Bootstrap styling for consistency
- Statistics will be calculated on-demand on every page load without caching to ensure data freshness (can be optimized with caching later if performance requires)

## Dependencies

- Existing Vulnerability Management features (Feature 004, 031, 034)
- Existing Asset Management functionality (Feature 008)
- Workgroup-based access control system (Feature 008)
- Role-based access control (ADMIN, VULN roles from Feature 025)

## Out of Scope

- Real-time updating of statistics (page refresh required to see latest data)
- Export of statistics to PDF/Excel formats (can be added in future iteration)
- Customizable dashboards where users can configure which statistics to display
- Comparison of statistics across different time periods side-by-side
- Predictive analytics or trend forecasting
- Integration with external vulnerability intelligence feeds
- Alerting or notification when statistics cross certain thresholds
- Custom severity level definitions (will use CVSS standard only)
