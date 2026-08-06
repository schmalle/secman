# Feature Specification: CrowdStrike System Vulnerability Lookup

**Feature Branch**: `015-we-have-currently`
**Created**: 2025-10-11
**Status**: Draft
**Input**: User description: "we have currently a vulnerability UI under Vuln Management / Vuln overview. I want you now to write a UI for system vulns, where the user can enter a system name and then you reach out to Crowdstrike API (https://www.falconpy.io) and search for vulnerabilities from the last 40 days, which are in state OPEN. Show the result in the same UI design as from Vuln Management / Vuln Overview. The credentials for the Corwdstrike API you have already stored in your backend. If you have collected the vulnerabilities, store them also in the same format as you stored the vulnerabilities, if you import vulnerabilities via the I/O import function."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Query CrowdStrike for System Vulnerabilities (Priority: P1)

A security analyst needs to check the current vulnerability status of a specific system by querying the CrowdStrike Falcon API in real-time. They enter the system name (hostname), click search, and see results displayed in the familiar vulnerability overview table format with filtering and sorting capabilities.

**Why this priority**: This is the core functionality that delivers immediate value - enabling real-time vulnerability lookups from CrowdStrike without manual export/import workflows. This is the foundation for all other functionality.

**Independent Test**: Can be fully tested by entering a system name, clicking search, and verifying the results are displayed in a table with columns for CVE, severity, product, days open, scan date, and exception status. Delivers immediate value by showing real-time vulnerability data.

**Acceptance Scenarios**:

1. **Given** the user is on the System Vulnerability Lookup page, **When** they enter a valid system name and click "Search", **Then** the system queries CrowdStrike API for vulnerabilities from the last 40 days with status OPEN and displays results in a table
2. **Given** the user has searched for a system, **When** the results are displayed, **Then** the table shows columns: System, IP, CVE, Severity, Product, Days Open, Scan Date, Exception Status (matching the current Vuln Overview design)
3. **Given** the user has searched for a system with no vulnerabilities, **When** the search completes, **Then** the system displays "No vulnerabilities found" message
4. **Given** the user enters a system name that doesn't exist in CrowdStrike, **When** the search completes, **Then** the system displays an appropriate "System not found" message
5. **Given** the CrowdStrike API is unavailable, **When** the user searches, **Then** the system displays a clear error message indicating the service is unavailable

---

### User Story 2 - Filter and Sort Live Results (Priority: P2)

After retrieving vulnerability results from CrowdStrike, the security analyst can filter by severity level, exception status, and product, as well as sort by any column (severity, days open, scan date, etc.) to prioritize remediation efforts.

**Why this priority**: Enhances the basic search capability by allowing analysts to focus on critical vulnerabilities and prioritize their work. This reuses existing UI patterns from Vuln Overview.

**Independent Test**: Can be tested by performing a search that returns multiple vulnerabilities, then applying filters (e.g., "Critical" severity only) and sorting (e.g., by days open descending), and verifying the table updates correctly. Delivers value by enabling efficient vulnerability triage.

**Acceptance Scenarios**:

1. **Given** vulnerability results are displayed, **When** the user selects a severity filter (Critical/High/Medium/Low), **Then** the table shows only vulnerabilities matching that severity
2. **Given** vulnerability results are displayed, **When** the user clicks a column header, **Then** the table sorts by that column in ascending order, clicking again sorts descending
3. **Given** vulnerability results are displayed, **When** the user filters by exception status, **Then** the table shows only vulnerabilities that are excepted or not excepted based on selection
4. **Given** vulnerability results are displayed, **When** the user enters text in the product filter field, **Then** the table shows only vulnerabilities where the product field contains that text

---

### User Story 3 - Persist Vulnerabilities to Database (Priority: P3)

After viewing the CrowdStrike vulnerability results, the security analyst can click a "Save to Database" button to persist all displayed vulnerabilities to the local database in the same format as imported vulnerabilities, enabling historical tracking and exception management.

**Why this priority**: Enables integration with existing vulnerability management workflows (exceptions, historical tracking) but is not required for the initial lookup functionality. Can be added after the core search works.

**Independent Test**: Can be tested by searching for a system, reviewing the results, clicking "Save to Database", and then verifying the vulnerabilities appear in the Vuln Management / Vuln Overview page and can be managed with exceptions. Delivers value by integrating live CrowdStrike data into the existing vulnerability management system.

**Acceptance Scenarios**:

