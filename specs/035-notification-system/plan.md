# Implementation Plan: Outdated Asset Notification System

**Branch**: `035-notification-system` | **Date**: 2025-10-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/035-notification-system/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

This feature implements a CLI-triggered email notification system for outdated assets and new vulnerabilities. The system sends two-level reminders to asset owners when their assets become outdated (leveraging the existing OutdatedAssetMaterializedView from Feature 034), allows users to configure preferences for new vulnerability notifications, aggregates multiple assets into a single email per owner, and provides comprehensive audit logging for ADMIN users. The CLI command is executed manually (cron-compatible) and processes notifications in batch mode.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21 (backend), Python 3.11+ (CLI), Astro 5.14 + React 19 (frontend)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, MariaDB 11.4, Apache POI 5.3, JavaMail API (SMTP), Bootstrap 5.3, Axios
**Storage**: MariaDB 11.4 (3 new tables: notification_preference, notification_log, asset_reminder_state)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E), pytest (CLI)
**Target Platform**: Linux server (backend), Web browsers (frontend), Command-line (CLI)
**Project Type**: web (backend + frontend) + CLI extension
**Performance Goals**: Process 10,000 assets in <2 minutes, email aggregation <5 seconds per owner, query response <500ms
**Constraints**: <200ms p95 for API endpoints, 95% email delivery success rate, zero duplicate emails per run, SMTP timeout 30 seconds
**Scale/Scope**: 10,000+ assets, 1,000+ asset owners, 100+ notification runs per month, 3 email templates, 11 API endpoints (5 new, 6 existing)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Principle I: Security-First ✅

- **File Validation**: N/A - no file uploads in this feature
- **Input Sanitization**: ✅ CLI arguments validated, email addresses validated before SMTP send
- **RBAC Enforcement**: ✅ NotificationPreference endpoints require authentication, audit log endpoints require ADMIN role
- **Sensitive Data**: ✅ Email addresses logged only in audit trail (ADMIN-accessible), no credentials in logs
- **Token Storage**: ✅ Reuses existing JWT sessionStorage pattern

**Status**: COMPLIANT

### Principle II: Test-Driven Development (NON-NEGOTIABLE) ✅

- **Contract Tests**: ✅ Required for 5 new API endpoints (notification preferences, audit logs)
- **Integration Tests**: ✅ Required for email sending, OutdatedAssetMaterializedView queries, UserMapping joins
- **Unit Tests**: ✅ Required for NotificationService (aggregation logic), ReminderStateService (level tracking), EmailTemplateService
- **TDD Workflow**: ✅ Red-Green-Refactor enforced
- **Coverage Target**: ✅ ≥80%
- **Test Frameworks**: ✅ JUnit 5 + MockK (backend), Playwright (frontend), pytest (CLI)

**Status**: COMPLIANT

### Principle III: API-First ✅

- **RESTful Design**: ✅ All notification operations exposed via REST APIs
- **OpenAPI Documentation**: ✅ New endpoints documented
- **Backward Compatibility**: ✅ No breaking changes to existing APIs
- **Error Formats**: ✅ Consistent error responses (HTTP 400/403/500)
- **HTTP Status Codes**: ✅ Appropriate codes for all endpoints

**Status**: COMPLIANT

### Principle IV: User-Requested Testing ✅

- **Test Planning**: ✅ Test cases will be generated during `/speckit.tasks` only if user requests testing
- **Optional Test Tasks**: ✅ Test tasks in tasks.md will be marked as OPTIONAL unless user explicitly requests them
- **TDD Framework**: ✅ Testing infrastructure (JUnit, Playwright, pytest) remains in place per Principle II

**Status**: COMPLIANT

### Principle V: Role-Based Access Control (RBAC) ✅

- **@Secured Annotations**: ✅ Required on all new endpoints
  - `/api/notification-preferences/*` → IS_AUTHENTICATED (user manages own preferences)
  - `/api/notification-logs/*` → ADMIN role
- **Frontend Role Checks**: ✅ Audit log UI only visible to ADMIN
- **Workgroup Filtering**: ✅ Notification logic respects workgroup assignments (users only notified for their own assets)
- **Service Layer Checks**: ✅ Authorization logic in NotificationService (verify user owns assets before sending)

