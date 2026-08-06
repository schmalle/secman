# Implementation Plan: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products

**Branch**: `006-please-evaluate-the` | **Date**: 2025-10-04 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/006-please-evaluate-the/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path ✓
2. Fill Technical Context ✓
3. Fill Constitution Check section ✓
4. Evaluate Constitution Check section ✓
5. Execute Phase 0 → research.md ✓
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, CLAUDE.md ✓
7. Re-evaluate Constitution Check section ✓
8. Plan Phase 2 → Describe task generation approach ✓
9. STOP - Ready for /tasks command ✓
```

**IMPORTANT**: The /plan command STOPS here. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
**Primary Requirement**: Expose security data (assets, scans, scan results, vulnerabilities, discovered products) via MCP tools to enable AI assistants and automation tools to query infrastructure security information without direct database access.

**Technical Approach**: Implement 7 new MCP tools (`get_assets`, `get_scans`, `get_vulnerabilities`, `search_products`, `get_asset_profile`, etc.) using existing MCP infrastructure. Add 3 new permissions (ASSETS_READ, SCANS_READ, VULNERABILITIES_READ) to McpPermission enum. Extend existing Micronaut Data repositories with pagination and filtering methods. Implement rate limiting in McpToolPermissionService using in-memory sliding windows. No database schema changes required - all entities already exist.

## Technical Context
**Language/Version**: Kotlin 2.1.0 / Java 21
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Micronaut Data, Jackson (JSON serialization)
**Storage**: MariaDB 11.4 via Hibernate JPA (existing tables, no schema changes)
**Testing**: JUnit 5, MockK (unit tests), Micronaut Test (integration tests)
**Target Platform**: Linux server (Docker AMD64/ARM64)
**Project Type**: web (Astro frontend + Micronaut backend)
**Performance Goals**: <5 seconds typical queries, <30 seconds complex aggregations, 1000 req/min rate limit
**Constraints**: Max 500 items per page, max 50,000 total results per query, READ_COMMITTED transaction isolation
**Scale/Scope**: Medium deployment (10,000 assets, 100,000 scan results, 500,000 vulnerabilities)

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Verify this feature complies with secman Constitution v1.0.0:

| Principle | Compliance Check | Status |
|-----------|------------------|--------|
| I. Security-First | Input validation via JSON schema, MCP permission framework enforced, API key authentication required, audit logging enabled | ✅ |
| II. TDD (NON-NEGOTIABLE) | Contract tests first (Phase 1), unit tests before implementation (Phase 4), integration tests for end-to-end validation | ✅ |
| III. API-First | MCP tools expose standardized JSON-RPC interface, contracts defined in Phase 1, backward compatible with existing MCP infrastructure | ✅ |
| IV. Docker-First | No new services required, existing backend container handles MCP tools, .env configuration unchanged | ✅ |
| V. RBAC | New MCP permissions (ASSETS_READ, SCANS_READ, VULNERABILITIES_READ), permission checks in McpToolRegistry, admin UI for API key management | ✅ |
| VI. Schema Evolution | No schema changes, extends existing entities via repository methods only, backward compatible with current DB | ✅ |

**Quality Gates**:
- [x] Tests achieve ≥80% coverage (specified in FR-019, NFR-007)
- [x] Linting passes (Kotlin conventions apply)
- [x] Docker builds succeed (no new containers)
- [x] API endpoints respond <200ms p95 (NFR-001: <5s typical queries)
- [x] Security scan shows no critical vulnerabilities (permission enforcement + input validation)

## Project Structure

### Documentation (this feature)
```
specs/006-please-evaluate-the/
├── plan.md              # This file (/plan command output) ✓
├── research.md          # Phase 0 output (/plan command) ✓
├── data-model.md        # Phase 1 output (/plan command) ✓
├── quickstart.md        # Phase 1 output (/plan command) ✓
├── contracts/           # Phase 1 output (/plan command) ✓
│   ├── get_assets.json
│   ├── get_scans.json
│   ├── get_vulnerabilities.json
│   ├── search_products.json
│   └── get_asset_profile.json
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
src/backendng/src/main/kotlin/com/secman/
├── mcp/
│   ├── tools/
│   │   ├── McpTool.kt                    # Existing interface
│   │   ├── GetRequirementsTool.kt        # Existing example
│   │   ├── GetAssetsTool.kt              # NEW
│   │   ├── GetScansTool.kt               # NEW
│   │   ├── GetVulnerabilitiesTool.kt     # NEW
│   │   ├── SearchProductsTool.kt         # NEW
│   │   └── GetAssetProfileTool.kt        # NEW
│   ├── McpToolRegistry.kt                # EXTEND (add permission mappings)
│   └── ToolCategories.kt                 # Existing
├── domain/
│   ├── Asset.kt                          # Existing entity
│   ├── Scan.kt                           # Existing entity
│   ├── ScanResult.kt                     # Existing entity
│   ├── ScanPort.kt                       # Existing entity
│   ├── Vulnerability.kt                  # Existing entity
│   └── McpPermission.kt                  # EXTEND (add new permissions)
├── repository/
│   ├── AssetRepository.kt                # EXTEND (add query methods)
│   ├── ScanRepository.kt                 # EXTEND (add filtering)
│   ├── ScanResultRepository.kt           # EXTEND (add asset queries)
│   ├── ScanPortRepository.kt             # EXTEND (add product search)
│   └── VulnerabilityRepository.kt        # EXTEND (add severity filtering)
├── service/
│   └── McpToolPermissionService.kt       # EXTEND (add rate limiting)
└── controller/
    └── McpController.kt                  # Existing (no changes)

