# Implementation Plan: CLI manage-user-mappings --send-email Option

**Branch**: `085-cli-mappings-email` | **Date**: 2026-04-08 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/085-cli-mappings-email/spec.md`

## Summary

Add a `--send-email` flag (plus a companion `--verbose` flag) to the existing `manage-user-mappings list` subcommand. When set, the CLI keeps its current console statistics output unchanged and additionally calls a new backend endpoint that:

1. Re-runs the user-mapping query with the same filters server-side,
2. Computes aggregate + per-user statistics,
3. Renders plain-text and HTML email templates (new `user-mapping-statistics.*` templates),
4. Dispatches the email to every ADMIN or REPORT user with a valid address (reusing `AdminSummaryService.getAdminRecipients()` — already exactly what we need),
5. Writes an audit row to a new `user_mapping_statistics_log` table (mirroring `AdminSummaryLog`),
6. Returns a structured result DTO the CLI prints as a per-recipient summary and maps to distinct non-zero exit codes.

Authorization is enforced server-side (`@Secured("ADMIN")` on the controller). `--dry-run` prints intended recipients without dispatching. Documentation sweep updates 10 surfaces (README, CLAUDE.md, INSTALL.md, docs/CLI.md, docs/ARCHITECTURE.md, USER_MAPPING_COMMANDS.md, `scripts/secmancli`, and Picocli `@Command` annotations on `ManageUserMappingsCommand` + `ListCommand`).

## Technical Context

**Language/Version**: Kotlin 2.3.20 / Java 21 (backend + CLI), bundled via Gradle 9.4.1
**Primary Dependencies**:
- Backend: Micronaut 4.10, Hibernate JPA, Jakarta Mail (via `EmailService`), SLF4J
- CLI: Picocli 4.7.7, `CliHttpClient` (existing, reused)
- Templates: `/email-templates/*.html|.txt` with `${var}` interpolation (same substitution scheme as `admin-summary.html/.txt`)
**Storage**: MariaDB 11.4 — one new table `user_mapping_statistics_log` created via Hibernate auto-migration + a Flyway script (per Constitution VI)
**Testing**: Not requested by user — per Constitution Principle IV (User-Requested Testing), no test planning or preparation in this phase. Existing infrastructure (JUnit 5, Mockk, Playwright) remains available if testing is later requested.
**Target Platform**: Linux server (backend), macOS/Linux CLI host
**Project Type**: Web-service + CLI (secman's existing dual-layer structure)
**Performance Goals**: SC-002 — 100% delivery within 60s for N recipients (N up to ~50 expected admin set size); dominated by SMTP RTT, not code
**Constraints**: Additive — must not change default `manage-user-mappings list` output (SC-005, FR-014); authorization enforced server-side; audit log written on every invocation including dry-run and zero-recipient cases
**Scale/Scope**: Backend endpoint expected to be called at most a few times per day from cron; per-invocation recipient set ≤ ~50 users; per-user mapping payload ≤ a few hundred rows in the largest realistic case. Streaming/pagination not required.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Compliance | Notes |
|---|---|---|
| **I. Security-First** | ✅ Pass | Input validation at controller boundary (filter params); server-side `@Secured("ADMIN")` gate (FR-008); no sensitive data logged (email addresses are logged but already logged by existing `AdminSummaryService`, consistent with existing policy); recipient email addresses treated as PII but are the necessary output; audit log recorded for every send. Security review will be done before merging. |
| **III. API-First** | ✅ Pass | New REST endpoints under `/api/cli/user-mappings/*` follow existing `CliController` patterns; consistent JSON error responses; backward-compatible — additive only, no existing endpoint signatures changed. OpenAPI annotations match existing style. |
| **IV. User-Requested Testing** | ✅ Pass | User did not request tests. Plan contains NO test tasks. Testing remains possible via existing infra if later requested. |
| **V. RBAC** | ✅ Pass | `@Secured("ADMIN")` on controller; invoker must hold ADMIN (FR-008). Recipient role filtering (ADMIN+REPORT) is a separate authorization concern enforced by reusing `AdminSummaryService.getAdminRecipients()` which already implements this correctly. |
| **VI. Schema Evolution** | ✅ Pass | One new table `user_mapping_statistics_log`, defined via JPA annotations on new entity + explicit Flyway migration script. Constraints (NOT NULL, indexes on `executed_at`) defined in entity. No data migration required (new table). |

**Verdict**: No violations. No entries in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/085-cli-mappings-email/
├── spec.md              # Feature specification (created by /speckit.specify + /speckit.clarify)
├── plan.md              # THIS FILE (/speckit.plan)
├── research.md          # Phase 0 output — open decisions resolved
├── data-model.md        # Phase 1 output — new entity + DTOs
├── quickstart.md        # Phase 1 output — manual verification walkthrough
├── contracts/
│   └── cli-user-mapping-email.md  # Phase 1 — contract for new REST endpoints + CLI flag shape
├── checklists/
│   └── requirements.md  # Created by /speckit.specify
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
src/
├── backendng/src/main/
│   ├── kotlin/com/secman/
│   │   ├── controller/
│   │   │   └── CliController.kt                       # EXTEND: add endpoints + DTOs
│   │   ├── service/
│   │   │   └── UserMappingStatisticsService.kt        # NEW: stats computation + email dispatch
│   │   ├── domain/
│   │   │   └── UserMappingStatisticsLog.kt            # NEW: audit log entity
│   │   └── repository/
│   │       └── UserMappingStatisticsLogRepository.kt  # NEW
│   └── resources/
│       ├── email-templates/
│       │   ├── user-mapping-statistics.html           # NEW
│       │   └── user-mapping-statistics.txt            # NEW
│       └── db/migration/
│           └── V[next]__create_user_mapping_statistics_log.sql  # NEW Flyway script
│
├── cli/src/main/
│   ├── kotlin/com/secman/cli/commands/
│   │   ├── ListCommand.kt                             # EXTEND: add --send-email + --verbose flags, call new endpoint
│   │   └── ManageUserMappingsCommand.kt               # EXTEND: update top-level @Command description
│   └── resources/cli-docs/
│       └── USER_MAPPING_COMMANDS.md                   # EXTEND: document --send-email
│
└── docs/
    ├── CLI.md                                         # EXTEND
    └── ARCHITECTURE.md                                # EXTEND (if it mentions manage-user-mappings)

scripts/
└── secmancli                                          # EXTEND: help text shows --send-email

CLAUDE.md                                              # EXTEND: CLI commands table
README.md                                              # EXTEND: features list (if it mentions CLI)
INSTALL.md                                             # EXTEND (if it mentions manage-user-mappings)
```

**Structure Decision**: Reuse secman's established backend + CLI dual-layer structure. Backend owns all business logic (stats computation, template rendering, email dispatch, audit logging). CLI is a thin proxy that (a) prints current console output unchanged and (b) posts to the new backend endpoint when `--send-email` is set. This matches the `send-admin-summary` precedent exactly and preserves the Constitution's API-First principle.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

(No entries — Constitution Check passed cleanly.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| _(none)_ | | |

## Phase 0: Outline & Research

**Status**: Complete — see [research.md](./research.md)

All items that would have been `NEEDS CLARIFICATION` are resolved during planning by reading the reference implementations already in the codebase (`AdminSummaryService`, `AdminSummaryLog`, `CliController.kt:184-237`, `UserMappingController.kt:75-147`). The deferred items from `/speckit.clarify` (notification-log integration, exit-code table, `--verbose` flag introduction) are all answered in `research.md`.

**Key decisions**:

1. **Reuse `AdminSummaryService.getAdminRecipients()`** for the ADMIN+REPORT recipient set — don't duplicate role-filtering logic.
2. **New dedicated service** (`UserMappingStatisticsService`) rather than extending `AdminSummaryService` — keeps single-responsibility boundaries (`AdminSummaryService` is about system-wide stats; this feature is user-mapping-specific).
3. **New dedicated audit table** (`user_mapping_statistics_log`) rather than reusing `admin_summary_log` — different payload columns (mapping counts vs vulnerability counts) and keeps analytics queries clean.
4. **CLI flow is "print then send"**: the CLI runs its existing `listMappings()` call and renders the TABLE/JSON/CSV console output first, then (if `--send-email`) posts to the new endpoint. This guarantees SC-005 (byte-identical default output) and makes failure modes clear (console always shows what was queried locally).
5. **`--verbose` flag is NEW on `ListCommand`** (confirmed via grep — doesn't exist today). Introduced by this feature, used to enable per-recipient logging. Default `false`.
6. **Exit-code contract**: 0=SUCCESS, 1=generic error, 2=authorization denied (invoker not ADMIN), 3=no eligible recipients, 4=partial failure (some sent, some failed), 5=full failure (zero sent, ≥1 attempted). Documented in `contracts/cli-user-mapping-email.md`.

**Output**: [research.md](./research.md)

## Phase 1: Design & Contracts

**Prerequisites**: research.md complete ✅

### 1.1 Data Model

See [data-model.md](./data-model.md) for full detail. Summary:

- **New entity `UserMappingStatisticsLog`** — mirrors `AdminSummaryLog` structure with mapping-specific columns: `totalUsers`, `totalMappings`, `activeMappings`, `pendingMappings`, `domainMappings`, `awsAccountMappings`, plus the standard `executedAt`, `recipientCount`, `emailsSent`, `emailsFailed`, `status`, `dryRun`, and the filter context (`filterEmail`, `filterStatus`) used for reproducibility.
- **New DTO `UserMappingStatisticsReport`** (service-layer) — holds aggregates + per-user detail for both console reuse (future) and email template rendering.
- **New request DTO `SendUserMappingStatisticsRequest`** — `filterEmail: String?`, `filterStatus: String?`, `dryRun: Boolean = false`, `verbose: Boolean = false`.
- **New response DTO `UserMappingStatisticsResultDto`** — `status`, `recipientCount`, `emailsSent`, `emailsFailed`, `recipients: List<String>`, `failedRecipients: List<String>`, `appliedFilters: Map<String,String>` (echoed back so the CLI can confirm filter round-trip).

No changes to existing entities. No schema changes to `user_mapping` table.

### 1.2 Contracts

See [contracts/cli-user-mapping-email.md](./contracts/cli-user-mapping-email.md) for full detail. Summary:

**New REST endpoints** (both under `/api/cli` which is already `@Secured("ADMIN")`):

- `POST /api/cli/user-mappings/send-statistics-email`
  - Body: `SendUserMappingStatisticsRequest`
  - Response: `200 UserMappingStatisticsResultDto` on success (incl. partial failures and dry-run), `403` if not ADMIN, `500` for internal errors
  - Behavior: Apply filter, compute stats, render templates, call `emailService.sendEmailWithInlineImages()` per recipient, persist audit log, return result DTO

**New CLI flags** on `ListCommand`:

- `--send-email` (Boolean, default `false`) — trigger email dispatch
- `--verbose`, `-v` (Boolean, default `false`) — enable per-recipient status output

**Exit-code contract** (CLI):

| Code | Meaning |
|------|---------|
| 0 | SUCCESS (all eligible recipients received the email) or DRY_RUN or default `list` without `--send-email` |
| 1 | Generic error (network, auth, unexpected exception) |
| 2 | Authorization denied (invoker does not hold ADMIN) |
| 3 | No eligible recipients |
| 4 | Partial failure (≥1 sent, ≥1 failed) |
| 5 | Full failure (0 sent, ≥1 attempted) |

Codes 2–5 only apply when `--send-email` is set. Without the flag, the command retains its current exit behavior (FR-014).

### 1.3 Quickstart

See [quickstart.md](./quickstart.md) for manual verification steps covering:
- Happy path (send to real ADMIN+REPORT users)
- `--dry-run` preview
- Filter-through (`--email foo@bar.com --send-email`)
- Partial failure simulation (disable SMTP to one recipient via bad address)
- Zero-recipient scenario (temporarily remove ADMIN/REPORT roles in test env)
- Documentation review checklist

### 1.4 Agent context update

Run `.specify/scripts/bash/update-agent-context.sh claude` after this plan is finalized to refresh CLAUDE.md's Active Technologies / Recent Changes sections. The script preserves manual additions between markers.

**Output**: data-model.md, contracts/cli-user-mapping-email.md, quickstart.md, updated CLAUDE.md

## Re-evaluated Constitution Check (post-design)

| Principle | Still compliant? | Notes |
|---|---|---|
| I. Security-First | ✅ | Contracts confirm server-side `@Secured("ADMIN")` gate; audit log covers all invocations |
| III. API-First | ✅ | New endpoint fits existing `/api/cli` controller; JSON error format consistent |
| IV. User-Requested Testing | ✅ | No test tasks planned |
| V. RBAC | ✅ | Role gate on both invoker (ADMIN) and recipient (ADMIN+REPORT) |
| VI. Schema Evolution | ✅ | New table defined with JPA annotations + Flyway script; no data migration |

**Verdict**: No new violations introduced by Phase 1 design. Ready for `/speckit.tasks`.
