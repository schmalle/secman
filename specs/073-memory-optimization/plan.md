# Implementation Plan: Memory and Heap Space Optimization

**Branch**: `073-memory-optimization` | **Date**: 2026-02-03 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/073-memory-optimization/spec.md`

## Summary

Reduce secman's memory/heap requirements through stability-first optimizations targeting the highest-impact areas: vulnerability queries with in-memory filtering, export data accumulation, EAGER entity loading, redundant DTO fields, and multiple access control queries. The approach prioritizes stability via feature flags for rollback capability and JVM metrics for monitoring.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, Apache POI 5.3 (SXSSFWorkbook)
**Storage**: MariaDB 11.4 (HikariCP connection pool, max 20 connections)
**Testing**: JUnit 5, Mockk (per constitution - testing only when requested)
**Target Platform**: Linux server (JVM-based backend)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: 50MB max memory overhead for queries, 100MB max for exports
**Constraints**: Backward API compatibility, stability-first (feature flags), existing caching infrastructure
**Scale/Scope**: 300K-500K vulnerabilities, 10+ concurrent users, 5-15 second export times

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | ✅ PASS | No new security surfaces; optimizations are internal refactoring |
| III. API-First | ✅ PASS | FR-010/FR-011 change DTO structure but maintain backward compatibility via flat fields |
| IV. User-Requested Testing | ✅ PASS | No test planning included; tests only when requested |
| V. RBAC | ✅ PASS | Access control logic unchanged; queries unified but same permissions |
| VI. Schema Evolution | ✅ PASS | No database schema changes required |

**Gate Result**: PASS - All principles satisfied

## Project Structure

### Documentation (this feature)

```text
specs/073-memory-optimization/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (minimal - internal refactoring)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── config/
│   │   └── MemoryOptimizationConfig.kt      # NEW: Feature flags for LAZY/EAGER toggle
│   ├── domain/
│   │   ├── Asset.kt                         # MODIFY: workgroups LAZY fetch
│   │   └── User.kt                          # MODIFY: workgroups LAZY fetch
│   ├── dto/
│   │   └── VulnerabilityWithExceptionDto.kt # MODIFY: Remove nested Asset object
│   ├── repository/
│   │   ├── AssetRepository.kt               # MODIFY: Add unified access control query
│   │   └── VulnerabilityRepository.kt       # MODIFY: Add SQL-level exception filtering
│   └── service/
│       ├── VulnerabilityService.kt          # MODIFY: SQL filtering, batched cleanup
│       ├── VulnerabilityExportService.kt    # MODIFY: Streaming export
│       ├── AssetFilterService.kt            # MODIFY: Unified query
│       └── HealthService.kt                 # NEW: JVM metrics endpoint
└── src/main/resources/
    └── application.yml                       # MODIFY: Feature flag config

src/frontend/
└── src/services/
    └── vulnerabilityManagementService.ts    # MODIFY: Handle DTO changes (if needed)
```

**Structure Decision**: Existing web application structure. Changes are refactoring within existing files, with minimal new files (config, health endpoint).

## Constitution Check (Post-Design)

*Re-evaluation after Phase 1 design completion*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | ✅ PASS | No new attack surfaces. Memory endpoint is read-only. RBAC unchanged. |
| III. API-First | ✅ PASS | DTO changes documented in contracts/api-changes.md. Flat fields maintain compatibility. |
| IV. User-Requested Testing | ✅ PASS | No test tasks included. Tests to be written only when requested. |
| V. RBAC | ✅ PASS | Unified query produces identical access results. Feature-flagged for rollback. |
| VI. Schema Evolution | ✅ PASS | No DDL changes. Entity annotations modified but schema unchanged. |

**Post-Design Gate Result**: PASS - All principles satisfied after design phase

## Complexity Tracking

> No constitution violations requiring justification. All changes are internal optimizations within existing architecture.

| Aspect | Approach | Rationale |
|--------|----------|-----------|
| Feature flags | Runtime config via application.yml | Enables rollback without redeployment per stability-first principle |
| DTO changes | Flat fields only | Already present (assetId, assetName, assetIp); removes redundant nested object |
| Query unification | UNION DISTINCT in repository | Standard SQL pattern, no schema changes |

## Generated Artifacts

| Artifact | Path | Purpose |
|----------|------|---------|
| Research | [research.md](./research.md) | Technical decisions and patterns |
| Data Model | [data-model.md](./data-model.md) | Entity and DTO modifications |
| API Contracts | [contracts/api-changes.md](./contracts/api-changes.md) | API changes documentation |
| Quickstart | [quickstart.md](./quickstart.md) | Implementation guide |

## Next Steps

Run `/speckit.tasks` to generate actionable implementation tasks.
