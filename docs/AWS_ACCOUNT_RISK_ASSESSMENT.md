# Auto Risk Assessment for Newly Discovered AWS Accounts

When an AWS account-mapping import introduces an account ID SecMan has never seen,
it can automatically start a risk assessment for that account's owner — measured
against the **current version of the security requirements**, scoped to a use case.

Opt-in. Nothing happens unless the caller asks for it.

| Surface | How |
|---|---|
| CLI | `manage-user-mappings import --start-risk-assessment --risk-usecase <name>` (also on `import-s3`) |
| REST | `POST /api/user-mappings/bulk` with `startRiskAssessment: true` (ADMIN) |
| MCP | `import_user_mappings` with `startRiskAssessment: true` (ADMIN, delegation) |
| Inspect | MCP `list_aws_account_risk_assessments` (ADMIN, delegation) |

## What counts as a "new" account

An account ID that appears on **no existing `user_mapping` row, database-wide**.

`UserMappingService.computeNewAccounts` runs **before** the inserts, so the
existence query reflects pre-import state. Detection is per-import, not
time-windowed — re-importing the same file finds nothing new the second time.

This is a different question from the one `notify-new-accounts` answers, which is
time-based ("accounts mapped in the last N hours").

## Who the owner is

There is no owner column on an AWS account. The owner is **every email the account
is mapped to in this import** (`NewAccountImportInfo.emails`). One assessment is
started per `(new account, owner email)` pair.

The owner becomes the assessment's **respondent** when a user account with that
email exists; otherwise the assessment is still created and the owner is emailed,
so it is ready when the user is provisioned.

## The standard: the ACTIVE release

> The current version of all uploaded security requirements *is* the standard.

In SecMan a version of the requirement corpus is a **`Release`**: creating one
snapshots every requirement into `requirement_snapshot`, and exactly one release is
`ACTIVE` at a time (`ReleaseService.updateReleaseStatus` archives its predecessor).

Every auto-started assessment is therefore **pinned to the ACTIVE release**:

```kotlin
assessment.lockedRelease = activeRelease
assessment.isReleaseLocked = true
assessment.contentSnapshotTaken = true
```

The questionnaire is then resolved from that release's frozen snapshots, filtered
to the assessment's use case tag (`ReleaseRequirementScopeService`, consumed by
`ResponseController.getRequirementsForAssessment`).

**Why pin.** Requirement imports are append-only. Without pinning, importing more
requirements while an assessment is open would silently add questions to a
questionnaire the respondent had already partly answered. With pinning, the
question set is fixed at start time.

The ACTIVE release is resolved **once per import**, so every account in one run is
measured against the same version even if a release is activated mid-run.

> **Note on `isCurrent`.** `Requirement.isCurrent` is *not* used for this and must
> not be: nothing in the codebase ever sets it to `false`, so the `findCurrent*`
> queries match every row ever imported, including superseded ones. Releases are
> the versioning mechanism.

### Requirements are matched by use case **tag**

A snapshot belongs to the questionnaire when its `usecaseIdsSnapshot` JSON array
contains the use case id — the same `requirement_usecase` relationship
`RequirementRepository.findByUsecaseId` uses for live requirements. The array is
parsed, not substring-matched, so use case `1` does not match a requirement tagged
`[11,12]`.

## Validation (fail fast, before anything is imported)

A request with `startRiskAssessment` is rejected with HTTP 400 (CLI exit code 2,
MCP `VALIDATION_ERROR`) when:

| Condition | Message |
|---|---|
| No use case given | `riskAssessmentUseCase is required when startRiskAssessment is true` |
| Deadline < 1 | `riskAssessmentDeadlineDays must be at least 1` |
| Deadline > 3650 | `riskAssessmentDeadlineDays must be at most 3650` |
| Use case unknown | `Use case '<name>' not found` |
| No SECCHAMPION user | `No user with SECCHAMPION role exists to act as assessor` |
| **No ACTIVE release** | `No ACTIVE release exists to base the risk assessment on - activate a requirements release first` |
| **ACTIVE release has no requirements for the use case** | `ACTIVE release '<version>' contains no requirements tagged with use case '<name>'` |

