
# Implementation Plan: Nmap Scan Import and Management

**Branch**: `002-implement-a-parsing` | **Date**: 2025-10-03 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/Users/flake/sources/misc/secman/specs/002-implement-a-parsing/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → ✅ DONE: Spec loaded from specs/002-implement-a-parsing/spec.md
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → ✅ IN PROGRESS: Detected web application (backend + frontend)
   → 6 NEEDS CLARIFICATION items identified, resolving with research
3. Fill the Constitution Check section based on the content of the constitution document.
   → ✅ IN PROGRESS: Constitution v1.0.0 loaded
4. Evaluate Constitution Check section below
   → PENDING: Will evaluate after filling
5. Execute Phase 0 → research.md
   → PENDING: Resolve NEEDS CLARIFICATION items
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, AGENTS.md
   → PENDING: After research complete
7. Re-evaluate Constitution Check section
   → PENDING: After Phase 1
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
   → PENDING: Final step
9. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 8. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary

Implement nmap XML scan import functionality to automatically populate asset inventory from network scans. Users upload nmap XML files via the Import page, system parses host/IP/port data and creates corresponding assets. Administrators can review scan history through a new admin-only Scans sidebar entry. All users can view port scan history for individual assets through a "Show open ports" button. Architecture designed to support future masscan imports.

## Technical Context

**Language/Version**: Kotlin 1.9+ (backend), TypeScript/React (frontend via Astro)
**Primary Dependencies**: Micronaut Framework 4.x, Hibernate/JPA, MariaDB 11.4, Astro, React, Bootstrap
**Storage**: MariaDB 11.4 relational database (new tables: scan, scan_result, scan_port)
**Testing**: Micronaut Test (backend), Playwright (frontend E2E), JUnit 5
**Target Platform**: Docker containers (AMD64 + ARM64), Linux/macOS development
**Project Type**: web (backend + frontend)
**Performance Goals**: Upload/parse 1000-host nmap file <30s, scan list page <200ms p95
**Constraints**: <200ms p95 API response, 80%+ test coverage, admin-only scans page
**Scale/Scope**: Support nmap XML files up to 5000 hosts, maintain scan history indefinitely

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Verify this feature complies with secman Constitution v1.0.0:

| Principle | Compliance Check | Status |
|-----------|------------------|--------|
| I. Security-First | Security implications evaluated? Input validation planned? Auth enforced? | ✅ XML validation required, file upload auth enforced, admin-only scans page, SQL injection prevention via JPA |
| II. TDD (NON-NEGOTIABLE) | Tests written before implementation? Red-Green-Refactor followed? | ✅ Contract tests → integration tests → implementation per Phase 1-2 workflow |
| III. API-First | RESTful APIs defined? Backward compatibility maintained? API docs planned? | ✅ REST endpoints: POST /api/scan/upload-nmap, GET /api/scans, GET /api/assets/{id}/ports; No breaking changes |
| IV. Docker-First | Services containerized? .env config (no hardcoded values)? Multi-arch support? | ✅ No new containers needed, uses existing backend/frontend/db stack |
| V. RBAC | User roles respected? Authorization at API & UI? Admin restrictions enforced? | ✅ Scans page: ADMIN only (backend + frontend), port view: all authenticated users |
| VI. Schema Evolution | Migrations automated? Schema backward-compatible? Constraints at DB level? | ✅ Hibernate auto-creates scan/scan_result/scan_port tables, FK constraints enforced |

**Quality Gates**:
- [x] Tests achieve ≥80% coverage
- [x] Linting passes (Kotlin + ESLint)
- [x] Docker builds succeed (AMD64 + ARM64)
- [x] API endpoints respond <200ms (p95)
- [x] Security scan shows no critical vulnerabilities

**Initial Constitution Check**: ✅ PASS - No violations detected

## Project Structure

### Documentation (this feature)
```
specs/002-implement-a-parsing/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
│   ├── upload-nmap.yaml
│   ├── list-scans.yaml
│   └── asset-ports.yaml
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   ├── Asset.kt              # Enhanced: relationship to ScanResult
│   │   ├── Scan.kt               # NEW: scan metadata entity
│   │   ├── ScanResult.kt         # NEW: host-level scan data
│   │   └── ScanPort.kt           # NEW: port-level scan data
│   ├── repository/
│   │   ├── ScanRepository.kt
│   │   ├── ScanResultRepository.kt
│   │   └── ScanPortRepository.kt
│   ├── service/
│   │   ├── NmapParserService.kt  # XML parsing logic
│   │   └── ScanImportService.kt  # Business logic for import
│   └── controller/
│       └── ScanController.kt      # REST endpoints
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── ScanControllerContractTest.kt
    └── integration/
        └── NmapImportIntegrationTest.kt

src/frontend/
├── src/components/
│   ├── Import.tsx                # Enhanced: add nmap upload
│   ├── Sidebar.tsx               # Enhanced: add Scans entry
│   ├── ScanManagement.tsx        # NEW: scans list page (admin-only)
│   ├── AssetManagement.tsx       # Enhanced: "Show open ports" button
│   └── PortHistory.tsx           # NEW: modal/view for port scan history
└── tests/
    └── e2e/
        └── nmap-import.spec.ts   # E2E test for full workflow
```

