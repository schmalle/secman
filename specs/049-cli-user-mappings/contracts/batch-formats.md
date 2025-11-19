# Batch Import File Formats

**Feature**: 049-cli-user-mappings
**Date**: 2025-11-19

## Overview

The `manage-user-mappings import` command supports two file formats for batch operations:
- **CSV**: Simple, Excel-compatible format
- **JSON**: Structured format with richer metadata

## CSV Format

### Schema

**Required Columns** (case-insensitive headers):

| Column | Type | Description | Example | Validation |
|--------|------|-------------|---------|------------|
| `email` | String | User email address | `user@example.com` | Email format, normalized to lowercase |
| `type` | Enum | Mapping type: `domain` or `aws` | `domain` | Must be `domain` or `aws` (case-insensitive) |
| `value` | String | Domain name or AWS account ID | `example.com` or `123456789012` | Depends on type |

### Format Rules

1. **Header Row**: Required as first line (case-insensitive)
2. **Delimiter**: Comma (`,`) - standard CSV
3. **Quotes**: Use double quotes (`"`) for values containing commas
4. **Encoding**: UTF-8 (with or without BOM)
5. **Line Endings**: CR+LF (`\r\n`) or LF (`\n`)
6. **Empty Lines**: Skipped automatically
7. **Comments**: Not supported (lines starting with `#` are treated as data)

### Example

```csv
email,type,value
john@example.com,domain,example.com
john@example.com,aws,123456789012
jane@example.com,domain,corp.local
admin@example.com,domain,example.com
admin@example.com,domain,test.local
admin@example.com,aws,987654321098
```

### Validation per Row

**Email Validation**:
- Must match regex: `^[^@]+@[^@]+\.[^@]+$`
- Automatically normalized to lowercase
- Example valid: `User@Example.COM` → `user@example.com`
- Example invalid: `not-an-email`, `user@`, `@example.com`

**Type Validation**:
- Must be exactly `domain` or `aws` (case-insensitive)
- Example valid: `domain`, `Domain`, `DOMAIN`, `aws`, `AWS`
- Example invalid: `account`, `ip`, `user`, `""` (empty)

**Value Validation (when type = domain)**:
- Must match regex: `^[a-zA-Z0-9.-]+$`
- Automatically normalized to lowercase
- No leading/trailing dots or hyphens
- Example valid: `example.com`, `test.local`, `sub.example.com`
- Example invalid: `domain with spaces`, `-example.com`, `.example.com`

**Value Validation (when type = aws)**:
- Must match regex: `^\d{12}$` (exactly 12 numeric digits)
- No spaces, hyphens, or other characters
- Example valid: `123456789012`, `000000000001`
- Example invalid: `12345` (too short), `1234567890123` (too long), `12345678901a` (non-numeric)

### Error Handling

**Partial Success Mode**:
- Each row validated and processed independently
- Invalid rows skipped with error message
- Valid rows processed successfully
- Duplicate rows skipped with warning

**Example Error Output**:
```
Line 5: Invalid email format 'not-an-email'
Line 12: Invalid type 'account' (must be 'domain' or 'aws')
Line 23: Invalid AWS account ID '12345' (must be 12 digits)
Line 45: Skipped (duplicate) user@example.com → example.com
```

### Advanced Examples

**Example 1: Multiple Mappings per User**
```csv
email,type,value
admin@example.com,domain,corp.local
admin@example.com,domain,dev.local
admin@example.com,domain,prod.local
admin@example.com,aws,123456789012
admin@example.com,aws,987654321098
```

**Example 2: Mixed Case (Normalized)**
```csv
email,type,value
User@Example.COM,domain,Example.Com
User@Example.COM,aws,123456789012
```
Result: Both rows normalized to `user@example.com` and `example.com`

**Example 3: Values with Special Characters**
```csv
email,type,value
user@example.com,domain,"sub-domain.example.com"
```

---

## JSON Format

### Schema

**Root Object**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `mappings` | Array | Yes | Array of user mapping objects |

**Mapping Object**:

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `email` | String | Yes | User email address | `"user@example.com"` |
| `domains` | Array\<String\> | No | List of AD domains | `["example.com", "test.local"]` |
| `awsAccounts` | Array\<String\> | No | List of AWS account IDs | `["123456789012", "987654321098"]` |

### Format Rules

1. **Valid JSON**: Must parse as valid JSON
2. **UTF-8 Encoding**: Required
3. **Root Structure**: Must be object with `mappings` array
4. **At Least One Value**: Each mapping must have either `domains` or `awsAccounts` (or both)
5. **Empty Arrays**: Treated as no mappings of that type

### Example

```json
{
  "mappings": [
    {
      "email": "john@example.com",
      "domains": ["example.com", "corp.local"],
      "awsAccounts": ["123456789012"]
    },
    {
      "email": "jane@example.com",
      "domains": ["test.local"]
    },
    {
      "email": "admin@example.com",
      "domains": ["example.com", "dev.local", "prod.local"],
      "awsAccounts": ["123456789012", "987654321098"]
    },
    {
      "email": "cloud-admin@example.com",
      "awsAccounts": ["111111111111", "222222222222"]
    }
  ]
}
```

### Validation

**Email Validation**: Same as CSV format

**Domains Validation**:
- Each domain validated individually
- Must match regex: `^[a-zA-Z0-9.-]+$`
- Normalized to lowercase
- Invalid domains skipped with warning

**AWS Accounts Validation**:
- Each account validated individually
- Must match regex: `^\d{12}$`
- Invalid accounts skipped with warning

### Expansion Logic

