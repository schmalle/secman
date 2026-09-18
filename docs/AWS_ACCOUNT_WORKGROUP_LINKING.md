# AWS Account → Workgroup Linking (`display_name`)

An AWS account mapping file names both the account and the team that owns it. This
feature turns that name into workgroup membership for the account:

> An account whose `display_name` is **`DevOps-x`** belongs to the workgroup
> **`aws-DevOps-x`**.

Linking an account to a workgroup grants every member of that workgroup access to the
account's assets — unified asset access **rule #9** (`WorkgroupAwsAccount`). Treat it as
an authorization change, not as bookkeeping.

For asset assignments derived from canonical workgroup-owner email addresses, use
[`./scripts/sync-workgroup-assets.sh [--dry-run]`](WORKGROUP_ASSET_SYNC.md).
That command evaluates canonical workgroup owners and AWS owner mappings, removes
all existing `asset_workgroups` links from participating workgroups, and rebuilds
their desired links, excluding AWS-managed workgroups. It does not create
workgroups or use the `display_name` naming rule described here.

## The rule, exactly

| Question | Answer |
|---|---|
| Workgroup name | `"aws-" + display_name`, with surrounding whitespace trimmed |
| Matching | **Case-insensitive** (`aws-devops-x` matches an existing `aws-DevOps-x`) |
| Fuzzy / suffix matching | **Never.** `aws-DevOps-x-rolegreen` is a different workgroup and is not touched |
| Workgroup missing | **Created** (root level, description "Auto-created from AWS account display name"), then linked |
| Account already linked | Reported as `alreadyLinked`; membership and direct asset links are still reconciled |
| Other account already linked, or multiple imported accounts for the same name | Reported as a conflict; existing assignments are preserved, not replaced |
| Direct assets | All direct asset associations on the matched workgroup are removed; assets themselves remain intact |
| Account owner | An existing SecMan user matching the imported owner email (case-insensitive) is added as a member; existing members remain |
| No matching owner user and no existing members | Workgroup is disabled and reported as incomplete; no user is created |
| Account id | Must be exactly 12 digits, or the row is an error |
| Display name that cannot be a workgroup name | **Error, nothing created** (see below) |
| Existing links to other workgroups | **Never removed** — see [Renamed accounts](#renamed-accounts) |

`Workgroup.name` permits letters, digits, spaces and hyphens only, 1–100 characters. A
display name containing `_`, `.`, `/` or one long enough to push `aws-<name>` past 100
characters is therefore reported as an error rather than force-created — an entity that
violates its own constraint helps nobody.

Everything above lives in exactly one place, `WorkgroupAccountLinkService`, which every
surface below calls. There is no second copy of the naming rule.

## Where linking happens

### 1. During an import (automatic)

Any import that carries display names links as it goes, **after** the mappings have
committed — a workgroup that cannot be created never costs you the mappings themselves.

| Surface | Field |
|---|---|
| CLI `manage-user-mappings import` | `display_name` in the Cloud Custodian JSON, or the optional `display_name` CSV column |
| REST `POST /api/user-mappings/bulk` | `mappings[].displayName` |
| MCP `import_user_mappings` | `mappings[].displayName` |
| REST `POST /api/import/upload-user-mappings-csv` | the optional `display_name` column |

A file with no display names links nothing and is not reported on at all — which is why
existing Excel and plain-CSV imports behave exactly as they did before.

```bash
# Preview: nothing is created, nothing is assigned
./scripts/secman manage-user-mappings import \
    -f testdata/user-mappings/accounts-with-display-name.json --dry-run

# For real
./scripts/secman manage-user-mappings import \
    -f testdata/user-mappings/accounts-with-display-name.json
```

```
Workgroup linking:
  Accounts processed: 4
  Workgroups created:  1
  Accounts linked:     1
  ❌ Failed:          3
  🆕 706840063453  ->  aws-Legacy-alpha: workgroup created, account linked
  ❌ 156674634739  ->  aws-DevOps-beta: multiple accounts target the same workgroup
  ❌ 421337195204  ->  aws-DevOps-beta: multiple accounts target the same workgroup
  ❌ 900112233445  ->  aws-Data_Platform.01: Display name yields workgroup name
     'aws-Data_Platform.01', which may contain only letters, numbers, spaces and hyphens
```

### 2. Correction (on demand, from the database)

Repairs everything an import did not cover — mappings imported before display names were
captured, or accounts whose workgroup was missing at the time. The source is the
`aws_account_name` already stored on the mappings, so **no file is needed**.

```bash
# What would happen
./scripts/secman manage-user-mappings link-workgroups --dry-run

# Do it
./scripts/secman manage-user-mappings link-workgroups
```

| Surface | Call |
|---|---|
| CLI | `manage-user-mappings link-workgroups [--dry-run]` |
| REST | `POST /api/user-mappings/link-workgroup-accounts` (ADMIN), body `{"dryRun": false}` |
| MCP | `link_workgroup_aws_accounts` (ADMIN + User Delegation), arg `dryRun` |

Idempotent: run it as often as you like. Accounts already assigned come back as
`alreadyLinked`, but empty membership or account conflicts still produce a non-zero exit status.

## Account-only workgroups

Migration V270 adds `workgroup.aws_account_managed` (default false). An exact
display-name match is enrolled during reconciliation; unrelated manually named
`aws-*` workgroups are not changed merely because of their prefix. No bulk data
cleanup runs during migration: existing workgroups are reconciled on their next import.

The importer removes **join-table links only**, not assets, vulnerabilities, or
other workgroups' links. Members retain access through the linked account when the
workgroup is enabled. After reconciliation, imports enable a matched group only
when it has an owner, at least one persisted member, exactly the imported account,
zero direct assets, and a member count below the configured catch-all safety limit.
Incomplete groups are disabled. This also repairs already-linked groups on reimport
and overrides a manual disable when all readiness conditions are met. Unrelated
workgroups are not enabled by a global migration or prefix-only scan.
The AWS root-account email is not used to enroll a member: the explicit account
owner (`vars["cov:owner"]`) is used. An existing AD owner is still preserved.

The `awsAccountManaged` list-response field tells the legacy asset synchronizer to
skip these groups. Backend assignment paths reject direct assets, and account
assignment rejects a second account on managed groups. Dry-run reports planned
memberships and link removals without writing anything. Review its output first.

An empty group cannot be made complete without a matching existing user or AD
membership. It remains disabled and is reported as incomplete, rather than
inventing a user. Import output includes `membersAdded`, `assetsRemoved`,
`emptyWorkgroups`, `disabledWorkgroups`, and per-workgroup
`memberOutcome`/`emptyMembership`/`statusOutcome`/`statusReason`.
Status outcomes are `ENABLED`, `DISABLED`, `UNCHANGED_ENABLED`, or
`UNCHANGED_DISABLED`; previews use `WOULD_ENABLE`/`WOULD_DISABLE` for transitions.
Reasons are `READY`, `MISSING_OWNER`, `NO_MEMBERS`, `ACCOUNT_MISMATCH`,
`DIRECT_ASSETS_REMAIN`, or `MEMBERSHIP_LIMIT`. Skipped reconciliation is
`NOT_EVALUATED`. Disabled/incomplete results cause CLI exit 1, including dry-run.
Account conflicts are reported without replacing existing assignments or enabling
the group. Dry-run includes planned owner/member/account additions and link removals,
but changes no data. Status writes and access-cache invalidation are transactional.

## Where the display name is stored

`user_mapping.aws_account_name` (migration **V260**, nullable, not backfilled).

It is deliberately **not** part of `uk_user_mapping_composite`
(`email, aws_account_id, domain, ip_address`): the display name describes the *account*,
not the identity of the mapping. A renamed account therefore updates the existing row
instead of forking a second one. For the same reason a re-import refreshes the name on
rows it skips as duplicates — otherwise the correction path would be blind to every
account whose mappings already existed.

Names longer than 255 characters are dropped rather than truncated (a truncated name
would resolve to a different, wrong workgroup) and the drop is reported in `errors[]`.

## Edge cases and what they do

| Situation | Outcome |
|---|---|
| Workgroup exists, any casing | Linked to it; no workgroup created |
| Workgroup missing | Created, then linked; `workgroupCreated: true` in the report |
| Account already in that workgroup | `alreadyLinked`; owner membership and direct asset links still reconciled |
| Same account + name repeated in one file | Deduped before any DB work |
| Two imports create the same workgroup concurrently | The loser re-reads and links to the winner's workgroup |
| Two accounts share a display name | Conflict: neither is selected; no workgroup changes |
| Display name with `_`, `.`, `/`, or `aws-<name>` > 100 chars | Error row; nothing created |
| Account id not 12 digits | Error row; no workgroup lookup at all |
| Display name blank/absent | Not a linking candidate; the mapping imports normally |
| One bad row among many | Only that row fails; the rest still link |
| More than 20,000 (account, name) pairs | Capped, logged and flagged `truncated` — re-run to continue |

### Renamed accounts

When an account carries several display names (renamed between imports), the correction
path links it under the **most recently updated** mapping's name. The assignment made
under the previous name is left in place: removing one revokes a workgroup's access to
that account's assets, which a correction run must never do unasked. Remove it
deliberately instead:

```bash
# via the API
DELETE /api/workgroups/{workgroupId}/aws-accounts/{awsAccountId}
```

or MCP `remove_workgroup_aws_account`.

## Authorization

Every entry point is ADMIN-only:

- REST — `@Secured("ADMIN")` on both `/bulk` and `/link-workgroup-accounts`
- MCP — User Delegation required **plus** an ADMIN check in `execute()`, with entries in
  both `McpToolPermissions.LISTING` and `.CALLING` under `WORKGROUPS_WRITE` (the same
  permission as `add_workgroup_aws_account`, because the effect is the same)
- CLI — authenticates as an ADMIN and goes through those endpoints

Every workgroup creation and every account assignment is written to the audit log with
actor, target and outcome (`operation=CREATE_WORKGROUP_FOR_AWS_ACCOUNT`,
`operation=LINK_WORKGROUP_ACCOUNT`).

## Reading the result

All surfaces report the same shape, and the same three shapes used by `riskAssessments`
and `onboarding`:

| Field | Meaning |
|---|---|
| `processed` | Distinct (account, display name) pairs considered |
| `workgroupsCreated` | Workgroups created (or, in a dry run, that would be) |
| `linked` | New assignments made (or that would be) |
| `alreadyLinked` | Account assignments that already existed; other relationships may still change |
| `failed` | Rows carrying an `error` |
| `truncated` | More exists than this run reported; re-run to continue |
| `links[]` | Per-account detail, capped at 500 rows (the counters stay exact) |

A CLI exit status of 1 means a failed row, owner conflict, or unresolved empty
membership. `alreadyLinked` alone does not cause one.

## Testing

Two tiers, and they answer different questions.

`WorkgroupAccountLinkServiceTest` covers the naming rule itself with cases over the
table above, including both concurrency variants — but it mocks the repositories, so
it can prove the rule and nothing downstream of it.

`AwsWorkgroupReconciliationIntegrationTest` uses the disposable test database to
verify owner enrollment, zero direct links, retained assets and unrelated links,
account-based visibility, disabled-group denial, and repeated-import idempotency.

`/aws-account-workgroup-import` covers what only a running stack can show:

```bash
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-aws-account-workgroup-import.sh --verbose
```

It drives all four linking surfaces plus the XLSX path that deliberately links
nothing, checks that they agree on the summary, and — the reason it exists — asserts
that a member of `aws-<display name>` can actually see the account's assets, and
stops being able to when the account is unlinked. This verifies rule #9 through
the import surfaces, complementing the database regression test above.

## Related

- `docs/S3_USER_MAPPING_IMPORT.md` — where the mapping files come from
- `docs/CLI.md` — `manage-user-mappings` reference
- `docs/MCP.md` — `import_user_mappings`, `link_workgroup_aws_accounts`
- `docs/workgroup-feature-documentation.md` — workgroups and access rule #9
- `testdata/user-mappings/` — runnable samples
# Account-owner fallback

JSON mapping imports (`manage-user-mappings import` and `import-s3`) carry the
validated `vars["cov:owner"]` email to the matching `aws-<display_name>` workgroup.
The top-level AWS root `email` remains a mapping fallback but is never inferred
to be a workgroup owner. A nonblank but invalid `vars["cov:owner"]` (for example,
a person's name) no longer blocks that fallback: if the top-level email is valid,
the CLI imports the mapping with `ownerEmail` unset and logs a warning identifying
the record and validated account ID. Neither address being valid still rejects
the record. Existing workgroup owners are preserved. Account
assignment and owner assignment are independent: an already-linked account can
still fill an empty owner. Concurrent writes use an atomic empty-owner check.

Distinct owner candidates for the same workgroup in one import are reported as
conflicts; account linking still proceeds. Dry-run reports proposed assignments
without writing. Filling an owner enables the group only if all readiness conditions
above hold; an owner alone is insufficient.
Import summaries include owner assignments, preserved owners, and conflicts.

AD import creates groups even when Graph owner lookup fails or is ambiguous,
and preserves an existing owner when no AD owner is available. A later resolved
AD owner retains the existing AD import update behavior. Member lookup failures
do not prevent group creation or remove existing membership.

The stored-mapping `link-workgroups` correction command only repairs account
links: stored email mappings do not prove account-owner provenance. Re-import
the source JSON to backfill owners safely.
