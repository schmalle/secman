# CLI Command Contracts: User Mapping Management

**Feature**: 064-mcp-cli-user-mapping
**Date**: 2026-01-19

## Command: manage-user-mappings import

**Description**: Import user mappings from CSV or JSON file

**Usage**:
```bash
./bin/secman manage-user-mappings import --file <path> [--format <CSV|JSON|AUTO>] [--dry-run]
```

### Options

| Option | Required | Default | Description |
|--------|----------|---------|-------------|
| `--file`, `-f` | Yes | - | Path to CSV or JSON file |
| `--format` | No | AUTO | File format: CSV, JSON, or AUTO (auto-detect) |
| `--dry-run` | No | false | Validate without creating mappings |

### CSV Format

```csv
email,type,value
user@example.com,DOMAIN,corp.example.com
user@example.com,AWS_ACCOUNT,123456789012
admin@example.com,DOMAIN,admin.example.com
```

**Headers**: `email`, `type`, `value` (case-insensitive)
**Types**: `DOMAIN`, `AWS_ACCOUNT`

### JSON Format

```json
[
  {
    "email": "user@example.com",
    "domains": ["corp.example.com", "dev.example.com"],
    "awsAccounts": ["123456789012"]
  },
  {
    "email": "admin@example.com",
    "domains": ["admin.example.com"]
  }
]
```

### Output (Success)

```
============================================================
Import User Mappings
============================================================

Admin user: admin@example.com
File: /path/to/mappings.csv
Format: CSV

============================================================
Summary
============================================================
Total: 5 mapping(s) processed
✅ Created: 3 active mapping(s)
⚠️  Created: 1 pending mapping(s)
⚠️  Skipped: 1 duplicate(s)

✓ Import successful
```

### Output (Dry-Run)

```
============================================================
Import User Mappings
============================================================

Admin user: admin@example.com
File: /path/to/mappings.csv
Format: CSV
Mode: DRY-RUN (validation only, no changes will be made)

============================================================
Summary
============================================================
Total: 5 mapping(s) processed
✅ Would create: 4 mapping(s)

✓ Validation successful (dry-run)
```

### Exit Codes

| Code | Condition |
|------|-----------|
| 0 | Success |
| 1 | Validation errors or import failures |

---

## Command: manage-user-mappings list

**Description**: List user mappings with optional filtering

**Usage**:
```bash
./bin/secman manage-user-mappings list [--email <email>] [--status <ACTIVE|PENDING>] [--format <table|json>]
```

### Options

| Option | Required | Default | Description |
|--------|----------|---------|-------------|
| `--email` | No | - | Filter by email address |
| `--status` | No | - | Filter by status: ACTIVE or PENDING |
| `--format` | No | table | Output format: table or json |

### Output (Table Format - Default)

```
============================================================
User Mappings
============================================================

ID    | Email                  | AWS Account ID  | Domain            | Status   | Applied At
------|------------------------|-----------------|-------------------|----------|------------------
1     | user@example.com       | 123456789012    | -                 | ACTIVE   | 2026-01-15 10:30
2     | user@example.com       | -               | corp.example.com  | ACTIVE   | 2026-01-15 10:30
3     | future@example.com     | 123456789012    | -                 | PENDING  | -

Total: 3 mapping(s)
```

### Output (JSON Format)

```json
{
  "mappings": [
    {
      "id": 1,
      "email": "user@example.com",
      "awsAccountId": "123456789012",
      "domain": null,
      "status": "ACTIVE",
      "appliedAt": "2026-01-15T10:30:00Z",
      "createdAt": "2026-01-15T10:00:00Z"
    },
    {
      "id": 2,
      "email": "user@example.com",
      "awsAccountId": null,
      "domain": "corp.example.com",
      "status": "ACTIVE",
      "appliedAt": "2026-01-15T10:30:00Z",
      "createdAt": "2026-01-15T10:00:00Z"
    }
  ],
  "total": 2
}
```

### Examples

```bash
# List all mappings (table format)
./bin/secman manage-user-mappings list

# List mappings for specific email
./bin/secman manage-user-mappings list --email user@example.com

# List pending mappings only
./bin/secman manage-user-mappings list --status PENDING

# Export to JSON for scripting
./bin/secman manage-user-mappings list --format json > mappings.json

# Pipe to jq for filtering
./bin/secman manage-user-mappings list --format json | jq '.mappings[] | select(.status == "PENDING")'
```

### Exit Codes

| Code | Condition |
|------|-----------|
| 0 | Success |
| 1 | Error (e.g., authentication failure) |
