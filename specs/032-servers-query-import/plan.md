# Implementation Plan: Servers Query Import

**Branch**: `032-servers-query-import` | **Date**: 2025-10-21 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/032-servers-query-import/spec.md`

## Summary

Add `secman query servers` CLI command that queries CrowdStrike API for HIGH and CRITICAL severity vulnerabilities on servers open >30 days, automatically imports discovered servers as Asset records, and replaces existing vulnerability data with current state. Includes backend API endpoint for transactional persistence, exponential backoff retry for rate limiting, and detailed import statistics.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend + CLI), TypeScript/JavaScript (frontend - minimal involvement)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, CrowdStrike shared module (existing), Picocli CLI framework
**Storage**: MariaDB 11.4 via Hibernate JPA with auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E - minimal)
**Target Platform**: JVM 21 (cross-platform CLI + backend service)
**Project Type**: web (backend + frontend + CLI tools)
**Performance Goals**: Query completes in <60s for <500 servers, transaction-per-server ensures atomicity
**Constraints**: CrowdStrike API rate limiting (429 responses), must handle pagination, preserve data consistency during partial failures
**Scale/Scope**: Expected 50-500 servers per query, 5-50 vulnerabilities per server

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Initial Check** (Before Phase 0): ✅ PASS (all principles satisfied)
**Post-Phase 1 Check** (After design): ✅ PASS (design preserves constitutional compliance)

### I. Security-First ✅

- **File uploads**: N/A - no file uploads in this feature
- **Input sanitization**: ✅ Backend validates JSON payload from CLI, hostname matching uses exact string comparison (no injection risk)
- **RBAC enforcement**: ✅ Backend endpoint requires authentication (CLI uses configured backend credentials)
- **Sensitive data**: ✅ No passwords/tokens logged, CrowdStrike credentials managed via existing config system
- **Token storage**: ✅ Uses existing JWT auth pattern for backend API calls

**Status**: PASS

### II. Test-Driven Development (TDD) ✅

- **Contract tests**: ✅ Required for new `/api/crowdstrike/vulnerabilities/save` endpoint
- **Integration tests**: ✅ Required for CLI → CrowdStrike → Backend flow
- **Unit tests**: ✅ Required for transaction logic, retry logic, statistics calculation
- **Red-Green-Refactor**: ✅ Tests written before implementation
- **Coverage target**: ≥80%
- **Frameworks**: JUnit 5 + MockK (backend/CLI), Playwright (minimal frontend if needed)

**Status**: PASS (tests will be prepared when user requests)

### III. API-First ✅

- **RESTful design**: ✅ New endpoint `POST /api/crowdstrike/vulnerabilities/save` follows REST patterns
- **OpenAPI documentation**: ✅ Will be added to existing Swagger docs
- **Error formats**: ✅ Returns standard error format (HTTP status codes + JSON error body)
- **Backward compatibility**: ✅ New endpoint, no breaking changes

**Status**: PASS

### IV. User-Requested Testing ✅

- **Proactive test planning**: ❌ NOT doing proactive test planning per principle
- **Test tasks**: Will be marked OPTIONAL in tasks.md unless user explicitly requests
- **TDD compliance**: ✅ When tests ARE written, they follow TDD (written first, fail before implementation)

**Status**: PASS

### V. Role-Based Access Control (RBAC) ✅

- **@Secured annotations**: ✅ Backend endpoint requires authentication
- **Roles**: Uses existing authentication (CLI configured with backend credentials)
- **Frontend role checks**: N/A - minimal frontend involvement (no new UI components)
- **Workgroup filtering**: ⚠️ NEEDS CLARIFICATION - should imported assets be assigned to workgroups?
- **Authorization at service layer**: ✅ Service layer enforces transaction-per-server logic

**Status**: PASS (with design clarification needed for workgroup assignment)

### VI. Schema Evolution ✅

- **Hibernate auto-migration**: ✅ No schema changes required - reuses existing Asset and Vulnerability entities
- **Database constraints**: ✅ Existing constraints preserved (FK relationships, indexes)
- **Foreign keys**: ✅ Vulnerability.asset_id FK already exists
- **Indexes**: ✅ Existing indexes on asset.name, vulnerability.asset_id, vulnerability.scan_timestamp
- **Data loss prevention**: ✅ Transaction-per-server ensures no data loss on partial failures

**Status**: PASS

## Project Structure

### Documentation (this feature)

```
specs/032-servers-query-import/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── crowdstrike-vulnerabilities-save.openapi.yaml
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
src/
├── cli/                                   # Kotlin CLI module (existing)
│   └── src/main/kotlin/com/secman/cli/
│       ├── commands/
│       │   └── ServersCommand.kt          # NEW: servers query command
│       └── storage/
│           └── VulnerabilityStorageService.kt  # NEW: backend API client
│
├── backendng/                             # Backend service (existing)
│   └── src/main/kotlin/com/secman/
│       ├── controller/
│       │   └── CrowdStrikeController.kt   # NEW: /api/crowdstrike endpoint
│       ├── service/
│       │   └── CrowdStrikeVulnerabilityImportService.kt  # NEW: import logic
│       └── dto/
│           ├── CrowdStrikeVulnerabilityBatchDto.kt  # NEW: request DTO
│           └── ImportStatisticsDto.kt     # NEW: response DTO
│
└── shared/                                # Shared CrowdStrike module (existing)
    └── src/main/kotlin/com/secman/crowdstrike/
        ├── client/
        │   └── CrowdStrikeApiClient.kt    # MODIFY: add server filtering
        └── dto/
            └── CrowdStrikeVulnerabilityDto.kt  # EXISTS: reuse

