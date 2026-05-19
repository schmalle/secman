# `secman asset-match-clear`

Reconcile secman's AWS asset inventory against an authoritative resource
snapshot stored in S3. Any AWS asset in secman whose `cloudInstanceId` is
missing from the snapshot — but whose `cloudAccountId` IS covered by the
snapshot — is deleted.

## Why

Cloud inventory drifts. EC2 instances get terminated upstream but the records
linger in secman, polluting vulnerability counts and asset dashboards. This
command treats an externally-produced JSON snapshot of "currently existing
AWS resources" as ground truth and removes the leftovers.

It is the complement to `delete-asset-not-seen`, which keys off CrowdStrike
import timestamps. Use `asset-match-clear` when you have a clean, authoritative
list of AWS resources (for example, an AWS Config / Resource Explorer dump
parked in S3).

## Snapshot format

A JSON array of objects. Only `accountId` and `resourceId` are read; any
other fields (`awsRegion`, `tags`, …) are ignored.

```json
[
  {
    "accountId": "199942131465",
    "resourceId": "i-0001e614fcd21166d",
    "awsRegion": "eu-central-1",
    "tags": [ ... ]
  },
  ...
]
```

Entries missing `accountId` or `resourceId` are skipped (and counted in the
verbose output). The snapshot file is capped at **10 MB** (a limit inherited
from the shared `S3DownloadService`).

`resourceId` is matched case-insensitively against `Asset.cloudInstanceId`.

## How matching works

1. The CLI parses the snapshot and extracts two sets:
   - `accountIds`  — every distinct `accountId` it saw
   - `resourceIds` — every distinct `resourceId` it saw (lower-cased)
2. The backend loads every asset with non-empty `cloudInstanceId` whose
   `cloudAccountId` is in `accountIds` (the **scoped set**).
3. From that scoped set, any asset whose `cloudInstanceId` is **not** in
   `resourceIds` becomes a delete candidate.
4. The safety brake (see below) is evaluated.
5. In delete mode, candidates are removed via `AssetCascadeDeleteService`,
   which also clears vulnerabilities, scan results, exception requests,
   and audit-logs the operation.

**Partial-snapshot safety**: assets in accounts NOT present in the snapshot
are NEVER touched. If your snapshot only covers account `111` you can never
accidentally wipe assets in account `222`.

## Check mode

`--check` is read-only. It downloads and parses the same S3 snapshot, then
checks whether every downloaded `resourceId` exists as a `cloudInstanceId` in
the secman asset inventory. It does **not** call the match-clear delete
endpoint, does **not** evaluate delete candidates, and does **not** delete
assets. If `--check` is set, `--save` is ignored so no snapshot copy is
written to `/tmp/asset.json`.

Use this when you want to validate whether AWS instance IDs from the snapshot
are already present in secman:

```bash
secman asset-match-clear --check --verbose
./scripts/clearmatchasset.sh --check --verbose
```

The summary reports how many downloaded instance IDs exist in secman and how
many are missing. With `--verbose`, missing downloaded instance IDs are listed.

`--strict` does not change check mode; strict scope only affects delete and
dry-run matching.

## Check-fix mode

`--check-fix` is mutating, but it never calls the match-clear delete endpoint.
It downloads and parses the snapshot, compares downloaded `resourceId`s to
secman `cloudInstanceId`s case-insensitively, and creates missing assets via
`PUT /api/assets/import`.

Created assets use these fixed values:

| Field | Value |
|---|---|
| `name` | snapshot `resourceId` |
| `type` | `SERVER` |
| `owner` | `AWS Asset Inventory` |
| `description` | `Auto-created by asset-match-clear --check-fix from AWS snapshot` |
| `cloudAccountId` | snapshot `accountId` |
| `cloudInstanceId` | snapshot `resourceId` |

If the same lower-cased `resourceId` appears with multiple `accountId` values,
the CLI skips that resource as ambiguous instead of creating an asset with the
wrong account. `--check-fix` cannot be combined with `--check` or `--dry-run`;
`--save` is ignored.

```bash
secman asset-match-clear --check-fix --verbose
./scripts/clearmatchasset.sh --check-fix --verbose
```

The summary reports secman IDs, downloaded IDs, missing IDs, created assets,
failed/skipped creates, and status. With `--verbose`, created and
failed/skipped resource IDs are listed with account IDs.

## Strict mode

By default, `asset-match-clear` is partial-snapshot safe: it only evaluates
secman AWS assets whose `cloudAccountId` appears in the downloaded snapshot.

`--strict` treats the snapshot as globally authoritative across all secman AWS
assets with a non-empty `cloudInstanceId`. In strict mode, an AWS asset can
become a delete candidate even when its `cloudAccountId` is absent from the
snapshot.

