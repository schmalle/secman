# S3 User Mapping Import

**Feature**: 065-s3-user-mapping-import
**Last Updated**: 2026-04-23

Import user mappings (domain and AWS account associations) directly from CSV or JSON files stored in AWS S3 buckets via the CLI.

---

## Overview

The `import-s3` subcommand extends the existing `manage-user-mappings` CLI with S3 support. This enables automated, scheduled imports from centralized S3 buckets without requiring local file copies.

Key capabilities:

- Download and import user mapping files from any S3 bucket
- Standard AWS credential chain (environment variables, profiles, IAM roles)
- Dry-run mode for validation before committing changes
- 10MB file size limit with pre-download validation
- Cron-friendly exit codes (0=success, 1=partial errors, 2=fatal S3 error, 3=unexpected)
- Automatic temp file cleanup with restricted permissions

---

## Prerequisites

### 1. AWS Account and Permissions

The IAM user or role needs the following S3 permissions on the target bucket:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:HeadObject"
      ],
      "Resource": "arn:aws:s3:::your-bucket-name/*"
    }
  ]
}
```

### 2. CLI Setup

Build the CLI fat JAR:

```bash
./gradlew :cli:shadowJar
```

Verify the command is available:

```bash
./scriptpp/secmanng manage-user-mappings import-s3 --help
```

### 3. Required Tools

- **Java 21+** - Runtime for CLI JAR
- **AWS CLI** (optional) - For uploading files to S3 and debugging

---

## Configuration

### Environment Variables


| Variable                | Required    | Default     | Description                                                                       |
| ----------------------- | ----------- | ----------- | --------------------------------------------------------------------------------- |
| `SECMAN_ADMIN_EMAIL`    | Yes*        | -           | Admin email for audit logging (alternative:`--admin-user` flag)                   |
| `AWS_ACCESS_KEY_ID`     | Conditional | -           | AWS access key (if not using profile/IAM role)                                    |
| `AWS_SECRET_ACCESS_KEY` | Conditional | -           | AWS secret key (if not using profile/IAM role)                                    |
| `AWS_ENDPOINT_URL`      | No          | -           | Custom S3 endpoint URL for local testing (e.g.`http://localhost:9090` for S3Mock) |
| `AWS_REGION`            | No          | SDK default | Default AWS region (can be overridden with`--aws-region`)                         |

*Either `SECMAN_ADMIN_EMAIL` env var or `--admin-user` flag is required.

### AWS Credential Chain

The command uses the standard AWS SDK credential resolution order:

1. **Environment variables**: `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`
2. **AWS credentials file**: `~/.aws/credentials` (use `--aws-profile` to select a named profile)
3. **IAM role**: Automatic on EC2/ECS/Lambda (no configuration needed)
4. **SSO credentials**: Via `aws sso login`

---

## Listing Bucket Contents

Before importing, you can browse an S3 bucket to discover available files using the `list-bucket` subcommand.

### List All Objects

```bash
./scriptpp/secmanng manage-user-mappings list-bucket \
  --bucket my-company-mappings
```

### Filter by Prefix

```bash
./scriptpp/secmanng manage-user-mappings list-bucket \
  --bucket my-company-mappings \
  --prefix user-mappings/
```

### With AWS Profile and Region

```bash
./scriptpp/secmanng manage-user-mappings list-bucket \
  --bucket my-company-mappings \
  --prefix user-mappings/ \
  --aws-profile production \
  --aws-region eu-west-1
```

### Example Output

```
============================================================
List S3 Bucket Contents
============================================================

Bucket: my-company-mappings
Prefix: user-mappings/

Listing objects...
Key                                    Size     Last Modified
---------------------------------------------------------------
user-mappings/latest.csv               2.3 KB   2026-02-13 10:30:00
user-mappings/2026-01-backup.csv       1.8 KB   2026-01-15 08:00:00
user-mappings/teams.json               4.1 KB   2026-02-10 14:22:00

Total: 3 object(s)
```

### list-bucket Options


| Option           | Short | Required | Default               | Description                              |
| ---------------- | ----- | -------- | --------------------- | ---------------------------------------- |
| `--bucket`       | `-b`  | Yes      | -                     | S3 bucket name                           |
| `--prefix`       | `-p`  | No       | -                     | Filter objects by key prefix             |
| `--aws-region`   | -     | No       | SDK default           | AWS region                               |
| `--aws-profile`  | -     | No       | default chain         | AWS credential profile name              |
| `--endpoint-url` | -     | No       | `$AWS_ENDPOINT_URL`   | Custom S3 endpoint URL for local testing |
| `--admin-user`   | `-u`  | No*      | `$SECMAN_ADMIN_EMAIL` | Admin email (inherited from parent)      |

