# User Mapping Schema Fix

## Issue

The `user_mapping` table was created with `aws_account_id` as `NOT NULL`, but Feature 017 requires that **either** `aws_account_id` OR `domain` can be null (but not both).

**Business Rule**: At least one of `aws_account_id` or `domain` must be provided.

## Error Message

```
org.hibernate.exception.ConstraintViolationException: could not execute statement 
[Conn=1408) Column 'aws_account_id' cannot be null]
```

## Root Cause

The original `user_mapping` table schema (Feature 013) was designed for CSV uploads where both fields were always provided. Feature 017 extends this to allow more flexible mappings:
- AWS Account ID only (domain = NULL)
- Domain only (aws_account_id = NULL)
- Both fields provided

## Solution

Execute the migration script to make both columns nullable:

### Option 1: Using Docker (Recommended)

```bash
cd /Users/flake/sources/misc/secman
./scripts/fix-user-mapping-schema.sh
```

### Option 2: Using MySQL CLI Directly

```bash
mysql -u root -proot secman_dev < scripts/fix-user-mapping-nullable-columns.sql
```

### Option 3: Manual SQL Commands

```sql
USE secman_dev;

ALTER TABLE user_mapping 
MODIFY COLUMN aws_account_id VARCHAR(12) NULL;

ALTER TABLE user_mapping 
MODIFY COLUMN domain VARCHAR(255) NULL;

-- Verify
DESCRIBE user_mapping;
```

## Migration Script Details

**Location**: `scripts/fix-user-mapping-nullable-columns.sql`

**Changes**:
1. `aws_account_id` column: `VARCHAR(12) NOT NULL` → `VARCHAR(12) NULL`
2. `domain` column: `VARCHAR(255) NOT NULL` → `VARCHAR(255) NULL`

**Important**: The `email` column remains `NOT NULL` as it is always required.

## Validation After Migration

### Expected Schema

```
+----------------+--------------+------+-----+---------+----------------+
| Field          | Type         | Null | Key | Default | Extra          |
+----------------+--------------+------+-----+---------+----------------+
| id             | bigint(20)   | NO   | PRI | NULL    | auto_increment |
| email          | varchar(255) | NO   | MUL | NULL    |                |
| aws_account_id | varchar(12)  | YES  | MUL | NULL    |                |  ← Should be YES
| domain         | varchar(255) | YES  | MUL | NULL    |                |  ← Should be YES
| created_at     | datetime(6)  | YES  |     | NULL    |                |
| updated_at     | datetime(6)  | YES  |     | NULL    |                |
+----------------+--------------+------+-----+---------+----------------+
```

### Test Cases

After running the migration, these operations should succeed:

```sql
-- Test 1: AWS Account ID only
INSERT INTO user_mapping (email, aws_account_id, domain, created_at, updated_at)
VALUES ('test1@example.com', '123456789012', NULL, NOW(), NOW());

-- Test 2: Domain only
INSERT INTO user_mapping (email, aws_account_id, domain, created_at, updated_at)
VALUES ('test2@example.com', NULL, 'example.com', NOW(), NOW());

-- Test 3: Both fields
INSERT INTO user_mapping (email, aws_account_id, domain, created_at, updated_at)
VALUES ('test3@example.com', '123456789012', 'example.com', NOW(), NOW());

-- Verify
SELECT * FROM user_mapping WHERE email LIKE 'test%';

-- Cleanup
DELETE FROM user_mapping WHERE email LIKE 'test%';
```

## Application-Level Validation

The service layer (`UserMappingService.kt`) enforces the business rule:

```kotlin
// This validation ensures at least one field is provided
if (request.awsAccountId == null && request.domain == null) {
    throw IllegalArgumentException("At least one of Domain or AWS Account ID must be provided")
}
```

## Rollback (If Needed)

If you need to revert the migration (not recommended):

```sql
USE secman_dev;

-- CAUTION: This will fail if any rows have NULL values
ALTER TABLE user_mapping 
MODIFY COLUMN aws_account_id VARCHAR(12) NOT NULL;

ALTER TABLE user_mapping 
MODIFY COLUMN domain VARCHAR(255) NOT NULL;
```

## Impact Analysis

### Affected Features
- **Feature 013**: User Mapping Upload (backward compatible - existing data unchanged)
- **Feature 017**: User Mapping Management (requires this fix to work)

### Backward Compatibility
✅ **YES** - Existing data is not affected. All existing rows have both fields populated.

### Data Migration Required
❌ **NO** - No existing data needs to be modified.

## Troubleshooting

### Error: "Table doesn't exist"
Check that you're connected to the correct database:
```bash
docker exec -it $(docker ps -qf "name=secman.*database") mysql -uroot -proot -e "SHOW DATABASES;"
```

### Error: "Access denied"
Verify database credentials in `docker-compose.yml` or `.env` file.

### Error: "Can't connect to MySQL server"
Start the database:
```bash
cd /Users/flake/sources/misc/secman
docker-compose up -d database
```

## Related Files

- Entity: `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`
- Service: `src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt`
- Migration: `scripts/fix-user-mapping-nullable-columns.sql`
- Script: `scripts/fix-user-mapping-schema.sh`

## Status

- [x] Migration script created
- [ ] Migration executed (run manually)
- [ ] Schema validated
- [ ] Application tested

## Next Steps

1. Execute the migration using one of the methods above
2. Restart the backend application
3. Test creating mappings with:
   - AWS Account ID only
   - Domain only
   - Both fields
4. Verify error handling for empty requests (both fields null)
