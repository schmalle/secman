# Data Model: User Mapping with AWS Account & Domain

**Feature**: 013-user-mapping-upload
**Created**: 2025-10-07
**Status**: Draft

---

## Entity: UserMapping

### Purpose
Store many-to-many mappings between user email addresses, AWS account IDs, and organizational domains to support future role-based access control in multi-tenant environments.

### Business Context
- Users may have access to multiple AWS accounts (consultants, cross-functional teams)
- Users may operate across multiple organizational domains (multi-tenant scenarios)
- Mappings may be created before user onboarding (pre-provisioning)
- Mappings serve as configuration data for future RBAC features

---

## Schema Definition

### Table: `user_mapping`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGINT | PRIMARY KEY, AUTO_INCREMENT | Internal identifier |
| `email` | VARCHAR(255) | NOT NULL, INDEX | User email address (normalized lowercase) |
| `aws_account_id` | VARCHAR(12) | NOT NULL, INDEX | AWS account identifier (12-digit string) |
| `domain` | VARCHAR(255) | NOT NULL, INDEX | Organizational domain name |
| `created_at` | TIMESTAMP | NOT NULL, DEFAULT CURRENT_TIMESTAMP | Creation timestamp |
| `updated_at` | TIMESTAMP | NOT NULL, DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | Last update timestamp |

### Indexes

```sql
-- Composite unique index to prevent duplicates
UNIQUE INDEX `idx_user_mapping_unique` ON user_mapping (email, aws_account_id, domain);

-- Individual indexes for query performance
INDEX `idx_user_mapping_email` ON user_mapping (email);
INDEX `idx_user_mapping_aws_account` ON user_mapping (aws_account_id);
INDEX `idx_user_mapping_domain` ON user_mapping (domain);

-- Composite index for common lookup pattern
INDEX `idx_user_mapping_email_aws` ON user_mapping (email, aws_account_id);
```

---

## Entity Attributes

### email
- **Type**: String (VARCHAR 255)
- **Required**: Yes
- **Format**: Valid email address (RFC 5322 basic pattern)
- **Normalization**: Stored as lowercase
- **Validation**: Must contain @ symbol, valid domain part
- **Examples**: 
  - Valid: `john.doe@example.com`, `admin@multi-tenant.io`
  - Invalid: `notanemail`, `user@`, `@example.com`

### awsAccountId
- **Type**: String (VARCHAR 12)
- **Required**: Yes
- **Format**: Exactly 12 numeric digits
- **Validation**: Must match pattern `^\d{12}$`
- **Storage**: String (not BIGINT) to preserve leading zeros
- **Examples**:
  - Valid: `123456789012`, `000000000001`
  - Invalid: `12345` (too short), `1234567890123` (too long), `ABC123456789` (non-numeric)

### domain
- **Type**: String (VARCHAR 255)
- **Required**: Yes
- **Format**: Valid domain name format (DNS-like)
- **Validation**: Alphanumeric, dots, hyphens only; cannot start/end with dot or hyphen
- **Normalization**: Stored as lowercase
- **Examples**:
  - Valid: `example.com`, `sub.domain.example.com`, `multi-tenant-app.io`
  - Invalid: `example .com` (space), `example..com` (double dot), `-example.com` (starts with hyphen)

### createdAt
- **Type**: Timestamp
- **Required**: Yes (auto-populated)
- **Default**: Current timestamp
- **Purpose**: Audit trail, troubleshooting, data lineage

### updatedAt
- **Type**: Timestamp
- **Required**: Yes (auto-updated)
- **Default**: Current timestamp
- **Behavior**: Updated automatically on record modification
- **Purpose**: Track last change for audit purposes

---

## Data Integrity Rules

### Uniqueness
- **Constraint**: Unique composite key on (email, awsAccountId, domain)
- **Rationale**: Prevent duplicate mappings
- **Behavior**: Attempting to insert duplicate returns constraint violation (skipped during upload)

### No Foreign Keys
- **Decision**: UserMapping.email does NOT have foreign key to User.email
- **Rationale**: 
  - Mappings may be created before users exist (pre-onboarding)
  - User deletion should not cascade delete mappings (mappings are configuration data)
  - UserMapping is independent reference data
- **Trade-off**: Orphaned mappings possible, but acceptable for configuration data

### Field Validation
- **Email**: Validated at application layer before persistence
- **AWS Account ID**: Validated at application layer (regex + length check)
- **Domain**: Validated at application layer (regex check)
- **Database**: All fields have NOT NULL constraint

---

## Cardinality & Relationships

### Email to AWS Account ID
- **Relationship**: Many-to-Many
- **Example**: `alice@example.com` → [`123456789012`, `987654321098`, `111111111111`]
- **Use Case**: User has access to multiple AWS accounts (dev, staging, prod)