tests/
├── cli/
│   └── src/test/kotlin/com/secman/cli/
│       └── commands/
│           └── ServersCommandTest.kt      # NEW: CLI command tests
│
└── backendng/
    └── src/test/kotlin/com/secman/
        ├── contract/
        │   └── CrowdStrikeImportContractTest.kt  # NEW: API contract tests
        └── service/
            └── CrowdStrikeVulnerabilityImportServiceTest.kt  # NEW: unit tests
```

**Structure Decision**: Web application structure (existing multi-module Gradle project). This feature spans three modules: `shared` (CrowdStrike API client), `cli` (servers command), and `backendng` (import API endpoint). No frontend changes required - purely backend/CLI feature.

## Complexity Tracking

*No constitutional violations - all gates PASS.*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| N/A | N/A | N/A |

---

## Phase Completion Summary

### Phase 0: Research ✅

**Status**: COMPLETE
**Output**: [research.md](research.md)

**Decisions Made**:
1. **Workgroup Assignment**: Do NOT auto-assign workgroups (empty set, manual assignment later)
2. **Retry Strategy**: Exponential backoff 1s, 2s, 4s, 8s (max 3 retries)
3. **Transaction Scope**: Per-server @Transactional (atomic delete+insert)
4. **CVE ID Handling**: Skip missing/empty, track in statistics, log warnings

**Unknowns Resolved**: All (4/4)

---

### Phase 1: Design & Contracts ✅

**Status**: COMPLETE
**Outputs**:
- [data-model.md](data-model.md) - Entity models and DTOs
- [contracts/crowdstrike-vulnerabilities-save.openapi.yaml](contracts/crowdstrike-vulnerabilities-save.openapi.yaml) - API contract
- [quickstart.md](quickstart.md) - Usage examples
- CLAUDE.md - Agent context updated

**Entities Defined**:
- Asset (existing, reused)
- Vulnerability (existing, reused)
- CrowdStrikeVulnerabilityBatchDto (new DTO)
- ImportStatisticsDto (new DTO)

**API Endpoints Designed**:
- `POST /api/crowdstrike/vulnerabilities/save` - Import server vulnerabilities

**Schema Changes**: NONE (reuses existing entities)

**Constitution Re-check**: ✅ PASS (design maintains constitutional compliance)

---

### Phase 2: Tasks ⏭️

**Status**: NOT STARTED (requires `/speckit.tasks` command)
**Next Step**: Run `/speckit.tasks` to generate actionable task list

---

## Implementation Readiness

✅ **Requirements**: Fully specified in spec.md
✅ **Clarifications**: All ambiguities resolved (5 questions answered)
✅ **Research**: All technical decisions made
✅ **Design**: Complete data model and API contracts
✅ **Documentation**: Quickstart guide prepared
✅ **Constitution**: Fully compliant (no violations)

**Ready for**: Task generation (`/speckit.tasks`)