### Required IAM Permissions

The `list-bucket` command requires the `s3:ListBucket` permission on the target bucket:

---

## Downloading the Mapping File Directly (No Backend)

The `download-s3` subcommand fetches an AWS account mapping file straight from an S3 bucket to a local file path. **It does not contact the secman backend** — only AWS credentials with `s3:GetObject` (and ideally `s3:HeadObject` for a pre-download size check) on the target object are required.

This is the read-only counterpart to `import-s3`:
- `import-s3` downloads AND POSTs the file to the secman backend.
- `download-s3` only copies the file to disk — useful for inspecting the source-of-truth file, diffing against backend state, or piping its contents into other tooling without going through secman.

### Basic Download

```bash
./scriptpp/secmanng manage-user-mappings download-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.csv \
  --output ./aws-mappings.csv
```

### With Profile and Force Overwrite

```bash
./scriptpp/secmanng manage-user-mappings download-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.csv \
  --aws-profile production \
  --aws-region eu-west-1 \
  --output ./aws-mappings.csv \
  --force
```

### Cron-Friendly Quiet Mode

```bash
./scriptpp/secmanng manage-user-mappings download-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.csv \
  --output /var/lib/secman/aws-mappings.csv \
  --force \
  --quiet
```

In `--quiet` mode, stdout is silent. Errors still print to stderr, and the success line `Wrote N bytes to /abs/path` is also written to stderr so cron logs capture it.

### download-s3 Options

| Option                    | Short | Required | Default               | Description                                                                       |
| ------------------------- | ----- | -------- | --------------------- | --------------------------------------------------------------------------------- |
| `--bucket`                | `-b`  | Yes      | -                     | S3 bucket name (plain name, not URL or ARN)                                       |
| `--key`                   | `-k`  | Yes      | -                     | S3 object key (path inside the bucket)                                            |
| `--output`                | `-o`  | Yes      | -                     | Local destination file path. Parent directory must already exist.                 |
| `--force`                 | `-f`  | No       | `false`               | Overwrite the destination if it already exists                                    |
| `--aws-region`            | -     | No       | SDK default           | AWS region                                                                        |
| `--aws-profile`           | -     | No       | default chain         | AWS credential profile name                                                       |
| `--aws-access-key-id`     | -     | No       | `$AWS_ACCESS_KEY_ID`  | Explicit AWS access key ID                                                        |
| `--aws-secret-access-key` | -     | No       | `$AWS_SECRET_ACCESS_KEY` | Explicit AWS secret access key                                                 |
| `--aws-session-token`     | -     | No       | `$AWS_SESSION_TOKEN`  | AWS session token for temporary credentials                                       |
| `--endpoint-url`          | -     | No       | `$AWS_ENDPOINT_URL`   | Custom S3 endpoint URL for local testing                                          |
| `--quiet`                 | `-q`  | No       | `false`               | Suppress progress output. Success/error lines are still written to stderr.        |

### Required IAM Permissions

`download-s3` needs the same permissions as `import-s3`:

- `s3:GetObject` on the target bucket and object — required.
- `s3:HeadObject` on the target object — recommended (used for the pre-download 10 MB size check; the command falls back to a post-download size check if denied).

### Constraints

- 10 MB hard size limit (matches `import-s3`).
- Parent directory of `--output` must already exist; the command does not auto-create it.
- Existing destination files are not overwritten unless `--force` is set.
- File contents are written verbatim — no parsing, validation, or normalization. Use `import-s3 --dry-run` if you want validation.

### Exit Codes (Cron-Friendly)

