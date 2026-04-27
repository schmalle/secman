# User Mapping Management Commands

**Feature**: 049-cli-user-mappings, 065-s3-user-mapping-import
**Version**: 1.1.0
**Last Updated**: 2026-01-20

## Overview

The `manage-user-mappings` command suite provides CLI tools for ADMIN users to manage user-to-domain and user-to-AWS-account mappings in SecMan. These mappings control which assets users can access based on asset metadata (AD domains and AWS account IDs).

**Key Features**:
- Add domain and AWS account mappings individually or in bulk
- List existing mappings with filtering and multiple output formats
- Remove mappings by specific criteria
- Batch import from CSV/JSON files with validation
- **Import from AWS S3** for automated daily imports (Feature 065)
- Pending mapping support for future users
- Audit logging for all operations

## Prerequisites

### Authentication
All commands require **ADMIN role** access. Specify admin credentials via:
- `--admin-user <email>` flag on each command, OR
- `SECMAN_ADMIN_EMAIL` environment variable

### Database Connection
Commands connect to the backend database via Micronaut Data JPA. Ensure:
- Database is running and accessible
- Connection details in `src/backendng/src/main/resources/application.yml`

## Commands

### 1. Add Domain Mappings

**Command**: `add-domain`

**Purpose**: Associate AD domains with users to grant access to assets in those domains.

**Syntax**:
```bash
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails <email1>,<email2> \
  --domains <domain1>,<domain2> \
  [--admin-user <admin-email>]'
```

**Options**:
- `--emails` (required): Comma-separated list of user email addresses
- `--domains` (required): Comma-separated list of AD domains
- `--admin-user` or `-u`: Admin user email (or set SECMAN_ADMIN_EMAIL)

**Behavior**:
- Creates **n×m mappings** (cross product of emails and domains)
- Validates email and domain formats
- Skips duplicates with warning
- Creates **PENDING** mappings for non-existent users (auto-applied on user creation)
- Creates **ACTIVE** mappings for existing users

**Examples**:
```bash
# Single user, single domain
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails alice@example.com \
  --domains corp.local \
  --admin-user admin@example.com'

# Multiple users and domains (creates 4 mappings)
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails alice@example.com,bob@example.com \
  --domains corp.local,dev.local \
  --admin-user admin@example.com'

# Using environment variable for admin
export SECMAN_ADMIN_EMAIL=admin@example.com
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails alice@example.com \
  --domains example.com'
```

**Output**:
```
============================================================
Add Domain Mappings
============================================================

Admin user: admin@example.com

Processing domain mappings...
Emails: alice@example.com, bob@example.com
Domains: corp.local, dev.local

✅ alice@example.com → corp.local
✅ alice@example.com → dev.local
⚠️  bob@example.com → corp.local (pending - user not found)
⚠️  bob@example.com → dev.local (pending - user not found)

============================================================
Summary
============================================================
Total: 4 mapping(s) processed
Created: 2 active
Created: 2 pending

✓ All mappings processed successfully
```

---

### 2. Add AWS Account Mappings

**Command**: `add-aws`

**Purpose**: Associate AWS accounts with users to grant access to cloud assets in those accounts.

**Syntax**:
```bash
./gradlew cli:run --args='manage-user-mappings add-aws \
  --emails <email1>,<email2> \
  --accounts <account1>,<account2> \
  [--admin-user <admin-email>]'
```

**Options**:
- `--emails` (required): Comma-separated list of user email addresses
- `--accounts` (required): Comma-separated list of 12-digit AWS account IDs
- `--admin-user` or `-u`: Admin user email

**Validation**:
- AWS account IDs must be exactly **12 digits**
- Invalid IDs will be rejected with error message

**Examples**:
```bash
# Single mapping
./gradlew cli:run --args='manage-user-mappings add-aws \
  --emails alice@example.com \
  --accounts 123456789012 \
  --admin-user admin@example.com'

# Multiple accounts
./gradlew cli:run --args='manage-user-mappings add-aws \
  --emails alice@example.com,bob@example.com \
  --accounts 123456789012,987654321098 \
  --admin-user admin@example.com'
```

**Error Handling**:
```bash
# Invalid account ID
./gradlew cli:run --args='manage-user-mappings add-aws \
  --emails alice@example.com \
  --accounts 12345 \
  --admin-user admin@example.com'

# Output:
❌ Error: Invalid AWS account ID (must be 12 digits): 12345
```

---

### 3. List Mappings

**Command**: `list`

**Purpose**: View existing mappings with filtering and multiple output formats.

