# Implementation Plan: Test Suite for Secman

**Branch**: `056-test-suite` | **Date**: 2025-12-29 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/056-test-suite/spec.md`

## Summary

Implement a comprehensive test suite for secman covering CLI and web API functionality. The test suite uses JUnit 5 with Mockk for unit tests and Testcontainers with MariaDB for integration tests. Primary focus is testing the CLI add-vulnerability command (system-a, HIGH criticality, 60 days open) and verifying the web API correctly exposes vulnerability data.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, Picocli 4.7, JUnit 5, Mockk, Testcontainers
**Storage**: MariaDB 11.4 (via Testcontainers for tests)
**Testing**: JUnit 5 + Mockk (unit), Micronaut Test + Testcontainers (integration)
**Target Platform**: JVM 21 (Linux/macOS/Windows)
**Project Type**: Web application (backend + CLI + frontend)
**Performance Goals**: Integration tests complete within 2 minutes
**Constraints**: Docker required for Testcontainers
**Scale/Scope**: ~20 test cases covering 3 user stories + 5 edge cases

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | ✅ PASS | Tests verify RBAC enforcement (FR-007), authentication (FR-006), and role-based access control |
| III. API-First | ✅ PASS | Tests verify REST API responses, HTTP status codes, and response formats (FR-005) |
| IV. User-Requested Testing | ✅ PASS | User explicitly requested test suite implementation - this feature IS the test request |
| V. RBAC | ✅ PASS | Tests verify @Secured annotations work correctly for ADMIN, VULN, USER roles |
| VI. Schema Evolution | ✅ PASS | Tests use Testcontainers with production-like MariaDB, Hibernate auto-migration applies |

**Gate Status**: ✅ ALL GATES PASSED - Proceed to Phase 0

## Project Structure

### Documentation (this feature)

```text
specs/056-test-suite/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (test contracts)
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── controller/      # VulnerabilityManagementController, AuthController
│   ├── service/         # VulnerabilityService
│   ├── repository/      # VulnerabilityRepository, AssetRepository
│   └── domain/          # Asset, Vulnerability, User entities
└── src/test/kotlin/com/secman/
    ├── service/         # VulnerabilityServiceTest (unit tests with Mockk)
    ├── controller/      # AuthControllerTest, VulnerabilityControllerTest
    ├── integration/     # VulnerabilityIntegrationTest (Testcontainers)
    └── testutil/        # TestDataFactory, BaseIntegrationTest

src/cli/
├── src/main/kotlin/com/secman/cli/
│   └── commands/        # AddVulnerabilityCommand
└── src/test/kotlin/com/secman/cli/
    └── commands/        # AddVulnerabilityCommandTest (parameter validation)

src/backendng/src/test/resources/
└── application-test.yml # Test configuration
```

**Structure Decision**: Existing web application structure with new test directories under `src/test/kotlin/`. Tests organized by layer (service, controller, integration) following standard Kotlin/Micronaut patterns.

## Complexity Tracking

> No constitution violations - no complexity justification needed.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| N/A | N/A | N/A |