src/backendng/src/test/kotlin/com/secman/
├── mcp/tools/
│   ├── GetAssetsToolTest.kt              # NEW (unit test)
│   ├── GetScansToolTest.kt               # NEW (unit test)
│   ├── GetVulnerabilitiesToolTest.kt     # NEW (unit test)
│   ├── SearchProductsToolTest.kt         # NEW (unit test)
│   └── GetAssetProfileToolTest.kt        # NEW (unit test)
└── integration/
    └── McpSecurityDataIntegrationTest.kt # NEW (E2E test)
```

**Structure Decision**: Web application structure (Option 2). Backend-only changes in `src/backendng/` directory. No frontend changes required - MCP tools accessed via MCP API clients, not web UI.

## Phase 0: Outline & Research
**Status**: ✅ Complete

**Output**: [research.md](./research.md)

**Key Decisions**:
1. **MCP Framework**: Leverage existing `McpController`, `McpToolRegistry`, `McpTool` interface
2. **Repository Pattern**: Extend Micronaut Data repositories with query methods (findByX, Pageable support)
3. **Pagination**: Use `Pageable.from(page, size)` with limits validated in tools (500/page, 50K total)
4. **Rate Limiting**: In-memory sliding window (ConcurrentHashMap) in McpToolPermissionService
5. **Transaction Isolation**: Default READ_COMMITTED (MariaDB MVCC handles snapshot isolation)
6. **Permissions**: Add ASSETS_READ, SCANS_READ, VULNERABILITIES_READ to McpPermission enum
7. **Testing**: 3-layer (contract tests → unit tests → integration tests) per Constitution II
8. **Performance**: Database indexes on service (ScanPort) and severity (Vulnerability) columns

All NEEDS CLARIFICATION items resolved via research.

## Phase 1: Design & Contracts
**Status**: ✅ Complete

**Outputs**:
- [data-model.md](./data-model.md) - Entity definitions, DTOs, repository extensions
- [contracts/](./contracts/) - JSON schemas for 5 MCP tools
- [quickstart.md](./quickstart.md) - End-to-end validation scenarios
- CLAUDE.md - Updated with feature context (via update-agent-context.sh script)

**Key Design Elements**:
1. **Entities**: No schema changes, 5 existing entities (Asset, Scan, ScanResult, ScanPort, Vulnerability)
2. **Response DTOs**: 7 new DTOs (AssetResponse, ScanResponse, VulnerabilityResponse, ProductResponse, AssetProfileResponse, etc.)
3. **Repository Methods**: ~15 new query methods across 5 repositories (pagination, filtering, sorting)
4. **MCP Tools**: 5 tools defined with JSON contracts (get_assets, get_scans, get_vulnerabilities, search_products, get_asset_profile)
5. **Permission Mapping**: Tool-to-permission mapping in McpToolRegistry.isToolAuthorized()

**Contract Tests**: Not yet implemented (Phase 4). Contracts defined as JSON schemas ready for test generation.

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
1. Load `.specify/templates/tasks-template.md` as base template
2. Generate TDD task sequence from Phase 1 artifacts:
   - Contract test tasks: One per MCP tool (5 tasks) - validate JSON schemas [P]
   - Repository extension tasks: Extend 5 repositories with query methods [P]
   - Permission enum task: Add 3 new permissions to McpPermission
   - Tool implementation tasks: Implement 5 MCP tools (one task per tool)
   - Registry update task: Add permission mappings to McpToolRegistry
   - Rate limiting task: Extend McpToolPermissionService with sliding window logic
   - Unit test tasks: One per tool (5 tasks) - mock repositories, test pagination/filtering
   - Integration test task: End-to-end MCP tool execution with real DB
   - Index creation task: Add database indexes for service and severity columns
   - Quickstart validation task: Execute all scenarios from quickstart.md

**Ordering Strategy**:
1. **Prerequisites** (parallel where possible):
   - [P] Add MCP permissions to enum
   - [P] Add database indexes (SQL migration)
   - [P] Write contract tests for all 5 tools (failing tests)
2. **Repository Layer** (parallel):
   - [P] Extend AssetRepository
   - [P] Extend ScanRepository
   - [P] Extend ScanResultRepository
   - [P] Extend ScanPortRepository
   - [P] Extend VulnerabilityRepository
3. **Rate Limiting** (depends on repositories):
   - Implement sliding window in McpToolPermissionService
4. **Tool Implementation** (depends on repositories + rate limiting, parallel per tool):
   - [P] Implement GetAssetsTool + unit tests
   - [P] Implement GetScansTool + unit tests
   - [P] Implement GetVulnerabilitiesTool + unit tests
   - [P] Implement SearchProductsTool + unit tests
   - [P] Implement GetAssetProfileTool + unit tests (depends on other tools for cross-referencing)
5. **Registry Integration** (depends on tools):
   - Update McpToolRegistry with permission mappings
6. **Integration Testing** (depends on all above):
   - Write and run integration tests
   - Execute quickstart.md scenarios
7. **Validation** (final):
   - Verify contract tests pass
   - Verify unit test coverage ≥80%
   - Run performance validation with medium-scale data

**Estimated Output**: ~25-30 numbered, dependency-ordered tasks in tasks.md

**Parallel Execution Opportunities**: Contract tests, repository extensions, and individual tool implementations can run in parallel (marked with [P] above).

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)
**Phase 4**: Implementation (execute tasks.md following Red-Green-Refactor TDD cycle)
**Phase 5**: Validation (run tests, execute quickstart.md, performance testing with 10K assets)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

No constitutional violations. All principles satisfied:
- Security-First: MCP permission framework + input validation
- TDD: Contract tests → unit tests → integration tests
- API-First: MCP tools expose JSON-RPC standard interface
- Docker-First: No new containers, existing backend handles tools
- RBAC: New permissions + admin API key management
- Schema Evolution: No DB changes, repository extensions only

## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning approach described (/plan command)
- [ ] Phase 3: Tasks generated (/tasks command) - **NEXT STEP**
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS (all principles satisfied)
- [x] Post-Design Constitution Check: PASS (no violations introduced)
- [x] All NEEDS CLARIFICATION resolved (via research.md)
- [x] Complexity deviations documented (none required)

**Artifacts Generated**:
- [x] research.md (Phase 0)
- [x] data-model.md (Phase 1)
- [x] contracts/*.json (5 files) (Phase 1)
- [x] quickstart.md (Phase 1)
- [x] CLAUDE.md updated (Phase 1)
- [ ] tasks.md (awaiting /tasks command)

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
*Planning completed: 2025-10-04*
*Ready for: `/tasks` command to generate implementation tasks*
