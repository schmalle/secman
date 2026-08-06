# Feature Specification: CrowdStrike Instance ID Lookup

**Feature Branch**: `041-falcon-instance-lookup`
**Created**: 2025-11-03
**Status**: Draft
**Input**: User description: "in the /vulnerabilities/system i want to be able also to enter an instance-id instead of a hostname. Also the query logic for Crowdstrike must then be adapted, as i want to have an online query, not from the database"

## Clarifications

### Session 2025-11-03

- Q: Should the system use case-sensitive or case-insensitive matching when detecting hexadecimal patterns for instance IDs? → A: Case-insensitive (both a-f and A-F accepted as valid hex characters)
- Q: Should instance ID queries use the 15-minute cache (for performance), or should they bypass cache completely to ensure real-time data? → A: Use 15-minute cache for both hostname and instance ID queries (consistent caching)
- Q: When saving instance ID query results, if the hostname already exists in the database but with a different (or null) cloudInstanceId, what should happen? → A: Update the existing asset with the instance ID (merge/enrich existing asset data)
- Q: What placeholder text should be displayed in the input field to guide users on both accepted formats? → A: web-server-01 or i-0048f94221fe110cf
- Q: Should the UI display a timestamp or indicator showing when the vulnerability data was last fetched from the CrowdStrike API? → A: Show "⚡ Live data" badge for fresh queries and "📋 Cached (X min ago)" for cached results

### Critical Clarification (Post-Session)

**IMPORTANT**: Instance IDs refer to **AWS EC2 Instance IDs** (format: `i-` followed by 8 or 17 hex characters, e.g., `i-0048f94221fe110cf`), NOT CrowdStrike Agent IDs. The system must query CrowdStrike Falcon API to find systems by their AWS instance ID metadata field.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Lookup Vulnerabilities by Instance ID (Priority: P1)

Security analysts need to query vulnerabilities for systems using their AWS EC2 Instance ID when the hostname is unknown, ambiguous, or when working with cloud instances that have dynamic hostnames.

**Why this priority**: This is the core capability - enabling AWS instance ID lookups in addition to hostname lookups. Without this, users cannot query systems by their cloud instance identifier, which is often more reliable than hostnames in AWS environments where hostnames change frequently.

**Independent Test**: Can be fully tested by entering a valid AWS instance ID (e.g., "i-0048f94221fe110cf") in the search field and verifying that vulnerabilities are returned from the CrowdStrike API by searching for systems with that AWS instance ID metadata.

**Acceptance Scenarios**:

1. **Given** a user is on the /vulnerabilities/system page, **When** they enter a valid AWS instance ID (format: i-XXXXXXXXX or i-XXXXXXXXXXXXXXXXX), **Then** the system queries the CrowdStrike Falcon API for systems with that instance ID metadata and displays all vulnerabilities
2. **Given** a user enters an AWS instance ID, **When** the query completes successfully, **Then** the results table shows the hostname associated with that instance ID along with all vulnerability details
3. **Given** a user enters an invalid AWS instance ID format, **When** they attempt to search, **Then** the system displays a validation error explaining the correct format (i-XXXXXXXX...)
4. **Given** a user enters an AWS instance ID that doesn't exist in CrowdStrike, **When** the query executes, **Then** the system displays a "System not found" message with the instance ID

---

### User Story 2 - Flexible Input Field with Auto-Detection (Priority: P1)

Users need a single, intelligent input field that accepts both hostnames and AWS instance IDs without requiring them to specify which type they're entering, making the interface more intuitive and reducing user friction.

**Why this priority**: This significantly improves user experience by eliminating the need for radio buttons or dropdown selectors. Users can simply paste their identifier and the system automatically detects the type.

**Independent Test**: Can be tested by entering various formats (hostnames like "web-server-01", AWS instance IDs like "i-0048f94221fe110cf") and verifying the system correctly identifies and processes each type without requiring user input about the identifier type.

**Acceptance Scenarios**:

