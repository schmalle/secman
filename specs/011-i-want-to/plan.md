# Implementation Plan: Release-Based Requirement Version Management

**Branch**: `011-i-want-to` | **Date**: 2025-10-05 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/011-i-want-to/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → Loaded successfully from specs/011-i-want-to/spec.md
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → Detected Project Type: web (frontend + backend)
   → Set Structure Decision based on existing secman architecture
3. Fill the Constitution Check section based on the content of the constitution document.
4. Evaluate Constitution Check section below
   → If violations exist: Document in Complexity Tracking
   → If no justification possible: ERROR "Simplify approach first"
   → Update Progress Tracking: Initial Constitution Check
5. Execute Phase 0 → research.md
   → If NEEDS CLARIFICATION remain: ERROR "Resolve unknowns"
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, CLAUDE.md
7. Re-evaluate Constitution Check section
   → If new violations: Refactor design, return to Phase 1
   → Update Progress Tracking: Post-Design Constitution Check
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
9. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 8. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
This feature implements point-in-time requirement versioning through releases. When a release is created, all current requirements are frozen into immutable snapshots. Users can export requirements from specific releases or the current state, and compare differences between releases through a side-by-side comparison UI. The implementation extends the existing Release and VersionedEntity infrastructure to create requirement snapshots, adds a new RELEASE_MANAGER role for release lifecycle management, enforces semantic versioning for release identifiers, and integrates release selection into existing export workflows.

## Technical Context
**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (Astro 5.14 + React 19, frontend)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Apache POI 5.3 (Excel export), Astro, React, Bootstrap 5.3
**Storage**: MariaDB 11.4 via Hibernate JPA with auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Docker containers (AMD64/ARM64), Linux server deployment
**Project Type**: web - Full-stack application (Kotlin/Micronaut backend + Astro/React frontend)
**Performance Goals**:
- API endpoints <200ms p95 (constitutional requirement)
- Export operations may take longer but provide progress feedback
- Database queries use indexes for filtering/sorting
**Constraints**:
- Must maintain backward compatibility with existing export APIs
- Frozen snapshots are immutable once created
- Semantic versioning enforced (MAJOR.MINOR.PATCH format)
- Requirement deletion prevented when frozen in releases
**Scale/Scope**:
- Multiple releases per organization
- Thousands of requirements per release
- Side-by-side comparison of large requirement sets
- Historical compliance tracking across years

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Verify this feature complies with secman Constitution v1.0.0:

| Principle | Compliance Check | Status |
|-----------|------------------|--------|
| I. Security-First | Security implications: RELEASE_MANAGER role introduces new permission model; Input validation: semantic version regex, release name/description sanitization; Auth enforced: @Secured annotations on all release endpoints, UI permission checks | ✅ |
| II. TDD (NON-NEGOTIABLE) | Tests written first: Contract tests for all endpoints, unit tests for snapshot creation, integration tests for freeze/export workflows; Red-Green-Refactor followed throughout | ✅ |
| III. API-First | RESTful APIs: /api/releases CRUD, /api/releases/{id}/snapshot, /api/releases/compare; Backward compatibility: Existing export endpoints extended with optional releaseId parameter; API docs: OpenAPI specs generated | ✅ |
| IV. Docker-First | Services containerized: Uses existing Docker Compose multi-container setup; .env config: Database credentials, no hardcoded values; Multi-arch support: Maintained for AMD64/ARM64 | ✅ |
| V. RBAC | Roles respected: New RELEASE_MANAGER role for release creation/deletion, ADMIN inherits all permissions; Authorization at API: @Secured("ADMIN", "RELEASE_MANAGER"), UI: Role-based rendering; Admin restrictions: Release deletion requires ADMIN or RELEASE_MANAGER | ✅ |
| VI. Schema Evolution | Migrations automated: Hibernate auto-creates requirement_snapshot table with indexes; Schema backward-compatible: Adds new tables/columns without modifying existing schema; Constraints at DB: Foreign keys, unique constraints on version, NOT NULL constraints | ✅ |

**Quality Gates**:
- [x] Tests achieve ≥80% coverage (TDD approach ensures high coverage)
- [x] Linting passes (Kotlin conventions + ESLint)
- [x] Docker builds succeed (AMD64 + ARM64) - uses existing multi-arch setup
- [x] API endpoints respond <200ms (p95) - simple CRUD + snapshot operations
- [x] Security scan shows no critical vulnerabilities - input validation throughout