### Email to Domain
- **Relationship**: Many-to-Many
- **Example**: `bob@contractor.com` → [`clientA.com`, `clientB.com`, `clientC.com`]
- **Use Case**: Consultant works with multiple client domains

### AWS Account ID to Domain
- **Relationship**: Many-to-Many (implicitly through email)
- **Example**: AWS account `123456789012` may be used by users from multiple domains
- **Use Case**: Shared AWS account across organizational units

### Overall Model
```
Email ← → UserMapping ← → AWS Account ID
  ↓                            ↓
  ↓                            ↓
  → → → → Domain ← ← ← ← ← ← ←
```
Each UserMapping record represents one edge in this many-to-many-to-many relationship.

---

## Data Lifecycle

### Creation
- **Trigger**: Excel file upload by admin
- **Process**: Row-by-row validation and insertion
- **Idempotency**: Duplicate mappings are skipped (no error)
- **Timestamps**: createdAt and updatedAt set to current time

### Update
- **Phase 1**: Not supported (no update functionality)
- **Future**: Update via new upload (upsert behavior) or manual edit UI

### Deletion
- **Phase 1**: Not supported via UI (manual database operation if needed)
- **Future**: Soft delete with deletedAt timestamp, or hard delete with cascade logic

### Query Patterns
Common access patterns for index optimization:
1. **Get all mappings for user**: `SELECT * FROM user_mapping WHERE email = ?`
2. **Get all users for AWS account**: `SELECT DISTINCT email FROM user_mapping WHERE aws_account_id = ?`
3. **Get all users for domain**: `SELECT DISTINCT email FROM user_mapping WHERE domain = ?`
4. **Check if mapping exists**: `SELECT COUNT(*) FROM user_mapping WHERE email = ? AND aws_account_id = ? AND domain = ?`
5. **Get all AWS accounts for user**: `SELECT aws_account_id FROM user_mapping WHERE email = ?`
6. **Get all domains for user**: `SELECT domain FROM user_mapping WHERE email = ?`

---

## Sample Data

### Example 1: Single User, Multiple AWS Accounts
```
| email               | aws_account_id | domain       | created_at          |
|---------------------|----------------|--------------|---------------------|
| alice@example.com   | 123456789012   | example.com  | 2025-10-07 10:00:00 |
| alice@example.com   | 987654321098   | example.com  | 2025-10-07 10:00:01 |
| alice@example.com   | 111111111111   | example.com  | 2025-10-07 10:00:02 |
```

### Example 2: Single User, Multiple Domains
```
| email                  | aws_account_id | domain         | created_at          |
|------------------------|----------------|----------------|---------------------|
| consultant@agency.com  | 555555555555   | clientA.com    | 2025-10-07 11:00:00 |
| consultant@agency.com  | 666666666666   | clientB.com    | 2025-10-07 11:00:01 |
| consultant@agency.com  | 777777777777   | clientC.com    | 2025-10-07 11:00:02 |
```

### Example 3: Cross-Tenant Access
```
| email               | aws_account_id | domain       | created_at          |
|---------------------|----------------|--------------|---------------------|
| admin@corp.com      | 123456789012   | corp.com     | 2025-10-07 12:00:00 |
| admin@corp.com      | 123456789012   | subsidiary.com | 2025-10-07 12:00:01 |
| admin@corp.com      | 987654321098   | corp.com     | 2025-10-07 12:00:02 |
| admin@corp.com      | 987654321098   | subsidiary.com | 2025-10-07 12:00:03 |
```
This represents admin having access to 2 AWS accounts across 2 domains = 4 mapping records.

---

## Migration Strategy

### Initial Migration (V1)
```sql
CREATE TABLE user_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    aws_account_id VARCHAR(12) NOT NULL,
    domain VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    CONSTRAINT uk_user_mapping_composite UNIQUE (email, aws_account_id, domain)
);

CREATE INDEX idx_user_mapping_email ON user_mapping(email);
CREATE INDEX idx_user_mapping_aws_account ON user_mapping(aws_account_id);
CREATE INDEX idx_user_mapping_domain ON user_mapping(domain);
CREATE INDEX idx_user_mapping_email_aws ON user_mapping(email, aws_account_id);
```

### Rollback Plan
- Drop table: `DROP TABLE user_mapping;`
- No foreign key dependencies, so safe to drop
- No impact on other entities

---

## Performance Considerations

### Index Strategy
- **Composite unique index**: Enforces uniqueness efficiently
- **Email index**: Fast user lookup (most common query)
- **AWS account index**: Fast account-to-users reverse lookup
- **Domain index**: Fast domain-to-users reverse lookup
- **Email+AWS composite index**: Optimizes check for user's AWS account access

