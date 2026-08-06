# Feature Specification: CLI manage-user-mappings --send-email Option

**Feature Branch**: `085-cli-mappings-email`
**Created**: 2026-04-08
**Status**: Draft
**Input**: User description: "i want to extend the cli manage-user-mappings function by adding an -send-email option, which send the statistics to all admin users, which is printed also to the console. Ensure all documentation and help text in the clients also take care of this."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Admin distributes user-mapping statistics by email in one command (Priority: P1)

A security administrator wants to share the current state of user-to-domain and user-to-AWS-account mappings with the entire admin team on a recurring basis (for example, after a scheduled maintenance window or as part of weekly operational reporting). Today, the administrator must run the `manage-user-mappings list` command, copy the console output, and manually distribute it. The administrator wants a single CLI invocation that both prints the statistics to the terminal and distributes the same statistics by email to every user holding the ADMIN or REPORT role.

**Why this priority**: This is the core capability requested. Without it, the feature has no value. It also replaces a manual copy-paste workflow with a reliable automated path, reducing human error and enabling scheduling from cron/CI.

**Independent Test**: Can be fully tested by running `manage-user-mappings list --send-email` on a system that has at least one ADMIN or REPORT user with a valid email address, and verifying that (a) the normal console statistics output is still displayed exactly as before, and (b) each ADMIN/REPORT user receives an email containing the same statistics.

**Acceptance Scenarios**:

1. **Given** an operator authenticated with ADMIN credentials and at least one other ADMIN or REPORT user who has a valid email address, **When** the operator runs `manage-user-mappings list --send-email`, **Then** the statistics are printed to the console in the same format as today AND every ADMIN/REPORT user with a valid email receives an email containing those statistics, AND the console shows a confirmation listing the recipient count and the outcome for each recipient.
2. **Given** an operator runs `manage-user-mappings list --send-email --dry-run`, **When** the command completes, **Then** the statistics are printed to the console, the list of intended ADMIN/REPORT recipients is displayed, and no email is actually delivered.
3. **Given** an operator runs `manage-user-mappings list` without the `--send-email` flag, **When** the command completes, **Then** the console output is identical to the current behavior and no email is sent (full backward compatibility).
4. **Given** an operator runs `manage-user-mappings list --send-email --format JSON`, **When** the command completes, **Then** the statistics are emitted in JSON on the console AND the email body contains the same underlying data — aggregate counts plus the per-user detail (each user with their domains and AWS accounts) — rendered in a human-readable form suitable for email (not raw JSON), matching the structure of the TABLE-format console output.

---

### User Story 2 - Operator gets clear feedback when email delivery has issues (Priority: P2)

An operator triggers the email option but one or more ADMIN/REPORT users have no email address on file, SMTP delivery fails for a subset of recipients, or no ADMIN/REPORT users exist at all. The operator needs the CLI to report exactly what happened so they can follow up (fix a missing email address, escalate an SMTP outage, or correct role assignments) without having to guess from a silent "success".

**Why this priority**: Without clear reporting, a "successful" run can silently deliver to nobody, which undermines trust in the feature. This is essential for operational use but not required for the happy path.

**Independent Test**: Can be fully tested by running the command in three distinct scenarios — (a) all recipients succeed, (b) a subset fail, (c) zero eligible recipients — and confirming each scenario produces a distinct, unambiguous console summary and an appropriate process exit code.

**Acceptance Scenarios**:

1. **Given** some ADMIN/REPORT users have no email address on file, **When** the operator runs `manage-user-mappings list --send-email`, **Then** the console summary clearly reports how many ADMIN/REPORT users were skipped because of a missing email address and lists their usernames for follow-up.
2. **Given** SMTP delivery fails for one or more recipients, **When** the command completes, **Then** the console summary reports a count of successful sends, a count of failed sends, names the failed recipients, and the process exits with a non-zero status code.
3. **Given** no ADMIN/REPORT users exist or none have a valid email, **When** the command completes, **Then** the console prints a clear "no eligible recipients" message and the process exits with a non-zero status code.

---

### User Story 3 - Help text and documentation clearly describe the new option (Priority: P2)

A new operator, or an existing operator who has not used the feature before, inspects the CLI help or reads the project documentation. They need to understand that `--send-email` exists, what it does, who receives the email, how it combines with other flags (`--dry-run`, `--format`, filters such as `--email` / `--status`), and what role the invoking user needs. Documentation that mentions `manage-user-mappings` anywhere in the codebase must also reflect the new option so operators do not rely on stale guidance.

**Why this priority**: A feature that is not discoverable or understandable is effectively broken for operators who did not implement it. Help text is the primary interface documentation for a CLI.

**Independent Test**: Can be fully tested by running `manage-user-mappings list --help` and inspecting every documentation artifact that previously mentioned `manage-user-mappings`, verifying that the new option, its behavior, and any role/permission requirements are described in a consistent manner.