## Project Structure

### Documentation (this feature)
```
specs/011-i-want-to/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
│   ├── release-api.yaml
│   ├── snapshot-api.yaml
│   └── comparison-api.yaml
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
src/
├── backendng/
│   └── src/main/kotlin/com/secman/
│       ├── domain/
│       │   ├── Release.kt                    # EXISTING - already in codebase
│       │   ├── VersionedEntity.kt            # EXISTING - already in codebase
│       │   ├── Requirement.kt                # EXISTING - extends VersionedEntity
│       │   └── RequirementSnapshot.kt        # NEW - frozen requirement version
│       ├── repository/
│       │   ├── ReleaseRepository.kt          # EXISTING - basic CRUD
│       │   └── RequirementSnapshotRepository.kt  # NEW - snapshot queries
│       ├── service/
│       │   ├── ReleaseService.kt             # NEW - release lifecycle + snapshot creation
│       │   └── RequirementComparisonService.kt   # NEW - diff calculation
│       └── controller/
│           ├── ReleaseController.kt          # NEW - release CRUD API
│           ├── RequirementController.kt      # EXTEND - add releaseId to exports
│           └── ReleaseComparisonController.kt  # NEW - comparison API
│
├── frontend/
│   └── src/
│       ├── components/
│       │   ├── ReleaseManagement.tsx         # EXISTING - UI skeleton exists
│       │   ├── ReleaseSelector.tsx           # NEW - release dropdown
│       │   ├── ReleaseComparison.tsx         # NEW - side-by-side diff view
│       │   └── RequirementManagement.tsx     # EXTEND - integrate release selector
│       └── pages/
│           └── releases.astro                # EXISTING - route exists
│
tests/
├── backend/
│   └── src/test/kotlin/com/secman/
│       ├── controller/
│       │   ├── ReleaseControllerTest.kt      # Contract tests
│       │   └── ReleaseComparisonControllerTest.kt
│       ├── service/
│       │   ├── ReleaseServiceTest.kt         # Unit tests
│       │   └── RequirementComparisonServiceTest.kt
│       └── integration/
│           └── ReleaseWorkflowTest.kt        # Integration tests
│
└── frontend/
    └── tests/e2e/
        ├── release-management.spec.ts        # E2E tests
        └── release-comparison.spec.ts
```

**Structure Decision**: Web application structure with separate backend/ and frontend/ directories. Backend uses Kotlin/Micronaut with domain-driven design (domain, repository, service, controller layers). Frontend uses Astro with React islands (components, pages). This matches the existing secman architecture and maximizes code reuse with existing infrastructure (Release entity, VersionedEntity pattern, export functionality).

## Phase 0: Outline & Research

### Extracted Unknowns from Technical Context
Based on the spec and clarifications, there are **2 remaining low-priority clarifications** that do not block implementation:
1. Should releases be immutable once created, or editable in Draft state? (Deferred - assume immutable for MVP)
2. Should users be able to rollback/restore requirements to previous release versions? (Deferred - out of scope for MVP)

These are documented as future enhancements and do not require research tasks for the initial implementation.

### Research Tasks

**No blocking unknowns identified.** All technical decisions are clear:
- ✅ Snapshot mechanism: Leverage existing VersionedEntity pattern
- ✅ Semantic versioning validation: Standard regex pattern `^\d+\.\d+\.\d+$`
- ✅ Permission model: Extend existing RBAC with RELEASE_MANAGER role
- ✅ Export integration: Add optional `releaseId` query parameter to existing endpoints
- ✅ Comparison algorithm: Standard diff algorithm on requirement field level

### Research Decisions

**Decision**: Use existing VersionedEntity infrastructure with RequirementSnapshot entity
**Rationale**: Requirements already extend VersionedEntity which has `release`, `versionNumber`, and `isCurrent` fields. This pattern was designed for exactly this use case. Creating a new RequirementSnapshot entity that copies all Requirement fields at freeze time provides immutability while maintaining clean separation between current and historical data.
**Alternatives considered**:
- Temporal database pattern - Rejected: Too complex for current scale
- Event sourcing with snapshots - Rejected: Overkill for requirements that change infrequently
- Soft deletes with version flags - Rejected: VersionedEntity already provides this pattern

