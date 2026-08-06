# Data Model: CSV-Based User Mapping Upload

**Feature**: 016-i-want-to
**Phase**: 1 - Design & Contracts
**Date**: 2025-10-13

## Overview

This feature reuses the existing `UserMapping` entity from Feature 013 without any schema changes. The CSV upload functionality creates UserMapping records using the same validation and persistence logic as the Excel upload, ensuring data consistency across upload formats.

## Entity: UserMapping (Existing - No Changes)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`

**Purpose**: Store many-to-many relationships between user emails, AWS account IDs, and domains for role-based access control and multi-tenant workgroup management.

### Schema

```kotlin
@Entity
@Table(
    name = "user_mapping",
    indexes = [
        Index(name = "idx_user_mapping_email", columnList = "email"),
        Index(name = "idx_user_mapping_aws_account", columnList = "aws_account_id"),
        Index(name = "idx_user_mapping_domain", columnList = "domain"),
        Index(name = "idx_user_mapping_email_aws", columnList = "email,aws_account_id")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_mapping_composite",
            columnNames = ["email", "aws_account_id", "domain"]
        )
    ]
)
data class UserMapping(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 255)
    val email: String,

    @Column(name = "aws_account_id", nullable = false, length = 12)
    val awsAccountId: String,

    @Column(nullable = false, length = 100)
    val domain: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
```

### Field Specifications

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | Long | PK, Auto-increment | Unique identifier |
| `email` | String(255) | NOT NULL, Indexed | User email (normalized lowercase) |
| `awsAccountId` | String(12) | NOT NULL, Indexed | AWS account ID (12 digits) |
| `domain` | String(100) | NOT NULL, Indexed | Organization domain or "-NONE-" |
| `createdAt` | LocalDateTime | NOT NULL, Immutable | Record creation timestamp |
| `updatedAt` | LocalDateTime | NOT NULL, Auto-updated | Last modification timestamp |

### Indexes

1. **idx_user_mapping_email**: Single-column index on `email` for user lookup
2. **idx_user_mapping_aws_account**: Single-column index on `aws_account_id` for account lookup
3. **idx_user_mapping_domain**: Single-column index on `domain` for domain filtering
4. **idx_user_mapping_email_aws**: Composite index on `(email, aws_account_id)` for duplicate detection

### Unique Constraint

**uk_user_mapping_composite**: Composite unique constraint on `(email, aws_account_id, domain)` prevents duplicate mappings.

### Validation Rules

1. **Email**:
   - Must contain `@` character
   - Stored as lowercase (case-insensitive matching)
   - Trimmed of leading/trailing whitespace
   - Pattern: `^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`

2. **AWS Account ID**:
   - Exactly 12 digits
   - Numeric characters only
   - No leading/trailing whitespace
   - Pattern: `^\d{12}$`

3. **Domain**:
   - Alphanumeric characters, dots, hyphens only
   - Stored as lowercase
   - Special sentinel value: `"-NONE-"` for CSV uploads without domain column
   - Trimmed of leading/trailing whitespace
   - Pattern: `^[a-zA-Z0-9.-]+$` OR `^-NONE-$`

---

## CSV Upload Data Flow

### Step 1: File Upload & Validation

**Input**: Multipart form data with `csvFile` (max 10MB, .csv extension)

**Validation**:
- File size ≤ 10MB
- File extension = `.csv`
- Content-type = `text/csv` or `application/csv`
- Non-empty file (> 0 bytes)

**Output**: InputStream or File object for parsing

**Error Codes**:
- 413 Payload Too Large (file > 10MB)
- 400 Bad Request (invalid format, empty file)

---

### Step 2: Encoding Detection & CSV Parsing

**Input**: CSV file InputStream

**Process**:
1. Detect encoding (UTF-8 BOM or default UTF-8 with ISO-8859-1 fallback)
2. Read first line for delimiter detection (comma, semicolon, tab)
3. Parse CSV using Apache Commons CSV with detected settings
4. Validate required headers present: `account_id`, `owner_email` (case-insensitive)

**Configuration**:
```kotlin
CSVFormat.RFC4180.builder()
    .setDelimiter(detectedDelimiter)
    .setHeader()
    .setSkipHeaderRecord(true)
    .setIgnoreEmptyLines(true)
    .setTrim(true)
    .build()
```

**Output**: Iterable<CSVRecord> with header mapping

**Error Handling**:
- Missing required headers → 400 Bad Request with list of missing columns
- Parsing errors → 400 Bad Request with line number and error message

---

