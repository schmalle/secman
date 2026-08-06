# Implementation Plan: Requirement ID.Revision Versioning

**Branch**: `066-requirement-versioning` | **Date**: 2026-01-23 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/066-requirement-versioning/spec.md`

## Summary

Extend the existing requirements management system to add explicit versioning with a user-facing "ID.Revision" format. This involves:
1. Adding `internalId` field (e.g., "REQ-001") to Requirement entity - unique, never changes, never reused
2. Using existing `versionNumber` from VersionedEntity as the revision counter
3. Adding ID.Revision to Excel/Word exports and release snapshots
4. Enhancing diff export to show revision changes between releases

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, Apache POI 5.3 (Excel/Word exports)
**Storage**: MariaDB 11.4 (existing `requirement`, `requirement_snapshot`, `releases` tables)
**Testing**: JUnit 5 with Mockk (user-requested only per constitution)
**Target Platform**: Linux server (web application)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Standard CRUD operations, no high-throughput requirements
**Constraints**: Maintain backward compatibility with existing API responses
**Scale/Scope**: Existing system scale (~100s of requirements, ~10s of releases)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | No new user input vectors; existing RBAC applies (ADMIN, REQ, SECCHAMPION for requirements) |
| III. API-First | PASS | Extends existing REST endpoints; backward compatible (adds fields, doesn't remove) |
| IV. User-Requested Testing | PASS | No test planning in this plan per constitution; tests only when requested |
| V. RBAC | PASS | Uses existing @Secured annotations; no new access patterns |
| VI. Schema Evolution | PASS | Adds columns via Hibernate auto-migration; Flyway script required for `internalId` and `requirement_id_sequence` table |

**Gate Result**: PASS - All principles satisfied.

## Project Structure

### Documentation (this feature)

```text
specs/066-requirement-versioning/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── api-changes.yaml # OpenAPI delta
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   ├── Requirement.kt           # Add internalId field
│   │   ├── RequirementSnapshot.kt   # Add internalId, revision fields
│   │   └── RequirementIdSequence.kt # New entity for ID generation
│   ├── repository/
│   │   ├── RequirementRepository.kt
│   │   └── RequirementIdSequenceRepository.kt # New repository
│   ├── service/
│   │   ├── RequirementService.kt    # ID assignment logic
│   │   └── RequirementIdService.kt  # New service for ID generation
│   └── controller/
│       └── RequirementController.kt # Update responses, export methods
└── src/main/resources/
    └── db/migration/
        └── V066__add_requirement_internal_id.sql # Flyway migration

src/frontend/
├── src/components/
│   ├── RequirementManagement.tsx    # Display ID.Revision
│   ├── ReleaseComparison.tsx        # Show revision changes in diff
│   └── ReleaseManagement.tsx        # Display ID.Revision in snapshots
└── src/utils/
    └── comparisonExport.ts          # Update diff export columns
```

**Structure Decision**: Web application structure following existing patterns. Backend changes focus on domain entities, service layer for ID generation, and controller updates for exports.

## Complexity Tracking

> No constitutional violations requiring justification.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| N/A | N/A | N/A |
