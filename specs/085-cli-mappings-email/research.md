# Phase 0 Research: CLI manage-user-mappings --send-email Option

**Feature**: 085-cli-mappings-email
**Date**: 2026-04-08
**Status**: Complete

## Purpose

Resolve open questions from the spec (including items deferred during `/speckit.clarify`) by reading the reference implementation (`send-admin-summary`, feature 070) that already exists in the codebase. Every decision below is grounded in an existing file, so the plan can proceed without guesswork.

---

## Decision 1: Recipient resolution — reuse `AdminSummaryService.getAdminRecipients()`?

**Decision**: **Yes**, call `AdminSummaryService.getAdminRecipients()` directly from the new `UserMappingStatisticsService`.

**Rationale**:
- The method at `src/backendng/src/main/kotlin/com/secman/service/AdminSummaryService.kt:145-154` already returns the exact ADMIN+REPORT deduplicated, email-filtered set this feature needs.
- Duplicating the logic in a new service would be a copy-paste anti-pattern and create two places to fix a future role-model change.
- The method is `public`, stateless, and has no side effects — safe to reuse cross-service.

**Alternatives considered**:
- **New helper in a shared utility class**: Adds a layer for no benefit; the existing method is already effectively a utility.
- **Duplicate the logic for "independence"**: Rejected. Independence at the cost of drift is not a virtue.
- **Extract to a `RoleBasedRecipientResolver` component**: Overkill for one additional call site. Can be done later if a third call site appears.

---

## Decision 2: One new service or extend `AdminSummaryService`?

**Decision**: **New dedicated service** — `UserMappingStatisticsService`.

**Rationale**:
- `AdminSummaryService` is named and documented around "system statistics" (users, vulnerabilities, assets) — extending it with user-mapping-specific logic would violate its single responsibility and confuse future readers.
- The two services have different data sources (one pulls from `VulnerabilityRepository` + `AssetRepository`; this feature pulls from `UserMappingRepository`).
- The audit log rows have different shapes (stats columns differ), so extending would bloat `AdminSummaryLog` or require a nullable-heavy schema.
- Cost of a new service is low — it's ~150 lines of Kotlin following the exact same template rendering pattern.

**Alternatives considered**:
- **Extend `AdminSummaryService` with a second method pair**: Rejected for the reasons above.
- **Put the logic in `UserMappingController` directly**: Violates Constitution V ("Authorization checks MUST happen at service layer, not just controller") and general layering.

---

## Decision 3: New audit table or reuse `admin_summary_log`?

**Decision**: **New table `user_mapping_statistics_log`** with its own entity and repository.

**Rationale**:
- `admin_summary_log` columns are vulnerability/asset-specific (`vulnerability_count`, `asset_count`) — they don't map to user-mapping statistics.
- Keeping audit tables feature-specific keeps analytics queries simple ("show me every user-mapping email sent in the last 30 days" is a single-table query, not a filtered join).
- Schema Evolution principle (Constitution VI) is satisfied either way; a new table with a Flyway migration is the standard secman pattern (see features 070, 069).

**Alternatives considered**:
- **Reuse `admin_summary_log` with a `log_type` discriminator column**: Would require schema change to an existing production table (data-loss-risk per Constitution VI), plus makes existing queries ambiguous. Rejected.
- **Skip audit logging entirely**: Rejected. Constitution I (Security-First) implies observability for authenticated dispatches of sensitive data; and feature 070 set the precedent that send-email CLI commands get a log row.

---

## Decision 4: CLI flow — "print then send" or "fetch once, render locally + remote"?

**Decision**: **"Print then send"** — CLI keeps its current `listMappings()` call for the console output, and (if `--send-email`) issues a separate POST to the new backend endpoint which re-queries the data.

**Rationale**:
- Guarantees SC-005 (byte-identical default output) because the default code path is untouched.
- Simpler contract — the backend endpoint is self-sufficient and can be called from other contexts in the future (a web UI button, a scheduled job).
- The duplicate query is cheap (user_mapping is a small table) and the ~ms-level race window is irrelevant for an audit report.
- Matches `send-admin-summary` exactly: CLI prints locally-computed stats then POSTs to `/admin-summary/send` which re-queries server-side.

**Alternatives considered**:
- **CLI fetches once, posts the full mapping list to the backend to render the email**: Creates a large request body for large sets, requires a pass-through DTO, and couples the CLI's fetch shape to the email template. Rejected.
- **Backend returns the rendered email body to the CLI which then dispatches via SMTP itself**: Rejected. CLI has no SMTP config and that would reintroduce credential sprawl.

---

## Decision 5: Exit-code contract

**Decision**:

| Code | Meaning | When |
|---|---|---|
| 0 | Success / default behavior / dry-run | Normal `list`, `--dry-run`, or full-success send |
| 1 | Generic error | Network failure, unexpected exception, JSON parse failure |
| 2 | Authorization denied | Invoker does not hold ADMIN (from 403 server response) |
| 3 | No eligible recipients | Server returned `status=FAILURE` AND `recipientCount=0` |
| 4 | Partial failure | Server returned `status=PARTIAL_FAILURE` |
| 5 | Full failure | Server returned `status=FAILURE` AND `recipientCount>0` |