1. **Given** a user enters a string matching hostname format (alphanumeric with dots/hyphens), **When** they submit the search, **Then** the system queries by hostname using the existing logic
2. **Given** a user enters an AWS instance ID format (i- followed by 8 or 17 hex characters), **When** they submit the search, **Then** the system recognizes it as an AWS instance ID and queries CrowdStrike for systems with that instance ID metadata
3. **Given** a user enters text that matches neither format, **When** they attempt to search, **Then** the system displays a clear error message explaining both accepted formats (hostname or AWS instance ID like i-XXXXXXXX...)
4. **Given** a user switches between entering hostnames and AWS instance IDs, **When** they perform multiple searches, **Then** each query type is correctly detected and executed without any residual state issues

---

### User Story 3 - Online API Query (No Database Dependency) (Priority: P1)

All vulnerability queries must fetch data directly from the CrowdStrike Falcon API (with 15-minute caching), ensuring users always see current vulnerability data without relying on the local database.

**Why this priority**: This ensures data accuracy and freshness. Querying from the database would show outdated information, which could lead to incorrect security assessments and missed vulnerabilities. The 15-minute cache balances freshness with API rate limits.

**Independent Test**: Can be tested by querying a system, verifying that results come from the API (or cache if within 15 minutes), confirming via network logs that no database queries are executed during the vulnerability lookup, and verifying the Refresh button bypasses cache.

**Acceptance Scenarios**:

1. **Given** a user performs a vulnerability query (by hostname or instance ID), **When** the query executes, **Then** the backend queries the CrowdStrike Falcon API (or returns cached results if within 15 minutes) without querying the local database
2. **Given** a user receives query results, **When** the data was fetched less than 1 minute ago, **Then** a "⚡ Live data" badge is displayed indicating fresh data
3. **Given** a user receives query results, **When** the data is from cache (1-15 minutes old), **Then** a "📋 Cached (X min ago)" badge is displayed showing the age
4. **Given** new vulnerabilities were added to CrowdStrike 5 minutes ago and cache is stale, **When** a user queries that system, **Then** the new vulnerabilities appear in the results
5. **Given** a user previously queried a system 10 minutes ago, **When** they query the same system again, **Then** cached results are returned immediately without a new API call and the cache age is displayed
6. **Given** a user has cached results, **When** they click the "Refresh" button, **Then** the cache is bypassed, fresh data is fetched from CrowdStrike Falcon API, and the "⚡ Live data" badge appears
7. **Given** the CrowdStrike API is temporarily unavailable, **When** a user attempts a query, **Then** the system displays an appropriate error message about API connectivity without falling back to database results

---

### User Story 4 - Save Online Results to Database (Priority: P2)

After retrieving vulnerability data from CrowdStrike via online query, users can optionally save those results to the local database for historical tracking, reporting, and offline analysis.

**Why this priority**: While online queries are primary, users still need the ability to persist results for compliance reporting, trend analysis, and historical comparisons. This is secondary to the online query capability.

**Independent Test**: Can be tested by performing an online query, clicking "Save to Database", verifying the data is persisted, and confirming it appears in the regular vulnerability management views without affecting the online query functionality.

**Acceptance Scenarios**:

1. **Given** a user has successfully retrieved vulnerabilities via online query (hostname or instance ID), **When** they click "Save to Database", **Then** the vulnerabilities are saved to the local database with the current timestamp
2. **Given** a user saves vulnerabilities for a system that already exists in the database, **When** the save operation completes, **Then** existing vulnerabilities are updated and new vulnerabilities are added without creating duplicates
3. **Given** a user queries by instance ID and saves the results, **When** the asset is created or updated in the database, **Then** the instance ID is stored in the asset's cloudInstanceId field for future reference
4. **Given** a user queries by instance ID and the hostname already exists in the database (with null or different cloudInstanceId), **When** they save the results, **Then** the existing asset is enriched with the instance ID and no duplicate asset is created
5. **Given** a user attempts to save results but the database is unavailable, **When** the save operation fails, **Then** the online query results remain visible and the system displays a clear error message about the save failure

---

### Edge Cases