The last two exist so an operator never ends up with assessments whose
questionnaire is empty. Mappings still import normally without
`--start-risk-assessment`.

### Why the deadline is capped at 10 years

`endDate` is a SQL `DATE`. Left unbounded, `--risk-deadline-days 2147483647`
produces a year the column cannot hold, and because assessments are started
*after* the import commits, the failure arrives per account with the mappings
already persisted. Well short of that limit, a deadline decades out is
indistinguishable from a typo — `100000` is one keystroke from `1000` — and
yields an assessment the reminder scheduler will never wake up for, since it
only looks two days ahead. `MAX_DEADLINE_DAYS = 3650` is checked in
`validateStartRequest` (both surfaces) and mirrored in the two CLI commands so
the operator sees the flag name rather than the JSON field name. A value that
reaches `startAssessmentsForNewAccounts` directly is clamped, not thrown: by
then we are past the commit point.

### Owner addresses are validated at the import boundary

A mapped email is not just a row: it becomes the SMTP recipient of the mails
below, it is interpolated into log lines, and it is written into the
assessment's `notes`. `UserMappingService.emailRegex` therefore excludes
whitespace, control characters and the separator/quoting characters
`, ; : < > " \` — a comma would make `InternetAddress.parse` split one
recipient into two, and a CR/LF would reach a log line. Length is capped at the
column width (255) so an overlong address fails as one ordinary row error
instead of a post-commit `DataException`. The CLI's `UserMappingValidator`
carries the same pattern; the backend is the boundary.

## What gets created

For each `(account, owner)` pair:

- **Basis** — an asset representing the account: name `AWS Account <id>`, type
  `AWS_ACCOUNT`, `cloudAccountId` = the account id, `owner` = the mapped email.
  Reused when it already exists. The owner reaches it through the unified asset
  access rules (owner match and cloud-account UserMapping match).
- **Assessor** — a user with the `SECCHAMPION` role, chosen round-robin across all
  SECCHAMPION users so load spreads evenly.
- **Requestor** — the ADMIN who ran the import; falls back to the assessor when
  unresolvable.
- **Respondent** — the owner, when a user with that email exists.
- **Deadline** — today + `--risk-deadline-days` (default 7).
- **Tracking row** — `aws_account_risk_assessment` (V237), which also carries the
  reminder state. Only import-triggered assessments are tracked, so the reminder
  scheduler never touches manually created ones.

## Emails

- **On start** — the owner is told the account, use case, **requirements version**,
  assessor and deadline. Sent after the persist transaction commits, so the
  blocking SMTP send never holds a pooled DB connection.
- **Reminders** — 2 days and 1 day before the deadline, daily job at 08:15
  (`AwsAccountRiskAssessmentReminderScheduler`). Only open (`STARTED`) assessments.

Subject of the start mail, which is also its only stable identifier:

```
Risk assessment started for your AWS account <accountId>
```

### Rendering

Both mails render from shared resources under `email-templates/`, not inline HTML,
so they carry the SecMan logo and match every other notification:

| Mail | Templates |
|---|---|
| Start | `aws-account-risk-assessment-started.{html,txt}` |
| Reminder | `aws-account-risk-assessment-reminder.{html,txt}` |

The logo is a CID inline image (`cid:secman-logo`), so both go out through
`EmailService.sendEmailWithInlineImages`. **That changes the success log line** —
it reads `Successfully sent email with inline images to …` rather than
`Successfully sent email to …`. Anything grepping for the send must tolerate both.

Values are HTML-escaped into the HTML part and left raw in the text part; the
reminder's requirements-version row is a `{ifVersion}…{/ifVersion}` block, since
assessments started before release pinning have no locked release.

### The action link

Both mails link to **the assessment itself**, not the list:

```
<SECMAN_BACKEND_URL>/risk-assessments?assessmentId=<id>
```

`RiskAssessmentManagement.tsx` consumes `assessmentId` once the list has loaded and
opens that assessment in *perform* mode. The id is honoured only if it appears in
the list the backend already access-filtered, so the link cannot expose someone
else's assessment; an unknown id shows an explanatory message instead.

**Why not `/respond/{token}`.** That route is token-authenticated and would let
anyone holding the mail answer on the owner's behalf. Linking into the normal
authenticated app forces a login: `Layout.astro` bounces an unauthenticated visitor
to `/login?redirect=<path+query>` and `Login.tsx` returns them afterwards. That
redirect value is attacker-controllable, so `safeRedirectTarget()` accepts only a
same-origin relative path — rejecting absolute URLs, protocol-relative `//host`,
and backslash normalisation tricks.