| Code | Meaning                                                            |
| ---- | ------------------------------------------------------------------ |
| 0    | Success — file written to `--output`                               |
| 1    | Generic / I/O error                                                |
| 2    | S3, credentials, or argument error (fatal — won't succeed on retry) |
| 3    | Unexpected error                                                   |

---

## Printing the Mapping File to the Console (No Disk Write, No Backend)

The `print-s3` subcommand downloads a mapping file from S3, parses it, and prints the **identified mappings** straight to stdout. It does not write to disk (the temp download is deleted on exit) and does not contact the secman backend. Default scope is AWS account mappings (`--type AWS`); use `--type DOMAIN` or `--type ALL` to widen.

### Basic Usage

```bash
./scriptpp/secmanng manage-user-mappings print-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.csv
```

### As JSON for downstream tooling

```bash
./scriptpp/secmanng manage-user-mappings print-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.csv \
  --type ALL \
  --format JSON
```

### Diff S3 source-of-truth against the secman DB

```bash
./scriptpp/secmanng manage-user-mappings print-s3 \
  --bucket my-company-mappings --key user-mappings/latest.csv \
  --format CSV --quiet > /tmp/s3.csv

./scriptpp/secmanng manage-user-mappings list \
  --type AWS --format CSV --output /tmp/db.csv

diff /tmp/s3.csv /tmp/db.csv
```

### print-s3 Options

| Option                    | Short | Required | Default               | Description                                                                       |
| ------------------------- | ----- | -------- | --------------------- | --------------------------------------------------------------------------------- |
| `--bucket`                | `-b`  | Yes      | -                     | S3 bucket name (plain name, not URL or ARN)                                       |
| `--key`                   | `-k`  | Yes      | -                     | S3 object key (path inside the bucket)                                            |
| `--type`                  | -     | No       | `AWS`                 | Mapping kind to print: `AWS`, `DOMAIN`, or `ALL`                                  |
| `--format`                | -     | No       | `TABLE`               | Console output format: `TABLE`, `JSON`, or `CSV`                                  |
| `--file-format`           | -     | No       | `AUTO`                | Source-file format in S3: `CSV`, `JSON`, or `AUTO`                                |
| `--show-errors`           | -     | No       | `false`               | Print parse errors (malformed rows) to stderr after the mapping output            |
| `--aws-region`            | -     | No       | SDK default           | AWS region                                                                        |
| `--aws-profile`           | -     | No       | default chain         | AWS credential profile name                                                       |
| `--aws-access-key-id`     | -     | No       | `$AWS_ACCESS_KEY_ID`  | Explicit AWS access key ID                                                        |
| `--aws-secret-access-key` | -     | No       | `$AWS_SECRET_ACCESS_KEY` | Explicit AWS secret access key                                                 |
| `--aws-session-token`     | -     | No       | `$AWS_SESSION_TOKEN`  | AWS session token for temporary credentials                                       |
| `--endpoint-url`          | -     | No       | `$AWS_ENDPOINT_URL`   | Custom S3 endpoint URL for local testing                                          |
| `--quiet`                 | `-q`  | No       | `false`               | Suppress the header banner and trailing summary; mapping output stays on stdout   |

### Stdout / Stderr Split (Important for Piping)

- **stdout** carries only the parsed mapping output (TABLE / JSON / CSV) — safe to pipe through `diff`, `jq`, `awk`, or `> file`.
- **stderr** carries the header banner ("Source: ...", "Scope: ...") and the trailing summary line. Suppressed by `--quiet`.
- Parse errors only print (also on stderr) when `--show-errors` is set.

### Required IAM Permissions

`print-s3` needs the same permissions as `download-s3` and `import-s3`:

- `s3:GetObject` on the target bucket and object — required.
- `s3:HeadObject` on the target object — recommended (used for the pre-download 10 MB size check).

### Constraints

- 10 MB hard size limit (matches `import-s3` / `download-s3`).
- File contents are parsed but never written to disk — the temp download is removed on exit.
- The secman DB is never queried. The only source of truth is the S3 file.

### Exit Codes

| Code | Meaning                                                          |
| ---- | ---------------------------------------------------------------- |
| 0    | Success — file parsed and printed without errors                 |
| 1    | Parse errors found in the file (valid rows still printed)        |
| 2    | S3, credentials, or argument error (fatal — won't succeed on retry) |
| 3    | Unexpected error                                                 |

---

### list-bucket — Required IAM Permissions

The `list-bucket` command requires the `s3:ListBucket` permission on the target bucket:

```json
{
  "Effect": "Allow",
  "Action": "s3:ListBucket",
  "Resource": "arn:aws:s3:::your-bucket-name"
}
```

---

## Usage Examples

### Basic Import

```bash
./scriptpp/secmanng manage-user-mappings import-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.csv
```

### Dry-Run (Validation Only)

```bash
./scriptpp/secmanng manage-user-mappings import-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.csv \
  --dry-run
```

### With AWS Profile

```bash
./scriptpp/secmanng manage-user-mappings import-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.csv \
  --aws-profile production
```

### With Explicit Region

```bash
./scriptpp/secmanng manage-user-mappings import-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.csv \
  --aws-region eu-west-1
```

### JSON Format

```bash
./scriptpp/secmanng manage-user-mappings import-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.json \
  --format JSON
```

### Cron Job Setup

```bash
# /etc/cron.d/secman-import
# Run daily at 2 AM, log output, alert on failure
0 2 * * * secman-user /opt/secman/bin/secman manage-user-mappings import-s3 \
  --bucket my-company-mappings \
  --key user-mappings/latest.csv \
  >> /var/log/secman/s3-import.log 2>&1 \
  || echo "S3 import failed with exit code $?" | mail -s "secman S3 import failure" ops@company.com
```

### Command-Line Options


| Option           | Short | Required | Default               | Description                              |
| ---------------- | ----- | -------- | --------------------- | ---------------------------------------- |
| `--bucket`       | `-b`  | Yes      | -                     | S3 bucket name                           |
| `--key`          | `-k`  | Yes      | -                     | S3 object key (path to file)             |
| `--aws-region`   | -     | No       | SDK default           | AWS region                               |
| `--aws-profile`  | -     | No       | default chain         | AWS credential profile name              |
| `--format`       | -     | No       | `AUTO`                | File format:`CSV`, `JSON`, or `AUTO`     |
| `--dry-run`      | -     | No       | `false`               | Validate without creating mappings       |
| `--endpoint-url` | -     | No       | `$AWS_ENDPOINT_URL`   | Custom S3 endpoint URL for local testing |
| `--admin-user`   | `-u`  | No*      | `$SECMAN_ADMIN_EMAIL` | Admin email for audit                    |

---

## File Formats

### CSV Format

Headers: `email`, `type`, `value` (case-insensitive)

```csv
email,type,value
alice@company.com,DOMAIN,corp.local
alice@company.com,DOMAIN,prod.company.com
alice@company.com,AWS_ACCOUNT,123456789012
bob@company.com,DOMAIN,dev.company.com
bob@company.com,AWS_ACCOUNT,987654321098
```

- **type** must be `DOMAIN` or `AWS_ACCOUNT`
- **AWS_ACCOUNT** values must be exactly 12 digits
- Empty lines are skipped
- Maximum 100,000 records per file

### JSON Format

```json
[
  {
    "email": "alice@company.com",
    "domains": ["corp.local", "prod.company.com"],
    "awsAccounts": ["123456789012"]
  },
  {
    "email": "bob@company.com",
    "domains": ["dev.company.com"],
    "awsAccounts": ["987654321098"]
  }
]
```

- Each entry can have `domains`, `awsAccounts`, or both
- Maximum 100,000 entries per file

---

## E2E Testing

An end-to-end test script validates the complete import flow against a real S3 bucket and running backend.

### Running the Test

```bash
./tests/s3-user-mapping-import-e2e-test.sh
```

### With Debug Output

```bash
DEBUG=1 ./tests/s3-user-mapping-import-e2e-test.sh
```

### Test Environment Variables


| Variable                | Required | Default                 | Description                  |
| ----------------------- | -------- | ----------------------- | ---------------------------- |
| `S3_TEST_BUCKET`        | Yes      | 1Password URI           | S3 bucket for test files     |
| `S3_TEST_REGION`        | Yes      | 1Password URI           | AWS region for test bucket   |
| `AWS_ACCESS_KEY_ID`     | Yes      | 1Password URI           | AWS credentials              |
| `AWS_SECRET_ACCESS_KEY` | Yes      | 1Password URI           | AWS credentials              |
| `SECMAN_ADMIN_EMAIL`    | Yes      | 1Password URI           | Admin email for CLI commands |
| `SECMAN_BASE_URL`       | No       | `http://localhost:8080` | Backend URL                  |

### Expected Output

```
=== E2E Test: S3 User Mapping Import (065-s3-user-mapping-import) ===

[PASS] Prerequisites check passed
[PASS] Credentials resolved
[PASS] Step 1: CLI JAR built successfully
[PASS] Step 2: Test CSV uploaded to s3://bucket/e2e-test/user-mappings-1234567890.csv
[PASS] Step 3: Dry-run completed successfully (exit code 0)
[PASS] Step 4: Import completed successfully (exit code 0)
[PASS] Step 5a: Domain mapping for e2e-1234567890.test.local found
[PASS] Step 5b: AWS account mapping for 999900567890 found
[INFO] Step 6: Cleaning up test data...
[PASS] Cleanup completed

============================================
  S3 USER MAPPING IMPORT E2E TEST PASSED
============================================
```

### Test Flow


| Step | Action                | Verification                             |
| ---- | --------------------- | ---------------------------------------- |
| 1    | Build CLI fat JAR     | JAR file exists                          |
| 2    | Upload test CSV to S3 | `aws s3 cp` succeeds                     |
| 3    | Dry-run import        | Exit code 0, validation messages present |
| 4    | Actual import         | Exit code 0, success messages present    |
| 5    | List mappings         | Test domain and AWS account found        |
| 6    | Cleanup               | Mappings deleted, S3 file removed        |

---

## Troubleshooting

### "AWS credentials not found"

Configure credentials via one of:

```bash
# Option 1: Environment variables
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...

# Option 2: AWS CLI configure
aws configure

# Option 3: Named profile
aws configure --profile myprofile
./scriptpp/secman manage-user-mappings import-s3 --aws-profile myprofile ...
```

### "Access denied" (403)

1. Verify IAM permissions include `s3:GetObject` and `s3:HeadObject`
2. Check bucket policy allows access from your account/IP
3. Ensure the bucket name and key are correct

### "Bucket does not exist"

1. Verify bucket name spelling (case-sensitive, lowercase only)
2. Ensure the bucket is in the expected region (use `--aws-region`)
3. Check that the bucket hasn't been deleted

### "File not found" (NoSuchKey)

1. Verify the S3 key (path) is correct
2. List objects to confirm: `aws s3 ls s3://bucket/path/`
3. Keys are case-sensitive

### "File exceeds 10MB limit"

Split the file into smaller batches. The 10MB limit is checked before download to avoid wasting bandwidth.

### "File exceeds maximum of 100,000 records"

Split the file into multiple files with fewer records each. Import them sequentially.

### "Network error connecting to S3"

1. Check internet connectivity
2. Verify the region matches the bucket's actual region
3. Check firewall/proxy settings

### Exit Codes


| Code | Meaning               | Action                                 |
| ---- | --------------------- | -------------------------------------- |
| 0    | Success               | All mappings imported                  |
| 1    | Partial success       | Some mappings had errors, check output |
| 2    | Fatal S3/config error | Fix credentials, bucket, or key        |
| 3    | Unexpected error      | Check logs, enable debug logging       |

---

## Security Considerations

- **Temp files**: Downloaded files are stored in the system temp directory with owner-only permissions (`rw-------`) and are automatically deleted after processing, even on error
- **Credential management**: Uses the standard AWS SDK credential chain; never pass credentials as CLI arguments
- **File size limit**: 10MB pre-download check prevents large file abuse
- **Record count limit**: Maximum 100,000 records per file prevents memory exhaustion
- **Stack traces**: Internal error details are only logged at debug level, not exposed to stdout/stderr
- **Audit logging**: All import operations are logged with admin email, operation type, and mapping details
- **Input validation**: Bucket names, object keys, email addresses, domains, and AWS account IDs are validated before processing

---

## Testing with S3Mock

You can use [Adobe S3Mock](https://github.com/adobe/S3Mock) (or MinIO, LocalStack) to test S3 imports locally without an AWS account.

### Setup

```bash
# Start S3Mock
docker run -p 9090:9090 adobe/s3mock

# Create a test bucket and upload a mapping file
aws s3api create-bucket --bucket test --endpoint-url http://localhost:9090
aws s3api put-object --bucket test --key mappings.csv --body ./test-mappings.csv --endpoint-url http://localhost:9090
```

### Using --endpoint-url

```bash
# List bucket contents
./scriptpp/secmanng manage-user-mappings list-bucket \
  --bucket test \
  --endpoint-url http://localhost:9090

# Dry-run import
./scriptpp/secmanng manage-user-mappings import-s3 \
  --bucket test \
  --key mappings.csv \
  --endpoint-url http://localhost:9090 \
  --dry-run
```

### Using AWS_ENDPOINT_URL Environment Variable

```bash
export AWS_ENDPOINT_URL=http://localhost:9090

./scriptpp/secman manage-user-mappings list-bucket --bucket test
./scriptpp/secman manage-user-mappings import-s3 --bucket test --key mappings.csv --dry-run
```

When `--endpoint-url` is set (or `AWS_ENDPOINT_URL`), the CLI automatically enables path-style S3 access, which is required by local S3 simulators.

---

## See Also

- [CLI Documentation](./CLI.md) - Full CLI reference
- [Environment Variables](./ENVIRONMENT.md) - All configuration options
- [User Mapping CLI Docs](../src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md) - Detailed CLI command reference
