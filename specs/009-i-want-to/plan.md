
# Implementation Plan: Enhanced MCP Tools for Security Data Access

**Branch**: `009-i-want-to` | **Date**: 2025-10-05 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/009-i-want-to/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → If not found: ERROR "No feature spec at {path}"
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → Detect Project Type from file system structure or context (web=frontend+backend, mobile=app+api)
   → Set Structure Decision based on project type
3. Fill the Constitution Check section based on the content of the constitution document.
4. Evaluate Constitution Check section below
   → If violations exist: Document in Complexity Tracking
   → If no justification possible: ERROR "Simplify approach first"
   → Update Progress Tracking: Initial Constitution Check
5. Execute Phase 0 → research.md
   → If NEEDS CLARIFICATION remain: ERROR "Resolve unknowns"
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, agent-specific template file (e.g., `CLAUDE.md` for Claude Code, `.github/copilot-instructions.md` for GitHub Copilot, `GEMINI.md` for Gemini CLI, `QWEN.md` for Qwen Code or `AGENTS.md` for opencode).
7. Re-evaluate Constitution Check section
   → If new violations: Refactor design, return to Phase 1
   → Update Progress Tracking: Post-Design Constitution Check
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
9. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 7. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
Extend the existing MCP (Model Context Protocol) server implementation (Feature 006) with new tools to provide comprehensive access to all stored asset information, open port/service data from scan results, and vulnerability records. This enables AI assistants and automation tools to query complete security data through standardized MCP interfaces with proper authentication, rate limiting, and access control.

## Technical Context
**Language/Version**: Kotlin 2.1.0 / Java 21 (backend MCP server)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, existing MCP server infrastructure from Feature 006
**Storage**: MariaDB 11.4 via Hibernate JPA (existing database schema)
**Testing**: JUnit 5 + MockK (backend unit/integration tests), contract tests for MCP tool schemas
**Target Platform**: Linux server (Docker containerized)
**Project Type**: web (backend-only extension - no frontend changes needed)
**Performance Goals**: 5000 requests/minute, 100K requests/hour per API key; <200ms p95 latency
**Constraints**: Real-time database queries (no caching), 1000 items/page max, 100K total results limit, workgroup-based access control enforced
**Scale/Scope**: Extends existing MCP server with 4-6 new tools, reuses existing domain models (Asset, ScanResult, Vulnerability, Workgroup)

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Verify this feature complies with secman Constitution v1.0.0:

| Principle | Compliance Check | Status |
|-----------|------------------|--------|
| I. Security-First | ✅ Security implications evaluated: API key authentication with permission scopes, workgroup-based access control enforced, rate limiting to prevent abuse, input validation on all filters/pagination params | ✅ |
| II. TDD (NON-NEGOTIABLE) | ✅ Contract tests for MCP tool schemas written first, unit tests for filtering/pagination logic, integration tests for access control enforcement | ✅ |
| III. API-First | ✅ MCP tool contracts defined (extends existing MCP server from Feature 006), backward compatible (new tools only, no changes to existing), schema validation enforced | ✅ |
| IV. Docker-First | ✅ Extends existing containerized MCP server, API key config via .env, multi-arch support inherited from existing infrastructure | ✅ |
| V. RBAC | ✅ API key permissions map to user roles (USER, ADMIN, VULN), workgroup membership enforced, access violations return explicit errors | ✅ |
| VI. Schema Evolution | ✅ No schema changes (uses existing entities: Asset, ScanResult, Vulnerability, Workgroup), read-only operations, no migrations needed | ✅ |

**Quality Gates**:
- [ ] Tests achieve ≥80% coverage (contract + unit + integration)
- [ ] Linting passes (Kotlin conventions)
- [ ] Docker builds succeed (AMD64 + ARM64) - inherited from existing MCP server
- [ ] MCP tool responses <200ms (p95) for typical queries
- [ ] Security scan shows no critical vulnerabilities

## Project Structure