The host comes from `appConfig.backend.baseUrl` (`SECMAN_BACKEND_URL`), matching
`AwsAccountSharingNotificationService`: nginx fronts API and UI on one host, while
`frontend.baseUrl` defaults to localhost and has no env override.

### The send is best-effort, and silence is not success

Two layers each swallow a failure, by design — a mail problem must never undo a
committed assessment:

| Layer | On failure |
|---|---|
| `startAssessmentsForNewAccounts` | catches anything `sendStartNotification` throws into a `log.warn` |
| `EmailService.sendEmail` | returns `false` **without throwing** when no `EmailConfig` is active, logging `No active email configuration found` |

So an import reports complete success whether or not mail was sent, and
"the assessment exists" is no evidence of delivery.

**There is no DB trail either.** The start notification calls `sendEmail()`, not
`sendNotificationEmail()`, and only the latter writes `email_notification_logs`.
`GET /api/notification-logs` therefore never shows these mails. The only
machine-checkable evidence is the `EmailService` INFO line:

```
Successfully sent email to <to> with subject: <subject>
```

### Testing it

`/aws-account-owner-email` (driver:
`scripts/test/test-e2e-aws-account-owner-email.sh`) covers delivery specifically:
it takes the mailbox to deliver to, imports a new account via the CLI and via MCP,
asserts that INFO line inside a per-import byte window of the backend log, and
then pauses for a human to confirm what actually arrived. It reuses the
environment's ACTIVE release and never creates or deletes a user for the
recipient address — see the skill for why both matter.

`/aws-account-risk-assessment` covers the assessment path instead and asserts
nothing about mail. It exercises both surfaces — CLI and MCP — and covers the
opt-in default (no flag → no assessment, no asset, no mail), release pinning and
its stability, the idempotent skip *including* that it does not fail the run,
the deadline bound on both surfaces, and the missing-ACTIVE-release and
non-admin negatives. Its cleanup runs before and after and is keyed on the
stable `e2e-awsra-` owner prefix, so it also sweeps up whatever an earlier
interrupted run left behind — including the `AWS_ACCOUNT` basis assets, which
nothing used to remove.

Reminder mails have **no manual trigger** — only the 08:15 scheduler — so nothing
tests them end to end today.

Reminders are idempotent across restarts *and* concurrent runs: each slot is taken
with an atomic guarded UPDATE (claim-before-send) committed per row, so overlapping
scheduler runs cannot double-send. A missed 2-day reminder collapses into a single
1-day catch-up. A send that fails after a successful claim releases the claim so
the next run retries.

## Idempotency

Re-running an import does not create a second assessment for a pair that already
has an **open** one — `createAssessment` skips and reports it in the result's
`skipped` / `skipReason` fields, **not** in `error`:

```
⏭️  111111111111  alice@corp.com: skipped — an open risk assessment (id=1000)
    already exists for this account/owner
```

A skip is a no-op, not a failure. It does not count towards the CLI's
`riskAssessmentFailures`, does not make the CLI exit 1, and suppresses the owner
mail (they were told when the assessment was first created). The notification
gate tests `!skipped` as well as `error == null` for exactly that reason — with
the message no longer in `error`, testing `error` alone would re-notify on every
re-import.