**Syntax**:
```bash
./gradlew cli:run --args='manage-user-mappings list \
  [--email <email>] \
  [--status <ACTIVE|PENDING|ALL>] \
  [--type <AWS|DOMAIN|ALL>] \
  [--format <TABLE|JSON|CSV>] \
  [--output <file> | -o <file>] \
  [--send-email] \
  [--dry-run] \
  [--verbose | -v] \
  [--admin-user <admin-email>]'
```

**Options**:
- `--email`: Filter by specific user email
- `--status`: Filter by mapping status (ACTIVE, PENDING, ALL)
- `--type`: Restrict by mapping kind. `AWS` returns AWS account mappings only, `DOMAIN` returns domain mappings only, `ALL` (default) returns both. Useful for downloading only the AWS account mapping subset.
- `--format`: Output format (default: TABLE)
- `--output`, `-o`: Write the rendered output to FILE instead of stdout. When combined with `--format CSV` the file is round-trip compatible with `manage-user-mappings import`. If `--format TABLE` is set, the format is auto-coerced to CSV (TABLE is interactive only). On success the byte count and absolute path are printed to **stderr** (so stdout can still be piped). Refuses to overwrite a path that exists and is not a regular file.
- `--send-email`: **(Feature 085)** After printing the console output, email the statistics report (aggregates + per-user detail) to every user holding the `ADMIN` or `REPORT` role with a valid email address. Recipient selection matches the existing `send-admin-summary` command.
- `--dry-run`: Used with `--send-email`. Preview the intended recipient list without dispatching any email. Still prints the console output and still writes a `DRY_RUN` row to the audit log.
- `--verbose`, `-v`: Used with `--send-email`. Show per-recipient send status (`SUCCESS <addr>` / `FAILED <addr>`) in addition to the summary block.
- `--admin-user` or `-u`: Admin user email

**Output Formats**:

**TABLE** (default) - Grouped by user:
```
================================================================================
User Mappings
================================================================================

✅ alice@example.com
  Domains:
    - corp.local
    - dev.local
  AWS Accounts:
    - 123456789012

⚠️  bob@example.com
  Domains:
    - corp.local (pending)

================================================================================
Summary
================================================================================
Total users: 2
Total mappings: 4
  - Active: 3
  - Pending: 1
  - Domains: 3
  - AWS Accounts: 1
```

**JSON** - Structured data:
```json
{
  "totalUsers": 2,
  "totalMappings": 4,
  "mappings": [
    {
      "email": "alice@example.com",
      "domains": [
        {"domain": "corp.local", "status": "ACTIVE", "createdAt": "..."}
      ],
      "awsAccounts": [
        {"awsAccountId": "123456789012", "status": "ACTIVE", "createdAt": "..."}
      ]
    }
  ]
}
```

**CSV** - Flat table:
```csv
Email,Type,Value,Status,Created At,Applied At
alice@example.com,DOMAIN,corp.local,ACTIVE,2025-01-19T10:00:00Z,2025-01-19T10:00:00Z
alice@example.com,AWS_ACCOUNT,123456789012,ACTIVE,2025-01-19T10:00:00Z,
bob@example.com,DOMAIN,corp.local,PENDING,2025-01-19T10:00:00Z,
```

**Examples**:
```bash
# List all mappings
./gradlew cli:run --args='manage-user-mappings list --admin-user admin@example.com'

# Filter by specific user
./gradlew cli:run --args='manage-user-mappings list \
  --email alice@example.com \
  --admin-user admin@example.com'

# Show only pending mappings
./gradlew cli:run --args='manage-user-mappings list \
  --status PENDING \
  --admin-user admin@example.com'

# Export to JSON
./gradlew cli:run --args='manage-user-mappings list \
  --format JSON \
  --admin-user admin@example.com' > mappings.json

# Export to CSV (stdout redirection)
./gradlew cli:run --args='manage-user-mappings list \
  --format CSV \
  --admin-user admin@example.com' > mappings.csv

# Download AWS account mappings only to a file (round-trip compatible with `import`)
./gradlew cli:run --args='manage-user-mappings list \
  --type AWS \
  --format CSV \
  --output aws-mappings.csv'

# Download all domain mappings as JSON
./gradlew cli:run --args='manage-user-mappings list \
  --type DOMAIN \
  --format JSON \
  --output domain-mappings.json'

# Download AWS mappings for one user only
./gradlew cli:run --args='manage-user-mappings list \
  --type AWS \
  --email alice@example.com \
  --format CSV \
  --output alice-aws.csv'

# Feature 085: Email statistics to all ADMIN/REPORT users (happy path)
./gradlew cli:run --args='manage-user-mappings list --send-email'

# Preview intended recipients without dispatching
./gradlew cli:run --args='manage-user-mappings list --send-email --dry-run'

# Per-recipient delivery status (useful for troubleshooting SMTP)
./gradlew cli:run --args='manage-user-mappings list --send-email --verbose'

# Email a filtered view (only mappings for one user)
./gradlew cli:run --args='manage-user-mappings list \
  --email alice@example.com \
  --send-email'
```

