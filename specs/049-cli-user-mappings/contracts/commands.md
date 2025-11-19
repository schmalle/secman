# CLI Command Specifications

**Feature**: 049-cli-user-mappings
**Date**: 2025-11-19

## Command Structure

All commands follow the pattern:
```bash
./gradlew cli:run --args='manage-user-mappings <subcommand> [options] [arguments]'
```

## Global Options

Available for all subcommands:

| Option | Short | Required | Description |
|--------|-------|----------|-------------|
| `--admin-user` | `-u` | No | Admin user email (defaults to SECMAN_ADMIN_EMAIL env var) |
| `--help` | `-h` | No | Show help message and exit |
| `--version` | `-v` | No | Show version information |

## Subcommands

### 1. add-domain

**Purpose**: Add one or more AD domain-to-user mappings

**Syntax**:
```bash
manage-user-mappings add-domain \
  --emails <email1,email2,...> \
  --domains <domain1,domain2,...> \
  [--admin-user <admin@example.com>]
```

**Arguments**:

| Argument | Type | Required | Description | Example |
|----------|------|----------|-------------|---------|
| `--emails` | String (comma-separated) | Yes | User email addresses | `user1@example.com,user2@example.com` |
| `--domains` | String (comma-separated) | Yes | AD domains to assign | `example.com,test.local` |
| `--admin-user` | String | No | Admin executing command | `admin@example.com` |

**Behavior**:
- Creates n×m mappings (cross product of emails and domains)
- Skips duplicates with warning
- Creates pending mappings for non-existent users (with warning)
- Validates email format and domain format
- Normalizes emails and domains to lowercase

**Output**:
```
Processing domain mappings...
✅ Created: user1@example.com → example.com
✅ Created: user1@example.com → test.local
⚠️  Skipped (duplicate): user2@example.com → example.com
⚠️  Pending (user not found): future@example.com → example.com

Summary:
  Total: 4 mappings processed
  Created: 2 active, 1 pending
  Skipped: 1 duplicate
```

**Exit Codes**:
- 0: Success (all mappings created or skipped)
- 1: Error (validation failure, database error)

**Examples**:
```bash
# Add single mapping
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails john@example.com \
  --domains example.com'

# Add multiple users to same domain
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails john@example.com,jane@example.com \
  --domains corp.local'

# Add one user to multiple domains
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails admin@example.com \
  --domains corp.local,dev.local,prod.local'
```

---

### 2. add-aws

**Purpose**: Add one or more AWS account-to-user mappings

**Syntax**:
```bash
manage-user-mappings add-aws \
  --emails <email1,email2,...> \
  --accounts <account1,account2,...> \
  [--admin-user <admin@example.com>]
```

**Arguments**:

| Argument | Type | Required | Description | Example |
|----------|------|----------|-------------|---------|
| `--emails` | String (comma-separated) | Yes | User email addresses | `user1@example.com,user2@example.com` |
| `--accounts` | String (comma-separated) | Yes | AWS account IDs (12 digits) | `123456789012,987654321098` |
| `--admin-user` | String | No | Admin executing command | `admin@example.com` |

**Behavior**:
- Creates n×m mappings (cross product of emails and accounts)
- Validates AWS account ID format (exactly 12 numeric digits)
- Skips duplicates with warning
- Creates pending mappings for non-existent users
- Normalizes emails to lowercase

**Output**:
```
Processing AWS account mappings...
✅ Created: user1@example.com → 123456789012
✅ Created: user1@example.com → 987654321098
❌ Error: Invalid AWS account ID '12345' (must be 12 digits)
⚠️  Pending (user not found): future@example.com → 123456789012

Summary:
  Total: 4 mappings attempted
  Created: 2 active, 1 pending
  Errors: 1 validation failure
```

**Exit Codes**:
- 0: Success (all valid mappings created or skipped)
- 1: Error (all mappings failed due to validation/database errors)

**Examples**:
```bash
# Add single mapping
./gradlew cli:run --args='manage-user-mappings add-aws \
  --emails john@example.com \
  --accounts 123456789012'

# Add multiple users to same account
./gradlew cli:run --args='manage-user-mappings add-aws \
  --emails john@example.com,jane@example.com \
  --accounts 123456789012'
```

---

### 3. remove

**Purpose**: Remove user mappings by email, domain, or AWS account

**Syntax**:
```bash
manage-user-mappings remove \
  --email <email> \
  [--domain <domain>] \
  [--account <aws-account-id>] \
  [--admin-user <admin@example.com>] \
  [--all]
```