- What happens when a user enters a string that could be ambiguous? *System uses pattern matching: if starts with "i-" followed by 8 or 17 hex characters (case-insensitive), treat as AWS instance ID; otherwise treat as hostname*
- How does the system handle CrowdStrike API rate limits during online queries? *Display user-friendly rate limit error with retry-after time, following existing error handling patterns*
- What happens when an AWS instance ID belongs to a system with a null or empty hostname in CrowdStrike? *Display instance ID as the system identifier (e.g., "i-0048f94221fe110cf") and show a placeholder for missing hostname*
- How does the system handle very large vulnerability responses (1000+ CVEs) from online API queries? *Apply same pagination/limiting logic as existing queries (default 100, max 1000 per request)*
- What happens when a user saves results for an AWS instance ID but the system doesn't exist in the local database? *Auto-create asset using AWS instance ID as cloudInstanceId and hostname (if available from CrowdStrike) as name, following Feature 030 patterns*
- What happens when a user saves AWS instance ID results but an asset with the same hostname already exists with a different cloudInstanceId? *Update/enrich the existing asset with the new AWS instance ID; the most recently saved instance ID takes precedence (asset merge pattern)*
- How does the system behave when CrowdStrike returns partial data (some fields missing)? *Gracefully handle null fields, display "-" for missing data, and include all available information in the results table*
- What happens when a user enters an older AWS instance ID format (i-XXXXXXXX with 8 hex chars)? *System supports both old (8 chars) and new (17 chars) AWS instance ID formats*
- What happens if CrowdStrike has multiple systems with the same AWS instance ID? *Display all matching systems with their respective hostnames and vulnerabilities*

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST accept both hostnames and AWS EC2 Instance IDs in the search input field on /vulnerabilities/system page
- **FR-002**: System MUST automatically detect whether the input is a hostname or AWS instance ID based on format patterns (starts with "i-" followed by 8 or 17 hex characters = AWS instance ID, otherwise = hostname)
- **FR-003**: System MUST validate AWS instance ID format (i-[0-9a-fA-F]{8,17}, case-insensitive) and display clear error messages for invalid formats explaining the expected format (i-XXXXXXXX...)
- **FR-004**: System MUST query the CrowdStrike Falcon API directly for all vulnerability lookups (both hostname and AWS instance ID queries), with results cached for 15 minutes to optimize performance and prevent excessive API calls
- **FR-005**: System MUST NOT query the local database for vulnerability data during initial lookup operations
- **FR-006**: Backend service MUST support querying CrowdStrike by AWS instance ID metadata field to find systems matching that instance ID and retrieve their vulnerabilities
- **FR-007**: System MUST display the hostname associated with an AWS instance ID in query results (if available from CrowdStrike metadata)
- **FR-008**: System MUST maintain existing filtering capabilities (severity, exception status, product) for results retrieved via instance ID
- **FR-009**: System MUST maintain existing sorting capabilities for results retrieved via instance ID
- **FR-010**: System MUST provide "Save to Database" functionality for vulnerabilities retrieved via online queries (both hostname and instance ID)
- **FR-011**: When saving results from AWS instance ID query, system MUST store the AWS instance ID in the asset's cloudInstanceId field; if an asset with the same hostname already exists, the system MUST update/enrich that asset with the AWS instance ID rather than creating a duplicate
- **FR-012**: System MUST update UI labels to "System Hostname or Instance ID" and use placeholder text "e.g., web-server-01 or i-0048f94221fe110cf" to show both accepted formats
- **FR-013**: System MUST handle CrowdStrike API errors gracefully with user-friendly error messages (not found, rate limit, network errors)
- **FR-014**: System MUST preserve existing security controls (ADMIN and VULN role requirements) for CrowdStrike queries
- **FR-015**: System MUST provide a "Refresh" button that bypasses the cache and fetches fresh data from CrowdStrike Falcon API immediately
- **FR-016**: System MUST display a data freshness indicator showing "⚡ Live data" for queries less than 1 minute old and "📋 Cached (X min ago)" for cached results to inform users about data age
- **FR-017**: System MUST support both legacy AWS instance ID format (i- + 8 hex chars) and current format (i- + 17 hex chars)

### Key Entities *(include if feature involves data)*