### Documentation (this feature)
```
specs/[###-feature]/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   ├── Asset.kt                    # Existing - no changes
│   │   ├── ScanResult.kt               # Existing - no changes
│   │   ├── Vulnerability.kt            # Existing - no changes
│   │   ├── Workgroup.kt                # Existing - no changes
│   │   └── McpApiKey.kt                # NEW - API key entity with permissions
│   ├── repository/
│   │   ├── AssetRepository.kt          # Existing - may add query methods
│   │   ├── ScanResultRepository.kt     # Existing - may add query methods
│   │   ├── VulnerabilityRepository.kt  # Existing - may add query methods
│   │   └── McpApiKeyRepository.kt      # NEW - API key persistence
│   ├── service/
│   │   ├── mcp/
│   │   │   ├── McpAuthService.kt       # NEW - API key validation & permission checks
│   │   │   ├── McpRateLimitService.kt  # NEW - rate limiting enforcement
│   │   │   ├── McpAssetService.kt      # NEW - asset query logic with access control
│   │   │   ├── McpScanService.kt       # NEW - scan result query logic
│   │   │   └── McpVulnService.kt       # NEW - vulnerability query logic
│   │   └── AssetFilterService.kt       # Existing - reuse for access control
│   └── mcp/
│       ├── tools/
│       │   ├── GetAllAssetsDetailTool.kt       # NEW - complete asset inventory
│       │   ├── GetAssetScanResultsTool.kt      # NEW - open ports/services
│       │   ├── GetAllVulnerabilitiesTool.kt    # NEW - all vulnerability records
│       │   ├── GetAssetCompleteTool.kt         # NEW - asset profile with all data
│       │   └── GetFilteredAssetsTool.kt        # NEW - advanced filtering
│       └── schemas/
│           └── [MCP tool JSON schemas]          # NEW - contract definitions
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── mcp/
    │       └── [MCP tool contract tests]        # NEW - schema validation tests
    ├── unit/
    │   └── service/mcp/
    │       └── [Service unit tests]             # NEW - filtering/pagination tests
    └── integration/
        └── mcp/
            └── [End-to-end MCP tool tests]      # NEW - access control tests
```

**Structure Decision**: Web application (backend-only). This feature extends the existing MCP server infrastructure from Feature 006. No frontend changes needed as MCP tools are consumed by external AI assistants, not the web UI. All new code in `src/backendng/` following existing Micronaut/Kotlin structure.

## Phase 0: Outline & Research
1. **Extract unknowns from Technical Context** above:
   - For each NEEDS CLARIFICATION → research task
   - For each dependency → best practices task
   - For each integration → patterns task

2. **Generate and dispatch research agents**:
   ```
   For each unknown in Technical Context:
     Task: "Research {unknown} for {feature context}"
   For each technology choice:
     Task: "Find best practices for {tech} in {domain}"
   ```

3. **Consolidate findings** in `research.md` using format:
   - Decision: [what was chosen]
   - Rationale: [why chosen]
   - Alternatives considered: [what else evaluated]

**Output**: research.md with all NEEDS CLARIFICATION resolved

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

1. **Extract entities from feature spec** → `data-model.md`:
   - Entity name, fields, relationships
   - Validation rules from requirements
   - State transitions if applicable

2. **Generate API contracts** from functional requirements:
   - For each user action → endpoint
   - Use standard REST/GraphQL patterns
   - Output OpenAPI/GraphQL schema to `/contracts/`

3. **Generate contract tests** from contracts:
   - One test file per endpoint
   - Assert request/response schemas
   - Tests must fail (no implementation yet)

4. **Extract test scenarios** from user stories:
   - Each story → integration test scenario
   - Quickstart test = story validation steps

5. **Update agent file incrementally** (O(1) operation):
   - Run `.specify/scripts/bash/update-agent-context.sh claude`
     **IMPORTANT**: Execute it exactly as specified above. Do not add or remove any arguments.
   - If exists: Add only NEW tech from current plan
   - Preserve manual additions between markers
   - Update recent changes (keep last 3)
   - Keep under 150 lines for token efficiency
   - Output to repository root