1. **Given** vulnerability results are displayed from CrowdStrike, **When** the user clicks "Save to Database", **Then** all displayed vulnerabilities are saved to the database with the current timestamp as scanTimestamp
2. **Given** vulnerabilities have been saved to the database, **When** the user navigates to Vuln Management / Vuln Overview, **Then** the saved vulnerabilities appear in the list with all standard fields populated
3. **Given** a vulnerability from CrowdStrike matches an existing vulnerability in the database (same CVE, same asset), **When** the user saves, **Then** the system creates a new historical record with the new scan timestamp (preserving historical data)
4. **Given** the system name from CrowdStrike does not match an existing asset, **When** the user saves vulnerabilities, **Then** the system creates a new Asset record with the hostname and IP from CrowdStrike

---

### User Story 4 - Refresh Results (Priority: P3)

While viewing vulnerability results, the security analyst can click a "Refresh" button to re-query CrowdStrike API with the same search parameters to get the latest data without re-entering the system name.

**Why this priority**: Nice-to-have convenience feature that improves user experience but doesn't add core functionality. The same result can be achieved by clicking Search again.

**Independent Test**: Can be tested by performing a search, clicking "Refresh", and verifying the API is called again and results are updated. Delivers value by making it easier to check if vulnerabilities have changed.

**Acceptance Scenarios**:

1. **Given** vulnerability results are displayed, **When** the user clicks the "Refresh" button, **Then** the system re-queries CrowdStrike API with the same system name and updates the displayed results
2. **Given** the user clicks "Refresh" and vulnerabilities have changed in CrowdStrike, **When** the results load, **Then** the table displays the updated vulnerability list

---

### Edge Cases

- What happens when the system name entered contains special characters or spaces? (System should handle by encoding properly in API calls)
- What happens when CrowdStrike API returns more than 1000 vulnerabilities? (System should implement pagination or warn user to refine search)
- What happens when the CrowdStrike API credentials are invalid or expired? (System should display a clear authentication error and log for admin investigation)
- What happens when the user tries to save vulnerabilities but the Asset cannot be created (validation errors)? (System should display validation errors and allow user to correct)
- What happens when the CrowdStrike API response contains vulnerabilities with missing fields (no CVE, no severity)? (System should handle gracefully, displaying "-" or "Unknown" for missing fields)
- What happens when the user searches multiple times in quick succession? (System should cancel previous requests or queue them appropriately)
- What happens when the scan timestamp from CrowdStrike is in the future or invalid format? (System should validate and use current timestamp as fallback)

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a new page/UI component for CrowdStrike system vulnerability lookup accessible from the Vuln Management section
- **FR-002**: System MUST accept a system name (hostname) as input from the user via a search form
- **FR-003**: System MUST query the CrowdStrike Falcon API using stored credentials to retrieve vulnerabilities for the specified system from the last 40 days with status OPEN
- **FR-004**: System MUST display vulnerability results in a table with the same design and columns as the current Vuln Management / Vuln Overview page (System, IP, CVE, Severity, Product, Days Open, Scan Date, Exception Status)
- **FR-005**: System MUST support filtering results by severity level (Critical, High, Medium, Low)
- **FR-006**: System MUST support filtering results by exception status (Excepted, Not Excepted)
- **FR-007**: System MUST support filtering results by product name (text search)
- **FR-008**: System MUST support sorting results by any column (ascending/descending)
- **FR-009**: System MUST provide a "Save to Database" action that persists displayed vulnerabilities to the database in the same format as imported vulnerabilities
- **FR-010**: System MUST create new Vulnerability records with scanTimestamp set to the CrowdStrike scan date when saving
- **FR-011**: System MUST create new Asset records when the system name from CrowdStrike does not match an existing asset
- **FR-012**: System MUST preserve historical vulnerability records (duplicate CVE on same asset differentiated by scanTimestamp)
- **FR-013**: System MUST display appropriate loading states while querying the CrowdStrike API
- **FR-014**: System MUST display clear error messages when API calls fail (authentication errors, network errors, not found, etc.)
- **FR-015**: System MUST display severity badges with color coding (Critical=red, High=orange, Medium=blue, Low=green) matching the existing Vuln Overview design
- **FR-016**: System MUST display exception status badges with color coding and tooltips matching the existing Vuln Overview design
- **FR-017**: System MUST provide a "Refresh" button to re-query the API with the same search parameters
- **FR-018**: Users MUST have appropriate authentication to access the CrowdStrike lookup page (same permissions as Vuln Management)
- **FR-019**: System MUST log CrowdStrike API queries and results for audit purposes
- **FR-020**: System MUST handle CrowdStrike API rate limits gracefully with appropriate retry logic and user notifications

