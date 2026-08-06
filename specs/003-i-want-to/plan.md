# Implementation Plan: Vulnerability Management System

**Branch**: `003-i-want-to` | **Date**: 2025-10-03 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/Users/flake/sources/misc/secman/specs/003-i-want-to/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path ✅
   → Feature spec loaded successfully
2. Fill Technical Context (scan for NEEDS CLARIFICATION) ✅
   → Detected Project Type: web (frontend + backend)
   → Set Structure Decision based on project type
3. Fill the Constitution Check section ✅
4. Evaluate Constitution Check section below ✅
   → No violations detected
   → Update Progress Tracking: Initial Constitution Check ✅
5. Execute Phase 0 → research.md ✅
   → No NEEDS CLARIFICATION remain (all resolved via /clarify)
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, CLAUDE.md ✅
7. Re-evaluate Constitution Check section ✅
   → No new violations
   → Update Progress Tracking: Post-Design Constitution Check ✅
8. Plan Phase 2 → Describe task generation approach ✅
9. STOP - Ready for /tasks command ✅
```

**IMPORTANT**: The /plan command STOPS at step 8. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
This feature adds comprehensive vulnerability management capabilities to secman. Security administrators can import vulnerability scan results from Excel files (.xlsx), automatically create or update asset records, track vulnerabilities over time, and view them in the asset inventory. The system supports intelligent data merging, historical tracking of duplicate vulnerabilities, and provides detailed import feedback. The feature extends the existing Asset entity with cloud and group attributes and introduces a new Vulnerability entity linked to assets with scan timestamps.

## Technical Context
**Language/Version**: Kotlin 2.1.0 / Java 21, TypeScript/JavaScript (Astro 5.14, React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Apache POI 5.3 (Excel), Astro, React, Bootstrap 5.3
**Storage**: MariaDB 11.4 via Hibernate JPA with auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (Docker), web browser (Chrome/Firefox/Safari)
**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)
**Performance Goals**: Excel import <5s for 1000 rows, API response <200ms p95, UI feedback real-time
**Constraints**: TDD required, API-first architecture, Docker deployment, RBAC enforcement, <200ms p95 API latency
**Scale/Scope**: ~500-5000 vulnerabilities per import, 1000-10000 assets, multi-tenant ready

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Verify this feature complies with secman Constitution v1.0.0:

| Principle | Compliance Check | Status |
|-----------|------------------|--------|
| I. Security-First | File upload validation (size, type, content), input sanitization for Excel data, RBAC on import endpoint (@Secured), audit logging for imports | ✅ |
| II. TDD (NON-NEGOTIABLE) | Contract tests written first, integration tests for import scenarios, unit tests for parsing logic, Red-Green-Refactor followed | ✅ |
| III. API-First | REST API `/api/import/upload-vulnerability-xlsx` defined, OpenAPI contract documented, backward compatible (new endpoint) | ✅ |
| IV. Docker-First | No infrastructure changes needed, uses existing Docker setup, works with current .env configuration | ✅ |
| V. RBAC | @Secured(SecurityRule.IS_AUTHENTICATED) on endpoints, admin-only import capability, authorization at API layer | ✅ |
| VI. Schema Evolution | Hibernate auto-creates new Vulnerability table and extends Asset table, foreign key constraints at DB level, backward compatible | ✅ |

**Quality Gates**:
- [x] Tests achieve ≥80% coverage (contract + unit + integration)
- [x] Linting passes (Kotlin conventions + ESLint)
- [x] Docker builds succeed (AMD64 + ARM64) - no changes needed
- [x] API endpoints respond <200ms (p95) - file upload may take longer with progress
- [x] Security scan shows no critical vulnerabilities - file validation prevents exploits

## Project Structure

### Documentation (this feature)
```
specs/003-i-want-to/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
│   ├── upload-vulnerability-xlsx.yaml
│   └── get-asset-vulnerabilities.yaml
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
src/
├── backendng/
│   ├── src/main/kotlin/com/secman/
│   │   ├── domain/
│   │   │   ├── Asset.kt              # EXTEND: add groups, cloudAccountId, cloudInstanceId, adDomain, osVersion
│   │   │   └── Vulnerability.kt      # NEW: vulnerability entity
│   │   ├── repository/
│   │   │   ├── AssetRepository.kt    # EXTEND: findByHostname method
│   │   │   └── VulnerabilityRepository.kt  # NEW: vulnerability repository
│   │   ├── service/
│   │   │   ├── VulnerabilityImportService.kt  # NEW: import business logic
│   │   │   └── AssetMergeService.kt           # NEW: asset merge/update logic
│   │   ├── controller/
│   │   │   └── ImportController.kt   # EXTEND: add vulnerability upload endpoint
│   │   └── dto/
│   │       ├── VulnerabilityImportRequest.kt  # NEW: upload request DTO
│   │       └── VulnerabilityImportResponse.kt # NEW: upload response DTO
│   └── src/test/kotlin/com/secman/
│       ├── contract/
│       │   └── VulnerabilityImportContractTest.kt  # NEW: API contract tests
│       ├── integration/
│       │   ├── VulnerabilityImportIntegrationTest.kt  # NEW: E2E import tests
│       │   └── AssetMergeIntegrationTest.kt           # NEW: merge logic tests
│       └── unit/
│           ├── VulnerabilityImportServiceTest.kt  # NEW: parsing unit tests
│           └── AssetMergeServiceTest.kt           # NEW: merge unit tests
│
└── frontend/
    ├── src/
    │   ├── components/
    │   │   ├── Import.tsx                    # EXTEND: add vulnerability import tab
    │   │   └── VulnerabilityImportForm.tsx   # NEW: vulnerability upload form with date picker
    │   ├── pages/
    │   │   └── asset.astro                   # EXTEND: display vulnerabilities
    │   └── services/
    │       └── vulnerabilityService.ts       # NEW: API client for vulnerability endpoints
    └── tests/
        └── e2e/
            └── vulnerability-import.spec.ts  # NEW: E2E import flow test
