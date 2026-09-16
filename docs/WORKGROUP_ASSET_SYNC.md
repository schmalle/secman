# AWS assets from workgroup member ownership

The Python AD importer now has a `sync-workgroup-assets` command. It uses
information already imported into SecMan; it does not contact Azure AD, AWS,
or CrowdStrike. Run the AD membership and AWS/CrowdStrike imports first.

From the repository root, with `pass-cli` authenticated and `uv` installed:

```bash
./scripts/sync-workgroup-assets.sh --dry-run
./scripts/sync-workgroup-assets.sh
./scripts/sync-workgroup-assets.sh --dry-run
```

Python 3.11+ is required. For help without resolving credentials or contacting
SecMan, run:

```bash
uv run --locked --project src/adread python src/adread/read.py sync-workgroup-assets --help
```

The wrapper resolves `SECMAN_BACKEND_URL`, `SECMAN_ADMIN_NAME`, and
`SECMAN_ADMIN_PASS` from the existing Test/SECMAN Proton Pass fields unless
those variables are already supplied. It installs the locked Python dependencies
and calls the existing argparse entry point:

```bash
uv run --locked --project src/adread python src/adread/read.py sync-workgroup-assets --dry-run
```

The direct invocation requires the same three environment variables. Both
credential wrappers resolve them for the client. No Azure credentials are needed.
The backend must use HTTPS with a trusted certificate; use `REQUESTS_CA_BUNDLE`
for your organization's CA.
The command rejects `--insecure` and a true `SECMAN_INSECURE`. It requires an
ADMIN login and refuses HTTP redirects. Existing AD import invocations retain
their argument structure.

| Environment variable | Proton Pass wrapper default / purpose |
|---|---|
| `SECMAN_BACKEND_URL` | `pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL`; required HTTPS base URL |
| `SECMAN_ADMIN_NAME` | `pass://Test/SECMAN/SECMAN_ADMIN_NAME` |
| `SECMAN_ADMIN_PASS` | `pass://Test/SECMAN/SECMAN_ADMIN_PASS` |
| `REQUESTS_CA_BUNDLE` | Optional trusted PEM CA bundle; otherwise Python's default CA trust |
| `LOG_LEVEL` | Optional log level; defaults to `INFO` |

This command runs through the Python entry point; `./scripts/secman
sync-workgroup-assets` is not a registered Kotlin command. `SECMAN_HOST` and
Kotlin CLI configuration files are not read by this Python client. The supported
sync options are `--dry-run` and `--help`/`-h`; the shared parser's `--import` and
`--insecure` options are rejected in sync mode.

## AWS Secrets Manager for production