### Expected Data Volume
- **Typical deployment**: 100-1000 users × 1-5 mappings each = 100-5000 records
- **Large deployment**: 10,000 users × 3 mappings each = 30,000 records
- **Max expected**: 100,000 records (manageable with indexes)

### Query Performance Targets
- User lookup: <10ms (indexed on email)
- Existence check: <5ms (unique composite index)
- Bulk insert: 1000 records in <10 seconds

---

## Security Considerations

### Data Sensitivity
- **Email addresses**: PII, but already stored in User table
- **AWS account IDs**: Sensitive configuration data, not secret but access-controlled
- **Domains**: Low sensitivity, organizational metadata

### Access Control
- **Read**: ADMIN role only (no public or user-level access)
- **Write**: ADMIN role only (upload endpoint)
- **Database**: Standard application-level access (no direct DB access for users)

### Audit Trail
- `created_at` provides basic audit trail
- Future enhancement: Add `created_by` field to track which admin uploaded each mapping

---

## Future Enhancements

### Schema Extensions (Post-Phase 1)
1. **Soft delete**: Add `deleted_at TIMESTAMP NULL` for soft delete capability
2. **Created by**: Add `created_by_user_id BIGINT` FK to User for admin audit trail
3. **Validity period**: Add `valid_from TIMESTAMP, valid_to TIMESTAMP` for time-bound access
4. **Metadata**: Add `metadata JSON` for extensibility (custom attributes per deployment)
5. **Source tracking**: Add `source VARCHAR(50)` to distinguish manual vs. uploaded vs. sync'd mappings

### Optimization (If Needed)
1. **Partitioning**: Partition by email hash if table grows beyond 1M records
2. **Archiving**: Move old/inactive mappings to archive table
3. **Caching**: Cache user's mappings in Redis for high-frequency access patterns

---

## Validation Rules Summary

| Field | Validation | Error Message |
|-------|------------|---------------|
| email | Not empty | "Email address is required" |
| email | Valid format (contains @) | "Invalid email format" |
| email | Max length 255 chars | "Email address too long" |
| awsAccountId | Not empty | "AWS account ID is required" |
| awsAccountId | Exactly 12 digits | "AWS account ID must be exactly 12 numeric digits" |
| awsAccountId | Numeric only | "AWS account ID must contain only digits" |
| domain | Not empty | "Domain is required" |
| domain | Valid format (alphanumeric, dots, hyphens) | "Invalid domain format" |
| domain | Max length 255 chars | "Domain name too long" |
| Composite | Unique (email, awsAccountId, domain) | "Mapping already exists" (skipped during upload) |

---

## Test Data Generation

### Minimal Test Dataset (5 records)
```
john.doe@example.com,123456789012,example.com
jane.smith@example.com,123456789012,example.com
john.doe@example.com,987654321098,example.com
admin@corp.com,111111111111,corp.com
consultant@agency.com,555555555555,clientA.com
```

### Comprehensive Test Dataset (20 records)
- 10 unique emails
- 5 unique AWS accounts
- 3 unique domains
- Mix of 1:1, 1:many, many:1, many:many relationships
- Edge cases: email with +alias, subdomains, hyphenated domains

### Invalid Test Cases
```
# Missing domain
john@example.com,123456789012,

# Invalid email
notanemail,123456789012,example.com

# Invalid AWS account (too short)
john@example.com,12345,example.com

# Invalid AWS account (non-numeric)
john@example.com,ABC123456789,example.com

# Invalid domain (space)
john@example.com,123456789012,example .com

# Duplicate
john@example.com,123456789012,example.com
john@example.com,123456789012,example.com
```

---

## Glossary

- **UserMapping**: Configuration record linking email, AWS account, and domain
- **AWS Account ID**: 12-digit numeric identifier for an Amazon Web Services account
- **Domain**: Organizational domain name (DNS-like format)
- **Composite Key**: Unique constraint on multiple columns together
- **Idempotent**: Operation that produces same result when repeated
- **Pre-provisioning**: Creating configuration before the actual user exists

---

## References

- AWS Account ID format: https://docs.aws.amazon.com/general/latest/gr/acct-identifiers.html
- Email validation: RFC 5322 (simplified subset)
- Domain name format: RFC 1035 (DNS naming conventions)
- Hibernate JPA: https://hibernate.org/orm/documentation/6.4/
- Micronaut Data: https://micronaut-projects.github.io/micronaut-data/latest/guide/

---

## Approval

- [ ] Data Architect: _______________
- [ ] DBA: _______________
- [ ] Security Lead: _______________
- [ ] Date: _______________