**Status**: COMPLIANT

### Principle VI: Schema Evolution ✅

- **Hibernate Auto-Migration**: ✅ JPA entities with annotations for 3 new tables
- **Database Constraints**: ✅ Foreign keys (userId, assetId), unique constraints, NOT NULL constraints
- **Indexes**: ✅ Required on assetId (asset_reminder_state), userId (notification_preference), sentAt (notification_log)
- **Migration Testing**: ✅ Development environment testing before production
- **No Data Loss**: ✅ Only new tables, no modifications to existing schema

**Status**: COMPLIANT

**Overall Gate**: ✅ PASSED - No violations, all principles satisfied

## Project Structure

### Documentation (this feature)

```text
specs/035-notification-system/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── notification-preferences-api.yaml
│   ├── notification-logs-api.yaml
│   └── email-templates.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
# Backend (Kotlin/Micronaut)
src/backendng/src/main/kotlin/com/secman/
├── domain/
│   ├── NotificationPreference.kt         # NEW: User notification settings
│   ├── NotificationLog.kt                # NEW: Audit trail
│   ├── AssetReminderState.kt             # NEW: Reminder tracking
│   └── NotificationType.kt               # NEW: Enum (OUTDATED_L1, OUTDATED_L2, NEW_VULNERABILITY)
├── repository/
│   ├── NotificationPreferenceRepository.kt  # NEW
│   ├── NotificationLogRepository.kt         # NEW
│   └── AssetReminderStateRepository.kt      # NEW
├── service/
│   ├── NotificationService.kt            # NEW: Core notification orchestration
│   ├── EmailTemplateService.kt           # NEW: HTML email generation
│   ├── ReminderStateService.kt           # NEW: Track reminder levels
│   └── NotificationLogService.kt         # NEW: Audit logging
├── controller/
│   ├── NotificationPreferenceController.kt  # NEW: 3 endpoints
│   └── NotificationLogController.kt          # NEW: 2 endpoints
└── config/
    └── EmailConfig.kt                    # NEW: SMTP configuration

src/backendng/src/test/kotlin/com/secman/
├── contract/
│   ├── NotificationPreferenceContractTest.kt
│   └── NotificationLogContractTest.kt
├── integration/
│   ├── NotificationServiceIntegrationTest.kt
│   └── EmailSendingIntegrationTest.kt
└── service/
    ├── NotificationServiceTest.kt
    ├── EmailTemplateServiceTest.kt
    └── ReminderStateServiceTest.kt

# Frontend (Astro/React)
src/frontend/src/
├── components/
│   ├── NotificationPreferences.tsx       # NEW: User preference toggle
│   └── NotificationLogTable.tsx          # NEW: ADMIN audit log viewer
├── pages/
│   ├── notification-preferences.astro    # NEW: Preferences page
│   └── admin/
│       └── notification-logs.astro       # NEW: ADMIN audit page
└── services/
    └── notificationService.ts            # NEW: Axios API calls

src/frontend/tests/
└── notification-preferences.spec.ts      # NEW: Playwright E2E

# CLI (Gradle task in existing CLI module)
src/cli/src/main/kotlin/com/secman/cli/
├── commands/
│   └── SendNotificationsCommand.kt       # NEW: CLI command implementation
└── services/
    └── NotificationCliService.kt         # NEW: CLI-specific logic (dry-run, progress)

src/cli/src/test/kotlin/com/secman/cli/
└── commands/
    └── SendNotificationsCommandTest.kt   # NEW: CLI tests

# Email Templates (resources)
src/backendng/src/main/resources/
└── email-templates/
    ├── outdated-reminder-level1.html     # NEW: Professional tone
    ├── outdated-reminder-level2.html     # NEW: Urgent tone
    ├── new-vulnerabilities.html          # NEW: Informational tone
    └── *.txt                             # NEW: Plain-text fallbacks
```

**Structure Decision**: This is a web application (backend + frontend) with CLI extension. The backend follows the standard Micronaut layered architecture (Domain → Repository → Service → Controller). The frontend uses Astro with React islands for the preferences UI and ADMIN audit log viewer. The CLI is integrated as a new Gradle task within the existing `src/cli/` module. Email templates are stored as resources for maintainability and localization support.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations detected. This section is not applicable.
