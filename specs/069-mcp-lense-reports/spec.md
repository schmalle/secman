# Feature Specification: MCP Lense Reports

**Feature Branch**: `069-mcp-lense-reports`
**Created**: 2026-01-27
**Status**: Draft
**Input**: User description: "make all reports from the Lense UI available via MCP and document it accordingly"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Retrieve Risk Assessment Summary via MCP (Priority: P1)

An AI assistant user wants to query the risk assessment summary report through MCP to understand the current state of security assessments across the organization without accessing the web UI.

**Why this priority**: This is the core report providing an overview of all risk assessments, their status, and high-priority risks. It's the primary dashboard view in the Lense UI and provides the most comprehensive picture of organizational security posture.

**Independent Test**: Can be fully tested by calling the `get_risk_assessment_summary` MCP tool and verifying it returns assessment counts, risk breakdowns, asset coverage metrics, and high-priority risk lists.

**Acceptance Scenarios**:

1. **Given** an authenticated MCP client with appropriate permissions, **When** calling `get_risk_assessment_summary`, **Then** the system returns assessment summary with total count and status breakdown
2. **Given** an authenticated MCP client, **When** calling `get_risk_assessment_summary`, **Then** the system returns risk summary with total count, status breakdown, and risk level breakdown (Low, Medium, High, Very High)
3. **Given** an authenticated MCP client, **When** calling `get_risk_assessment_summary`, **Then** the system returns asset coverage metrics (total assets, assets with assessments, coverage percentage)
4. **Given** an authenticated MCP client, **When** calling `get_risk_assessment_summary`, **Then** the system returns the 10 most recent assessments with id, asset name, status, assessor, and dates
5. **Given** an authenticated MCP client, **When** calling `get_risk_assessment_summary`, **Then** the system returns high-priority risks list with id, name, asset, risk level, status, owner, severity, and deadline
6. **Given** a delegated user context, **When** calling `get_risk_assessment_summary`, **Then** the results are filtered to only include assets accessible to that user

---

### User Story 2 - Retrieve Risk Mitigation Status via MCP (Priority: P1)

An AI assistant user wants to query the risk mitigation status report to track open risks, identify overdue items, and monitor mitigation progress without accessing the web UI.

**Why this priority**: This report is equally critical for operational security management. It provides actionable intelligence on open risks, overdue items requiring immediate attention, and unassigned risks needing ownership.

**Independent Test**: Can be fully tested by calling the `get_risk_mitigation_status` MCP tool and verifying it returns summary statistics and a detailed list of risks with their mitigation status.

**Acceptance Scenarios**:

1. **Given** an authenticated MCP client with appropriate permissions, **When** calling `get_risk_mitigation_status`, **Then** the system returns summary with total open risks, overdue risks, and unassigned risks counts
2. **Given** an authenticated MCP client, **When** calling `get_risk_mitigation_status`, **Then** the system returns detailed risk list including id, name, description, asset name, risk level, status, owner, severity, and deadline
3. **Given** an authenticated MCP client, **When** calling `get_risk_mitigation_status`, **Then** each risk in the list includes likelihood and impact scores
4. **Given** an authenticated MCP client, **When** a risk has a deadline in the past and is not resolved, **Then** that risk is marked as overdue in the response
5. **Given** a delegated user context, **When** calling `get_risk_mitigation_status`, **Then** the results are filtered to only include assets accessible to that user
6. **Given** an authenticated MCP client, **When** calling `get_risk_mitigation_status` with an optional status filter, **Then** only risks matching that status are returned

---

### User Story 3 - Retrieve Vulnerability Statistics via MCP (Priority: P2)

An AI assistant user wants to query vulnerability statistics from the Lense dashboard to understand vulnerability distribution, trends, and top affected assets without accessing the web UI.

**Why this priority**: The vulnerability statistics are already implemented in the backend with multiple endpoints. Exposing them via MCP provides valuable security intelligence for AI-assisted analysis.

**Independent Test**: Can be fully tested by calling the `get_vulnerability_statistics` MCP tool and verifying it returns severity distribution, most common vulnerabilities, top 50 servers by vulnerability count, and temporal trends.

**Acceptance Scenarios**:

1. **Given** an authenticated MCP client with appropriate permissions, **When** calling `get_vulnerability_statistics`, **Then** the system returns severity distribution (Critical, High, Medium, Low counts)
2. **Given** an authenticated MCP client, **When** calling `get_vulnerability_statistics`, **Then** the system returns the top 10 most common vulnerabilities
3. **Given** an authenticated MCP client, **When** calling `get_vulnerability_statistics`, **Then** the system returns the top 10 most vulnerable products
4. **Given** an authenticated MCP client, **When** calling `get_vulnerability_statistics`, **Then** the system returns the top assets by vulnerability count
5. **Given** an authenticated MCP client, **When** calling `get_vulnerability_statistics`, **Then** the system returns vulnerability counts by asset type
6. **Given** an authenticated MCP client, **When** calling `get_vulnerability_statistics`, **Then** the system returns the top 50 servers with the most vulnerabilities, including server name, vulnerability count, and critical/high counts
7. **Given** an authenticated MCP client with an optional domain filter, **When** calling `get_vulnerability_statistics` with a domain parameter, **Then** results are filtered to that domain
8. **Given** a delegated user context, **When** calling `get_vulnerability_statistics`, **Then** the results are filtered based on workgroup/domain access