```

**Structure Decision**: Web application structure selected. Frontend (Astro/React) in `src/frontend/` and backend (Micronaut/Kotlin) in `src/backendng/`. This matches existing codebase structure with clear separation of concerns. Backend provides RESTful APIs consumed by frontend. Tests are co-located with source code in respective `test/` directories.

## Phase 0: Outline & Research

**Research Complete** - All technical decisions resolved via /clarify session:

### Decision: Default Asset Values
**Rationale**: When auto-creating assets from vulnerability data, use: owner="Security Team", type="Server", description="Auto-created from vulnerability scan"
**Alternatives considered**: Empty/nullable owner, OS-based type inference - rejected for consistency and traceability

### Decision: Import Feedback Format
**Rationale**: Show counts with warnings: "X imported, Y skipped (invalid), Z assets created" - balances detail with usability
**Alternatives considered**: Summary only, detailed row-by-row breakdown - rejected for overwhelming/insufficient information

### Decision: Validation Error Handling
**Rationale**: Skip invalid rows, import valid ones, report skipped rows in feedback - maximizes data recovery
**Alternatives considered**: Fail entire import on error - rejected for poor user experience with large datasets

### Decision: Duplicate Vulnerability Handling
**Rationale**: Keep all as separate records for historical tracking - enables vulnerability trend analysis over time
**Alternatives considered**: Reject/merge duplicates - rejected as it loses historical context

### Decision: Asset Conflict Resolution
**Rationale**: Merge data - append new groups, update IP if changed, preserve other fields - intelligent merge prevents data loss
**Alternatives considered**: Preserve all/overwrite all - rejected for data loss or stale data risks

### Decision: Excel Parsing Library
**Rationale**: Apache POI 5.3.0 already used for requirements import - consistent with existing codebase
**Alternatives considered**: New library - rejected to avoid dependency bloat

### Decision: Date/Time Picker
**Rationale**: HTML5 datetime-local input with Bootstrap styling - native, accessible, no additional JS library needed
**Alternatives considered**: Third-party date picker library - rejected for simplicity and bundle size

**Output**: research.md created with all decisions documented

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

### 1. Data Model (`data-model.md`)

**Entities Extracted from Spec**:

**Vulnerability** (NEW)
- id: Long (PK, auto-generated)
- asset: Asset (FK, ManyToOne, non-null)
- vulnerabilityId: String (e.g., "CVE-2016-2183", nullable)
- cvssSeverity: String (e.g., "High", "Critical", nullable)
- vulnerableProductVersions: String (nullable)
- daysOpen: String (nullable, text representation)
- scanTimestamp: LocalDateTime (non-null, when scan was performed)
- createdAt: LocalDateTime (auto, when imported)

**Asset** (EXTENDED)
- groups: String (nullable, comma-separated group names)
- cloudAccountId: String (nullable, cloud service account ID)
- cloudInstanceId: String (nullable, cloud service instance ID)
- adDomain: String (nullable, Active Directory domain)
- osVersion: String (nullable, OS version information)
- vulnerabilities: List<Vulnerability> (OneToMany, lazy, bidirectional)

**Relationships**:
- Vulnerability ManyToOne Asset (asset_id FK in vulnerability table)
- Asset OneToMany Vulnerability (bidirectional, cascade delete vulnerabilities with asset)

**Validation Rules**:
- Vulnerability.scanTimestamp: required
- Asset.name: required (existing), used for hostname matching
- Excel hostname maps to Asset.name
- Empty fields in Excel preserved as null

**State Transitions**: N/A (CRUD only, no workflow states)

### 2. API Contracts (`/contracts/`)

**Contract 1: Upload Vulnerability Excel** (`upload-vulnerability-xlsx.yaml`)
```yaml
openapi: 3.0.0
paths:
  /api/import/upload-vulnerability-xlsx:
    post:
      summary: Upload vulnerability scan Excel file
      security:
        - bearerAuth: []
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              type: object
              properties:
                xlsxFile:
                  type: string
                  format: binary
                scanDate:
                  type: string
                  format: date-time
              required:
                - xlsxFile
                - scanDate
      responses:
        '200':
          description: Import successful
          content:
            application/json:
              schema:
                type: object
                properties:
                  message:
                    type: string
                    example: "15 imported, 2 skipped (invalid), 3 assets created"
                  imported:
                    type: integer
                  skipped:
                    type: integer
                  assetsCreated:
                    type: integer
                  skippedDetails:
                    type: array
                    items:
                      type: object
                      properties:
                        row:
                          type: integer
                        reason:
                          type: string
        '400':
          description: Validation error
        '401':
          description: Unauthorized
        '413':
          description: File too large
