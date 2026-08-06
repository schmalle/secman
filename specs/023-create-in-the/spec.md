# Feature Specification: CrowdStrike CLI - Vulnerability Query Tool

**Feature Branch**: `023-create-in-the`  
**Created**: October 16, 2025  
**Status**: Draft  
**Input**: User description: "create in the folder src/backendng/cli a micronaut based command line application written in kotlin build using gradle being able to access the crowdstrike API and the vulnerabilities"

## Clarifications

### Session 2025-10-16

- Q: CLI Project Location - Should the CLI be created inside `src/backendng/cli/` (as per original input) or as `src/cli/` (sibling to frontend and backendng)? → A: Option B - Create as `src/cli/` at the same level as frontend and backendng, treating CLI as a first-class application component
- Q: Gradle Build Integration - Should CLI be an independent Gradle project, a multi-project subproject, or extend backendng's build? → A: Option B - Create multi-project Gradle build with shared module at `src/shared/` containing CrowdStrike API client code. Both `backendng` and `cli` will depend on this shared module to maximize code reuse while maintaining separation of concerns
- Q: API Response Data Completeness - What level of detail should be displayed by default when showing vulnerabilities? → A: Option B - Display essential fields only (CVE ID, severity, affected software, description summary < 100 chars) for concise output, with full details available via export
- Q: Code Reuse Architecture - How should CLI reuse existing CrowdStrike API code from backendng? → A: Option B - Multi-project Gradle build with shared module (`src/shared/`) containing CrowdStrike API client, DTOs, and authentication logic extracted from backendng. Both projects depend on shared module. Database handling code remains in backendng (not needed in CLI)
- Q: Backend Service Dependency - Should CLI work standalone or require backendng service running? → A: Option A - CLI requires backendng service running for database operations (saving vulnerabilities to Asset/Vulnerability tables). CLI can query CrowdStrike API directly via shared module but must call backendng REST API for persistence

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Query Vulnerabilities by Host (Priority: P1)

Security administrators need to quickly check all known vulnerabilities for a specific host by querying the CrowdStrike API from the command line.

**Why this priority**: This is the foundational capability - being able to retrieve vulnerability data for a single host. Without this, no other functionality is possible.

**Independent Test**: Can be fully tested by running the CLI with a hostname parameter and verifying that vulnerability data is retrieved and displayed, delivering immediate value for single-host security assessments.

**Acceptance Scenarios**:

1. **Given** the CLI is installed and CrowdStrike API credentials are configured, **When** an administrator runs the command with a valid hostname, **Then** all vulnerabilities for that host are retrieved and displayed
2. **Given** the CLI is running, **When** an administrator provides an invalid hostname, **Then** an appropriate error message is displayed
3. **Given** the CrowdStrike API is unavailable, **When** an administrator runs a query, **Then** a clear error message indicates connectivity issues

---

### User Story 2 - Authenticate with CrowdStrike API (Priority: P1)

Security administrators need to securely authenticate with CrowdStrike API using their API credentials to access vulnerability data.

**Why this priority**: Authentication is a prerequisite for all API interactions. This must work before any vulnerability queries can be performed.

**Independent Test**: Can be tested by configuring credentials and verifying successful authentication, or testing with invalid credentials to confirm proper error handling.

**Acceptance Scenarios**:

1. **Given** valid API credentials are provided, **When** the CLI attempts to authenticate, **Then** an access token is successfully obtained
2. **Given** invalid API credentials are provided, **When** the CLI attempts to authenticate, **Then** a clear authentication failure message is displayed
3. **Given** credentials are stored in configuration, **When** the CLI starts, **Then** credentials are loaded securely without exposing them in logs

---

### User Story 3 - Filter Vulnerabilities by Severity (Priority: P2)

Security administrators need to filter vulnerability results by severity level (critical, high, medium, low) to prioritize remediation efforts.

**Why this priority**: While retrieving all vulnerabilities is essential (P1), filtering by severity helps administrators focus on the most critical issues first, increasing operational efficiency.