### Key Entities *(include if feature involves data)*

- **CrowdStrike Vulnerability Query**: Represents a real-time API query with parameters (hostname, days range: 40, status: OPEN). Not persisted, used only for API communication.
- **Vulnerability**: Existing entity, extended to support CrowdStrike-sourced data. Key attributes: vulnerabilityId (CVE), cvssSeverity, vulnerableProductVersions, daysOpen, scanTimestamp, asset (FK).
- **Asset**: Existing entity, may be auto-created if system from CrowdStrike doesn't exist locally. Key attributes: name (hostname), ip, type, owner, lastSeen.
- **CrowdStrike API Response**: Temporary data structure containing vulnerability records from API with fields: hostname, ip, cveId, severity, affectedProduct, daysOpen, detectedDate, status.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Security analysts can look up real-time vulnerability data for any system in under 10 seconds from entering the system name to seeing results
- **SC-002**: The vulnerability lookup page provides the same filtering and sorting capabilities as the existing Vuln Overview page, ensuring consistent user experience
- **SC-003**: 100% of vulnerabilities retrieved from CrowdStrike API can be saved to the local database with all required fields populated correctly
- **SC-004**: System handles CrowdStrike API errors gracefully with clear error messages displayed to users within 3 seconds of error occurrence
- **SC-005**: Users can complete the entire workflow (search → filter → sort → save) without encountering UI inconsistencies or errors in 95% of cases
- **SC-006**: The feature integrates seamlessly with existing vulnerability management workflows, allowing saved CrowdStrike vulnerabilities to be managed with exceptions and historical tracking

## Assumptions

1. **CrowdStrike API Credentials**: The backend already has valid CrowdStrike API credentials (Client ID, Client Secret, Cloud Region) stored in environment variables or configuration, as indicated by the user
2. **API Access**: The application has network access to the CrowdStrike Falcon API endpoints (https://api.crowdstrike.com or relevant cloud-specific endpoint)
3. **FalconPy Library**: The backend can use the FalconPy library (already present in helper tools) or implement direct HTTP calls to the CrowdStrike API
4. **Authentication Permissions**: Users accessing the CrowdStrike lookup feature have the same role-based permissions as the existing Vuln Management section (ADMIN or VULN roles)
5. **System Name Format**: System names entered by users are hostnames as they appear in CrowdStrike Falcon (FQDN or short hostname)
6. **40-Day Window**: The 40-day lookback period is a business requirement and matches the data retention or typical scan frequency in CrowdStrike
7. **OPEN Status**: Only vulnerabilities with status "OPEN" in CrowdStrike are relevant; resolved/closed vulnerabilities are not displayed
8. **Asset Matching**: When saving vulnerabilities, assets are matched by hostname (case-insensitive) or IP address to existing Asset records
9. **Default Asset Values**: When auto-creating assets from CrowdStrike data, default values are: owner="CrowdStrike", type="Endpoint", description="" (can be refined during implementation)
10. **Scan Timestamp Source**: The scanTimestamp for saved vulnerabilities comes from the CrowdStrike detection date or the current timestamp if not available
11. **UI Framework**: The frontend uses the existing Astro/React/Bootstrap 5 stack with the same design patterns as CurrentVulnerabilitiesTable.tsx
12. **API Rate Limits**: CrowdStrike API has rate limits that may require retry logic or throttling for high-volume queries (standard practice for third-party APIs)

## Dependencies

- Existing vulnerability management system (Vulnerability entity, Asset entity, VulnerabilityException logic)
- Current Vuln Overview UI design and components (CurrentVulnerabilitiesTable.tsx as reference)
- CrowdStrike Falcon API access and credentials
- FalconPy library or equivalent HTTP client for API integration
- Existing authentication and authorization system (ADMIN, VULN roles)

## Out of Scope

- Scheduling automated CrowdStrike vulnerability imports (this feature is manual, on-demand lookup only)
- Bulk querying multiple systems at once (single system lookup only in v1)
- Exporting CrowdStrike results to Excel/CSV before saving (export functionality exists in Vuln Overview after saving)
- Modifying the CrowdStrike API configuration or credentials via the UI (assumed to be managed via environment variables or admin configuration)
- Retroactive historical imports of vulnerabilities older than 40 days (40-day window is fixed requirement)
- Integration with other vulnerability data sources beyond CrowdStrike (this feature is CrowdStrike-specific)
