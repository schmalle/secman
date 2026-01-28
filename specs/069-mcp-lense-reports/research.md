# Research: MCP Lense Reports

**Feature Branch**: `069-mcp-lense-reports`
**Date**: 2026-01-27

## Technical Decisions

### Decision 1: MCP Tool Architecture Pattern

**Decision**: Follow existing McpTool interface pattern with service injection

**Rationale**: The codebase has a mature MCP infrastructure with 40+ tools following a consistent pattern:
- `McpTool` interface with `name`, `description`, `operation`, `inputSchema`, and `execute()` method
- Singleton tools injected into `McpToolRegistry`
- Permission-based authorization via `isToolAuthorized()` in registry
- `McpExecutionContext` provides access control with `getFilterableAssetIds()` and `canAccessAsset()`

**Alternatives Considered**:
- Direct controller endpoints → Rejected: Would bypass MCP protocol and miss delegation features
- Generic report tool with type parameter → Rejected: Each report has distinct schema and access control needs

### Decision 2: Access Control Implementation

**Decision**: Use `context.getFilterableAssetIds()` for row-level filtering, consistent with existing tools

**Rationale**:
- Returns `null` for ADMIN or non-delegated API keys (no filtering)
- Returns `Set<Long>` for delegated users (filter to accessible assets)
- Already integrated with workgroup, domain mapping, and AWS account access rules
- Used consistently in GetAssetsTool, GetVulnerabilitiesTool, etc.

**Alternatives Considered**:
- Per-tool access control logic → Rejected: Would duplicate existing AssetFilterService logic

### Decision 3: Backend Service Architecture

**Decision**: Create dedicated `ReportService` for risk assessment summary and mitigation status; reuse existing `VulnerabilityStatisticsService` and `ExceptionRequestStatisticsService`

**Rationale**:
- `VulnerabilityStatisticsService` already provides: severity distribution, most common vulns, top products, top assets, temporal trends
- `ExceptionRequestStatisticsService` provides: approval rates, request counts by status, top requesters/CVEs
- Risk assessment summary and mitigation status need new aggregation logic
- New service keeps report-specific logic separate from existing domain services

**Alternatives Considered**:
- Add methods to RiskAssessmentService → Rejected: Would bloat existing service with reporting concerns
- No new service, aggregate in MCP tool → Rejected: Would make tools too complex and harder to test

### Decision 4: Permission Requirements

**Decision**: Use `ASSESSMENTS_READ` for risk-related reports, `VULNERABILITIES_READ` for vulnerability statistics and exceptions

**Rationale**:
- Aligns with existing permission structure in `McpPermission` enum
- Risk assessments are part of assessment domain
- Exception requests relate to vulnerability workflow
- Consistent with how other MCP tools map to permissions

**Alternatives Considered**:
- New REPORTS_READ permission → Rejected: Adds unnecessary complexity
- Single permission for all reports → Rejected: Violates principle of least privilege

### Decision 5: Top 50 Servers Implementation

**Decision**: Extend `VulnerabilityStatisticsService` with new method `getTopServersByVulnerabilities(limit: Int, domain: String?)`

**Rationale**:
- Existing service already has access control, domain filtering, and asset queries
- `getTopAssetsByVulnerabilities()` returns top 10 - new method generalizes with limit parameter
- Can reuse existing patterns for severity breakdown

**Alternatives Considered**:
- Add to separate ReportService → Rejected: Vulnerability statistics belong together
- Modify existing method with optional limit → Rejected: Would break existing API contract

## Existing Infrastructure Analysis

### Available Services

| Service | Methods Available | Access Control |
|---------|-------------------|----------------|
| `VulnerabilityStatisticsService` | getSeverityDistribution, getMostCommonVulnerabilities, getMostVulnerableProducts, getTopAssetsByVulnerabilities, getVulnerabilitiesByAssetType, getTemporalTrends | Authentication-based + domain filtering |
| `ExceptionRequestStatisticsService` | getApprovalRate, getAverageApprovalTime, getRequestsByStatus, getTopRequesters, getTopCVEs | Date range filtering |
| `RiskAssessmentService` | getAssessments, getAssessmentsByStatus, searchAssessments | No built-in filtering |
| `RiskRepository` | findByStatus, findByRiskLevel, findOverdueRisks, findHighPriorityRisks | Direct queries |

### Existing DTOs

| DTO | Purpose |
|-----|---------|
| `SeverityDistributionDto` | Vulnerability counts by severity with percentages |
| `MostCommonVulnerabilityDto` | CVE ID, severity, occurrence count, affected asset count |
| `MostVulnerableProductDto` | Product, vulnerability count, critical/high counts |
| `TopAssetByVulnerabilitiesDto` | Asset info with severity breakdown |
| `ExceptionStatisticsDto` | Total requests, approval rate, avg time, status counts |

### Risk Domain Model

| Entity | Key Fields | Notes |
|--------|------------|-------|
| `Risk` | id, name, description, likelihood (1-5), impact (1-5), riskLevel (1-4), status, severity, deadline, owner, asset | riskLevel auto-computed from likelihood × impact |
| `RiskAssessment` | id, startDate, endDate, status, assessmentBasisType (DEMAND/ASSET), assessor, requestor | Links to assets via basis or demand chain |

### Risk Level Mapping

| Level | Name | Calculation |
|-------|------|-------------|
| 1 | Low | likelihood × impact = 1-4 |
| 2 | Medium | likelihood × impact = 5-9 |
| 3 | High | likelihood × impact = 10-15 |
| 4 | Critical | likelihood × impact = 16-25 |

## New Components Required

### New DTOs

1. **RiskAssessmentSummaryDto** - Aggregated summary for risk assessment report
2. **RiskMitigationStatusDto** - Open risks with mitigation tracking
3. **TopServerByVulnerabilitiesDto** - Extended asset info with 50-server support

### New Service Methods

1. `ReportService.getRiskAssessmentSummary(accessibleAssetIds: Set<Long>?)`
2. `ReportService.getRiskMitigationStatus(accessibleAssetIds: Set<Long>?, statusFilter: String?)`
3. `VulnerabilityStatisticsService.getTopServersByVulnerabilities(limit: Int, domain: String?)`

### New MCP Tools

1. `GetRiskAssessmentSummaryTool` - Calls ReportService
2. `GetRiskMitigationStatusTool` - Calls ReportService with optional status filter
3. `GetVulnerabilityStatisticsTool` - Aggregates existing VulnerabilityStatisticsService methods
4. `GetExceptionStatisticsTool` - Wraps ExceptionRequestStatisticsService

## Performance Considerations

- **5 second latency target**: Use efficient JPA queries with proper indexes
- **Risk assessment counts**: GROUP BY status queries are efficient
- **Asset coverage**: Single count query with left join
- **Top 50 servers**: Limit query with ORDER BY, existing index on vulnerability count
- **Pagination**: Not needed for aggregate reports (fixed-size responses)