**Independent Test**: Can be tested by querying a host with known vulnerabilities and verifying that only vulnerabilities matching the specified severity filter are displayed.

**Acceptance Scenarios**:

1. **Given** a host has vulnerabilities of multiple severity levels, **When** an administrator queries with a severity filter, **Then** only vulnerabilities matching that severity are displayed
2. **Given** a host has no critical vulnerabilities, **When** an administrator queries with critical severity filter, **Then** an appropriate message indicates no critical vulnerabilities found
3. **Given** no severity filter is specified, **When** an administrator queries, **Then** all vulnerabilities are displayed with their severity levels

---

### User Story 4 - Export Results to File (Priority: P2)

Security administrators need to export vulnerability query results to a file (JSON, CSV) for further analysis, reporting, or integration with other tools.

**Why this priority**: While displaying results on screen is useful for quick checks, exporting enables integration with reporting workflows and long-term tracking.

**Independent Test**: Can be tested by running a query with export flag and verifying that a properly formatted output file is created with the correct data.

**Acceptance Scenarios**:

1. **Given** a successful vulnerability query, **When** an administrator specifies an output file path, **Then** results are written to the specified file in the requested format
2. **Given** an output file already exists, **When** an administrator runs a query with the same output path, **Then** the CLI prompts the user for confirmation before overwriting
3. **Given** the output directory doesn't exist, **When** an administrator specifies an output path, **Then** the directory is created automatically or an error is displayed

---

### User Story 5 - Query Multiple Hosts (Priority: P3)

Security administrators need to query vulnerabilities for multiple hosts in a single command to assess vulnerability exposure across infrastructure.

**Why this priority**: This is an efficiency enhancement - administrators can already query hosts one at a time with P1 functionality, but batch queries save time for larger assessments.

**Independent Test**: Can be tested by providing a list of hostnames and verifying that vulnerabilities are retrieved for all hosts and results are clearly separated by host.

**Acceptance Scenarios**:

1. **Given** a list of valid hostnames, **When** an administrator runs a bulk query, **Then** vulnerabilities for each host are retrieved and displayed with clear host identification
2. **Given** some hostnames are invalid, **When** an administrator runs a bulk query, **Then** valid hosts are processed and errors are reported for invalid hosts without stopping execution
3. **Given** a file containing hostnames, **When** an administrator provides the file path, **Then** all hosts in the file are queried

---

### Edge Cases

- What happens when the API rate limit is exceeded?
- How does the system handle network timeouts during long-running queries?
- What happens when a host has no vulnerabilities?
- How does the CLI behave when credentials expire mid-query?
- What happens if the output file path is not writable?
- How does the system handle hosts that don't exist in CrowdStrike?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST authenticate with CrowdStrike API using client ID and client secret
- **FR-002**: System MUST support querying vulnerabilities for a single host by hostname or host ID
- **FR-003**: System MUST display vulnerability details including CVE ID, severity, affected software, and description summary (truncated to ~100 characters for console readability)
- **FR-004**: System MUST provide clear error messages for authentication failures, network errors, and invalid inputs
- **FR-005**: System MUST allow filtering vulnerabilities by severity level (critical, high, medium, low)
- **FR-006**: System MUST support exporting results to JSON format
- **FR-007**: System MUST support exporting results to CSV format
- **FR-008**: System MUST handle API rate limiting gracefully with appropriate retry logic
- **FR-009**: System MUST support querying multiple hosts in a single command execution
- **FR-010**: System MUST validate all user inputs (hostnames, file paths, credentials) before processing
- **FR-011**: System MUST provide a help command that displays usage instructions and available options
- **FR-012**: System MUST read API credentials from a configuration file (e.g., ~/.secman/crowdstrike.conf) with appropriate file permissions for security
- **FR-013**: System MUST log API request metadata (timestamps, HTTP status codes, error messages) for debugging purposes but MUST NOT log sensitive vulnerability data or API response payloads
- **FR-014**: System MUST support reading hostnames from a file for bulk queries
- **FR-015**: System MUST prompt for confirmation before overwriting existing export files to prevent accidental data loss

### Key Entities