**Acceptance Scenarios**:

1. **Given** an operator runs `manage-user-mappings list --help`, **When** the help text is displayed, **Then** the `--send-email` option is listed with a concise description explaining that it emails the statistics to all ADMIN/REPORT users in addition to printing them on the console.
2. **Given** an operator reads the top-level `manage-user-mappings` help, **When** the help is displayed, **Then** the description or a usage example mentions that statistics can be distributed by email.
3. **Given** project documentation such as the CLI README, scripts help, CLAUDE.md command table, and any user-facing usage guides, **When** an operator searches for `manage-user-mappings`, **Then** every occurrence that previously described the statistics output has been updated to mention the `--send-email` option.

---

### Edge Cases

- **Large admin population**: The admin set is expected to be modest (tens of users). The command MUST still complete in a reasonable time and report per-recipient status without truncation.
- **Filtered list**: If the operator combines `--send-email` with filters (`--email`, `--status`), the emailed statistics MUST reflect the filtered view and the email body MUST clearly state which filters were applied so recipients are not misled.
- **Invoking user is not ADMIN**: The command MUST refuse to send email and return a clear authorization error. Email sending MUST be blocked server-side even if the CLI flag is present.
- **Invoking admin inclusion**: Whether the invoking admin is in the recipient list is assumed below — see Assumptions.
- **Empty statistics**: If there are zero mappings, the command MUST still print "no mappings found" and the email, if sent, MUST clearly convey that result rather than appearing blank.
- **`--dry-run` interaction**: `--send-email --dry-run` MUST print the intended recipients without dispatching any message; the normal statistics console output MUST still appear so the operator can verify content before the real send.
- **Partial SMTP failure**: A failure for one recipient MUST NOT prevent delivery attempts for the remaining recipients.
- **Authentication failure during send**: If the backend cannot authenticate the caller during the send step (expired token, revoked credentials), the CLI MUST fail loudly with a non-zero exit, not silently skip email.

## Clarifications

### Session 2026-04-08

- Q: Which users should receive the emailed user-mapping statistics? → A: ADMIN + REPORT roles (mirrors the existing `send-admin-summary` command for consistency across "send stats to administrators" features).
- Q: What should the email body actually contain? → A: Aggregates PLUS the full per-user table (every user with their domains and AWS accounts), mirroring the TABLE-format console output so recipients see equivalent, actionable detail.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The CLI `manage-user-mappings list` subcommand MUST accept a new flag named `--send-email` (boolean, default `false`).
- **FR-002**: When `--send-email` is supplied, the CLI MUST still print the statistics to the console using the current format logic (TABLE, JSON, or CSV as selected by `--format`) so console behavior is additive, not replacement.
- **FR-003**: When `--send-email` is supplied and not combined with `--dry-run`, the system MUST send an email containing the user-mapping statistics to every user who holds the ADMIN role OR the REPORT role and has a valid email address on file.
- **FR-004**: The emailed statistics MUST match the same data set as the printed console output, including any active filters such as `--email` or `--status`, and the email MUST indicate which filters (if any) were applied.
- **FR-005**: The email body MUST be rendered in a format suitable for email readers (not raw CSV or pretty-printed JSON dumps), including a clear subject line identifying it as a user-mapping statistics report, a timestamp of when the report was generated, the **aggregate counts**, AND a **per-user detail section** listing every user in the filtered set with their email, status, mapped domains, and mapped AWS account IDs — matching the structure of the TABLE-format console output.
- **FR-006**: When `--send-email` is combined with `--dry-run`, the system MUST print the intended recipient list to the console and MUST NOT dispatch any email.
- **FR-007**: After a real (non-dry-run) send, the CLI MUST print a summary to the console containing: total recipient count, number of successful sends, number of failed sends, and (when verbose or when failures occur) the specific failed recipient addresses.
- **FR-008**: If the invoking CLI user does not hold the ADMIN role, the `--send-email` path MUST be refused with a clear authorization error and a non-zero exit code.
- **FR-009**: If there are zero eligible recipients (no users holding ADMIN or REPORT role, or none of them have a valid email address), the command MUST print an explanatory message identifying the reason and exit with a non-zero status code.
- **FR-010**: If one or more recipients fail but at least one succeeds, the command MUST report a partial-failure state, list the failed recipients, and exit with a non-zero status code.
- **FR-011**: The `--send-email` option MUST appear in the `list` subcommand help output (`manage-user-mappings list --help`) with a concise description.
- **FR-012**: The top-level `manage-user-mappings --help` text and the `list` subcommand description MUST mention the email distribution capability so operators can discover it.
- **FR-013**: All project documentation that references the `manage-user-mappings` command — including but not limited to CLAUDE.md, the CLI README, `scripts/secman` help, and any user-facing usage guides — MUST be updated to describe the `--send-email` option and its behavior.
- **FR-014**: The command MUST NOT change the exit-code semantics of the existing `list` subcommand when `--send-email` is not supplied (full backward compatibility).
- **FR-015**: The feature MUST integrate with the existing credentialing model used by `manage-user-mappings` (backend username/password via flags or `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` env vars) — no new credential mechanism is introduced.
- **FR-016**: The command MUST report enough information that an operator scheduling it from cron can detect failure from the exit code alone without parsing stdout.