Only use `--strict` when the snapshot is known to cover the full AWS resource
universe you want secman to reconcile against. The safety brake still applies
to real delete runs.

## Safety brake

`--max-delete-percent` (default `25`) aborts a real run if the number of
delete candidates exceeds N% of the scoped account total. Trips with
`status=ABORTED_SAFETY_BRAKE` and exit code 2.

| Value | Behaviour |
|---|---|
| `--max-delete-percent 0` | brake disabled |
| `--max-delete-percent 25` (default) | abort when proposed deletion > 25% |
| `--max-delete-percent 99` | brake-of-last-resort (only stops 100%-deletion runs) |
| `--max-delete-percent 100` or higher | equivalent to disabling |

The brake only applies to real runs; `--dry-run` always reports the full
candidate list regardless of percentage.

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `AWS_ASSET_BUCKET_NAME` | yes (unless `--bucket`) | S3 bucket name |
| `AWS_ASSET_BUCKET_KEY_NAME` | yes (unless `--key`) | S3 object key (path to JSON) |
| `AWS_REGION` | optional | S3 region (SDK chain fallback otherwise) |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` | optional | Standard AWS SDK credentials |
| `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS` | yes | Backend auth |
| `SECMAN_HOST` / `SECMAN_BACKEND_URL` | optional | Backend URL; defaults to `http://localhost:8080` |
| `SECMAN_INSECURE` | optional | Accept self-signed TLS (or use `--insecure`) |

The shared `pass-cli` convention applies — fetch secrets from Proton Pass,
never hardcode.

## IAM permissions

Attach to the AWS principal running the CLI:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:HeadObject"],
      "Resource": "arn:aws:s3:::<AWS_ASSET_BUCKET_NAME>/<key-prefix>/*"
    }
  ]
}
```

`s3:HeadObject` is optional but recommended — it lets the CLI reject files
that exceed the 10 MB cap before downloading them.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success (dry-run or real) |
| 1 | Per-asset deletion errors, S3/auth/parse failure, or pre-flight error |
| 2 | Safety brake tripped — no assets deleted |

## Typical workflow

```bash
# 1. Configure once.
export AWS_ASSET_BUCKET_NAME=cov-aws-inventory
export AWS_ASSET_BUCKET_KEY_NAME=cov-instances/latest.json
export AWS_REGION=eu-central-1
export SECMAN_ADMIN_NAME=$(pass-cli get secman/admin/username)
export SECMAN_ADMIN_PASS=$(pass-cli get secman/admin/password)

# 2. Always start with a dry-run.
secman asset-match-clear --dry-run --verbose

# 3. Optionally create secman assets missing from the AWS snapshot.
secman asset-match-clear --check-fix --verbose

# 4. Review the candidate list. If it looks right, execute.
secman asset-match-clear

# 5. If the safety brake trips, investigate before raising the threshold.
secman asset-match-clear --max-delete-percent 40    # only if justified
```

## Cron example

```cron
# Daily at 03:30 UTC — fail-safe by default (safety brake at 25%).
30 3 * * *  pass-cli run -- secman asset-match-clear >> /var/log/secman/asset-match-clear.log 2>&1
```

If the brake trips, the run exits 2 and your monitoring will alert. Don't
auto-raise the threshold — investigate the source snapshot first.

## Troubleshooting

| Symptom | Cause / Fix |
|---|---|
| `S3 bucket required. Use --bucket …` | Set `AWS_ASSET_BUCKET_NAME` or pass `--bucket`. |
| `S3 error: Access denied` | Add `s3:GetObject` (and ideally `s3:HeadObject`) to the IAM role. |
| `S3 error: File … not found` | Wrong key, or the upstream job hasn't written today's snapshot yet. |
| `Snapshot root is not a JSON array` | Snapshot must be a top-level JSON array, not an object. |
| `Snapshot has no usable (accountId, resourceId) entries` | Every row was missing one of the two required fields. |
| `Refusing to treat as authoritative` (HTTP 400 from backend) | Snapshot was empty after de-dup. The backend refuses to act on it. |
| `Safety brake tripped` (`status=ABORTED_SAFETY_BRAKE`, exit 2) | Proposed deletion exceeds `--max-delete-percent`. Investigate the snapshot for missing rows before lowering the bar. |
| `username looks like an unresolved pass-cli reference` | Shell expanded `$VAR` before `pass-cli run --`. Drop the redundant flag or use `$(pass-cli get ...)`. |
| `Authentication failed (HTTP 401 / invalid credentials)` | Re-run with `--verbose` to see exactly which user/source/URL the CLI used. |

## See also

- `secman help delete-asset-not-seen` — timestamp-based stale-asset cleanup.
- `docs/CROWDSTRIKE_IMPORT.md` — adjacent inventory reconciliation pattern.
- `docs/ENVIRONMENT.md` — full env-var reference.