Use `scripts/sync-workgroup-assets-aws.sh` with the same preview/apply options.
It requires AWS CLI, `jq`, `uv`, Python 3.11+, and an AWS identity allowed to read
the selected secret. Proton Pass and the Kotlin CLI JAR are not required.
See [AWS secret setup](AWS_SECRETS_SETUP.md#production-workgroup-asset-sync).

Set your production secret name or ARN and its region, then preview and apply:

```bash
export SECMAN_AWS_SECRET_ID=prod/secman/credentials
export AWS_REGION=eu-central-1
# Optional for a named AWS CLI profile; omit when using an instance role:
# export AWS_PROFILE=production

./scripts/sync-workgroup-assets-aws.sh --dry-run
./scripts/sync-workgroup-assets-aws.sh
./scripts/sync-workgroup-assets-aws.sh --dry-run
```

Replace the example secret name and region with your deployment's values.
`SECMAN_AWS_SECRET_ID` must be explicit; this wrapper does not fall back to
`secman/dev`. Region resolution uses `AWS_REGION`, then `AWS_DEFAULT_REGION`,
then `eu-central-1`, following the shared AWS helper.

The secret must be a JSON object with these non-empty string fields:

| Secret field | Exported environment variable |
|---|---|
| `SECMAN_BACKEND_BASE_URL` | `SECMAN_BACKEND_URL`; verified HTTPS backend URL |
| `SECMAN_ADMIN_NAME` | `SECMAN_ADMIN_NAME`; existing SecMan ADMIN account |
| `SECMAN_ADMIN_PASS` | `SECMAN_ADMIN_PASS`; that account's password |

The existing production field `SECMAN_BACKEND_URL` is also accepted when
`SECMAN_BACKEND_BASE_URL` is absent or null. When both are supplied, the base-URL
field wins. All three settings come from one secret fetch and replace inherited
SecMan credentials. Missing/invalid fields or a failed AWS fetch stop the wrapper
before it launches synchronization; it does not fall back to Proton Pass.

Only these three fields are exported from the secret. Database, Azure,
CrowdStrike and application-side AWS key fields are unnecessary. The wrapper
does not load `SECMAN_SSL_ACCEPT_ALL` or `SECMAN_INSECURE` from the secret;
certificate verification remains enabled. `REQUESTS_CA_BUNDLE` and an inherited
`SECMAN_INSECURE` are handled by the Python client as described above. Credentials
are passed through the environment, never as command-line arguments.

## Data flow

| Source | Existing model / API | Use |
|---|---|---|
| AD groups and members | `src/adread/read.py --import` → `Workgroup`, `User.workgroups` | Reads actual direct membership, including users created by the importer |
| Membership snapshot | `GET /api/workgroups`; `GET /api/users?includeWorkgroups=true` | Builds normalized email → workgroup ID sets |
| AWS owner imports | Kotlin CLI `manage-user-mappings import` / `import-s3` → `UserMapping` | Each row links one email to an optional `awsAccountId`; multiple rows support multiple owners |
| Owner snapshot | Paged `GET /api/user-mappings/current` and `/applied-history` | Both partitions count: applied mappings still participate in existing AWS asset access |
| CrowdStrike assets | `CrowdStrikeVulnerabilityImportService` → `Asset.cloudAccountId` | `GET /api/assets` supplies account IDs and existing workgroup IDs |
| Missing associations | `POST /api/workgroups/{id}/assets`, `{"assetIds": [...]}` | Reuses `WorkgroupService.assignAssetsToWorkgroup` and `asset_workgroups` |

`AwsAccount` stores account display names, not owner emails. Neither display-name
matching nor the separate `WorkgroupAwsAccount` account-visibility relationship
is changed. See [AWS account display-name linking](AWS_ACCOUNT_WORKGROUP_LINKING.md)
for that existing, different command.

Email matching trims whitespace, folds case, and rejects malformed mailbox
values. AWS IDs must be strings of exactly 12 ASCII digits (surrounding whitespace
is trimmed), preserving leading zeroes. Each owner can belong to several
workgroups, and every mapped owner participates. Sets deduplicate desired links;
workgroup IDs and asset IDs are processed in sorted order.

All existing workgroups participate, regardless of their names. The `AWS-`
prefix filter belongs to the AD import mode only. Membership inherited through
a parent workgroup is not expanded by synchronization.

## Reconciliation and diagnostics

The command computes desired links minus existing links before making writes.
Repeated runs with unchanged data produce zero additional links. Each write
batch contains at most 500 asset IDs for one workgroup.

**All existing links are preserved, including stale automatically added links.**
`asset_workgroups` stores only the two foreign keys, with no manual/automatic
provenance. Removing stale links would risk deleting manual assignments. This
command never deletes relationships, changes memberships, or creates accounts,
users, workgroups, or assets. Assets without an AWS account are untouched.

Dry-run reads the complete inputs and computes the same plan, but sends no
assignment requests. Authentication still occurs. A completed run writes one
JSON summary to standard output with these fields:

| Field | Meaning |
|---|---|
| `workgroups_evaluated` | Unique valid workgroup IDs, including empty groups |
| `members_evaluated` | User records with direct memberships; a user in several groups counts once |
| `unique_email_addresses` | Distinct valid normalized member emails |
| `aws_accounts_matched` | Distinct accounts linked to at least one member, including accounts with no assets |
| `assets_matched` | Distinct valid AWS assets resolved to a workgroup, including already assigned assets |
| `relationships_to_add` | Missing links calculated before writes |
| `relationships_to_remove` | Always zero because provenance is unavailable |
| `relationships_added` | Links in successful assignment batches; zero in dry-run |
| `skipped_accounts` | Distinct accounts seen in mappings or assets with no matching owner/member |
| `errors` | Malformed records skipped plus failed assignment batches |
| `dry_run` | Whether this execution only planned changes |

Example preview for one workgroup, member, account, and missing asset link
(formatted for readability):

```json
{
  "dry_run": true,
  "workgroups_evaluated": 1,
  "members_evaluated": 1,
  "unique_email_addresses": 1,
  "aws_accounts_matched": 1,
  "assets_matched": 1,
  "relationships_to_add": 1,
  "relationships_to_remove": 0,
  "relationships_added": 0,
  "skipped_accounts": 0,
  "errors": 0
}
```

After applying this plan, rerunning with unchanged data reports
`relationships_to_add: 0`. On a request/configuration failure inside the run,
stdout instead reports `aborted: true` and `errors: 1`. Missing required
environment variables or invalid arguments can exit before emitting JSON;
automation should check the exit status and stderr as well as the summary.

Missing, invalid, or unknown owner emails are valid account skips. Account IDs
are logged for investigation. Malformed member/asset records are skipped with
numeric record IDs; a missing asset workgroup collection is not assumed empty.
Missing owners for accounts with neither assets nor mappings cannot be reported
from these APIs.

Failed/incomplete collection reads abort before any assignment. A failed write
batch is reported as unconfirmed, stops further batches for that workgroup, and
allows other workgroups to proceed. Successful earlier batches remain. Rerun to
reconcile after resolving the error: a timeout can occur after a server commit.
An interrupted process similarly leaves completed batches intact.

Exit status is `0` for a successful run (including valid account skips), `1` for
record/write/configuration errors, `2` for invalid arguments, and `130` for a
keyboard interrupt. Logs go to stderr and contain actor, IDs, counts and outcomes;
response bodies, passwords, cookies and tokens are not logged by the sync flow.

## Execution and scale

Run periodically after imports, with only one synchronization process at a time.
This command does not install a scheduler. Membership reads, owner reads, asset
reads, and batch writes are separate requests, so there is no global transaction
or protection from concurrent source edits. Schedule during a quiet import window.

Reads cost three bulk requests plus the mapping pages; writes cost one request
per 500 missing links per workgroup. There are no per-member or per-account HTTP
lookups. In-memory indexes scale with inputs and desired associations. Existing
user/workgroup/asset endpoints return full collections, and the existing server
assignment service loads each asset inside a batch. These backend limitations
remain; the command adds no new query or parallel persistence model.

## Troubleshooting

| Symptom | Check / action |
|---|---|
| Missing configuration | Use the Proton Pass or AWS wrapper, with all three required SecMan fields in the selected secret. The AWS wrapper also requires `SECMAN_AWS_SECRET_ID`. |
| AWS fetch failed | Verify the secret name/ARN, region and the AWS identity's permission to read it. |
| `http_status=401` / `403` | Verify the selected backend and ADMIN credentials in your secret store; the command does not accept a delegated MCP identity. |
| `SSLError` | Verify the HTTPS URL and certificate chain; set `REQUESTS_CA_BUNDLE` to a trusted CA bundle for a private CA. |
| Invalid-arguments exit with `SECMAN_INSECURE` | Unset the legacy TLS bypass or set it to false; keep certificate verification enabled. |
| Zero additions with skipped accounts | Check direct workgroup membership, member/owner emails, AWS mapping rows and 12-digit asset `cloudAccountId` values. |
| Invalid asset workgroup collection | Inspect the asset identified in stderr; the sync skips it rather than assuming no existing links. |
| Unconfirmed batch / interrupted run | Resolve the request failure, then preview and rerun; already committed links are retained and deduplicated. |
| An old assignment remains after a member leaves | Expected: manual and derived links have no source marker. Review and remove obsolete links through existing workgroup management. |

## Verification

Run the offline fixture tests from the repository root:

```bash
uv run --locked --project src/adread python -m unittest discover -s tests -p test_sync_workgroup_assets.py -v
uv run --locked --project src/adread python -m unittest discover -s tests -p test_sync_workgroup_assets_aws.py -v
```

Tests cover the ownership combinations, normalization, duplicate records,
missing/unknown owners, invalid records, manual/stale preservation, full dry-run,
repeat-run idempotency, mapping pagination including applied owners, bounded
batching, read/write failures, authentication, TLS policy, and CLI exit status.
The CLI fixture executes dry-run, apply, and apply again with a simulated SecMan
client; it does not write to the configured live instance.
The AWS wrapper tests require `jq` and use simulated `aws`/`uv` executables with
the real shared helper. They cover secret selection, URL compatibility,
credential/argument forwarding, failure handling and exit status without
contacting AWS or SecMan.
