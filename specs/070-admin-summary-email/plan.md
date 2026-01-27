# Implementation Plan: Admin Summary Email

**Branch**: `070-admin-summary-email` | **Date**: 2026-01-27 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/070-admin-summary-email/spec.md`

## Summary

Implement a CLI command `send-admin-summary` that sends a summary email to all ADMIN users containing system statistics (total users, vulnerabilities, assets). The feature reuses the existing email infrastructure from feature 035 (notification system), adds a new email template, and logs each execution to the database for audit purposes.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25
**Primary Dependencies**: Micronaut 4.10, PicoCLI (CLI framework), Jakarta Mail (email)
**Storage**: MariaDB 11.4 (existing database)
**Testing**: User-requested only (per Constitution Principle IV)
**Target Platform**: Linux server (CLI command executed via cron or manually)
**Project Type**: CLI extension to existing web application
**Performance Goals**: Complete within 30 seconds for up to 100 ADMIN users
**Constraints**: Must reuse existing EmailService infrastructure
**Scale/Scope**: Small feature - 1 CLI command, 1 service class, 1 entity, 2 email templates

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | ✅ PASS | No user input beyond CLI flags; uses existing auth-free CLI execution model |
| III. API-First | ✅ PASS | CLI command only; no new REST API endpoints needed |
| IV. User-Requested Testing | ✅ PASS | Tests prepared only when requested |
| V. RBAC | ✅ PASS | No new endpoints; CLI runs with system privileges |
| VI. Schema Evolution | ✅ PASS | New AdminSummaryLog entity uses Hibernate auto-migration |

**All gates pass. Proceeding to Phase 0.**

### Post-Phase 1 Re-check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | ✅ PASS | No injection vectors; email addresses from database only |
| III. API-First | ✅ PASS | No REST endpoints added |
| IV. User-Requested Testing | ✅ PASS | No test tasks created |
| V. RBAC | ✅ PASS | Uses existing User.Role.ADMIN for recipient filtering |
| VI. Schema Evolution | ✅ PASS | AdminSummaryLog entity follows JPA/Hibernate patterns |

**All gates pass. Phase 1 design complete.**

## Project Structure

### Documentation (this feature)

```text
specs/070-admin-summary-email/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # N/A - no REST API
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
src/cli/src/main/kotlin/com/secman/cli/
├── commands/
│   └── SendAdminSummaryCommand.kt       # NEW: CLI command
└── service/
    └── AdminSummaryCliService.kt        # NEW: CLI service

src/backendng/src/main/kotlin/com/secman/
├── domain/
│   └── AdminSummaryLog.kt               # NEW: Execution log entity
├── repository/
│   └── AdminSummaryLogRepository.kt     # NEW: Repository
└── service/
    └── AdminSummaryService.kt           # NEW: Backend service

src/backendng/src/main/resources/email-templates/
├── admin-summary.html                   # NEW: HTML email template
└── admin-summary.txt                    # NEW: Plain text template
```

**Structure Decision**: Follows existing CLI pattern (SendNotificationsCommand → NotificationCliService → backend services). Backend service handles statistics gathering and email sending; CLI service coordinates the flow.

## Complexity Tracking

> No Constitution violations - no complexity tracking required.
