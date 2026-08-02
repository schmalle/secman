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
| Use case unknown | `Use case '<name>' not found` |
| No SECCHAMPION user | `No user with SECCHAMPION role exists to act as assessor` |
| **No ACTIVE release** | `No ACTIVE release exists to base the risk assessment on - activate a requirements release first` |
| **ACTIVE release has no requirements for the use case** | `ACTIVE release '<version>' contains no requirements tagged with use case '<name>'` |

The last two exist so an operator never ends up with assessments whose
questionnaire is empty. Mappings still import normally without
`--start-risk-assessment`.

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

Reminders are idempotent across restarts *and* concurrent runs: each slot is taken
with an atomic guarded UPDATE (claim-before-send) committed per row, so overlapping
scheduler runs cannot double-send. A missed 2-day reminder collapses into a single
1-day catch-up. A send that fails after a successful claim releases the claim so
the next run retries.

## Idempotency

Re-running an import does not create a second assessment for a pair that already
has an **open** one — `createAssessment` skips and reports
`Skipped: an open risk assessment (id=…) already exists for this account/owner`.

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
| E2E | `scripts/test/test-e2e-aws-account-risk-assessment.sh`, skill `/aws-account-risk-assessment` |

See also: `docs/CLI.md`, `docs/MCP.md`.
