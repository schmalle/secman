# S3 User-Mapping Import

Spec: `specs/065-s3-user-mapping-import/`.

Four S3 subcommands on `manage-user-mappings`:

| Sub | Purpose | Hits backend? | Disk write? |
|---|---|---|---|
| `list-bucket` | discover keys in a bucket / prefix | no | no |
| `import-s3` | download + POST to backend | yes | temp only |
| `download-s3` | download to a local file | no | yes |
| `print-s3` | download + parse + print to stdout | no | no (temp deleted) |

All share a 10 MB hard size cap. Default credential chain: env → `~/.aws/credentials` → IAM role → SSO. Override with `--aws-region`, `--aws-profile`, `--aws-{access-key-id, secret-access-key, session-token}`, `--endpoint-url` (also `AWS_ENDPOINT_URL`, used for S3Mock/MinIO/LocalStack — auto-enables path-style).

## Prereqs

Build CLI: `./gradlew :cli:shadowJar`.

IAM (per-bucket):
```json
{ "Version": "2012-10-17", "Statement": [
  { "Effect": "Allow", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::your-bucket/*" },
  { "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::your-bucket" }
] }
```
- `import-s3` / `download-s3` / `print-s3` — `s3:GetObject` authorizes both GetObject and HeadObject; there is no `s3:HeadObject` IAM action. A denied metadata check falls back to the download and a post-download size check.
- `list-bucket` — `s3:ListBucket`.

Env: `SECMAN_ADMIN_EMAIL` (or `--admin-user`) for audit logging.

## `list-bucket`

```bash
./scripts/secmanng manage-user-mappings list-bucket --bucket my-bucket
./scripts/secmanng manage-user-mappings list-bucket --bucket my-bucket --prefix user-mappings/
./scripts/secmanng manage-user-mappings list-bucket --bucket my-bucket --prefix user-mappings/ \
  --aws-profile production --aws-region eu-west-1
```

Output:
```
Key                           Size    Last Modified
user-mappings/latest.csv      2.3 KB  2026-02-13 10:30:00
user-mappings/teams.json      4.1 KB  2026-02-10 14:22:00
```

Options: `--bucket -b` (req), `--prefix -p`, `--aws-region`, `--aws-profile`, `--endpoint-url`, `--admin-user -u`.

## `import-s3` (download + push to backend)

```bash
./scripts/secmanng manage-user-mappings import-s3 --bucket my-bucket --key user-mappings/latest.csv
./scripts/secmanng manage-user-mappings import-s3 --bucket my-bucket --key …  --dry-run
./scripts/secmanng manage-user-mappings import-s3 --bucket my-bucket --key …  --aws-profile prod
./scripts/secmanng manage-user-mappings import-s3 --bucket my-bucket --key … --aws-region eu-west-1
./scripts/secmanng manage-user-mappings import-s3 --bucket my-bucket --key …  --format JSON
```

Options: `--bucket -b` (flag or `AWS_ACCOUNT_BUCKET_NAME` env var), `--key -k` (flag or `AWS_ACCOUNT_BUCKET_KEY_NAME` env var) — flags take priority over the env vars; `--format` (`AUTO|CSV|JSON`, default `AUTO`), `--dry-run`, `--admin-user -u` (or `$SECMAN_ADMIN_EMAIL`), all AWS shared options.

Cron-safe wrapper:
```cron
0 2 * * * secman /opt/secman/bin/secman manage-user-mappings import-s3 \
  --bucket my-bucket --key user-mappings/latest.csv \
  >> /var/log/secman/s3-import.log 2>&1 \
  || echo "S3 import exit $?" | mail -s "secman S3 import failure" ops@company.com
```