**Decision**: Enforce semantic versioning with regex validation
**Rationale**: Semantic versioning provides predictable, sortable version identifiers that align with industry standards. The `^\d+\.\d+\.\d+$` regex is simple, unambiguous, and rejects common malformed inputs.
**Alternatives considered**:
- Free-form text versions - Rejected per clarification session (enforced semantic versioning)
- CalVer (calendar versioning) - Rejected: Users prefer semantic meaning over dates
- Auto-incrementing integers - Rejected: Loses semantic meaning

**Decision**: Add RELEASE_MANAGER role to existing RBAC system
**Rationale**: Separating release management permissions from full ADMIN privileges follows principle of least privilege. Compliance managers need release creation/deletion without broader administrative access.
**Alternatives considered**:
- ADMIN-only access - Rejected per clarification session
- User-level access - Rejected: Too permissive for version control
- Fine-grained permission flags - Rejected: Role-based model is simpler

**Decision**: Extend existing export endpoints with optional `releaseId` parameter
**Rationale**: Maintains backward compatibility (existing calls default to current version) while enabling historical exports through optional parameter. Single endpoint serves both use cases without duplication.
**Alternatives considered**:
- Separate endpoints for release exports - Rejected: Code duplication
- Version parameter in URL path - Rejected: Less RESTful, breaks backward compatibility
- Header-based release selection - Rejected: Query parameter is more discoverable

**Output**: All research complete, no NEEDS CLARIFICATION remain

## Phase 1: Design & Contracts

### 1. Data Model Design

#### RequirementSnapshot Entity
```kotlin
@Entity
@Table(name = "requirement_snapshot")
data class RequirementSnapshot(
    @Id @GeneratedValue
    var id: Long? = null,

    // Relationship to release (required)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_id", nullable = false)
    var release: Release,

    // Original requirement ID for traceability
    @Column(name = "original_requirement_id", nullable = false)
    var originalRequirementId: Long,

    // Snapshot of all requirement fields
    @Column(nullable = false)
    var shortreq: String,

    @Column(name = "description", columnDefinition = "TEXT")
    var details: String? = null,

    @Column
    var language: String? = null,

    @Column(columnDefinition = "TEXT")
    var example: String? = null,

    @Column(columnDefinition = "TEXT")
    var motivation: String? = null,

    @Column(columnDefinition = "TEXT")
    var usecase: String? = null,

    @Column(name = "norm", columnDefinition = "TEXT")
    var norm: String? = null,

    @Column(name = "chapter", columnDefinition = "TEXT")
    var chapter: String? = null,

    // Snapshot of relationships (stored as JSON or separate tables)
    @Column(columnDefinition = "TEXT")
    var usecaseIdsSnapshot: String? = null,  // JSON array of IDs

    @Column(columnDefinition = "TEXT")
    var normIdsSnapshot: String? = null,  // JSON array of IDs

    // Metadata
    @Column(name = "snapshot_timestamp", nullable = false)
    var snapshotTimestamp: Instant = Instant.now(),

    // Indexes for query performance
    @Index(name = "idx_snapshot_release", columnList = "release_id"),
    @Index(name = "idx_snapshot_original", columnList = "original_requirement_id")
)
```

#### Release Entity Extensions
No schema changes needed - existing entity already has:
- version (unique), name, description, status (DRAFT/PUBLISHED/ARCHIVED)
- createdBy, createdAt, updatedAt
- Relationship to snapshots via RequirementSnapshot.release

#### User Role Extensions
Add RELEASE_MANAGER to existing role enum/table.

### 2. API Contracts