**Output**: data-model.md, /contracts/*, failing tests, quickstart.md, agent-specific file

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
- Load `.specify/templates/tasks-template.md` as base
- Generate tasks from Phase 1 design docs (contracts, data model, quickstart)
- Each MCP tool contract → contract test task [P]
- McpApiKey entity → model + repository + service tasks
- Authentication/rate limiting services → service test + implementation tasks
- Each MCP tool → tool implementation task (extends McpTool interface)
- Integration tests from quickstart scenarios

**Ordering Strategy** (TDD-compliant):
1. **Phase 1 - Foundation** (Tests First):
   - [P] Contract tests for all 4 MCP tools (get_all_assets_detail, get_asset_scan_results, get_all_vulnerabilities_detail, get_asset_complete_profile)
   - [P] McpApiKey entity contract tests (Hibernate entity validation)
   - Unit tests for McpAuthService (API key validation, permission checks)
   - Unit tests for McpRateLimitService (token bucket algorithm)

2. **Phase 2 - Implementation** (Make Tests Pass):
   - [P] McpApiKey domain entity + repository
   - [P] McpAuthService implementation
   - [P] McpRateLimitService implementation (Redis-backed)
   - McpAssetService (asset queries with access control)
   - McpScanService (scan result queries)
   - McpVulnService (vulnerability queries with exception filtering)

3. **Phase 3 - MCP Tools** (Contract-Driven):
   - [P] GetAllAssetsDetailTool (implements get_all_assets_detail contract)
   - [P] GetAssetScanResultsTool (implements get_asset_scan_results contract)
   - [P] GetAllVulnerabilitiesDetailTool (implements get_all_vulnerabilities_detail contract)
   - [P] GetAssetCompleteProfileTool (implements get_asset_complete_profile contract)
   - Register tools in McpToolRegistry

4. **Phase 4 - Integration Tests** (End-to-End):
   - Integration test: Security audit report generation (quickstart scenario 1)
   - Integration test: Port exposure analysis (quickstart scenario 2)
   - Integration test: Compliance dashboard (quickstart scenario 3)
   - Integration test: Error scenarios (unauthorized access, rate limits, pagination)
   - Integration test: API key lifecycle (creation, usage, expiration, revocation)

5. **Phase 5 - Admin Features** (Optional - UI for key management):
   - API key management endpoints (create, list, revoke)
   - Admin UI for API key management (future feature, not in this spec)

**Dependency Graph**:
```
McpApiKey entity ──┐
                   ├──> McpAuthService ──┐
                   │                      ├──> All MCP Tools
McpRateLimitService ─┘                    │
                                          │
AssetFilterService (existing) ────────────┤
                                          │
Asset/ScanResult/Vulnerability            │
(existing repositories) ──────────────────┘
```

**Estimated Output**: 35-40 numbered, ordered tasks in tasks.md

**Parallel Execution Opportunities**:
- All contract tests can run in parallel (independent schemas)
- Entity/repository implementations can run in parallel
- MCP tool implementations can run in parallel (after services complete)

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)  
**Phase 4**: Implementation (execute tasks.md following constitutional principles)  
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |


## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command) ✅
- [x] Phase 1: Design complete (/plan command) ✅
- [x] Phase 2: Task planning complete (/plan command - describe approach only) ✅
- [x] Phase 3: Tasks generated (/tasks command) ✅
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS ✅
- [x] Post-Design Constitution Check: PASS ✅
- [x] All NEEDS CLARIFICATION resolved (via /clarify command) ✅
- [x] Complexity deviations documented (none - full compliance) ✅

**Artifacts Generated**:
- ✅ research.md - Technical research and architecture decisions
- ✅ data-model.md - Entity schemas, DTOs, validation rules
- ✅ contracts/ - 4 MCP tool JSON schemas
  - get_all_assets_detail.json
  - get_asset_scan_results.json
  - get_all_vulnerabilities_detail.json
  - get_asset_complete_profile.json
- ✅ quickstart.md - Usage examples and integration test scenarios
- ✅ tasks.md - 42 numbered, ordered tasks for TDD implementation
- ✅ CLAUDE.md - Updated agent context file

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