Exit codes: `0` ok / `1` partial errors (imported what it could) / `2` fatal S3/config (won't succeed on retry) / `3` unexpected.

## `download-s3` (no backend)

```bash
./scripts/secmanng manage-user-mappings download-s3 \
  --bucket my-bucket --key user-mappings/latest.csv --output ./aws-mappings.csv

./scripts/secmanng manage-user-mappings download-s3 \
  --bucket my-bucket --key user-mappings/latest.csv \
  --aws-profile production --aws-region eu-west-1 \
  --output ./aws-mappings.csv --force

./scripts/secmanng manage-user-mappings download-s3 \
  --bucket my-bucket --key user-mappings/latest.csv \
  --output /var/lib/secman/aws-mappings.csv --force --quiet      # cron mode
```

Options: `--bucket -b`, `--key -k`, `--output -o` (all req), `--force -f` (overwrite), `--quiet -q` (success/error still printed to **stderr**), all AWS shared options.

Constraints: 10 MB cap; parent dir must exist (no auto-mkdir); verbatim copy (no validation — use `import-s3 --dry-run` to validate).

Exit codes: `0` ok / `1` I/O / `2` fatal S3/config / `3` unexpected.

## `print-s3` (no backend, no disk)

Downloads to a temp file, parses, prints to **stdout**, deletes the temp on exit.

```bash
./scripts/secmanng manage-user-mappings print-s3 --bucket my-bucket --key user-mappings/latest.csv

# JSON for downstream tooling
./scripts/secmanng manage-user-mappings print-s3 \
  --bucket my-bucket --key user-mappings/latest.csv \
  --type ALL --format JSON

# Diff S3 vs DB (note --quiet keeps stdout pure)
./scripts/secmanng manage-user-mappings print-s3 \
  --bucket my-bucket --key user-mappings/latest.csv \
  --format CSV --quiet > /tmp/s3.csv
./scripts/secmanng manage-user-mappings list \
  --type AWS --format CSV --output /tmp/db.csv
diff /tmp/s3.csv /tmp/db.csv
```

Options:
- `--bucket -b`, `--key -k` (req).
- `--type AWS|DOMAIN|ALL` (default `AWS`).
- `--format TABLE|JSON|CSV` (default `TABLE`).
- `--file-format CSV|JSON|AUTO` (source format; default `AUTO`, detected by extension/content).
- `--show-errors` — also print parse errors to stderr (otherwise dropped silently).
- `--quiet -q` — suppress header/summary on stderr.
- AWS shared options.

**stdout/stderr split**:
- `stdout` — only the parsed mappings (TABLE/JSON/CSV). Pipe-safe.
- `stderr` — banner ("Source/Scope") + summary ("Parsed N mapping(s) …"). Suppressed by `--quiet`. Parse errors only when `--show-errors`.

Exit codes: `0` ok / `1` parse errors found (valid rows still printed) / `2` fatal S3/config / `3` unexpected.

## File formats

### CSV (header required, case-insensitive)
```csv
email,type,value,display_name
alice@company.com,DOMAIN,corp.local,
alice@company.com,DOMAIN,prod.company.com,
alice@company.com,AWS_ACCOUNT,123456789012,DevOps-x
bob@company.com,DOMAIN,dev.company.com,
bob@company.com,AWS_ACCOUNT,987654321098,Legacy-y
```
- `type` = `DOMAIN` or `AWS_ACCOUNT`.
- `AWS_ACCOUNT` value = exactly 12 digits.
- `display_name` optional, AWS_ACCOUNT rows only — links the account to the workgroup
  `aws-<display_name>` (created when missing). Omit the column entirely and nothing
  changes. See `docs/AWS_ACCOUNT_WORKGROUP_LINKING.md`.
- Empty lines skipped.
- ≤100,000 rows per file.

### JSON
```json
[
  { "email":"alice@company.com",
    "domains":["corp.local","prod.company.com"],
    "awsAccounts":["123456789012"],
    "display_name":"DevOps-x" },
  { "email":"bob@company.com",
    "domains":["dev.company.com"],
    "awsAccounts":["987654321098"] }
]
```

The Cloud Custodian export shape is also accepted — a root object with an `accounts`
array whose entries carry `account_id`, `display_name` and `vars["cov:owner"]` (which
wins over the root `email`). `display_name` links the account to the workgroup
`aws-<display_name>`; entries without one import exactly as before. Sample:
`testdata/user-mappings/accounts-with-display-name.json`.

## E2E test

```bash
./tests/s3-user-mapping-import-e2e-test.sh        # uses pass-cli
DEBUG=1 ./tests/s3-user-mapping-import-e2e-test.sh
```
Required env (from Proton Pass `test/secman-s3` vault): `S3_TEST_BUCKET`, `S3_TEST_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `SECMAN_ADMIN_EMAIL`. Optional `SECMAN_BASE_URL` (default `http://localhost:8080`).

Flow: build JAR → upload test CSV → `import-s3 --dry-run` → `import-s3` → list mappings → cleanup.

## S3Mock for local dev

```bash
docker run -p 9090:9090 adobe/s3mock
aws s3api create-bucket --bucket test --endpoint-url http://localhost:9090
aws s3api put-object --bucket test --key mappings.csv --body ./test-mappings.csv \
  --endpoint-url http://localhost:9090

./scripts/secmanng manage-user-mappings list-bucket --bucket test --endpoint-url http://localhost:9090
./scripts/secmanng manage-user-mappings import-s3 --bucket test --key mappings.csv \
  --endpoint-url http://localhost:9090 --dry-run

# Or via env:
export AWS_ENDPOINT_URL=http://localhost:9090
./scripts/secman manage-user-mappings list-bucket --bucket test
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `AWS credentials not found` | export `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`, or `aws configure`, or `--aws-profile` |
| `Access denied` (403) | Check the AWS-stated denial, selected identity, IAM and bucket policy, and exact key. A missing key can also return 403 without `s3:ListBucket`; do not assume GetObject permission alone is missing |
| `Bucket does not exist` | spelling (lowercase, case-sensitive); region (`--aws-region`) |
| `NoSuchKey` | key path; `aws s3 ls s3://bucket/path/`; case-sensitive |
| `File exceeds 10MB` | split into batches |
| `>100,000 records` | split files |
| Network error | connectivity; region matches bucket region; firewall/proxy |

## Security

- Temp files: system temp dir, owner-only `0600`, deleted on exit (even on error).
- Prefer the default SDK credential chain (env vars, `~/.aws/credentials`, IAM role, SSO) or `--aws-profile` over raw `--aws-access-key-id`/`--aws-secret-access-key`/`--aws-session-token` flags — those are supported (§ above) but visible in shell history and process listings, so avoid them on shared hosts.
- 10 MB pre-download cap; 100k-row cap.
- Stack traces only at debug level.
- Audit log entries for every operation (admin email, op type, mapping details).
- All inputs (bucket, key, email, domain, AWS account ID) validated before use.


## Welcome every owner of a newly discovered AWS account

```bash
./scripts/secman manage-user-mappings import --file mappings.csv \
  --onboarding-mode WELCOME_ONLY --dry-run
./scripts/secman manage-user-mappings import --file mappings.csv \
  --onboarding-mode WELCOME_ONLY
# Same options for an S3 mapping upload (bucket/key from AWS_ACCOUNT_* env vars):
./scripts/secman manage-user-mappings import-s3 \
  --onboarding-mode WELCOME_ONLY --dry-run
```

ADMIN access and an active email provider are required for live delivery. The
existing welcome template is configured in Account Onboarding. Each distinct
mapped owner of a newly discovered AWS account is eligible; adding an owner to a
known account does not trigger this event. Dry run saves nothing and contacts no
mail provider. FAILED or uncertain PENDING welcome delivery exits with code 1,
even if mappings were saved. Inspect the notification before retrying.

See [recipient rules, template setup, delivery statuses, and retries](ACCOUNT_ONBOARDING.md#welcome-every-owner-of-a-newly-discovered-aws-account).
Both `import --help` and `import-s3 --help` include welcome-only preview examples.

### AWS credential precedence for `import-s3`

Explicit `--aws-access-key-id` and `--aws-secret-access-key` flags (with
`--aws-session-token` for temporary credentials) take priority over `--aws-profile`.
An explicit profile takes priority over environment credentials, including those
resolved by `./scripts/secman`. Without either override, the SDK default credential
chain resolves the environment and configured AWS providers. Supply explicit
credentials as a complete set; flags are never combined with environment values.
Blank or incomplete explicit credentials are rejected.

A 403 report identifies HeadObject or GetObject and retains AWS's diagnostic reason
and request ID. Verify the exact key before changing permissions. For cross-account
access, the bucket policy must also permit the selected identity. ListBucket is
required for listing and lets S3 distinguish a missing key (404) from denied access;
it is not a blanket prerequisite for downloading a known, permitted object.

### Expired AWS sessions and metadata errors

A generic HeadObject HTTP 400 without an AWS error code triggers a GetObject
attempt to obtain the detailed AWS error. Known metadata errors, such as a wrong
signing region, remain errors. Downloads enforce the 10 MB limit while streaming;
failed or oversized downloads remove their temporary file.

`ExpiredToken` reports that the session credentials have expired. Refresh the
access key, secret key, and session token together in the credential source, or
use a valid `--aws-profile`, then rerun. The CLI does not renew vault credentials
or change AWS policies. `import-s3` exits 2 on this error before importing rows or
sending owner mail. S3 download errors identify HeadObject or GetObject and retain
AWS's error code and request ID.