---

### User Story 4 - Retrieve Exception Approval Statistics via MCP (Priority: P3)

An AI assistant user wants to query exception approval statistics to understand the state of vulnerability exception requests without accessing the web UI.

**Why this priority**: Exception statistics provide important compliance and governance insights. While less frequently accessed than vulnerability data, they complete the reporting picture for security management workflows.

**Independent Test**: Can be fully tested by calling the `get_exception_statistics` MCP tool and verifying it returns pending, approved, and rejected counts.

**Acceptance Scenarios**:

1. **Given** an authenticated MCP client with appropriate permissions, **When** calling `get_exception_statistics`, **Then** the system returns summary counts (pending, approved, rejected)
2. **Given** an authenticated MCP client with an optional date range, **When** calling `get_exception_statistics` with date parameters, **Then** statistics are calculated for that date range
3. **Given** a delegated user context, **When** calling `get_exception_statistics`, **Then** the results reflect only exceptions related to assets accessible to that user

---

### Edge Cases

- What happens when no risk assessments exist? The tools return empty lists with zero counts.
- What happens when a user has no accessible assets? The tools return empty results rather than an error.
- How does the system handle domain filters that don't exist? The tools return empty results rather than an error.
- What happens when date range filters are invalid (end before start)? The tools return an appropriate error message.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide an MCP tool `get_risk_assessment_summary` that returns the complete risk assessment summary report data
- **FR-002**: System MUST provide an MCP tool `get_risk_mitigation_status` that returns the risk mitigation status report data
- **FR-003**: System MUST provide an MCP tool `get_vulnerability_statistics` that returns aggregated vulnerability statistics from the Lense dashboard
- **FR-004**: System MUST provide an MCP tool `get_exception_statistics` that returns vulnerability exception request statistics
- **FR-005**: All report MCP tools MUST respect user delegation and filter results based on the delegated user's accessible assets
- **FR-006**: All report MCP tools MUST be classified as READ operations and require the ASSESSMENTS_READ or VULNERABILITIES_READ permission as appropriate
- **FR-007**: The `get_risk_mitigation_status` tool MUST support an optional status filter parameter
- **FR-008**: The `get_vulnerability_statistics` tool MUST support an optional domain filter parameter
- **FR-009**: The `get_exception_statistics` tool MUST support optional start_date and end_date filter parameters
- **FR-010**: System MUST implement backend REST endpoints for `/api/reports/risk-assessment-summary` and `/api/reports/risk-mitigation-status` (currently missing from backend)
- **FR-011**: MCP documentation MUST be updated to include all new report tools with usage examples
- **FR-012**: The `get_vulnerability_statistics` tool MUST return the top 50 servers with the most vulnerabilities, including server name, total vulnerability count, and breakdown by critical/high severity

### Key Entities

- **ReportSummary**: Aggregated risk assessment data including assessment summary, risk summary, asset coverage, recent assessments, and high-priority risks
- **MitigationReport**: Summary statistics (open, overdue, unassigned) and detailed risk list with mitigation status
- **VulnerabilityStatistics**: Aggregated statistics including severity distribution, most common vulnerabilities, top products, top 50 servers by vulnerability count (with critical/high breakdown), and asset type breakdown
- **ExceptionStatistics**: Summary counts of pending, approved, and rejected exception requests with optional date-range filtering

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All four report types (risk assessment summary, risk mitigation status, vulnerability statistics, exception statistics) are accessible via MCP tools
- **SC-002**: MCP report tools return data consistent with the corresponding Lense UI reports within 5 seconds
- **SC-003**: Delegated users see only data they are authorized to access, matching their web UI permissions
- **SC-004**: MCP documentation includes complete reference for all report tools with input parameters and output format descriptions
- **SC-005**: All report tools can be discovered and invoked successfully through both the Node.js MCP bridge and the direct Streamable HTTP transport

## Clarifications

### Session 2026-01-27

- Q: How many recent assessments should `get_risk_assessment_summary` return? → A: 10 recent assessments
- Q: What is the acceptable latency target for report tools? → A: 5 seconds
- Addition: `get_vulnerability_statistics` must return top 50 servers with most vulnerabilities (per user request)

## Assumptions

- The backend services for risk assessment and risk data already exist (`RiskAssessmentService`, `Risk` domain model)
- The `VulnerabilityStatisticsService` already provides the required aggregation methods
- The `ExceptionRequestStatisticsService` provides exception statistics
- The existing MCP tool pattern (interface implementation, registry registration) will be followed
- ADMIN users will continue to have unrestricted access to all report data