**Arguments**:

| Argument | Type | Required | Description | Example |
|----------|------|----------|-------------|---------|
| `--email` | String | Yes | User email to remove mappings for | `user@example.com` |
| `--domain` | String | No | Specific domain to remove | `example.com` |
| `--account` | String | No | Specific AWS account to remove | `123456789012` |
| `--all` | Boolean | No | Remove ALL mappings for the user | N/A |
| `--admin-user` | String | No | Admin executing command | `admin@example.com` |

**Behavior**:
- If `--all`: Removes all mappings for the email
- If `--domain`: Removes only domain mappings matching the domain
- If `--account`: Removes only AWS account mappings matching the account
- If `--domain` and `--account`: Removes mappings matching BOTH criteria
- Validates email format
- Returns error if no mappings found

**Output**:
```
Removing mappings for user@example.com...
✅ Removed: user@example.com → example.com (domain)
✅ Removed: user@example.com → 123456789012 (AWS account)

Summary:
  Mappings removed: 2
```

**Exit Codes**:
- 0: Success (mappings removed)
- 1: Error (no mappings found, validation failure)

**Examples**:
```bash
# Remove specific domain mapping
./gradlew cli:run --args='manage-user-mappings remove \
  --email john@example.com \
  --domain example.com'

# Remove specific AWS account mapping
./gradlew cli:run --args='manage-user-mappings remove \
  --email john@example.com \
  --account 123456789012'

# Remove ALL mappings for a user
./gradlew cli:run --args='manage-user-mappings remove \
  --email john@example.com \
  --all'
```

---

### 4. list

**Purpose**: List all user mappings with optional filtering

**Syntax**:
```bash
manage-user-mappings list \
  [--email <email>] \
  [--status <ACTIVE|PENDING|ALL>] \
  [--format <TABLE|JSON|CSV>] \
  [--admin-user <admin@example.com>]
```

**Arguments**:

| Argument | Type | Required | Description | Default |
|----------|------|----------|-------------|---------|
| `--email` | String | No | Filter by user email | All emails |
| `--status` | Enum | No | Filter by status (ACTIVE/PENDING/ALL) | ALL |
| `--format` | Enum | No | Output format (TABLE/JSON/CSV) | TABLE |
| `--admin-user` | String | No | Admin executing command | - |

**Behavior**:
- Lists all mappings (or filtered subset)
- Groups by user email
- Shows status (ACTIVE/PENDING) for each mapping
- Supports multiple output formats

**Output (TABLE format)**:
```
User Mappings
================================================================================

Email: john@example.com (Status: ACTIVE)
  Domains:
    - example.com (ACTIVE, created: 2025-11-19 10:30:00)
    - test.local (ACTIVE, created: 2025-11-19 10:31:00)
  AWS Accounts:
    - 123456789012 (ACTIVE, created: 2025-11-19 10:32:00)

Email: future@example.com (Status: PENDING)
  Domains:
    - example.com (PENDING, created: 2025-11-19 10:33:00)

Total Users: 2
Total Mappings: 4 (3 active, 1 pending)
```

**Output (JSON format)**:
```json
{
  "users": [
    {
      "email": "john@example.com",
      "status": "ACTIVE",
      "mappings": {
        "domains": [
          {"value": "example.com", "status": "ACTIVE", "createdAt": "2025-11-19T10:30:00Z"}
        ],
        "awsAccounts": [
          {"value": "123456789012", "status": "ACTIVE", "createdAt": "2025-11-19T10:32:00Z"}
        ]
      }
    }
  ],
  "summary": {
    "totalUsers": 1,
    "totalMappings": 2,
    "activeCount": 2,
    "pendingCount": 0
  }
}
```

**Output (CSV format)**:
```csv
email,type,value,status,created_at
john@example.com,domain,example.com,ACTIVE,2025-11-19T10:30:00Z
john@example.com,aws_account,123456789012,ACTIVE,2025-11-19T10:32:00Z
```

**Exit Codes**:
- 0: Success
- 1: Error (database error, validation failure)

**Examples**:
```bash
# List all mappings
./gradlew cli:run --args='manage-user-mappings list'

# List mappings for specific user
./gradlew cli:run --args='manage-user-mappings list --email john@example.com'

# List only pending mappings
./gradlew cli:run --args='manage-user-mappings list --status PENDING'

# Export to JSON
./gradlew cli:run --args='manage-user-mappings list --format JSON > mappings.json'
```

---

### 5. import

**Purpose**: Batch import mappings from CSV or JSON file