**Email Distribution (Feature 085)**

When `--send-email` is set, the command:

1. Prints the normal TABLE/JSON/CSV console output (unchanged behavior).
2. Calls `POST /api/cli/user-mappings/send-statistics-email` on the backend.
3. Backend re-queries mappings with the same filters, computes aggregates and
   per-user detail, renders a plain-text + HTML email, and dispatches to every
   `ADMIN` or `REPORT` user with a valid email address.
4. Writes one row to the `user_mapping_statistics_log` table on every
   invocation (including dry-runs and zero-recipient failures) for audit.
5. Prints a summary block and exits with a status-specific exit code.

**Exit codes when `--send-email` is set:**

| Code | Meaning                                                               |
| ---- | --------------------------------------------------------------------- |
| 0    | Success, dry-run, or default `list` without `--send-email`            |
| 1    | Generic error (network, parse, unexpected) or `--dry-run` without `--send-email` |
| 2    | Authorization denied — invoker does not hold ADMIN                    |
| 3    | No eligible recipients (no ADMIN/REPORT users with valid email)       |
| 4    | Partial failure (≥1 sent, ≥1 failed)                                  |
| 5    | Full failure (0 sent, ≥1 attempted)                                   |

These codes are only emitted when `--send-email` is set — without it, the
command retains its pre-Feature-085 exit behavior (0 on success, 1 on error).

**Cron example:**

```bash
# Weekly Monday 08:00 distribution
0 8 * * 1 /opt/secman/scriptpp/secmancli manage-user-mappings list --send-email \
  || echo "user-mapping stats distribution failed with exit $?" | mail -s "secman alert" ops@example.com
```

---

### 4. Remove Mappings

**Command**: `remove`

**Purpose**: Delete user mappings to revoke access.

**Syntax**:
```bash
./gradlew cli:run --args='manage-user-mappings remove \
  --email <email> \
  [--domain <domain> | --account <account> | --all] \
  [--admin-user <admin-email>]'
```

**Options**:
- `--email` (required): User email address
- `--domain`: Remove specific domain mapping
- `--account`: Remove specific AWS account mapping
- `--all`: Remove ALL mappings for the user
- `--admin-user` or `-u`: Admin user email

**IMPORTANT**: Must specify **exactly one** of `--domain`, `--account`, or `--all`.

**Examples**:
```bash
# Remove specific domain
./gradlew cli:run --args='manage-user-mappings remove \
  --email alice@example.com \
  --domain corp.local \
  --admin-user admin@example.com'

# Remove specific AWS account
./gradlew cli:run --args='manage-user-mappings remove \
  --email alice@example.com \
  --account 123456789012 \
  --admin-user admin@example.com'

# Remove all mappings for user
./gradlew cli:run --args='manage-user-mappings remove \
  --email alice@example.com \
  --all \
  --admin-user admin@example.com'
```

**Output**:
```
============================================================
Remove User Mappings
============================================================

Admin user: admin@example.com

Removing: domain mapping: alice@example.com → corp.local

============================================================
Summary
============================================================
✅ Removed 1 mapping(s)
```

**Error Handling**:
```bash
# No mapping found
❌ Error: No mappings found matching the specified criteria
```

---

### 5. Batch Import

**Command**: `import`

**Purpose**: Import multiple mappings from CSV or JSON files.

**Syntax**:
```bash
./gradlew cli:run --args='manage-user-mappings import \
  --file <path> \
  [--format <CSV|JSON|AUTO>] \
  [--dry-run] \
  [--admin-user <admin-email>]'
```

**Options**:
- `--file` or `-f` (required): Path to import file
- `--format`: File format (default: AUTO for auto-detection)
- `--dry-run`: Validate file without creating mappings
- `--admin-user` or `-u`: Admin user email

**CSV Format**:
```csv
email,type,value
alice@example.com,DOMAIN,corp.local
alice@example.com,AWS_ACCOUNT,123456789012
bob@example.com,DOMAIN,dev.local
```

**Field Descriptions**:
- `email`: User email address (required)
- `type`: Mapping type - `DOMAIN` or `AWS_ACCOUNT` (required)
- `value`: Domain name or AWS account ID (required)

**JSON Format**:
```json
[
  {
    "email": "alice@example.com",
    "domains": ["corp.local", "dev.local"],
    "awsAccounts": ["123456789012"]
  },
  {
    "email": "bob@example.com",
    "domains": ["corp.local"]
  }
]
```

