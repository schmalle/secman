# Implementation Plan: CrowdStrike Asset Auto-Creation

**Branch**: `030-crowdstrike-asset-auto-create` | **Date**: 2025-10-19 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/030-crowdstrike-asset-auto-create/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Extend the "Save to Database" button in `/vulnerabilities/system` to automatically create assets when saving CrowdStrike vulnerability data. When a queried hostname doesn't exist in the database, the system creates a new asset with type "Server", assigns it to the current user as owner, leaves workgroups empty, and links all discovered vulnerabilities. If the asset already exists, vulnerabilities are added and the IP address is updated if CrowdStrike provides a different value. Invalid vulnerability data is skipped with validation reporting. The feature maintains scan history by creating new vulnerability records for each scan date while preventing exact duplicates.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, React 19, Bootstrap 5.3, Axios
**Storage**: MariaDB 11.4 via Hibernate JPA with auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Web application (Linux/macOS server, modern browsers)
**Project Type**: web (frontend + backend)
**Performance Goals**: <5 seconds save time for 100 vulnerabilities (SC-001), <1 second user feedback (SC-006)
**Constraints**: 99.9% transactional integrity (SC-005), 100% user attribution (SC-002), zero duplicate assets (SC-003)
**Scale/Scope**: Extends existing CrowdStrike integration, existing Asset/Vulnerability entities, single page modification, 2-3 new service methods

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Principle I: Security-First ✅

- **File uploads**: N/A (no new file uploads)
- **Input sanitization**: ✅ FR-017 requires validation of CVE ID format, severity values, numeric fields
- **RBAC enforcement**: ✅ Endpoint requires authentication, no role restrictions (all authenticated users)
- **Sensitive data**: ✅ FR-015 specifies role-based error messages (user-friendly for users, technical for admins)
- **Authentication tokens**: ✅ Uses existing JWT authentication system

**Status**: PASS

### Principle II: Test-Driven Development ✅

- **Contract tests**: Required for new/modified API endpoints (save operation)
- **Integration tests**: Required for asset creation + vulnerability save transaction flow
- **Unit tests**: Required for validation logic, IP update logic, deduplication logic
- **TDD cycle**: Red-Green-Refactor enforced
- **Coverage target**: ≥80%
- **Backend testing**: JUnit 5 + MockK
- **Frontend testing**: Playwright E2E

**Status**: PASS (tests will be written before implementation per TDD)

### Principle III: API-First ✅

- **RESTful design**: Extends existing POST endpoint or creates new save endpoint
- **Error formats**: ✅ FR-015 specifies consistent error message format
- **HTTP status codes**: Required (200 success, 400 validation, 401 auth, 500 server error)
- **Backward compatibility**: No breaking changes to existing API

**Status**: PASS

### Principle IV: User-Requested Testing ✅

- **Testing scope**: User has not explicitly requested test planning
- **Test task marking**: Test tasks will be marked as OPTIONAL in tasks.md
- **TDD still applies**: Tests will be written first per Principle II, but detailed test planning deferred

**Status**: PASS

### Principle V: Role-Based Access Control ✅

- **@Secured annotations**: Required on endpoint (IS_AUTHENTICATED)
- **Role requirements**: No specific role required (all authenticated users can save)
- **Frontend role checks**: N/A (button available to all authenticated users)
- **Workgroup filtering**: ✅ FR-004 specifies no workgroups assigned to auto-created assets
- **Service layer checks**: User identification from authentication context required

**Status**: PASS

### Principle VI: Schema Evolution ✅

- **Hibernate auto-migration**: Uses existing Asset and Vulnerability entities (no schema changes)
- **Database constraints**: Existing constraints support requirements:
  - Asset: unique hostname (case-insensitive) via FR-006
  - Vulnerability: unique (asset + CVE ID + scan date) via FR-011
- **Foreign key relationships**: Existing Vulnerability → Asset relationship used
- **Indexes**: Existing indexes on asset_id, scan_timestamp support queries
- **No data loss**: N/A (additive only, no schema modifications)

**Status**: PASS

**Overall Gate Status**: ✅ PASS - All constitutional principles satisfied

## Project Structure

### Documentation (this feature)

```
specs/030-crowdstrike-asset-auto-create/
├── spec.md              # Feature specification (completed)
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (next)
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (API contracts)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   ├── Asset.kt                    # Existing entity (no changes)
│   │   └── Vulnerability.kt            # Existing entity (no changes)
│   ├── repository/
│   │   ├── AssetRepository.kt          # Existing (may add findByNameIgnoreCase)
│   │   └── VulnerabilityRepository.kt  # Existing (used for saves)
│   ├── service/
│   │   └── CrowdStrikeVulnerabilitySaveService.kt  # NEW service
│   └── controller/
│       └── CrowdStrikeController.kt    # NEW or modify existing
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── CrowdStrikeSaveContractTest.kt  # NEW contract tests
    ├── integration/
    │   └── CrowdStrikeSaveIntegrationTest.kt  # NEW integration tests
    └── service/
        └── CrowdStrikeVulnerabilitySaveServiceTest.kt  # NEW unit tests

src/frontend/
├── src/
│   ├── components/
│   │   └── CrowdStrikeVulnerabilityLookup.tsx  # MODIFY (add save handler)
│   └── services/
│       └── crowdstrikeService.ts       # NEW or modify existing
└── tests/e2e/
    └── crowdstrike-save.spec.ts        # NEW E2E tests
```

**Structure Decision**: Web application structure with backend (Kotlin/Micronaut) and frontend (Astro/React). Follows existing pattern where backend services handle business logic and transactions, frontend components handle UI and API calls. No new entities required - extends existing Asset and Vulnerability entities via service layer.

## Complexity Tracking

*No constitutional violations - table not required.*