**Syntax**:
```bash
manage-user-mappings import \
  --file <path/to/file> \
  [--format <CSV|JSON>] \
  [--dry-run] \
  [--admin-user <admin@example.com>]
```

**Arguments**:

| Argument | Type | Required | Description | Default |
|----------|------|----------|-------------|---------|
| `--file` | String (path) | Yes | Path to import file | - |
| `--format` | Enum | No | File format (CSV/JSON), auto-detected if omitted | Auto-detect |
| `--dry-run` | Boolean | No | Validate without creating mappings | false |
| `--admin-user` | String | No | Admin executing command | - |

**Behavior**:
- Reads CSV or JSON file
- Validates each entry independently
- Creates mappings in batch (partial success mode)
- Skips duplicates
- Creates pending mappings for non-existent users
- Reports detailed summary with line-level errors

**Output**:
```
Importing mappings from file: mappings.csv
================================================================================

Processing 100 entries...
✅ Line 1: Created user1@example.com → example.com
✅ Line 2: Created user1@example.com → 123456789012
⚠️  Line 3: Skipped (duplicate) user2@example.com → example.com
❌ Line 4: Error - Invalid AWS account ID '12345'
⚠️  Line 5: Pending (user not found) future@example.com → test.com

Summary:
  Total processed: 100
  Created: 85 (70 active, 15 pending)
  Skipped: 10 duplicates
  Errors: 5 validation failures

Error details:
  Line 4: Invalid AWS account ID '12345' (must be 12 digits)
  Line 7: Invalid email format 'not-an-email'
  Line 12: Missing required field 'type'
  Line 45: Invalid domain format 'domain with spaces'
  Line 89: Duplicate entry in file (already processed in line 12)
```

**Exit Codes**:
- 0: Success (all valid entries processed, some may be skipped/pending)
- 1: Error (file not found, parse error, all entries failed)

**Examples**:
```bash
# Import from CSV
./gradlew cli:run --args='manage-user-mappings import --file mappings.csv'

# Import from JSON
./gradlew cli:run --args='manage-user-mappings import --file mappings.json'

# Dry-run (validation only)
./gradlew cli:run --args='manage-user-mappings import \
  --file mappings.csv \
  --dry-run'

# Explicit format specification
./gradlew cli:run --args='manage-user-mappings import \
  --file data.txt \
  --format CSV'
```

---

## Authentication

All commands require authentication via one of:

1. **Command-line argument**:
   ```bash
   --admin-user admin@example.com
   ```

2. **Environment variable**:
   ```bash
   export SECMAN_ADMIN_EMAIL=admin@example.com
   ./gradlew cli:run --args='manage-user-mappings list'
   ```

3. **Priority**: `--admin-user` flag > `SECMAN_ADMIN_EMAIL` env var > Error

**Authorization**:
- All commands require ADMIN role
- Non-ADMIN users will receive authorization error
- Audit logs record the admin user who executed each command

## Error Handling

All commands follow consistent error handling:

**Validation Errors** (Exit Code 1):
```
❌ Error: Invalid email format: 'not-an-email'
❌ Error: Invalid AWS account ID: '12345' (must be 12 digits)
❌ Error: Admin user required (use --admin-user or set SECMAN_ADMIN_EMAIL)
```

**Database Errors** (Exit Code 1):
```
❌ Error: Database connection failed
❌ Error: Failed to save mapping: [exception details]
```

**Not Found Errors** (Exit Code 1):
```
❌ Error: No mappings found for user@example.com
❌ Error: File not found: mappings.csv
```

## Audit Logging

All commands automatically log to audit trail:

```json
{
  "timestamp": "2025-11-19T10:30:00Z",
  "operation": "CREATE_DOMAIN_MAPPING",
  "actor": "admin@example.com",
  "entity_type": "UserMapping",
  "entity_id": 12345,
  "email": "user@example.com",
  "domain": "example.com",
  "status": "ACTIVE",
  "command": "add-domain",
  "cli_args": "--emails user@example.com --domains example.com"
}
```

## Summary

| Command | Purpose | Key Arguments | Output |
|---------|---------|---------------|--------|
| add-domain | Add domain mappings | --emails, --domains | Created/Skipped/Pending counts |
| add-aws | Add AWS account mappings | --emails, --accounts | Created/Skipped/Pending counts |
| remove | Remove mappings | --email, --domain/--account/--all | Removed count |
| list | List mappings | --email, --status, --format | Table/JSON/CSV output |
| import | Batch import | --file, --format, --dry-run | Batch result summary |