In practice the guard is only reached when the mapping row was deleted and the
account is imported again while its assessment is still open. A plain re-import
never gets that far: the account is no longer new, so nothing is even attempted.

There is deliberately no DB unique constraint: a *new* assessment for the same pair
is legitimate once the previous one is completed. Only concurrent or repeated
creation of open ones is a defect.

## Failure isolation

Assessments are started **after** the mapping import commits. A failure while
starting one:

- never rolls back imported mappings,
- never aborts the remaining accounts (each pair persists in its own
  `REQUIRES_NEW` transaction),
- is reported per account in `riskAssessments[].error`, and makes the CLI exit 1.

A failed owner email does not undo a created assessment.

## Inspecting the result

MCP `list_aws_account_risk_assessments` (ADMIN, requires delegation, API-key
permission `ASSESSMENTS_READ`) lists tracked assessments with filters
`awsAccountId`, `ownerEmail`, `status`, `limit` (1-100, default 20). Each row
carries `releaseVersion` / `releaseName` — the version of the requirements it is
measured against — plus assessor, respondent, dates, status and reminder stamps.

`releaseVersion` is `null` for assessments started before release pinning existed.

## Limitations

- **The admin UI upload path has no detection.** `POST /api/import/upload-user-mappings`
  and `-csv` go through `UserMappingImportService` / `CSVUserMappingParser`, which
  do not compute new accounts and cannot start assessments. Only the bulk endpoint
  (CLI and MCP) does.
- **Deleting the pinned release unpins the assessment.** `ReleaseService.deleteRelease`
  nulls `lockedRelease` on every assessment referencing it, after which the
  questionnaire falls back to unpinned resolution. Deleting an `ACTIVE` release
  already requires `force`.
- **No UI surface for the pinned version.** Auto-started assessments render
  normally in `RiskAssessmentManagement.tsx`; the release is not displayed.
*(Resolved 2026-08-03: `DELETE /api/user-mappings/{id}` used to pass the **caller's**
user id into the scoped `deleteMapping(userId, …)`, whose `mapping.email == user.email`
check then failed for every mapping but the admin's own — surfacing as HTTP 500 because
the controller catches only `NoSuchElementException`. The admin surface now calls
`deleteMappingById(id)`, which performs no ownership comparison; the scoped endpoint
`DELETE /api/users/{userId}/mappings/{mappingId}` keeps the check, since there `userId`
names whose mapping is meant. Covered by `UserMappingServiceDeleteTest`.)*

## Key files

| Concern | File |
|---|---|
| New-account detection | `service/UserMappingService.kt` (`computeNewAccounts`) |
| Shared import orchestration | `service/UserMappingBulkImportService.kt` |
| Assessment creation + reminders | `service/AwsAccountRiskAssessmentService.kt` |
| Requirement version scoping | `service/ReleaseRequirementScopeService.kt` |
| Questionnaire resolution | `controller/ResponseController.kt` (`getRequirementsForAssessment`) |
| Tracking entity / migration | `domain/AwsAccountRiskAssessment.kt`, `db/migration/V237__aws_account_risk_assessment.sql` |
| Reminder scheduler | `scheduler/AwsAccountRiskAssessmentReminderScheduler.kt` |
| MCP | `mcp/tools/ImportUserMappingsTool.kt`, `mcp/tools/ListAwsAccountRiskAssessmentsTool.kt` |
| CLI | `cli/commands/ImportCommand.kt`, `ImportS3Command.kt`, `cli/service/UserMappingCliService.kt` |
| E2E (assessment) | `scripts/test/test-e2e-aws-account-risk-assessment.sh`, skill `/aws-account-risk-assessment` |
| E2E (owner email) | `scripts/test/test-e2e-aws-account-owner-email.sh`, skill `/aws-account-owner-email` |

See also: `docs/CLI.md`, `docs/MCP.md`.