**Features**:
- **Auto-detection**: Detects format from file extension or content
- **Partial success**: Continues processing on errors, reports all issues
- **Line-level errors**: CSV errors include line numbers
- **Validation**: Same validation as individual commands
- **Dry-run**: Test import without database changes

**Examples**:
```bash
# Import CSV
./gradlew cli:run --args='manage-user-mappings import \
  --file /path/to/mappings.csv \
  --admin-user admin@example.com'

# Import JSON
./gradlew cli:run --args='manage-user-mappings import \
  --file /path/to/mappings.json \
  --admin-user admin@example.com'

# Dry-run validation
./gradlew cli:run --args='manage-user-mappings import \
  --file /path/to/mappings.csv \
  --dry-run \
  --admin-user admin@example.com'

# Force specific format
./gradlew cli:run --args='manage-user-mappings import \
  --file /path/to/data.txt \
  --format CSV \
  --admin-user admin@example.com'
```

**Output**:
```
============================================================
Import User Mappings
============================================================

Admin user: admin@example.com
File: /path/to/mappings.csv
Format: AUTO

============================================================
Summary
============================================================
Total: 10 mapping(s) processed
✅ Created: 8 active mapping(s)
⚠️  Created: 1 pending mapping(s)
⚠️  Skipped: 1 duplicate(s)
❌ Errors: 0 failure(s)

✓ Import successful
```

**Error Handling**:
```
============================================================
Summary
============================================================
Total: 10 mapping(s) processed
✅ Created: 5 active mapping(s)
❌ Errors: 5 failure(s)

Errors:
  - Line 3: Invalid email format
  - Line 7: Invalid AWS account ID (must be 12 digits)
  - Line 9: Missing required fields (email, type, value)

✗ Import completed with errors
```

---

### 6. S3 Import (Feature 065)

**Command**: `import-s3`

**Purpose**: Import multiple mappings from a file stored in AWS S3. Ideal for automated daily imports via cron.

**Syntax**:
```bash
./scriptpp/secman manage-user-mappings import-s3 \
  --bucket <bucket-name> \
  --key <object-key> \
  [--aws-region <region>] \
  [--aws-profile <profile>] \
  [--format <CSV|JSON|AUTO>] \
  [--dry-run] \
  [--admin-user <admin-email>]
```

**Options**:
- `--bucket` or `-b` (required): S3 bucket name
- `--key` or `-k` (required): S3 object key (path to file in bucket)
- `--aws-region`: AWS region (default: SDK default resolution)
- `--aws-profile`: AWS credential profile name (default: default credential chain)
- `--format`: File format (default: AUTO for auto-detection)
- `--dry-run`: Validate file without creating mappings
- `--admin-user` or `-u`: Admin user email

**AWS Authentication**:
The command uses the standard AWS SDK credential chain:
1. Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)
2. AWS credentials file (`~/.aws/credentials`)
3. IAM roles (for EC2/ECS deployments)

**Required IAM Permissions**:
```json
{
  "Effect": "Allow",
  "Action": ["s3:GetObject", "s3:HeadObject"],
  "Resource": "arn:aws:s3:::your-bucket/path/*"
}
```

**File Formats**: Same CSV and JSON formats as the local `import` command.

**File Size Limit**: 10MB maximum.

**Exit Codes** (for cron automation):
- `0`: Success - all mappings imported
- `1`: Partial success - some mappings failed validation
- `2`: Fatal error - S3 access or authentication failure
- `3`: Unexpected error

**Examples**:
```bash
# Basic import
./scriptpp/secman manage-user-mappings import-s3 \
  --bucket my-mapping-bucket \
  --key user-mappings/current.csv \
  --admin-user admin@example.com

# With specific region and profile
./scriptpp/secman manage-user-mappings import-s3 \
  --bucket my-mapping-bucket \
  --key user-mappings/current.csv \
  --aws-region eu-west-1 \
  --aws-profile production \
  --admin-user admin@example.com

# Dry-run validation
./scriptpp/secman manage-user-mappings import-s3 \
  --bucket my-mapping-bucket \
  --key user-mappings/current.csv \
  --dry-run \
  --admin-user admin@example.com
```

**Email Notification (Feature 085)**:

To notify ADMIN/REPORT users about the imported mappings, follow up with:
```bash
./scriptpp/secman manage-user-mappings list --send-email
```

