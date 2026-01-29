# Implementation Plan: Enhanced Admin Summary Email

**Branch**: `069-enhanced-admin-summary` | **Date**: 2026-01-28 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/069-enhanced-admin-summary/spec.md`

## Summary

Extend the admin summary email (Feature 070) to include:
1. A clickable link to the vulnerability statistics page using the configured backend base URL
2. Top 10 most affected products (name + vulnerability count)
3. Top 10 most affected servers (name + vulnerability count)

Changes are backend-only: extend `SystemStatistics` data class, update `AdminSummaryService` to gather top-10 data from `VulnerabilityStatisticsService`, update both HTML and plain-text email templates, and update CLI output for dry-run preview.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, PicoCLI (CLI)
**Storage**: MariaDB 11.4 (read-only queries for statistics)
**Testing**: JUnit 5, Mockk (user-requested only per constitution)
**Target Platform**: Linux server (backend), CLI tool
**Project Type**: Web application (backend + CLI, no frontend changes)
**Performance Goals**: Email generation should complete within existing timeouts (30s read timeout)
**Constraints**: Top-10 queries must use existing repository methods; no new database tables or schema changes
**Scale/Scope**: Extends 3 existing files (service, 2 templates), updates 1 CLI service; ~150 lines of changes

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | No user input handling; read-only statistics; email sent to ADMIN-only recipients |
| III. API-First | PASS | No new API endpoints; internal service method extension only |
| IV. User-Requested Testing | PASS | No test tasks included unless user requests them |
| V. RBAC | PASS | Admin summary uses system-wide unfiltered statistics (all recipients are ADMIN) |
| VI. Schema Evolution | PASS | No database schema changes; read-only queries on existing tables |

No violations. All gates pass.

## Project Structure

### Documentation (this feature)

```text
specs/069-enhanced-admin-summary/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (N/A - no new APIs)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/backendng/src/main/kotlin/com/secman/
├── service/
│   └── AdminSummaryService.kt          # Extend SystemStatistics, add top-10 data gathering
├── config/
│   └── AppConfig.kt                    # Already has backend.baseUrl (no changes needed)
└── resources/email-templates/
    ├── admin-summary.html              # Add link button, top-10 tables
    └── admin-summary.txt               # Add link URL, top-10 ASCII tables

src/cli/src/main/kotlin/com/secman/cli/
├── service/
│   └── AdminSummaryCliService.kt       # Update to pass through new statistics
└── commands/
    └── SendAdminSummaryCommand.kt      # Update dry-run output to show top-10 data
```

**Structure Decision**: Backend-only changes in existing files. No new files needed. The feature extends the existing admin summary email infrastructure (Feature 070).

## Complexity Tracking

No violations to justify.