#### Release Management API
```yaml
# specs/011-i-want-to/contracts/release-api.yaml
paths:
  /api/releases:
    get:
      summary: List all releases
      security: [bearerAuth: []]
      parameters:
        - name: status
          in: query
          schema:
            type: string
            enum: [DRAFT, PUBLISHED, ARCHIVED]
      responses:
        200:
          description: List of releases
          content:
            application/json:
              schema:
                type: array
                items: $ref: '#/components/schemas/ReleaseResponse'

    post:
      summary: Create new release (freezes current requirements)
      security: [bearerAuth: [ADMIN, RELEASE_MANAGER]]
      requestBody:
        required: true
        content:
          application/json:
            schema: $ref: '#/components/schemas/ReleaseCreateRequest'
      responses:
        201:
          description: Release created with frozen snapshots
        400:
          description: Validation error (duplicate version, invalid format)
        403:
          description: Insufficient permissions

  /api/releases/{id}:
    get:
      summary: Get release details
      security: [bearerAuth: []]
      responses:
        200:
          description: Release details
        404:
          description: Release not found

    delete:
      summary: Delete release and associated snapshots
      security: [bearerAuth: [ADMIN, RELEASE_MANAGER]]
      responses:
        204:
          description: Release deleted
        403:
          description: Insufficient permissions
        404:
          description: Release not found

  /api/releases/{id}/requirements:
    get:
      summary: List requirements frozen in this release
      security: [bearerAuth: []]
      responses:
        200:
          description: Frozen requirement snapshots
          content:
            application/json:
              schema:
                type: array
                items: $ref: '#/components/schemas/RequirementSnapshotResponse'

components:
  schemas:
    ReleaseCreateRequest:
      type: object
      required: [version, name]
      properties:
        version:
          type: string
          pattern: '^\d+\.\d+\.\d+$'
          example: "1.0.0"
        name:
          type: string
          maxLength: 100
          example: "Q4 2024 Compliance Review"
        description:
          type: string
          maxLength: 1000

    ReleaseResponse:
      type: object
      properties:
        id: { type: integer, format: int64 }
        version: { type: string }
        name: { type: string }
        description: { type: string, nullable: true }
        status: { type: string, enum: [DRAFT, PUBLISHED, ARCHIVED] }
        requirementCount: { type: integer }
        createdBy: { type: string }
        createdAt: { type: string, format: date-time }
```

#### Export API Extensions
```yaml
# specs/011-i-want-to/contracts/export-api.yaml
paths:
  /api/requirements/export/xlsx:
    get:
      summary: Export requirements to Excel
      security: [bearerAuth: []]
      parameters:
        - name: releaseId
          in: query
          required: false
          schema:
            type: integer
            format: int64
          description: Optional release ID for historical export
      responses:
        200:
          description: Excel file
          content:
            application/vnd.openxmlformats-officedocument.spreadsheetml.sheet:
              schema:
                type: string
                format: binary
```

#### Comparison API
```yaml
# specs/011-i-want-to/contracts/comparison-api.yaml
paths:
  /api/releases/compare:
    get:
      summary: Compare requirements between two releases
      security: [bearerAuth: []]
      parameters:
        - name: fromReleaseId
          in: query
          required: true
          schema: { type: integer, format: int64 }
        - name: toReleaseId
          in: query
          required: true
          schema: { type: integer, format: int64 }
      responses:
        200:
          description: Requirement differences
          content:
            application/json:
              schema: $ref: '#/components/schemas/ComparisonResult'
        400:
          description: Invalid release IDs
        404:
          description: Release not found

components:
  schemas:
    ComparisonResult:
      type: object
      properties:
        fromRelease: $ref: '#/components/schemas/ReleaseInfo'
        toRelease: $ref: '#/components/schemas/ReleaseInfo'
        added: { type: array, items: $ref: '#/components/schemas/RequirementSnapshotResponse' }
        deleted: { type: array, items: $ref: '#/components/schemas/RequirementSnapshotResponse' }
        modified: { type: array, items: $ref: '#/components/schemas/RequirementDiff' }
        unchanged: { type: integer }

    RequirementDiff:
      type: object
      properties:
        id: { type: integer, format: int64 }
        shortreq: { type: string }
        changes: { type: array, items: $ref: '#/components/schemas/FieldChange' }

    FieldChange:
      type: object
      properties:
        fieldName: { type: string }
        oldValue: { type: string, nullable: true }
        newValue: { type: string, nullable: true }
```

### 3. Contract Test Generation

Contract tests will be generated for each endpoint during task execution phase. Tests validate:
- Request/response schemas match OpenAPI specs
- Authentication/authorization rules enforced
- Validation errors return 400 with descriptive messages
- Permission errors return 403
- Not found errors return 404
- Success cases return correct status codes and data structures

### 4. Integration Test Scenarios

From user stories in spec:
1. **Release Creation Workflow**
   - Given: Multiple requirements exist
   - When: Create release with version "1.0.0"
   - Then: All requirements frozen in snapshots, release associated

2. **Export Current vs Historical**
   - Given: Release "1.0.0" exists
   - When: Export without releaseId
   - Then: Current requirements exported
   - When: Export with releaseId=1
   - Then: Frozen requirements from "1.0.0" exported

3. **Requirement Update After Release**
   - Given: Release "1.0.0" with frozen requirement
   - When: Update requirement shortreq
   - Then: Current requirement updated, snapshot unchanged