### Key Entities

- **User Mapping Statistics Report**: A data structure containing two sections for a given filter set: (1) **Aggregates** — total user count, total mapping count, active-vs-pending breakdown, domain-vs-AWS-account breakdown; and (2) **Per-user detail** — for every user in the filtered set, their email, status (ACTIVE/PENDING/mixed), mapped domains, and mapped AWS account IDs. Used as the common payload for both the console output and the email body so recipients see the same actionable detail the operator saw.
- **Statistics Recipient**: A user account in secman that holds the ADMIN role OR the REPORT role and has a non-empty, syntactically valid email address on file; the command's email delivery targets exactly this set. This matches the recipient set used by the existing `send-admin-summary` command.
- **Send Result**: A per-invocation outcome capturing eligible recipient count, successful sends, failed sends (with addresses), skipped recipients (with reasons such as "missing email"), and an overall status (SUCCESS, PARTIAL_FAILURE, FAILURE, DRY_RUN) used to determine the exit code and console summary.

## Assumptions

- **Recipient roles = ADMIN + REPORT** *(resolved in clarification 2026-04-08)*: The emailed statistics are sent to every user holding either the `ADMIN` role or the `REPORT` role. Other administrative-adjacent roles (REQADMIN, RELEASE_MANAGER, SECCHAMPION) are **not** recipients. This matches the recipient set used by the existing `send-admin-summary` command, keeping "send stats to administrators" commands consistent.
- **Invoking user included if eligible**: The invoking user is treated like any other recipient — if they hold ADMIN or REPORT and have a valid email, they also receive the email. This matches the existing `send-admin-summary` behavior and is the least-surprising default. Note: the invoking user still needs ADMIN to be *allowed* to run the command (see FR-008), but REPORT-only users can still *receive* the email when an ADMIN dispatches it.
- **Email body format**: A plain-text email with a clearly labeled section for each statistic (same structure as the TABLE console output) is sufficient; HTML email is out of scope unless the project already ships an HTML email template for similar reports.
- **Filters flow through**: Running `manage-user-mappings list --email foo@bar --send-email` emails the filtered view, not the unfiltered view, and the email clearly labels the applied filters. This mirrors "what you see on the console is what recipients see in email".
- **Email transport already exists**: The backend already has SMTP configuration and a delivery pipeline (used by notification-logs, admin-summary, etc.). This feature does not introduce a new transport, only a new report endpoint and email template.
- **No scheduling built in**: This feature is a one-shot CLI option. Recurring delivery is the operator's responsibility (cron, CI scheduler). This keeps scope minimal and avoids duplicating scheduling concerns.
- **Authorization enforced server-side**: The backend is the authoritative gate — even if the CLI flag is present, the backend MUST verify the caller's ADMIN role before dispatching emails, to prevent a tampered CLI from bypassing authorization.
- **Subcommand placement**: The `--send-email` flag lives on the `list` subcommand (the one that currently produces statistics). Placing it on the parent `manage-user-mappings` command or creating a new subcommand was considered and rejected as less discoverable.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator can distribute user-mapping statistics to every admin in a single command invocation, eliminating the copy-paste workflow from at least 3 manual steps down to 1.
- **SC-002**: When run against a test environment with N eligible recipients (ADMIN or REPORT users, N ≥ 2), 100% of recipients with a valid email address receive the statistics email within 60 seconds of command completion in the happy path.
- **SC-003**: For every failure mode (no recipients, partial failure, full failure, authorization denied), the command exits with a distinct non-zero status code and prints a human-readable explanation naming the root cause, so a scheduled (cron) caller can detect failure without parsing message text.
- **SC-004**: 100% of documentation surfaces that currently mention `manage-user-mappings` also mention the `--send-email` option after the feature ships, verified by a repo-wide search before merge.
- **SC-005**: Running `manage-user-mappings list` without the new flag produces byte-identical console output to the pre-feature baseline (zero regression in default behavior), verified by snapshot comparison.
- **SC-006**: A new operator can discover the feature and understand its behavior using only `manage-user-mappings list --help` — measured by the help text containing: the flag name, a one-line description, the fact that recipients are admin users, the interaction with `--dry-run`, and the fact that console output is still produced.
