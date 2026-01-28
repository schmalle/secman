# Implementation Plan: MCP Lense Reports

**Branch**: `069-mcp-lense-reports` | **Date**: 2026-01-27 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/069-mcp-lense-reports/spec.md`

## Summary

Expose all Lense UI reports via MCP (Model Context Protocol) by creating 4 new MCP tools:
1. `get_risk_assessment_summary` - Risk assessment overview with metrics and high-priority risks
2. `get_risk_mitigation_status` - Open risks tracking with overdue/unassigned identification
3. `get_vulnerability_statistics` - Aggregated vulnerability data including top 50 servers
4. `get_exception_statistics` - Exception request approval statistics

Technical approach: Follow existing MCP tool patterns with service injection, use McpExecutionContext for access control, create new ReportService for risk aggregations, extend VulnerabilityStatisticsService for top 50 servers.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, MCP Protocol
**Storage**: MariaDB 11.4 (existing tables: risk, risk_assessment, vulnerability, vulnerability_exception_request)
**Testing**: JUnit 5, Mockk, Testcontainers
**Target Platform**: Linux server (existing backend)
**Project Type**: Web application (backend API + MCP)
**Performance Goals**: 5 seconds maximum response time for all report tools
**Constraints**: Must respect existing RBAC and user delegation patterns
**Scale/Scope**: Aggregates over existing data (~100K+ vulnerabilities, ~1K+ assets)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

No violations. This feature:
- Follows existing MCP tool patterns (no new architectural paradigms)
- Uses existing services where available (VulnerabilityStatisticsService, ExceptionRequestStatisticsService)
- Creates minimal new code (1 new service, 4 new tools, DTOs)
- Respects existing access control mechanisms

## Project Structure

### Documentation (this feature)

```text
specs/069-mcp-lense-reports/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 research
├── data-model.md        # DTO definitions
├── quickstart.md        # Development guide
├── contracts/           # MCP tool contracts
│   └── mcp-tools.md     # Tool schemas and authorization
└── tasks.md             # Phase 2 output (via /speckit.tasks)
```

### Source Code (repository root)

```text
src/backendng/src/main/kotlin/com/secman/
├── dto/
│   └── reports/
│       ├── RiskAssessmentSummaryDto.kt       # New
│       ├── RiskMitigationStatusDto.kt        # New
│       └── VulnerabilityStatisticsAggregateDto.kt  # New
├── service/
│   ├── ReportService.kt                      # New
│   └── VulnerabilityStatisticsService.kt     # Extend with getTopServersByVulnerabilities
├── mcp/
│   ├── tools/
│   │   ├── GetRiskAssessmentSummaryTool.kt   # New
│   │   ├── GetRiskMitigationStatusTool.kt    # New
│   │   ├── GetVulnerabilityStatisticsTool.kt # New
│   │   └── GetExceptionStatisticsTool.kt     # New
│   └── McpToolRegistry.kt                    # Update to register new tools
└── repository/
    ├── RiskRepository.kt                     # Add count queries
    └── RiskAssessmentRepository.kt           # Add count queries

docs/
└── MCP.md                                    # Update with new tool documentation
```

**Structure Decision**: Extend existing backend structure following established patterns. New DTOs in dedicated reports package, new service for risk aggregations, new MCP tools following singleton pattern with service injection.

## Complexity Tracking

No violations requiring justification. This feature:
- Adds 4 new tools following exact existing patterns
- Creates 1 new service with straightforward aggregation logic
- Uses existing access control infrastructure
- No new architectural components or patterns

## Implementation Phases

### Phase 1: DTOs and Report Service

1. Create DTO classes for report responses:
   - `RiskAssessmentSummaryDto` with nested summary DTOs
   - `RiskMitigationStatusDto` with risk detail list
   - `TopServerByVulnerabilitiesDto` for top 50 servers

2. Add repository query methods:
   - `RiskRepository.countByStatus()`, `countByRiskLevel()`, `countOverdueRisks()`, `countUnassignedRisks()`
   - `RiskAssessmentRepository.countByStatus()`, `findRecentAssessments(limit)`

3. Create `ReportService`:
   - `getRiskAssessmentSummary(accessibleAssetIds: Set<Long>?)`
   - `getRiskMitigationStatus(accessibleAssetIds: Set<Long>?, statusFilter: String?)`

4. Extend `VulnerabilityStatisticsService`:
   - `getTopServersByVulnerabilities(authentication, limit, domain)`

### Phase 2: MCP Tools

1. Create MCP tool classes:
   - `GetRiskAssessmentSummaryTool` - inject ReportService
   - `GetRiskMitigationStatusTool` - inject ReportService, handle status filter
   - `GetVulnerabilityStatisticsTool` - inject VulnerabilityStatisticsService, aggregate calls
   - `GetExceptionStatisticsTool` - inject ExceptionRequestStatisticsService

2. Register tools in `McpToolRegistry`:
   - Add tool instances to registry
   - Add permission mappings in `isToolAuthorized()`

### Phase 3: Documentation

1. Update `docs/MCP.md`:
   - Add tool descriptions and examples
   - Document input/output schemas
   - Add to tool reference table

2. Verify all tools appear in capabilities response

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Single `get_vulnerability_statistics` tool | Aggregates 6 service calls into one response for efficiency |
| ReportService for risk data | Keeps aggregation logic separate from domain services |
| No pagination for reports | Fixed-size aggregate responses (10 recent, 50 top servers) |
| Use existing ExceptionStatisticsDto | Reuse proven DTO structure |

## Testing Strategy

1. **Unit Tests**: Mock services, verify tool logic and error handling
2. **Integration Tests**: Verify queries return expected aggregations
3. **Manual Verification**: Compare MCP output to Lense UI reports

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Performance exceeds 5s | Use efficient GROUP BY queries, add indexes if needed |
| Access control gaps | Reuse proven `getFilterableAssetIds()` pattern |
| Data inconsistency with UI | Use same service methods where possible |

## Dependencies

- Existing `VulnerabilityStatisticsService` methods
- Existing `ExceptionRequestStatisticsService` methods
- Existing MCP infrastructure (`McpToolRegistry`, `McpExecutionContext`)
- Existing domain models (`Risk`, `RiskAssessment`, `Vulnerability`)

## Deliverables Checklist

- [ ] DTOs created in `dto/reports/`
- [ ] ReportService with risk assessment and mitigation methods
- [ ] VulnerabilityStatisticsService extended with top 50 servers
- [ ] 4 MCP tools created and registered
- [ ] Permission mappings added to registry
- [ ] docs/MCP.md updated with tool documentation
- [ ] All tools discoverable via `tools/list`
- [ ] Response time under 5 seconds verified
