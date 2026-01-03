# Implementation Plan: AI-Powered Norm Mapping for Requirements

**Branch**: `058-ai-norm-mapping` | **Date**: 2026-01-02 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/058-ai-norm-mapping/spec.md`

## Summary

Implement AI-powered automated mapping of security requirements to ISO 27001 and IEC 62443 standards using OpenRouter with Claude Opus 4.5 model. The feature enables users to click a "Missing mapping" button to analyze requirements without existing norm mappings, receive AI suggestions with confidence levels, review and select suggestions via a modal dialog, and persist approved mappings to the database. New norms are auto-created if they don't exist in the database.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21 (backend), TypeScript/React 19 (frontend)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, Axios, Bootstrap 5.3
**Storage**: MariaDB 11.4 (existing `requirement`, `norm`, `requirement_norm` tables)
**Testing**: JUnit 5, Mockk (backend), Vitest (frontend) - prepared when requested
**Target Platform**: Web application (server-side + browser)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Return AI suggestions within 60 seconds for up to 50 requirements
**Constraints**: Single batch API call to OpenRouter, ADMIN/REQ/SECCHAMPION role restriction
**Scale/Scope**: ~168 requirements, ~65 unmapped currently

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | API key stored encrypted in TranslationConfig, RBAC enforced via @Secured |
| III. API-First | PASS | New REST endpoints follow existing patterns, OpenAPI docs will be updated |
| IV. User-Requested Testing | PASS | Test tasks only added when explicitly requested |
| V. RBAC | PASS | Feature restricted to ADMIN, REQ, SECCHAMPION roles (FR-014) |
| VI. Schema Evolution | PASS | Uses existing schema, only adds norms dynamically (no DDL changes) |

**Gate Result**: PASS - No violations. Ready for Phase 0.

## Project Structure

### Documentation (this feature)

```text
specs/058-ai-norm-mapping/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── controller/
│   │   └── NormMappingController.kt    # NEW: API endpoints for AI mapping
│   ├── service/
│   │   └── NormMappingService.kt       # NEW: AI query and mapping logic
│   ├── dto/
│   │   └── NormMappingDto.kt           # NEW: Request/response DTOs
│   ├── domain/
│   │   ├── Requirement.kt              # EXISTING: Has norms relationship
│   │   └── Norm.kt                     # EXISTING: Standard reference entity
│   └── repository/
│       ├── RequirementRepository.kt    # EXISTING: May add query method
│       └── NormRepository.kt           # EXISTING: findByName already exists
└── src/main/resources/
    └── application.yml                 # EXISTING: No changes needed

src/frontend/
├── src/
│   ├── components/
│   │   └── RequirementManagement.tsx   # MODIFY: Enable "Missing mapping" button, add modal logic
│   └── services/
│       └── normMappingService.ts       # NEW: API client for norm mapping
└── tests/                              # Tests added when requested
```

**Structure Decision**: Web application pattern with separate backend/frontend. New files added to existing service/controller structure following established patterns.

## Complexity Tracking

> No violations - table not needed.