### Step 3: Row Processing & Validation

**Input**: CSVRecord iterable

**For Each Row**:

1. **Extract Fields**:
   ```kotlin
   val accountId = record.get("account_id") ?: record.get("Account_ID") ?: record.get("ACCOUNT_ID")
   val ownerEmail = record.get("owner_email") ?: record.get("Owner_Email") ?: record.get("OWNER_EMAIL")
   val domain = record.get("domain")?.takeIf { it.isNotBlank() } ?: "-NONE-"
   ```

2. **Parse Account ID** (handle scientific notation):
   ```kotlin
   val parsedAccountId = parseAccountId(accountId) // BigDecimal → 12-digit string
   ```

3. **Validate Fields**:
   - Email format (contains @, valid pattern)
   - Account ID (exactly 12 digits after parsing)
   - Domain format (alphanumeric + dots + hyphens, or "-NONE-")

4. **Normalize**:
   - Email → lowercase, trimmed
   - Account ID → trimmed
   - Domain → lowercase, trimmed

5. **Check for Duplicates**:
   ```kotlin
   val exists = userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
       email = normalizedEmail,
       awsAccountId = parsedAccountId,
       domain = normalizedDomain
   )
   ```

6. **Create or Skip**:
   - If valid and not duplicate → Create UserMapping
   - If invalid or duplicate → Add to skipped list with reason

**Output**:
- `importedMappings: List<UserMapping>`
- `skippedRows: List<SkippedRow>` where `SkippedRow(lineNumber, reason, values)`

---

### Step 4: Persistence

**Input**: List of validated UserMapping entities

**Process**:
```kotlin
val savedMappings = userMappingRepository.saveAll(importedMappings)
```

**Transaction**: All inserts within single transaction (rollback on failure)

**Output**: Persisted UserMapping records with generated IDs

---

### Step 5: Response Assembly

**Input**: Import statistics from processing

**Response Structure**:
```kotlin
data class ImportResult(
    val message: String,
    val imported: Int,
    val skipped: Int,
    val errors: List<ImportError>
)

data class ImportError(
    val line: Int,
    val field: String?,
    val reason: String,
    val value: String?
)
```

**Example Success Response** (200 OK):
```json
{
  "message": "Successfully imported 8 user mappings",
  "imported": 8,
  "skipped": 2,
  "errors": [
    {
      "line": 3,
      "field": "owner_email",
      "reason": "Invalid email format",
      "value": "not-an-email"
    },
    {
      "line": 5,
      "field": "account_id",
      "reason": "Must be exactly 12 digits",
      "value": "12345"
    }
  ]
}
```

**Example Error Response** (400 Bad Request):
```json
{
  "message": "CSV parsing failed",
  "imported": 0,
  "skipped": 0,
  "errors": [
    {
      "line": 1,
      "field": null,
      "reason": "Missing required header: owner_email",
      "value": null
    }
  ]
}
```

---

## CSV Format Specification

### Required Headers (Case-Insensitive)

1. `account_id` - AWS account identifier
2. `owner_email` - User email address

### Optional Headers

1. `domain` - Organization domain (defaults to "-NONE-" if omitted)

### Additional Columns

All other columns are ignored (CSV may contain extra metadata columns).

### Example Valid CSV

```csv
account_id,owner_email,domain
000487141098,markus.schmall@covestro.com,covestro.com
9.98987E+11,test1.test1@covestro.com,covestro.com
123456789012,admin@example.com,-NONE-
```

### Example Valid CSV (No Domain Column)

```csv
account_id,owner_email
000487141098,markus.schmall@covestro.com
9.98987E+11,test1.test1@covestro.com
123456789012,admin@example.com
```

All mappings will have `domain = "-NONE-"`.

---

## Service Layer Design

### New Service: CSVUserMappingParser

**Location**: `src/backendng/src/main/kotlin/com/secman/service/CSVUserMappingParser.kt`

**Responsibility**: Parse CSV files and extract UserMapping data with validation

**Interface**:
```kotlin
@Singleton
class CSVUserMappingParser(
    private val userMappingRepository: UserMappingRepository
) {
    fun parse(file: File): ImportResult
    private fun detectDelimiter(firstLine: String): Char
    private fun parseAccountId(value: String): String?
    private fun validateEmail(email: String): Boolean
    private fun validateDomain(domain: String): Boolean
}
```

**Dependencies**:
- Apache Commons CSV (org.apache.commons:commons-csv:1.11.0)
- UserMappingRepository (existing)

---

## Repository Layer (Existing - No Changes)

