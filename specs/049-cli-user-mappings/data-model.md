# Data Model: CLI User Mapping Management

**Feature**: 049-cli-user-mappings
**Date**: 2025-11-19

## Entity Overview

This feature extends the existing `UserMapping` entity from Feature 042 with a new `status` field to explicitly track pending vs active mappings.

## Core Entities

### UserMapping (Extended)

**Purpose**: Represents the association between a user (identified by email) and access control identifiers (AD domains, AWS accounts, IP addresses)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`

**Changes Required**:
- Add `status` enum field (PENDING/ACTIVE)
- Update `@PrePersist` to set default status based on user existence

**Schema**:

| Field | Type | Nullable | Default | Description |
|-------|------|----------|---------|-------------|
| id | BIGINT | No | AUTO | Primary key |
| email | VARCHAR(255) | No | - | User email (normalized to lowercase) |
| user_id | BIGINT | Yes | NULL | Foreign key to User table (null = future user) |
| applied_at | TIMESTAMP | Yes | NULL | When mapping was applied to user |
| **status** | VARCHAR(20) | No | **'ACTIVE'** | **NEW: PENDING or ACTIVE** |
| aws_account_id | VARCHAR(12) | Yes | NULL | AWS account (12 digits) |
| domain | VARCHAR(255) | Yes | NULL | AD domain (normalized to lowercase) |
| ip_address | VARCHAR(100) | Yes | NULL | IP address or range |
| ip_range_type | VARCHAR(20) | Yes | NULL | SINGLE/CIDR/DASH_RANGE |
| ip_range_start | BIGINT | Yes | NULL | Numeric IP range start |
| ip_range_end | BIGINT | Yes | NULL | Numeric IP range end |
| created_at | TIMESTAMP | No | NOW() | Creation timestamp |
| updated_at | TIMESTAMP | No | NOW() | Last update timestamp |

**Indexes** (existing + new):
```sql
-- Existing indexes (from Feature 042)
CREATE INDEX idx_user_mapping_email ON user_mapping(email);
CREATE INDEX idx_user_mapping_aws_account ON user_mapping(aws_account_id);
CREATE INDEX idx_user_mapping_domain ON user_mapping(domain);
CREATE INDEX idx_user_mapping_applied_at ON user_mapping(applied_at);

-- NEW index for status filtering
CREATE INDEX idx_user_mapping_status ON user_mapping(status);
CREATE INDEX idx_user_mapping_email_status ON user_mapping(email, status);
```

**Constraints**:
```sql
-- Unique constraint prevents duplicates
CONSTRAINT uk_user_mapping_composite UNIQUE (email, aws_account_id, domain, ip_address);

-- Foreign key to users table
CONSTRAINT fk_user_mapping_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;
```

**Validation Rules**:
- `email`: Must match email regex `^[^@]+@[^@]+\.[^@]+$`
- `awsAccountId`: If provided, must be exactly 12 digits `^\d{12}$`
- `domain`: If provided, must contain only letters, numbers, dots, hyphens `^[a-zA-Z0-9.-]+$`
- At least one of `awsAccountId`, `domain`, or `ipAddress` must be non-null

### MappingStatus (New Enum)

**Purpose**: Explicit status for pending vs active mappings

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/MappingStatus.kt` (new file)

**Values**:

| Value | Description | Usage |
|-------|-------------|-------|
| PENDING | Mapping created for non-existent user | Set when `user == null` at creation time |
| ACTIVE | Mapping applied to existing user | Set when `user != null` or when pending mapping is applied |

**State Transitions**:
```
PENDING → ACTIVE  (when user is created and mapping is applied)
ACTIVE → ACTIVE   (no reverse transition; mappings deleted instead)
```

## Supporting DTOs (CLI-specific)

### BatchMappingResult

**Purpose**: Result object for batch import operations

**Location**: `src/cli/src/main/kotlin/com/secman/cli/model/BatchMappingResult.kt` (new file)

**Fields**:

| Field | Type | Description |
|-------|------|-------------|
| totalProcessed | Int | Total lines/entries processed |
| created | Int | Successfully created mappings |
| skipped | Int | Skipped (duplicates) |
| errors | List\<String\> | Error messages with line numbers |
| warnings | List\<String\> | Warnings (e.g., future user mappings) |

**Example**:
```kotlin
data class BatchMappingResult(
    val totalProcessed: Int,
    val created: Int,
    val skipped: Int,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
    val successRate: Double get() = if (totalProcessed > 0) created.toDouble() / totalProcessed else 0.0
}
```

### MappingOperation (Result Enum)

**Purpose**: Result type for individual mapping operations

**Location**: `src/cli/src/main/kotlin/com/secman/cli/model/MappingOperation.kt` (new file)

**Values**:

| Value | Description |
|-------|-------------|
| CREATED | New mapping created successfully |
| SKIPPED_DUPLICATE | Mapping already exists |
| SKIPPED_INVALID | Validation error |
| DELETED | Mapping removed |

## Database Migration

### Hibernate Auto-Migration

