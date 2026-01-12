# Implementation Plan: MCP Tools for Overdue Vulnerabilities and Exception Handling

**Branch**: `062-mcp-vuln-exceptions` | **Date**: 2026-01-11 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/062-mcp-vuln-exceptions/spec.md`

## Summary

Implement 7 new MCP tools to expose overdue asset querying and vulnerability exception handling functionality via the Model Context Protocol. These tools wrap existing service layer functionality (`OutdatedAssetService`, `VulnerabilityExceptionRequestService`) with proper access control using `McpExecutionContext` for User Delegation.

**Tools to implement:**
1. `get_overdue_assets` - List assets with overdue vulnerabilities (ADMIN/VULN)
2. `create_exception_request` - Create new exception request (all authenticated users)
3. `get_my_exception_requests` - View own exception requests (all authenticated users)
4. `get_pending_exception_requests` - View all pending requests (ADMIN/SECCHAMPION)
5. `approve_exception_request` - Approve pending request (ADMIN/SECCHAMPION)
6. `reject_exception_request` - Reject pending request (ADMIN/SECCHAMPION)
7. `cancel_exception_request` - Cancel own pending request (original requester)

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA
**Storage**: MariaDB 11.4 (existing tables: `vulnerability_exception_request`, `vulnerability_exception`, `outdated_asset_materialized_view`)
**Testing**: JUnit 5, Mockk (per constitution Principle IV - test preparation only when explicitly requested)
**Target Platform**: Linux server (Docker/VM deployment)
**Project Type**: Web application (backend MCP server)
**Performance Goals**: <3s for overdue assets retrieval, <2s for exception request operations
**Constraints**: Must respect existing RBAC, User Delegation required for role checks
**Scale/Scope**: 7 new MCP tools, ~7 Kotlin files, MCP documentation update

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | All tools use `McpExecutionContext` for access control; role checks via `context.isAdmin`, `context.delegatedUserRoles`; input sanitization delegated to existing service layer |
| III. API-First | PASS | MCP tools follow existing patterns; JSON-RPC 2.0 protocol maintained |
| IV. User-Requested Testing | PASS | No test preparation included unless explicitly requested |
| V. RBAC | PASS | All tools enforce role requirements matching REST API; ADMIN/VULN for overdue assets, ADMIN/SECCHAMPION for exception approval |
| VI. Schema Evolution | PASS | No schema changes required; using existing entities |

## Project Structure

### Documentation (this feature)

```text
specs/062-mcp-vuln-exceptions/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (MCP tool schemas)
│   ├── get-overdue-assets.md
│   ├── create-exception-request.md
│   ├── get-my-exception-requests.md
│   ├── get-pending-exception-requests.md
│   ├── approve-exception-request.md
│   ├── reject-exception-request.md
│   └── cancel-exception-request.md
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
src/backendng/src/main/kotlin/com/secman/
├── mcp/
│   ├── McpToolRegistry.kt                    # MODIFY - register new tools
│   └── tools/
│       ├── GetOverdueAssetsTool.kt          # NEW
│       ├── CreateExceptionRequestTool.kt    # NEW
│       ├── GetMyExceptionRequestsTool.kt    # NEW
│       ├── GetPendingExceptionRequestsTool.kt # NEW
│       ├── ApproveExceptionRequestTool.kt   # NEW
│       ├── RejectExceptionRequestTool.kt    # NEW
│       └── CancelExceptionRequestTool.kt    # NEW
├── domain/
│   └── McpPermission.kt                      # MODIFY - add EXCEPTIONS_READ, EXCEPTIONS_WRITE if needed
└── dto/mcp/
    └── McpExecutionContext.kt                # READ ONLY - use existing context pattern

docs/
└── MCP.md                                    # MODIFY - document new tools
```

**Structure Decision**: Web application structure using existing `src/backendng/` layout. All new files placed in `src/backendng/src/main/kotlin/com/secman/mcp/tools/` following established MCP tool patterns (e.g., `ListUsersTool.kt`, `GetAssetMostVulnerabilitiesTool.kt`).

## Complexity Tracking

> No constitution violations to justify. Implementation uses existing service layer and patterns.

| Aspect | Decision | Justification |
|--------|----------|---------------|
| Service reuse | Delegate to existing services | `OutdatedAssetService` and `VulnerabilityExceptionRequestService` contain all business logic |
| No new entities | Use existing domain model | `VulnerabilityExceptionRequest`, `VulnerabilityException`, `OutdatedAssetMaterializedView` already exist |
| No new permissions | Reuse existing McpPermission | `VULNERABILITIES_READ`, `ASSETS_READ`, `USER_ACTIVITY` sufficient for authorization mapping |