- **AWS EC2 Instance ID**: A unique identifier assigned by AWS to each EC2 instance, format: "i-" followed by 8 hexadecimal characters (legacy) or 17 hexadecimal characters (current), case-insensitive. Example: i-0048f94221fe110cf
- **Asset (Enhanced)**: Existing entity with cloudInstanceId field used to store AWS EC2 Instance IDs when assets are created or updated from instance ID queries
- **Query Input**: A flexible text input that accepts either hostname (alphanumeric with dots/hyphens, max 255 chars) or AWS instance ID (i-[0-9a-fA-F]{8,17})
- **Online Query Result**: Real-time vulnerability data fetched directly from CrowdStrike Falcon API by searching for systems with matching AWS instance ID metadata, not persisted until user explicitly saves
- **CrowdStrike System Metadata**: Systems in CrowdStrike Falcon contain AWS instance ID as a metadata field that can be queried to find matching systems

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can successfully query vulnerabilities using AWS EC2 Instance IDs with a success rate matching hostname queries (>95%)
- **SC-002**: All vulnerability queries return data from CrowdStrike Falcon API (via 15-minute cache) with no database fallback logic
- **SC-003**: Users see vulnerability results within 3 seconds for typical queries (both hostname and AWS instance ID)
- **SC-004**: System correctly auto-detects input type (hostname vs AWS instance ID) with 100% accuracy based on format patterns (i- prefix detection)
- **SC-005**: Users can save online query results to database with same reliability as existing save functionality (>98% success rate)
- **SC-006**: Error messages for invalid AWS instance IDs clearly explain the expected format (i-XXXXXXXX...), reducing user confusion and support requests
- **SC-007**: AWS instance ID queries support all existing features (filtering, sorting, exception detection, save functionality) without degradation
- **SC-008**: Users can immediately distinguish between live and cached data through visual indicators (badges), improving data freshness awareness

## Assumptions

- CrowdStrike Falcon API supports querying systems by AWS instance ID metadata field
- Systems monitored by CrowdStrike Falcon have AWS instance ID populated in their metadata when running on AWS EC2
- The existing FalconConfig credentials have appropriate API permissions to query by system metadata fields
- AWS instance IDs follow the standard format (i- + 8 or 17 hex characters) consistently
- The existing caching strategy (15-minute TTL) applies consistently to both hostname and AWS instance ID queries to prevent excessive API calls while maintaining acceptable data freshness
- Users have access to AWS instance IDs through the AWS console, CloudWatch, or other AWS management tools
- The cloudInstanceId field in the Asset entity is appropriate for storing AWS EC2 Instance IDs
- Network connectivity to CrowdStrike Falcon API is sufficiently reliable for queries with 15-minute caching

## Dependencies

- Existing CrowdStrike API client in shared module must support querying by system metadata fields (specifically AWS instance ID metadata) - may require enhancement
- Feature 030 (CrowdStrike Asset Auto-Creation) patterns for asset creation when saving AWS instance ID query results
- Feature 023 (CrowdStrike Query Service) as the foundation for online API queries
- Access to CrowdStrike API documentation for system metadata query endpoints and AWS instance ID field names
- CrowdStrike Falcon sensors must be configured to collect and report AWS instance ID metadata for EC2 instances

## Out of Scope

- Bulk AWS instance ID lookups (querying multiple instance IDs simultaneously)
- Historical query logs or audit trail for instance ID searches
- Database-first query mode with fallback to API (all queries are API-first with 15-minute cache)
- Conversion or mapping between AWS instance IDs and hostnames in the database
- Auto-refresh or polling for real-time vulnerability updates (users must click Refresh button)
- Support for other cloud provider instance IDs (Azure VM ID, Google Cloud instance ID) - only AWS EC2 instance IDs
- Support for other AWS resource identifiers (ECS task IDs, Lambda function ARNs, etc.)
- Validation of whether an AWS instance ID actually exists in AWS (only validates format)
- Mobile-specific UI optimizations for instance ID input
- Import/export of instance ID query results to Excel or CSV
- Automatic synchronization of AWS instance ID metadata from AWS to CrowdStrike (assumes CrowdStrike Falcon agent already collects this)