**DDL Changes** (Hibernate will generate):
```sql
-- Add status column with default value
ALTER TABLE user_mapping
ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

-- Create index for status queries
CREATE INDEX idx_user_mapping_status ON user_mapping(status);
CREATE INDEX idx_user_mapping_email_status ON user_mapping(email, status);
```

**Data Migration Strategy**:
1. **Existing Records**: Default to 'ACTIVE' (already applied to users)
2. **New Records**:
   - Set 'PENDING' if `user_id IS NULL`
   - Set 'ACTIVE' if `user_id IS NOT NULL`

### Rollback Plan

If migration fails or needs rollback:
```sql
-- Remove status column
ALTER TABLE user_mapping DROP COLUMN status;

-- Remove indexes
DROP INDEX idx_user_mapping_status ON user_mapping;
DROP INDEX idx_user_mapping_email_status ON user_mapping;
```

## Entity Relationships

```
User (existing)
  └─< UserMapping (1:many, optional)
        ├── email: String (required, indexed)
        ├── user: User? (nullable FK)
        ├── status: MappingStatus (PENDING/ACTIVE)
        ├── awsAccountId: String? (optional)
        ├── domain: String? (optional)
        └── ipAddress: String? (optional)
```

**Access Patterns**:

1. **Find pending mappings for email**:
   ```sql
   SELECT * FROM user_mapping
   WHERE email = ? AND status = 'PENDING'
   ```

2. **Find active mappings for user**:
   ```sql
   SELECT * FROM user_mapping
   WHERE email = ? AND status = 'ACTIVE'
   ```

3. **Apply pending mappings on user creation**:
   ```sql
   UPDATE user_mapping
   SET user_id = ?, status = 'ACTIVE', applied_at = NOW()
   WHERE email = ? AND status = 'PENDING'
   ```

4. **Check for duplicate**:
   ```sql
   SELECT COUNT(*) FROM user_mapping
   WHERE email = ?
     AND aws_account_id = ?
     AND domain = ?
   ```

## Lifecycle Management

### Creation

1. **CLI receives command** (add-domain/add-aws)
2. **Validate input** (email format, AWS account ID format)
3. **Check for duplicate** using repository method
4. **Determine status**:
   - Check if user exists in database by email
   - If exists: `status = ACTIVE`, set `user` FK
   - If not exists: `status = PENDING`, `user = null`
5. **Save to database** with audit log

### Activation (Pending → Active)

1. **User created** (via web UI, API, or admin action)
2. **UserCreatedEvent published** by UserService
3. **UserMappingApplicationService listener**:
   - Query: `findByEmailAndStatus(email, PENDING)`
   - Update each mapping: `status = ACTIVE`, `user = newUser`, `appliedAt = NOW()`
4. **Audit log** records activation

### Deletion

1. **CLI receives remove command**
2. **Find matching mappings** by email/domain/aws-account
3. **Delete from database** (soft delete not required)
4. **Audit log** records deletion

## Performance Considerations

### Query Optimization

**Indexes for CLI Operations**:
- `idx_user_mapping_email_status`: Fast filtering for list command
- `idx_user_mapping_email`: Fast duplicate detection
- `idx_user_mapping_domain`: Fast removal by domain
- `idx_user_mapping_aws_account`: Fast removal by AWS account

**Expected Query Performance**:
- List mappings for email: O(log n) with index scan
- Check duplicate: O(log n) with unique constraint index
- Bulk import 100 records: < 5 seconds (per FR-003)

### Batch Insert Optimization

For batch imports, use JPA batch insert:
```kotlin
@Transactional
fun importBatch(mappings: List<UserMapping>) {
    mappings.chunked(50).forEach { chunk ->
        userMappingRepository.saveAll(chunk)
        entityManager.flush()
        entityManager.clear()
    }
}
```

## Security Considerations

### Data Protection

- **Email normalization**: Always lowercase before save (prevents case-sensitivity issues)
- **Domain normalization**: Lowercase per DNS standards
- **No sensitive data**: Emails are not considered sensitive in this context (organizational identifiers)
- **Audit trail**: All changes logged with actor identity

### Access Control

- **CLI operations**: ADMIN role only (enforced in command layer)
- **Database constraints**: Unique constraint prevents duplicates
- **Foreign key**: `ON DELETE SET NULL` prevents orphaned mappings if user deleted

## Validation Summary

| Field | Validation | Enforced By |
|-------|------------|-------------|
| email | Email format | Jakarta @Email + regex |
| awsAccountId | 12 digits | Jakarta @Pattern + regex |
| domain | Alphanumeric + dots/hyphens | Jakarta @Pattern + regex |
| status | PENDING or ACTIVE | Enum constraint |
| At least one value | awsAccountId OR domain OR ipAddress | Service layer check |

## Summary

This data model extends the existing `UserMapping` entity with:
1. **New `status` field** for explicit PENDING/ACTIVE tracking
2. **Database indexes** for CLI query performance
3. **DTOs for CLI operations** (BatchMappingResult, MappingOperation)
4. **Clear lifecycle management** (create → pending → active → delete)
5. **Event-driven activation** via UserCreatedEvent listener

All changes are backward-compatible with existing Feature 042 functionality.