Use `--dry-run` to preview recipients, or `--verbose` for per-recipient delivery status.
See [Section 3 (List Mappings)](#3-list-mappings) for full `--send-email` documentation.

**Cron Setup** (daily import at 2 AM with email notification):
```bash
# Using environment variables — import then notify admins
0 2 * * * root AWS_ACCESS_KEY_ID=xxx AWS_SECRET_ACCESS_KEY=xxx \
  /opt/secman/bin/secman manage-user-mappings import-s3 \
  --bucket company-mappings --key daily/users.csv \
  --admin-user admin@company.com \
  && /opt/secman/bin/secman manage-user-mappings list --send-email \
  >> /var/log/secman/s3-import.log 2>&1

# Using IAM role (EC2) — import only, no email
0 2 * * * root /opt/secman/bin/secman manage-user-mappings import-s3 \
  --bucket company-mappings --key daily/users.csv \
  --admin-user admin@company.com >> /var/log/secman/s3-import.log 2>&1
```

**Output**:
```
============================================================
Import User Mappings from S3
============================================================

Admin user: admin@example.com
Source: s3://my-bucket/user-mappings/current.csv
AWS Region: us-east-1
Format: AUTO

Downloading from S3...
Download complete.

============================================================
Summary
============================================================
Total: 50 mapping(s) processed
Created: 45 active mapping(s)
Created: 3 pending mapping(s)
Skipped: 2 duplicate(s)

Import successful
```

---

### 7. S3 Download (Direct, Bypasses Backend)

**Command**: `download-s3`

**Purpose**: Download an AWS account mapping file *straight* from an S3 bucket to a local file path. **Does not contact the secman backend** — only AWS credentials with `s3:GetObject` (and optionally `s3:HeadObject` for a pre-download size check) are required. Useful for inspecting the source-of-truth file, diffing it against backend state, or piping its contents into other tooling without going through secman.

This is the read-only counterpart to `import-s3`:
- `import-s3` downloads AND POSTs the file to the secman backend for processing.
- `download-s3` only copies the file to disk.

**Syntax**:
```bash
./gradlew cli:run --args='manage-user-mappings download-s3 \
  --bucket <bucket-name> \
  --key <object-key> \
  --output <local-file> \
  [--force | -f] \
  [--aws-region <region>] \
  [--aws-profile <profile>] \
  [--aws-access-key-id <key>] \
  [--aws-secret-access-key <secret>] \
  [--aws-session-token <token>] \
  [--endpoint-url <url>] \
  [--quiet | -q]'
```

**Required Options**:
- `--bucket`, `-b`: S3 bucket name (plain name — not a URL or ARN)
- `--key`, `-k`: S3 object key (path inside the bucket)
- `--output`, `-o`: Local destination file path (parent dir must already exist)

**Optional Options**:
- `--force`, `-f`: Overwrite the destination file if it already exists. Without `--force` the command refuses to clobber an existing file.
- `--aws-region`: AWS region (default: SDK resolution from env/config)
- `--aws-profile`: AWS credential profile name (reads `~/.aws/credentials`)
- `--aws-access-key-id` / `--aws-secret-access-key` / `--aws-session-token`: Explicit credentials (also read from `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` env vars)
- `--endpoint-url`: Custom S3 endpoint URL (e.g. `http://localhost:9090` for S3Mock; also reads `AWS_ENDPOINT_URL`)
- `--quiet`, `-q`: Suppress progress output. The "Wrote N bytes to ..." confirmation line is still printed to **stderr** so cron logs and shell wrappers can capture it.

**AWS Credential Resolution** (highest priority first):
1. Explicit CLI flags (`--aws-access-key-id` + `--aws-secret-access-key` [+ `--aws-session-token`])
2. Environment variables (`AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` [+ `AWS_SESSION_TOKEN`])
3. Named profile (`--aws-profile`)
4. Default credential chain (IAM role, SSO, ECS task role, etc.)

**Constraints**:
- 10 MB hard size limit (matches `import-s3`).
- Parent directory must already exist; the command does not auto-create it.
- Existing destination files are not overwritten unless `--force` is set.
- File contents are written verbatim — no parsing, validation, or normalization. Use `import-s3 --dry-run` if you want validation.

**Examples**:
```bash
# Download with default credential chain
./gradlew cli:run --args='manage-user-mappings download-s3 \
  --bucket my-bucket \
  --key mappings.csv \
  --output ./aws-mappings.csv'

# Use a named AWS profile, overwrite if file exists
./gradlew cli:run --args='manage-user-mappings download-s3 \
  --bucket my-bucket \
  --key data/users.json \
  --aws-profile prod \
  --output ./users.json \
  --force'

# Explicit credentials + region (typical cron usage)
./scriptpp/secman manage-user-mappings download-s3 \
  --bucket my-bucket \
  --key mappings.csv \
  --aws-access-key-id "$AWS_KEY" \
  --aws-secret-access-key "$AWS_SECRET" \
  --aws-region eu-west-1 \
  --output /var/lib/secman/aws-mappings.csv \
  --quiet

# Local S3Mock testing
./gradlew cli:run --args='manage-user-mappings download-s3 \
  --bucket test-bucket \
  --key mappings.csv \
  --endpoint-url http://localhost:9090 \
  --output /tmp/mappings.csv'

# Diff S3 source-of-truth against backend state
./gradlew cli:run --args='manage-user-mappings download-s3 \
  --bucket my-bucket --key mappings.csv --output /tmp/s3.csv'
./gradlew cli:run --args='manage-user-mappings list \
  --type AWS --format CSV --output /tmp/db.csv'
diff /tmp/s3.csv /tmp/db.csv
```

**Exit codes**:
| Code | Meaning |
| ---- | ------- |
| 0    | Success — file written to `--output` |
| 1    | Generic / I/O error |
| 2    | S3, credentials, or argument error (fatal — won't succeed on retry) |
| 3    | Unexpected error |

**Output (default mode)**:
```
============================================================
Download AWS Account Mapping from S3
============================================================

Source: s3://my-bucket/mappings.csv
Destination: ./aws-mappings.csv
AWS Credentials: profile 'prod'

Downloading from S3...

============================================================
Summary
============================================================
Wrote 4096 bytes to /path/to/aws-mappings.csv
Download successful
```

**Output (`--quiet`)**: nothing on stdout; only `Wrote N bytes to /abs/path` on stderr.

---

### 8. S3 Print (Direct, Bypasses Backend, No Disk Write)

**Command**: `print-s3`

**Purpose**: Download a mapping file from S3, parse it, and print the **identified mappings** straight to the console. Like `download-s3` it bypasses the secman backend entirely. Unlike `download-s3` it never writes the file to disk — the temp file used during the download is deleted on exit. Default scope is `--type AWS` because the typical use case is inspecting AWS account → email assignments.

This is the parse-and-pretty-print counterpart in the S3 family:
- `import-s3` downloads, parses, and POSTs to the secman backend.
- `download-s3` only copies the raw file to disk.
- `print-s3` (this command) downloads and parses, then prints. No disk write, no backend.

**Syntax**:
```bash
./gradlew cli:run --args='manage-user-mappings print-s3 \
  --bucket <bucket-name> \
  --key <object-key> \
  [--type <AWS|DOMAIN|ALL>] \
  [--format <TABLE|JSON|CSV>] \
  [--file-format <CSV|JSON|AUTO>] \
  [--show-errors] \
  [--aws-region <region>] \
  [--aws-profile <profile>] \
  [--aws-access-key-id <key>] \
  [--aws-secret-access-key <secret>] \
  [--aws-session-token <token>] \
  [--endpoint-url <url>] \
  [--quiet | -q]'
```

**Required Options**:
- `--bucket`, `-b`: S3 bucket name (plain name — not a URL or ARN)
- `--key`, `-k`: S3 object key (path inside the bucket)

**Optional Options**:
- `--type` (default `AWS`): `AWS` prints only AWS account mappings, `DOMAIN` prints only domain mappings, `ALL` prints both.
- `--format` (default `TABLE`): `TABLE` (per-user grouped, human-friendly), `JSON` (structured aggregate), or `CSV` (`Email,Type,Value` rows; pipe-friendly).
- `--file-format` (default `AUTO`): format of the source file in S3 — `CSV`, `JSON`, or `AUTO` (auto-detected from `.csv`/`.json` extension, then by content sniffing).
- `--show-errors`: print parse errors (malformed rows, invalid email/account/domain syntax) to stderr after the mapping output. Without this flag, errors are silently dropped and only valid rows are printed.
- `--aws-region` / `--aws-profile` / `--aws-access-key-id` / `--aws-secret-access-key` / `--aws-session-token` / `--endpoint-url`: same AWS credential-resolution chain as `import-s3` and `download-s3` (CLI flag → env var → profile → default chain).
- `--quiet`, `-q`: suppress the header banner ("Source: ...", "Scope: ...") and the trailing summary line. The parsed mapping output is still printed on stdout so the command remains pipeable.

**Stdout / stderr split (important for piping)**:
- **stdout** carries only the parsed mapping output (TABLE / JSON / CSV).
- **stderr** carries the header banner and trailing summary line. Suppressed by `--quiet`.
- Parse errors only appear (also on stderr) when `--show-errors` is set.

This split lets you do things like `print-s3 ... --format CSV --quiet | jq -R 'split(",")'` or pipe straight into `diff` against a `list --output` capture.

**Constraints**:
- 10 MB hard size limit on the S3 object (matches `import-s3`).
- The temp download is created in the system temp dir with owner-only permissions and removed on exit.
- File contents are parsed but not modified — invalid rows are reported via `--show-errors`, valid rows are echoed verbatim.
- The secman DB is **never queried**. The only source of truth is the S3 file.

**Examples**:
```bash
# Print AWS account mappings as a table (default)
./gradlew cli:run --args='manage-user-mappings print-s3 \
  --bucket my-bucket \
  --key mappings.csv'

# Print everything (AWS + domains) as JSON
./gradlew cli:run --args='manage-user-mappings print-s3 \
  --bucket my-bucket \
  --key mappings.csv \
  --type ALL \
  --format JSON'

# Print as CSV, pipe through downstream tooling
./scriptpp/secman manage-user-mappings print-s3 \
  --bucket my-bucket --key mappings.csv \
  --format CSV --quiet | grep '^alice@'

# Diff S3 source-of-truth against secman DB
./scriptpp/secman manage-user-mappings print-s3 \
  --bucket my-bucket --key mappings.csv \
  --format CSV --quiet > /tmp/s3.csv
./scriptpp/secman manage-user-mappings list \
  --type AWS --format CSV --output /tmp/db.csv
diff /tmp/s3.csv /tmp/db.csv

# Troubleshoot a malformed file
./gradlew cli:run --args='manage-user-mappings print-s3 \
  --bucket my-bucket \
  --key broken.csv \
  --show-errors'
```

**Exit codes**:
| Code | Meaning |
| ---- | ------- |
| 0    | Success — file parsed and printed without errors |
| 1    | Parse errors found in the file (valid rows still printed) |
| 2    | S3, credentials, or argument error (fatal — won't succeed on retry) |
| 3    | Unexpected error |

**Output (default mode, `--format TABLE`)**:

`stderr`:
```
============================================================
Print Mapping File from S3
============================================================
Source: s3://my-bucket/mappings.csv
Scope: AWS  Format: TABLE  File-format: AUTO

============================================================
Parsed 12 mapping(s) from s3://my-bucket/mappings.csv (8 after --type filter)
```

`stdout`:
```
alice@example.com
  AWS Accounts:
    - 123456789012
    - 987654321098
bob@example.com
  AWS Accounts:
    - 555566667777

Total: 8 mapping(s) across 5 user(s)
```

**Output (`--quiet --format CSV`)**:

`stderr`: empty.

`stdout`:
```
Email,Type,Value
alice@example.com,AWS_ACCOUNT,123456789012
alice@example.com,AWS_ACCOUNT,987654321098
bob@example.com,AWS_ACCOUNT,555566667777
```

---

## Common Workflows

### 1. Onboard New User with Multiple Domains
```bash
# User joins organization, needs access to multiple domains
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails newuser@example.com \
  --domains corp.local,dev.local,staging.local \
  --admin-user admin@example.com'

# Verify mappings were created
./gradlew cli:run --args='manage-user-mappings list \
  --email newuser@example.com \
  --admin-user admin@example.com'
```

### 2. Bulk Import from Spreadsheet
```bash
# 1. Export from Excel/Google Sheets to CSV
# 2. Validate with dry-run
./gradlew cli:run --args='manage-user-mappings import \
  --file users_mappings.csv \
  --dry-run \
  --admin-user admin@example.com'

# 3. If validation passes, import
./gradlew cli:run --args='manage-user-mappings import \
  --file users_mappings.csv \
  --admin-user admin@example.com'

# 4. Verify results
./gradlew cli:run --args='manage-user-mappings list \
  --admin-user admin@example.com'
```

### 3. Migrate User to Different AWS Account
```bash
# Remove old account
./gradlew cli:run --args='manage-user-mappings remove \
  --email user@example.com \
  --account 123456789012 \
  --admin-user admin@example.com'

# Add new account
./gradlew cli:run --args='manage-user-mappings add-aws \
  --emails user@example.com \
  --accounts 987654321098 \
  --admin-user admin@example.com'
```

### 4. S3 Import with Admin Notification
```bash
# 1. Import mappings from S3
./scriptpp/secman manage-user-mappings import-s3 \
  --bucket company-mappings --key daily/users.csv

# 2. Preview who would receive the email
./scriptpp/secman manage-user-mappings list --send-email --dry-run

# 3. Send statistics email to all ADMIN/REPORT users
./scriptpp/secman manage-user-mappings list --send-email

# Or chain both steps (email only sent if import succeeds)
./scriptpp/secman manage-user-mappings import-s3 \
  --bucket company-mappings --key daily/users.csv && \
  ./scriptpp/secman manage-user-mappings list --send-email
```

### 5. Audit User Access
```bash
# Export all mappings to JSON for analysis
./gradlew cli:run --args='manage-user-mappings list \
  --format JSON \
  --admin-user admin@example.com' > audit_$(date +%Y%m%d).json

# Export to CSV for spreadsheet analysis
./gradlew cli:run --args='manage-user-mappings list \
  --format CSV \
  --admin-user admin@example.com' > audit_$(date +%Y%m%d).csv
```

---

## Troubleshooting

### Issue: "Admin user required" Error
**Cause**: No admin user specified
**Solution**: Set `SECMAN_ADMIN_EMAIL` environment variable or use `--admin-user` flag
```bash
export SECMAN_ADMIN_EMAIL=admin@example.com
# OR
./gradlew cli:run --args='manage-user-mappings <command> --admin-user admin@example.com ...'
```

### Issue: "Invalid email format" Error
**Cause**: Email doesn't match required pattern
**Solution**: Ensure email follows format: `user@domain.tld`
- Valid: `alice@example.com`, `bob.smith@corp.local`
- Invalid: `alice`, `@example.com`, `alice@`

### Issue: "Invalid AWS account ID" Error
**Cause**: AWS account ID is not exactly 12 digits
**Solution**: Verify account ID is 12-digit numeric string
- Valid: `123456789012`
- Invalid: `12345`, `1234567890123`, `abc123456789`

### Issue: "File not found" Error
**Cause**: Import file path is incorrect or file doesn't exist
**Solution**: Use absolute path or verify relative path
```bash
# Absolute path
./gradlew cli:run --args='manage-user-mappings import \
  --file /Users/admin/mappings.csv'

# Relative path (from project root)
./gradlew cli:run --args='manage-user-mappings import \
  --file ./data/mappings.csv'
```

### Issue: CSV Import Parsing Errors
**Cause**: CSV format doesn't match expected schema
**Solution**: Verify CSV has correct headers and format
- Headers must include: `email`, `type`, `value`
- Headers are case-insensitive: `Email`, `TYPE`, `Value` all work
- Type must be: `DOMAIN` or `AWS_ACCOUNT`
- Use dry-run to test: `--dry-run`

### Issue: Duplicate Mappings Skipped
**Behavior**: This is expected - duplicates are detected and skipped with warning
**Action**: Review skipped count in summary output. If unexpected, check existing mappings:
```bash
./gradlew cli:run --args='manage-user-mappings list \
  --email <email> \
  --admin-user admin@example.com'
```

---

## Best Practices

### 1. Use Environment Variable for Admin
```bash
# Set once per session
export SECMAN_ADMIN_EMAIL=admin@example.com

# Then omit --admin-user from all commands
./gradlew cli:run --args='manage-user-mappings add-domain ...'
```

### 2. Always Test with Dry-Run for Bulk Imports
```bash
# Test before importing
./gradlew cli:run --args='manage-user-mappings import \
  --file large_import.csv \
  --dry-run'

# If successful, import
./gradlew cli:run --args='manage-user-mappings import \
  --file large_import.csv'
```

### 3. Export Mappings Before Bulk Operations
```bash
# Backup before bulk remove
./gradlew cli:run --args='manage-user-mappings list \
  --format JSON' > backup_$(date +%Y%m%d).json

# Then proceed with operation
./gradlew cli:run --args='manage-user-mappings remove ...'
```

### 4. Use Pending Mappings for Future Users
```bash
# Create mappings before user account exists
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails future.hire@example.com \
  --domains corp.local'

# Mappings auto-activate when user is created via:
# - OAuth login
# - Manual user creation
# - OIDC auto-provisioning
```

### 5. Regular Audits
```bash
# Weekly: Export all mappings
./gradlew cli:run --args='manage-user-mappings list \
  --format CSV' > audit_$(date +%Y%m%d).csv

# Monthly: Review pending mappings
./gradlew cli:run --args='manage-user-mappings list \
  --status PENDING'
```

---

## Security Considerations

1. **ADMIN Role Required**: All commands enforce ADMIN role check
2. **Audit Logging**: All operations logged with actor, timestamp, and entities
3. **Input Validation**: Email, domain, and AWS account ID formats validated
4. **Pending Mappings**: Unactivated mappings don't grant access until user exists
5. **No Wildcards**: Exact matches only - no pattern-based access grants

---

## Related Documentation

- **Feature Spec**: `specs/049-cli-user-mappings/spec.md`
- **Implementation Plan**: `specs/049-cli-user-mappings/plan.md`
- **Task Breakdown**: `specs/049-cli-user-mappings/tasks.md`
- **API Endpoints**: Feature 042 (Future User Mappings) for web interface
- **Access Control**: CLAUDE.md - "Unified Access Control" section

---

## Support

For issues or questions:
1. Check troubleshooting guide above
2. Review audit logs: `SELECT * FROM user_mapping WHERE email = '...' ORDER BY created_at DESC`
3. Verify database connectivity: `./gradlew backendng:run`
4. Report bugs: https://github.com/schmalle/secman/issues

---

**End of User Mapping Commands Documentation**