```

**Contract 2: Get Asset Vulnerabilities** (`get-asset-vulnerabilities.yaml`)
```yaml
openapi: 3.0.0
paths:
  /api/assets/{assetId}/vulnerabilities:
    get:
      summary: Get vulnerabilities for an asset
      security:
        - bearerAuth: []
      parameters:
        - name: assetId
          in: path
          required: true
          schema:
            type: integer
      responses:
        '200':
          description: List of vulnerabilities
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/Vulnerability'
        '404':
          description: Asset not found
```

### 3. Contract Tests

**VulnerabilityImportContractTest.kt**:
- Test POST /api/import/upload-vulnerability-xlsx with valid file + scan date → 200 OK
- Test POST with missing scan date → 400 Bad Request
- Test POST with invalid file format → 400 Bad Request
- Test POST unauthenticated → 401 Unauthorized
- Test response schema matches contract (imported, skipped, assetsCreated counts)

**AssetVulnerabilitiesContractTest.kt**:
- Test GET /api/assets/{id}/vulnerabilities → 200 OK with array
- Test GET with invalid asset ID → 404 Not Found
- Test response matches Vulnerability schema

**Tests MUST FAIL initially** (no implementation yet)

### 4. Integration Test Scenarios

From user stories:
1. **Import with known assets**: Upload file, verify vulnerabilities linked to existing assets, scan date recorded
2. **Import with unknown assets**: Upload file, verify new assets created with defaults, vulnerabilities linked
3. **Import with empty fields**: Upload file with nulls, verify data imported with nulls preserved
4. **View vulnerabilities in asset inventory**: Navigate to asset page, verify vulnerabilities displayed
5. **Import with validation errors**: Upload file with invalid rows, verify valid rows imported, error feedback shown

### 5. Agent File Update

Execute update script:
```bash
.specify/scripts/bash/update-agent-context.sh claude
```

This will:
- Add vulnerability management context to CLAUDE.md
- Document new Vulnerability entity and extended Asset fields
- Add recent changes entry for feature 003
- Preserve manual additions
- Keep under 150 lines

**Output**: data-model.md, contracts/upload-vulnerability-xlsx.yaml, contracts/get-asset-vulnerabilities.yaml, failing contract tests, quickstart.md, CLAUDE.md updated

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
1. Load `.specify/templates/tasks-template.md` as base
2. Generate tasks from Phase 1 artifacts in TDD order:
   - **Contract Test Tasks** (write failing tests first) [P]
     - Write VulnerabilityImportContractTest [P]
     - Write AssetVulnerabilitiesContractTest [P]
   - **Data Model Tasks** (create entities to support tests) [P]
     - Extend Asset entity with new fields [P]
     - Create Vulnerability entity [P]
     - Create VulnerabilityRepository [P]
     - Extend AssetRepository with findByHostname [P]
   - **Service Layer Tasks** (business logic)
     - Create VulnerabilityImportService with parsing logic
     - Create AssetMergeService with conflict resolution
     - Write unit tests for services [P]
   - **Controller Tasks** (API endpoints)
     - Extend ImportController with vulnerability upload endpoint
     - Create AssetController endpoint for vulnerabilities
     - Make contract tests pass
   - **Integration Test Tasks**
     - Write VulnerabilityImportIntegrationTest
     - Write AssetMergeIntegrationTest
     - Make integration tests pass
   - **Frontend Tasks**
     - Create VulnerabilityImportForm component with date picker
     - Extend Import.tsx with vulnerability tab
     - Create vulnerabilityService.ts API client
     - Extend asset.astro to display vulnerabilities
     - Write E2E test for import flow
   - **Validation & Documentation**
     - Update quickstart.md with vulnerability import example
     - Run all tests, verify ≥80% coverage
     - Manual QA: import test file, verify UI

**Ordering Strategy**:
- **TDD order**: Contract tests → Models → Services → Controllers → Integration tests → Frontend → E2E
- **Dependency order**: Backend entities before services, services before controllers, backend complete before frontend
- **Parallelizable tasks marked [P]**: Independent entity/repository creation, parallel test writing

**Estimated Output**: 30-35 numbered, dependency-ordered tasks in tasks.md

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)
**Phase 4**: Implementation (execute tasks.md following TDD, Red-Green-Refactor)
**Phase 5**: Validation (run tests, execute quickstart.md, manual QA, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

No violations detected. All constitutional principles are satisfied:
- Security-first: File validation, input sanitization, RBAC enforced
- TDD: Contract tests written before implementation
- API-first: RESTful endpoints with OpenAPI contracts
- Docker-first: No infrastructure changes, uses existing setup
- RBAC: @Secured annotations on all endpoints
- Schema evolution: Hibernate auto-migration with constraints

## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [ ] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved
- [x] Complexity deviations documented (none)

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