**Location**: `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt`

**Used Methods**:
- `existsByEmailAndAwsAccountIdAndDomain(email: String, awsAccountId: String, domain: String): Boolean`
- `saveAll(entities: Iterable<UserMapping>): List<UserMapping>`

---

## Database Schema Changes

**None** - This feature uses the existing `user_mapping` table from Feature 013 without modifications.

---

## Data Migration

**Not Required** - No schema changes, no data transformation needed.

---

## Testing Strategy

### Unit Tests (CSVUserMappingParserTest.kt)

1. **Delimiter Detection**:
   - Comma-separated CSV
   - Semicolon-separated CSV
   - Tab-separated CSV
   - Single-column CSV (default to comma)

2. **Scientific Notation Parsing**:
   - `"9.98987E+11"` → `"998987000000"`
   - `"1.23456789012E+11"` → `"123456789012"`
   - `"123456789012"` → `"123456789012"` (pass-through)
   - Invalid values (non-numeric, wrong length)

3. **Encoding Detection**:
   - UTF-8 with BOM
   - UTF-8 without BOM
   - ISO-8859-1
   - Invalid encoding (error handling)

4. **Validation**:
   - Valid email/account/domain combinations
   - Invalid email formats
   - Invalid account ID formats (non-numeric, wrong length)
   - Invalid domain formats
   - Empty/null values

5. **Duplicate Detection**:
   - Duplicate within CSV file
   - Duplicate with existing database record
   - Case-insensitive email matching

### Integration Tests

1. **End-to-End CSV Upload**:
   - Upload valid CSV → verify 200 OK, correct import counts
   - Upload CSV with errors → verify 200 OK, correct skip counts
   - Upload invalid file → verify 400 Bad Request

2. **Repository Integration**:
   - Verify UserMapping records created correctly
   - Verify duplicate detection via unique constraint
   - Verify indexes used for lookups

### Contract Tests (CSVUploadContractTest.kt)

1. OpenAPI contract validation
2. Request/response schema validation
3. Error response formats
4. Authentication/authorization enforcement

---

## Performance Considerations

### Expected Performance

- **1000 rows**: < 10 seconds (same as Excel upload)
- **Memory**: Streaming parser, ~50MB max for 10MB CSV
- **Database**: Batch inserts via `saveAll()`, minimal query overhead

### Optimization Strategies

1. **Streaming**: Apache Commons CSV reads line-by-line (no full-file loading)
2. **Batch Processing**: Collect all valid rows, single `saveAll()` call
3. **Index Usage**: Duplicate checks use composite index `(email, aws_account_id)`
4. **Connection Pooling**: Hibernate manages DB connections

### Bottlenecks

- Database unique constraint checks (mitigated by batch insert)
- File I/O (mitigated by streaming)
- Scientific notation parsing (minimal overhead)

---

## Security Considerations

### Access Control

- Endpoint: `@Secured("ADMIN")` (only ADMIN role can upload)
- Repository: No additional access control (UserMapping is ADMIN-only entity)

### Input Validation

- File size limit: 10MB (prevents DoS)
- File extension validation: `.csv` only
- Header validation: Required headers must be present
- Field validation: Email, account ID, domain patterns enforced
- No SQL injection risk (JPA parameterized queries)

### Data Sanitization

- Email normalized to lowercase
- Whitespace trimmed from all fields
- Domain normalized to lowercase
- No HTML/script content (plain text CSV)

### Error Message Safety

- Error messages do not expose system internals
- Invalid row data included in response (admin-only, safe to expose)
- No stack traces in production responses

---

## Monitoring & Logging

### Log Events

1. **INFO**: CSV upload started (user, file size, row count)
2. **INFO**: CSV upload completed (imported count, skipped count, duration)
3. **WARN**: Invalid rows skipped (line number, reason)
4. **ERROR**: CSV parsing failure (exception, file details)
5. **ERROR**: Database constraint violation (duplicate detection failure)

### Metrics

- Upload count (success/failure)
- Average processing time
- Average rows per upload
- Skip rate (skipped/total rows)

---

## Data Model Summary

**Entities**: 1 (existing UserMapping, no changes)
**Tables**: 1 (existing user_mapping, no schema changes)
**Indexes**: 4 (existing, no changes)
**Unique Constraints**: 1 (existing composite constraint)
**New Services**: 1 (CSVUserMappingParser)
**New Controllers**: 0 (extend existing ImportController)
**Database Migrations**: 0 (no schema changes)

**Phase 1 Data Model Status**: ✅ COMPLETE