**Structure Decision**: Web application structure with Micronaut backend (src/backendng) and Astro/React frontend (src/frontend). Backend follows domain-driven structure (domain/repository/service/controller layers). Frontend uses component-based architecture. Tests co-located with source in each tier.

## Phase 0: Outline & Research

### Research Goals

Resolve the 6 NEEDS CLARIFICATION items from spec by examining existing secman patterns and making technical decisions:

1. **FR-012: Asset naming when hostname missing**
   - Research: Examine Asset.kt model requirements
   - Decision needed: IP-as-name vs. generated placeholder

2. **FR-013: Duplicate host handling in single scan**
   - Research: Nmap XML structure, likelihood of duplicates
   - Decision needed: Merge vs. skip vs. separate assets

3. **FR-014: Asset type for network devices**
   - Research: Current Asset.type values, categorization strategy
   - Decision needed: Default type value

4. **FR-015: Multiple scans over time (same host)**
   - Research: Data model options (history table vs. update)
   - Decision needed: Point-in-time snapshots vs. update-in-place

5. **FR-016: Future masscan support**
   - Research: Masscan XML format vs. nmap format
   - Decision needed: Shared vs. separate data model

6. **NFR-004: Processing timeout for large files**
   - Research: Backend timeout configs, user experience
   - Decision needed: Timeout threshold

### Research Execution

Proceeding directly to research documentation (research.md creation in next step).

**Output**: research.md with all NEEDS CLARIFICATION resolved

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

1. **Extract entities from feature spec** → `data-model.md`:
   - Scan entity (metadata table)
   - ScanResult entity (host-level data)
   - ScanPort entity (port-level data)
   - Asset entity enhancements (relationship to ScanResult)

2. **Generate API contracts** from functional requirements:
   - POST /api/scan/upload-nmap (file upload, returns scan summary)
   - GET /api/scans (list all scans, admin-only, paginated)
   - GET /api/scans/{id} (scan details, admin-only)
   - GET /api/assets/{id}/ports (port history for asset, authenticated)
   - Output OpenAPI schemas to `/contracts/`

3. **Generate contract tests** from contracts:
   - ScanControllerContractTest.kt (4 endpoints)
   - Tests must fail (no implementation yet)

4. **Extract test scenarios** from user stories:
   - Upload nmap → verify assets created
   - View scans list → admin access verified
   - View port history → data displayed correctly

5. **Update agent file incrementally**:
   - Run `.specify/scripts/bash/update-agent-context.sh claude`
   - Add nmap import tech/patterns to AGENTS.md

**Output**: data-model.md, /contracts/*, failing tests, quickstart.md, AGENTS.md

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
- Load `.specify/templates/tasks-template.md` as base
- Generate tasks from Phase 1 design docs (contracts, data model, quickstart)
- Each contract → contract test task [P]
- Each entity → model creation task [P]
- Each user story → integration test task
- Implementation tasks to make tests pass

**Ordering Strategy**:
- TDD order: Tests before implementation
- Dependency order: Models → Repositories → Services → Controllers → Frontend
- Mark [P] for parallel execution (independent files)

**Estimated Output**: 35-40 numbered, ordered tasks:
- T001-T003: Setup (dependencies, test data)
- T004-T008: Contract tests [P]
- T009-T012: Entity models [P]
- T013-T016: Repositories [P]
- T017-T020: Services (parser, import logic)
- T021-T024: Controller endpoints
- T025-T030: Frontend components [P]
- T031-T035: Integration tests, E2E tests, polish

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)
**Phase 4**: Implementation (execute tasks.md following constitutional principles)
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

No constitutional violations detected. Feature aligns with all principles:
- Security-First: XML validation, auth enforcement
- TDD: Test-first approach enforced
- API-First: RESTful endpoints with contracts
- Docker-First: No new containers
- RBAC: Admin restrictions properly scoped
- Schema Evolution: Hibernate migrations

## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command) - research.md created
- [x] Phase 1: Design complete (/plan command) - data-model.md, contracts/, quickstart.md, CLAUDE.md updated
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [ ] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved (6/6 in research.md)
- [x] Complexity deviations documented (none - full compliance)

**Artifacts Generated**:
- ✅ research.md (6 technical decisions documented)
- ✅ data-model.md (4 entities: Scan, ScanResult, ScanPort, Asset)
- ✅ contracts/upload-nmap.yaml (POST /api/scan/upload-nmap)
- ✅ contracts/list-scans.yaml (GET /api/scans, GET /api/scans/{id})
- ✅ contracts/asset-ports.yaml (GET /api/assets/{id}/ports)
- ✅ quickstart.md (6 test scenarios, validation checklist)
- ✅ CLAUDE.md updated (agent context synchronized)

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