Each mapping object expands to multiple database rows:

**Example Input**:
```json
{
  "email": "user@example.com",
  "domains": ["example.com", "test.local"],
  "awsAccounts": ["123456789012"]
}
```

**Expands To** (3 database rows):
1. `user@example.com` → `domain:example.com`
2. `user@example.com` → `domain:test.local`
3. `user@example.com` → `aws:123456789012`

### Error Handling

**Parsing Errors**:
```
❌ Error: Invalid JSON syntax at line 12, column 5
❌ Error: Missing required field 'email' in mapping at index 3
❌ Error: Empty 'mappings' array (no entries to process)
```

**Validation Errors** (per mapping):
```
Mapping #1 (user@example.com):
  ✅ Domain: example.com → Created
  ❌ Domain: invalid domain → Skipped (invalid format)
  ✅ AWS Account: 123456789012 → Created

Mapping #2 (invalid-email):
  ❌ Skipped entire mapping (invalid email format)
```

### Advanced Examples

**Example 1: Domains Only**
```json
{
  "mappings": [
    {
      "email": "domain-admin@example.com",
      "domains": ["corp.local", "dev.local", "prod.local"]
    }
  ]
}
```

**Example 2: AWS Accounts Only**
```json
{
  "mappings": [
    {
      "email": "cloud-admin@example.com",
      "awsAccounts": ["123456789012", "987654321098", "111111111111"]
    }
  ]
}
```

**Example 3: Mixed (Some with Both)**
```json
{
  "mappings": [
    {
      "email": "full-admin@example.com",
      "domains": ["example.com"],
      "awsAccounts": ["123456789012"]
    },
    {
      "email": "domain-only@example.com",
      "domains": ["test.local"]
    },
    {
      "email": "aws-only@example.com",
      "awsAccounts": ["987654321098"]
    }
  ]
}
```

**Example 4: Empty Arrays (Valid but No-Op)**
```json
{
  "mappings": [
    {
      "email": "user@example.com",
      "domains": [],
      "awsAccounts": []
    }
  ]
}
```
Result: Warning - No mappings created for `user@example.com` (empty arrays)

---

## Format Comparison

| Feature | CSV | JSON |
|---------|-----|------|
| **Ease of Creation** | ✅ Excel/Google Sheets | ⚠️ Text editor/script |
| **Human Readability** | ✅ Very readable | ⚠️ Moderate |
| **Compactness** | ⚠️ One row per mapping | ✅ Multiple mappings per user |
| **Validation** | ⚠️ Per-row only | ✅ Per-user grouping |
| **Error Recovery** | ✅ Line-level errors | ⚠️ Mapping-level errors |
| **File Size** | ⚠️ Larger for multi-mapping users | ✅ Smaller |
| **Excel Compatible** | ✅ Yes | ❌ No |
| **API-Friendly** | ❌ No | ✅ Yes |

## Format Auto-Detection

The `import` command auto-detects format based on:

1. **File Extension**:
   - `.csv` → CSV format
   - `.json` → JSON format
   - Other → Try JSON first, fall back to CSV

2. **Content Inspection**:
   - Starts with `{` or `[` → JSON format
   - Starts with `email,type,value` (case-insensitive) → CSV format
   - Otherwise → Error (ambiguous format)

3. **Override**: Use `--format` flag to force specific format
   ```bash
   ./gradlew cli:run --args='manage-user-mappings import \
     --file data.txt \
     --format CSV'
   ```

## Generating Sample Files

### CSV Template
```bash
cat > sample-mappings.csv <<EOF
email,type,value
admin@example.com,domain,example.com
admin@example.com,aws,123456789012
user@example.com,domain,corp.local
EOF
```

### JSON Template
```bash
cat > sample-mappings.json <<EOF
{
  "mappings": [
    {
      "email": "admin@example.com",
      "domains": ["example.com"],
      "awsAccounts": ["123456789012"]
    },
    {
      "email": "user@example.com",
      "domains": ["corp.local"]
    }
  ]
}
EOF
```

## Best Practices

### CSV Best Practices

1. **Use Excel/Google Sheets**: Easiest way to create and edit
2. **Save as UTF-8**: Ensure proper encoding
3. **One Mapping Per Row**: Simpler to understand and debug
4. **Include Header**: Always include `email,type,value` as first line
5. **Quote Special Values**: Use quotes for values with commas or quotes

### JSON Best Practices

1. **Group by User**: Put all mappings for a user in one object
2. **Validate JSON**: Use `jq` or online validator before import
3. **Pretty Print**: Use indentation for readability
4. **Consistent Ordering**: Keep `email` first, then `domains`, then `awsAccounts`
5. **Test with Dry-Run**: Always test with `--dry-run` first

### General Import Best Practices

1. **Start Small**: Test with 10-20 rows before importing thousands
2. **Use Dry-Run**: Always validate first with `--dry-run` flag
3. **Check Duplicates**: Review skipped duplicates (may indicate data quality issues)
4. **Backup Database**: Backup before large imports
5. **Monitor Performance**: Large imports (1000+ rows) may take minutes
6. **Review Errors**: Fix validation errors and re-import failed rows only

## Summary

| Format | Use When | Advantages | File Extension |
|--------|----------|------------|----------------|
| CSV | Creating from Excel/DB exports | Simple, Excel-compatible, line-level errors | .csv |
| JSON | Programmatic generation, API integration | Compact, structured, multiple per user | .json |

Both formats support:
- Partial success mode (continue on errors)
- Duplicate detection and skipping
- Pending mapping creation for non-existent users
- Comprehensive validation with line/mapping-level error reporting
