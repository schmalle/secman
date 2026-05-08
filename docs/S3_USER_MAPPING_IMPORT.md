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
{ "Effect":"Allow",
  "Action": ["s3:GetObject","s3:HeadObject","s3:ListBucket"],
  "Resource":["arn:aws:s3:::your-bucket","arn:aws:s3:::your-bucket/*"] }
```
- `import-s3` / `download-s3` / `print-s3` — `s3:GetObject` (required) + `s3:HeadObject` (recommended; falls back to post-download size check).
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

Options: `--bucket -b`, `--key -k` (both req), `--format` (`AUTO|CSV|JSON`, default `AUTO`), `--dry-run`, `--admin-user -u` (or `$SECMAN_ADMIN_EMAIL`), all AWS shared options.

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
email,type,value
alice@company.com,DOMAIN,corp.local
alice@company.com,DOMAIN,prod.company.com
alice@company.com,AWS_ACCOUNT,123456789012
bob@company.com,DOMAIN,dev.company.com
bob@company.com,AWS_ACCOUNT,987654321098
```
- `type` = `DOMAIN` or `AWS_ACCOUNT`.
- `AWS_ACCOUNT` value = exactly 12 digits.
- Empty lines skipped.
- ≤100,000 rows per file.

### JSON
```json
[
  { "email":"alice@company.com",
    "domains":["corp.local","prod.company.com"],
    "awsAccounts":["123456789012"] },
  { "email":"bob@company.com",
    "domains":["dev.company.com"],
    "awsAccounts":["987654321098"] }
]
```

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
| `Access denied` (403) | IAM lacks `s3:GetObject`/`s3:HeadObject`; bucket policy; bucket+key correct |
| `Bucket does not exist` | spelling (lowercase, case-sensitive); region (`--aws-region`) |
| `NoSuchKey` | key path; `aws s3 ls s3://bucket/path/`; case-sensitive |
| `File exceeds 10MB` | split into batches |
| `>100,000 records` | split files |
| Network error | connectivity; region matches bucket region; firewall/proxy |

## Security

- Temp files: system temp dir, owner-only `0600`, deleted on exit (even on error).
- Credentials only via SDK chain — never CLI args.
- 10 MB pre-download cap; 100k-row cap.
- Stack traces only at debug level.
- Audit log entries for every operation (admin email, op type, mapping details).
- All inputs (bucket, key, email, domain, AWS account ID) validated before use.