4. **Deletion Prevention**
   - Given: Requirement frozen in release "1.0.0"
   - When: Attempt to delete requirement
   - Then: Error 400 listing releases containing requirement

5. **Release Comparison**
   - Given: Releases "1.0.0" and "1.1.0"
   - When: Compare via /api/releases/compare?fromReleaseId=1&toReleaseId=2
   - Then: Additions, deletions, modifications highlighted

### 5. Agent Context Update

Will run `.specify/scripts/bash/update-agent-context.sh claude` to update CLAUDE.md with:
- Feature 011: Release-Based Requirement Version Management
- New entities: RequirementSnapshot
- New services: ReleaseService, RequirementComparisonService
- New controllers: ReleaseController, ReleaseComparisonController
- New role: RELEASE_MANAGER
- Recent changes: Release snapshot creation, comparison UI

**Output**:
- data-model.md created with RequirementSnapshot schema
- contracts/ directory with release-api.yaml, export-api.yaml, comparison-api.yaml
- Integration test scenarios documented
- quickstart.md created with user workflow
- CLAUDE.md updated

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
1. Load `.specify/templates/tasks-template.md` as base structure
2. Generate tasks from Phase 1 design artifacts:
   - Each API contract → contract test task [P]
   - RequirementSnapshot entity → JPA entity creation + repository [P]
   - ReleaseService → snapshot creation logic + unit tests
   - RequirementComparisonService → diff algorithm + unit tests
   - ReleaseController → CRUD endpoints + tests [P]
   - Export API extensions → add releaseId parameter logic
   - Frontend ReleaseSelector component [P]
   - Frontend ReleaseComparison component
   - E2E tests for complete workflows
3. Follow TDD order: Test tasks before implementation tasks
4. Respect dependencies: Entities before services, services before controllers

**Ordering Strategy**:
1. **Setup & Schema** (Parallel where possible)
   - Add RELEASE_MANAGER role to User enum/table [P]
   - Create RequirementSnapshot entity [P]
   - Create RequirementSnapshotRepository [P]

2. **Contract Tests** (Parallel - all independent)
   - Write ReleaseController contract tests [P]
   - Write ReleaseComparisonController contract tests [P]
   - Write Export API extension contract tests [P]

3. **Service Layer** (Sequential - services depend on repositories)
   - Write ReleaseService unit tests
   - Implement ReleaseService (snapshot creation logic)
   - Write RequirementComparisonService unit tests
   - Implement RequirementComparisonService (diff algorithm)

4. **Controller Layer** (Parallel after services complete)
   - Implement ReleaseController [P]
   - Implement ReleaseComparisonController [P]
   - Extend RequirementController with releaseId parameter [P]

5. **Frontend Components** (Parallel - independent)
   - Create ReleaseSelector component with tests [P]
   - Create ReleaseComparison component with tests [P]
   - Integrate ReleaseSelector into RequirementManagement [P]
   - Update export UI with release selection [P]

6. **Integration Tests** (Sequential - requires all pieces)
   - Write release creation workflow E2E test
   - Write export current vs historical E2E test
   - Write requirement update after release E2E test
   - Write deletion prevention E2E test
   - Write release comparison E2E test

7. **Documentation & Validation**
   - Update API documentation
   - Run quickstart.md validation
   - Performance validation (<200ms p95)

**Estimated Output**: 35-40 numbered, dependency-ordered tasks in tasks.md

**Parallel Execution Markers**: Tasks marked [P] can execute concurrently

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md from this plan)
**Phase 4**: Implementation (execute tasks.md following TDD and constitutional principles)
**Phase 5**: Validation (run all tests, execute quickstart.md, performance validation <200ms p95)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

**No constitutional violations identified.** This feature aligns with all constitutional principles:
- Security-First: RBAC enforced, input validated
- TDD: Tests written first throughout
- API-First: RESTful contracts defined upfront
- Docker-First: Uses existing containerization
- RBAC: New RELEASE_MANAGER role follows existing patterns
- Schema Evolution: Additive changes via Hibernate auto-migration

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
- [x] Initial Constitution Check: PASS (all 6 principles compliant)
- [x] Post-Design Constitution Check: PASS (no violations introduced)
- [x] All NEEDS CLARIFICATION resolved (2 deferred as future enhancements)
- [x] Complexity deviations documented (none - no violations)

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