- **Vulnerability**: Represents a security vulnerability with CVE identifier, severity rating (critical/high/medium/low), affected software/packages, and description summary. Full details (discovery date, remediation guidance, CVSS scores) available in export formats
- **Host**: Represents a system being monitored with hostname, host ID in CrowdStrike, operating system details, and associated vulnerabilities
- **API Credentials**: Client ID and client secret for CrowdStrike API authentication with token expiration handling
- **Query Result**: Container for vulnerability data retrieved from API with timestamp, host information, vulnerability list, and metadata (query parameters, execution time)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can retrieve vulnerability data for a host in under 5 seconds (excluding network latency)
- **SC-002**: System successfully handles authentication and token refresh without user intervention
- **SC-003**: 95% of valid queries return results without errors on first attempt
- **SC-004**: Exported files are readable by standard tools (JSON parsers, spreadsheet applications for CSV)
- **SC-005**: Error messages clearly indicate the problem and suggest corrective action in 100% of failure cases
- **SC-006**: System handles bulk queries for up to 100 hosts without memory issues or crashes
- **SC-007**: Help documentation enables new users to perform basic queries within 5 minutes without external documentation

## Assumptions *(optional)*

- CrowdStrike API documentation is available and accessible
- API credentials will be provided by the organization's CrowdStrike administrator
- The CLI will run on standard Linux/Unix environments where the application is deployed
- Network connectivity to CrowdStrike API endpoints is available from execution environment
- Standard JSON and CSV formats are sufficient for export functionality
- API rate limits are documented and can be handled with exponential backoff retry logic
- Configuration file location follows standard conventions (~/.secman/crowdstrike.conf or similar)
- Users can manage file permissions on the configuration file to restrict access (chmod 600)
- Interactive prompts (for file overwrite confirmation) are acceptable for the CLI workflow
- Log files will be stored in a location with appropriate access controls
- CLI application will be located at `src/cli/` as a sibling to `src/frontend/` and `src/backendng/`, following the established project structure pattern
- Multi-project Gradle build with shared module at `src/shared/` containing CrowdStrike API client code reused from backendng implementation
- Both `backendng` and `cli` projects depend on `src/shared/` module for CrowdStrike API integration (authentication, vulnerability queries, DTOs)
- Database handling code remains in backendng only - CLI calls backendng REST API for persistence operations
- CLI can query CrowdStrike API directly via shared module but requires backendng service running for save operations
- Console output will display essential vulnerability information only (CVE ID, severity, affected software, short description) for readability; full details accessible via JSON/CSV export

## Dependencies *(optional)*

- CrowdStrike API availability and documented endpoints
- Valid CrowdStrike API subscription with vulnerability data access
- Network access to CrowdStrike API endpoints (firewalls, proxies configured)
- Java runtime environment (JDK 21) for executing the CLI application
- Shared module (`src/shared/`) with CrowdStrike API client code
- Backendng service running (for database persistence operations)
- File system permissions to read configuration files and write export files
- User's ability to set up and maintain configuration file with credentials

## Security Considerations *(optional)*

- API credentials must not be logged or displayed in output
- Configuration file containing credentials must be secured with restrictive file permissions (recommended: chmod 600)
- API tokens should be handled securely in memory and not written to disk
- Network communication with CrowdStrike API must use HTTPS
- Exported files containing vulnerability data should have restricted file permissions by default
- Rate limiting errors should not expose API endpoint details that could aid attackers
- Log files must not contain sensitive vulnerability data or API response payloads to prevent information disclosure
- Configuration file location should follow principle of least privilege (user-specific, not system-wide)
- CLI should validate configuration file permissions on startup and warn if overly permissive

## Out of Scope *(optional)*

- Automated remediation of vulnerabilities
- Integration with ticket/incident management systems
- Real-time monitoring or scheduled vulnerability scans
- User interface (GUI) - this is CLI only
- Historical trend analysis of vulnerability data
- Custom vulnerability scoring or prioritization beyond CrowdStrike severity
- Multi-tenant support or role-based access control
- Integration with other security tools beyond CrowdStrike
