# Implementation Plan: MCP User Delegation

**Branch**: `050-mcp-user-delegation` | **Date**: 2025-11-28 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/050-mcp-user-delegation/spec.md`

## Summary

Extend the existing MCP server to support user delegation, allowing trusted external tools to pass through an authenticated user's email address via the `X-MCP-User-Email` header. The system will look up the user by email, compute the intersection of user roles and API key permissions, and apply both for access control. Includes mandatory domain restrictions for delegation-enabled keys, enhanced audit logging, and threshold-based security alerting.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA
**Storage**: MariaDB 12 (existing mcp_api_keys, mcp_audit_logs tables, users table)
**Testing**: User-requested (per constitution Principle IV)
**Target Platform**: Linux server (backend), Web browser (frontend admin UI)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: User lookup < 100ms (NFR-001), total request < 200ms (SC-001)
**Constraints**: Backward compatible with existing MCP integrations (SC-004)
**Scale/Scope**: Extends existing MCP infrastructure (~10 files modified, 2 entities extended)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | Mandatory domain restrictions, intersection permissions, audit logging, threshold alerting |
| III. API-First | PASS | Extends existing MCP REST API with new header, maintains backward compatibility |
| IV. User-Requested Testing | PASS | No tests planned unless explicitly requested |
| V. RBAC | PASS | Intersection of user roles + API key permissions enforces defense-in-depth |
| VI. Schema Evolution | PASS | Entity extensions use Hibernate auto-migration |

**Gate Result**: PASS - All principles satisfied.

### Post-Design Constitution Re-Check

| Principle | Status | Design Verification |
|-----------|--------|---------------------|
| I. Security-First | PASS | Domain validation prevents unauthorized delegation; intersection model limits scope; audit trails complete |
| III. API-First | PASS | OpenAPI contract defined; new header follows existing conventions; backward compatible |
| IV. User-Requested Testing | PASS | Testing checklist in quickstart.md, not auto-planned |
| V. RBAC | PASS | McpDelegationService computes intersection; both user roles AND API key permissions required |
| VI. Schema Evolution | PASS | Flyway migration V050 defined; defaults ensure backward compatibility |

**Post-Design Gate Result**: PASS

## Project Structure

### Documentation (this feature)

```text
specs/050-mcp-user-delegation/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   ├── McpApiKey.kt           # Extended: delegationEnabled, allowedDelegationDomains
│   │   └── McpAuditLog.kt         # Extended: delegatedUserEmail
│   ├── service/
│   │   ├── McpAuthenticationService.kt  # Extended: delegation authentication
│   │   ├── McpDelegationService.kt      # NEW: delegation validation logic
│   │   └── McpAuditService.kt           # Extended: delegation audit logging
│   ├── controller/
│   │   ├── McpController.kt             # Extended: X-MCP-User-Email header handling
│   │   └── McpApiKeyController.kt       # Extended: delegation config endpoints
│   └── dto/mcp/
│       └── McpDelegationDtos.kt         # NEW: DTOs for delegation
└── src/main/resources/
    └── application.yml              # Alerting threshold config

src/frontend/
├── src/pages/admin/
│   └── mcp-api-keys.astro           # Extended: delegation UI
└── src/components/
    └── McpApiKeyForm.tsx            # Extended: delegation fields

docs/
└── MCP_INTEGRATION.md               # Extended: delegation documentation
```

**Structure Decision**: Web application pattern - extends existing backend MCP module and frontend admin UI for API key management.

## Complexity Tracking

> No violations - implementation follows existing patterns.

| Aspect | Approach | Justification |
|--------|----------|---------------|
| New service (McpDelegationService) | Dedicated service | Separation of concerns - keeps delegation logic isolated from authentication |
| Entity extensions | Add columns | Simpler than new tables; delegation is an attribute of existing keys |