**Rationale**:
- SC-003 requires "distinct non-zero status codes per failure mode". Without a table, a cron caller cannot reliably branch on outcome.
- Codes 2–5 only apply when `--send-email` is set; otherwise the command retains its current 0/1 exit behavior (FR-014).
- Codes are mapped from the backend's existing `ExecutionStatus` enum (`SUCCESS`, `PARTIAL_FAILURE`, `FAILURE`, `DRY_RUN`) — no new enum needed, just a CLI-side switch statement.

**Alternatives considered**:
- **Collapse to 0/1 only**: Rejected — violates SC-003.
- **Use codes 64+ (BSD sysexits.h convention)**: More technically correct but inconsistent with the existing CLI's use of `System.exit(1)` for all errors. Rejected for consistency.

---

## Decision 6: Introduce `--verbose` flag on `ListCommand`?

**Decision**: **Yes, introduce it**. It does not exist today (`grep verbose src/cli/src/main/kotlin/com/secman/cli/commands/ListCommand.kt` returns no matches).

**Rationale**:
- FR-007 requires per-recipient status display "when verbose or when failures occur".
- The reference command `SendAdminSummaryCommand.kt:22-23` uses `--verbose` / `-v` with the same semantics — consistency is valuable.
- Without `--verbose`, a successful send to 30 recipients would print 30 lines of clutter by default.

**Alternatives considered**:
- **Always show per-recipient status**: Rejected — noisy for the common case.
- **Reuse Picocli's built-in `--verbose` from a parent command**: No parent command has it. Rejected.

---

## Decision 7: Notification-log integration

**Decision**: **Do NOT integrate with the existing `notification_log` table**. Use the dedicated new `user_mapping_statistics_log` table instead.

**Rationale**:
- The existing `notification_log` tracks per-user outdated-asset reminders and vulnerability notifications (feature-specific domain model).
- This feature's audit is about a per-invocation admin action, not a per-user notification event. Conflating them would muddle the log's semantics.
- Feature 070 (`AdminSummaryLog`) set the precedent that CLI admin-summary dispatches get their own log table — following that convention.

**Alternatives considered**:
- **Log each email to `notification_log` with a new type**: Would create N rows per invocation for a batch action that is conceptually atomic. Rejected.
- **Both**: Double audit write with no extra value. Rejected.

---

## Decision 8: Email template format — HTML + text or text-only?

**Decision**: **Both HTML and plain-text**, matching `admin-summary.html/.txt`.

**Rationale**:
- The existing `emailService.sendEmailWithInlineImages()` supports `htmlContent` and `textContent` together (multipart/alternative) and that is what every existing email template in the project produces.
- Using only plain-text would be inconsistent and lose the SecMan logo + branded styling that recipients already see from `send-admin-summary`.
- Template variable substitution is straightforward string replacement (`${varname}` → value) — same as the existing templates.

**Alternatives considered**:
- **Plain-text only**: Rejected for consistency with existing admin emails.
- **HTML only**: Rejected — some email clients render plain-text preferentially (accessibility, security).

---

## Decision 9: Documentation surfaces to sweep (FR-013)

**Decision**: The following 10 files must be updated. (A grep of `manage-user-mappings` across the repo was run to produce this list, excluding historical `specs/` subdirectories which are frozen artifacts.)

| # | File | What to update |
|---|---|---|
| 1 | `CLAUDE.md` | The CLI Commands section already lists `manage-user-mappings` — add `--send-email` mention |
| 2 | `README.md` | If README lists CLI features — confirm during tasks phase |
| 3 | `INSTALL.md` | Check whether CLI section mentions this command |
| 4 | `docs/CLI.md` | Primary CLI usage guide — add `--send-email` under `list` subcommand docs |
| 5 | `docs/ARCHITECTURE.md` | Update if it mentions `manage-user-mappings` data flow |
| 6 | `src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md` | Dedicated CLI command doc — primary place, needs full `--send-email` section |
| 7 | `scripts/secmancli` | Shell wrapper's help text (search for `manage-user-mappings`) |
| 8 | `src/cli/src/main/kotlin/com/secman/cli/commands/ManageUserMappingsCommand.kt` | `@Command(description = ...)` at class level |
| 9 | `src/cli/src/main/kotlin/com/secman/cli/commands/ListCommand.kt` | `@Command` and new `@Option` annotations for `--send-email` and `--verbose` |
| 10 | `specs/085-cli-mappings-email/quickstart.md` | Usage examples (created as part of this plan) |

**Rationale**: FR-013 explicitly requires this sweep. Listing the files up-front prevents the tasks phase from missing one.

**Alternatives considered**: None — the requirement is explicit.

---

## Summary

All open questions are resolved. The design reuses existing patterns almost verbatim — the only new code is a service, an entity, a repository, a Flyway script, two email templates, two controller endpoints, and two CLI flags. No new frameworks, no new dependencies, no constitutional violations.

**Ready for Phase 1**: ✅
