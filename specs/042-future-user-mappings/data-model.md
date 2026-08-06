# Data Model: Future User Mapping Support

**Feature**: 042-future-user-mappings | **Date**: 2025-11-07

## Overview

This document describes the data model changes required to support future user mappings. The primary change is extending the existing `UserMapping` entity to support entries without a corresponding user (future users) and tracking when mappings are applied.

## Entity Changes

### UserMapping (MODIFIED)

**Purpose**: Represents the association between a user email and their AWS account ID/domain. Extended to support future users who don't yet exist in the system.

**Table**: `user_mapping`

#### Fields

| Field | Type | Constraints | Description | Change |
|-------|------|-------------|-------------|--------|
| id | BIGINT | PRIMARY KEY, AUTO_INCREMENT | Unique identifier | Existing |
| email | VARCHAR(255) | UNIQUE, NOT NULL | User email address (case-insensitive matching) | **Modified**: Add UNIQUE constraint |
| user_id | BIGINT | NULLABLE, FK to users(id) | Reference to User entity | **Modified**: Changed from NOT NULL to NULLABLE |
| aws_account_id | VARCHAR(12) | NULLABLE | 12-digit AWS account ID | Existing |
| domain | VARCHAR(255) | NULLABLE | Active Directory domain | Existing |
| applied_at | TIMESTAMP | NULLABLE | **NEW**: Timestamp when mapping was applied to user (NULL = not yet applied) | **NEW FIELD** |
| created_at | TIMESTAMP | NOT NULL | Creation timestamp | Existing |
| updated_at | TIMESTAMP | NOT NULL | Last update timestamp | Existing |

#### Indexes

| Index Name | Columns | Type | Purpose | Change |
|------------|---------|------|---------|--------|
| idx_email | email | UNIQUE | Fast lookup by email, enforce uniqueness | **Modified**: Add if not exists |
| idx_applied_at | applied_at | NON-UNIQUE | **NEW**: Efficient filtering for Current vs Applied History tabs | **NEW INDEX** |
| idx_user_id | user_id | NON-UNIQUE | Fast lookup by user | Existing |

#### Constraints

- **Email Uniqueness**: Email must be unique across ALL mappings (future and active) per clarification Q1
- **Foreign Key**: user_id → users(id) with ON DELETE SET NULL (optional relationship)
- **Validation**:
  - Email format validation (application layer)
  - AWS Account ID: 12 digits (application layer)
  - Domain format validation (application layer)
  - At least one of aws_account_id or domain must be non-null (application layer)

#### State Lifecycle

```text
┌─────────────────┐
│ Future Mapping  │  user_id = NULL
│ appliedAt = NULL│  applied_at = NULL
└────────┬────────┘
         │
         │ User Created
         │ (Manual or OAuth)
         ▼
┌─────────────────┐
│ Active Mapping  │  user_id = <user_id>
│ appliedAt = NOW │  applied_at = <timestamp>
└─────────────────┘
         │
         │ (Retained for
         │  audit history)
         ▼
┌─────────────────┐
│ Historical      │  Never deleted
│ Record          │  (audit trail)
└─────────────────┘
```

**State Indicators**:
- **Future User Mapping**: `user_id IS NULL AND applied_at IS NULL`
- **Active User Mapping**: `user_id IS NOT NULL AND applied_at IS NULL` (mapping existed before feature, or manually created for existing user)
- **Applied Historical Mapping**: `applied_at IS NOT NULL` (was future mapping, now applied)

#### Kotlin Entity

```kotlin
package com.secman.domain

import io.micronaut.data.annotation.DateCreated
import io.micronaut.data.annotation.DateUpdated
import java.time.Instant
import javax.persistence.*

@Entity
@Table(
    name = "user_mapping",
    indexes = [
        Index(name = "idx_email", columnList = "email", unique = true),
        Index(name = "idx_applied_at", columnList = "applied_at")
    ]
)
data class UserMapping(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(unique = true, nullable = false, length = 255)
    val email: String,

    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    val user: User? = null,

    @Column(name = "aws_account_id", nullable = true, length = 12)
    val awsAccountId: String? = null,

    @Column(name = "domain", nullable = true, length = 255)
    val domain: String? = null,

    @Column(name = "applied_at", nullable = true)
    val appliedAt: Instant? = null,

    @DateCreated
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant? = null,

    @DateUpdated
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant? = null
) {
    /**
     * Returns true if this is a future user mapping (not yet applied)
     */
    fun isFutureMapping(): Boolean = appliedAt == null && user == null

    /**
     * Returns true if this is an applied historical mapping
     */
    fun isAppliedMapping(): Boolean = appliedAt != null
}
```

### User (NO CHANGES)

**Purpose**: Existing entity representing system users. No structural changes required.

**Note**: User creation process must trigger mapping lookup and application via event listener pattern.

## Repository Changes

### UserMappingRepository (MODIFIED)

**Purpose**: Data access layer for UserMapping entity

```kotlin
package com.secman.repository

import com.secman.domain.UserMapping
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable

@Repository
interface UserMappingRepository : JpaRepository<UserMapping, Long> {
    // Existing methods
    fun findByEmail(email: String): UserMapping?

    // NEW: Case-insensitive email lookup
    fun findByEmailIgnoreCase(email: String): UserMapping?

    // NEW: Find future user mappings (Current Mappings tab - not yet applied)
    fun findByAppliedAtIsNull(pageable: Pageable): Page<UserMapping>

    // NEW: Find applied historical mappings (Applied History tab)
    fun findByAppliedAtIsNotNull(pageable: Pageable): Page<UserMapping>

    // NEW: Count future user mappings
    fun countByAppliedAtIsNull(): Long

    // NEW: Count applied historical mappings
    fun countByAppliedAtIsNotNull(): Long
}
```

## Data Migration Strategy

### Schema Evolution Steps

1. **Add `applied_at` column** (nullable, default NULL)
2. **Modify `user_id` column** to allow NULL
3. **Add UNIQUE constraint** on `email` if not exists
4. **Add index** on `applied_at`

### Hibernate Auto-Migration

Hibernate will automatically detect entity changes and execute DDL statements:

```sql
-- Step 1: Add applied_at column
ALTER TABLE user_mapping ADD COLUMN applied_at TIMESTAMP NULL;

-- Step 2: Modify user_id to nullable (if currently NOT NULL)
ALTER TABLE user_mapping MODIFY COLUMN user_id BIGINT NULL;

-- Step 3: Add unique constraint on email (if not exists)
ALTER TABLE user_mapping ADD CONSTRAINT uk_user_mapping_email UNIQUE (email);

-- Step 4: Add index on applied_at
CREATE INDEX idx_applied_at ON user_mapping(applied_at);
```

### Existing Data Handling

- **Existing mappings**: All current user_mapping records will have `applied_at = NULL`
- **No data loss**: Purely additive changes
- **Backward compatibility**: Existing queries continue to work (user_id still populated for existing mappings)

## Query Patterns

### Find Future User Mapping for Email

```kotlin
val mapping = userMappingRepository.findByEmailIgnoreCase(email.trim().lowercase())
if (mapping?.isFutureMapping() == true) {
    // This is a future user mapping
}
```

### Apply Future User Mapping on User Creation

```kotlin
fun applyFutureUserMapping(user: User): UserMapping? {
    val mapping = userMappingRepository.findByEmailIgnoreCase(user.email)
    if (mapping != null && mapping.appliedAt == null) {
        val updated = mapping.copy(
            user = user,
            appliedAt = Instant.now()
        )
        return userMappingRepository.save(updated)
    }
    return null
}
```

### Fetch Current Mappings (Tab View)

```kotlin
// Future user mappings (user_id NULL, applied_at NULL) +
// Active mappings (user_id NOT NULL, applied_at NULL)
val currentMappings = userMappingRepository.findByAppliedAtIsNull(
    Pageable.from(page, pageSize)
)
```

### Fetch Applied History (Tab View)

```kotlin
// Mappings with applied_at NOT NULL
val appliedHistory = userMappingRepository.findByAppliedAtIsNotNull(
    Pageable.from(page, pageSize).order("applied_at", Sort.Order.Direction.DESC)
)
```

## Validation Rules

### Email Validation

- Format: Standard email regex pattern (existing validation from Feature 013/016)
- Case handling: Normalized to lowercase before storage/comparison
- Uniqueness: Enforced at database level (unique constraint)

### AWS Account ID Validation

- Length: Exactly 12 digits
- Format: Numeric only (existing validation from Feature 013/016)
- Scientific notation: Parsed correctly (existing behavior from Feature 013/016)

### Domain Validation

- Format: Valid domain name pattern (existing validation from Feature 013/016)
- Case handling: Case-insensitive matching in unified access control

### Business Rules

- At least one of `aws_account_id` or `domain` must be non-null
- Email uniqueness across ALL mappings (future and active)
- `applied_at` can only be set once (immutable after application)

## Performance Considerations

### Index Strategy

- **Email index**: Unique index supports fast lookups for mapping application during user creation (<2 seconds per NFR-001)
- **Applied_at index**: Enables efficient filtering for tab views (Current vs History)
- **User_id index**: Existing index for user-based queries

### Expected Query Performance

- Email lookup: O(log n) via unique index
- Tab filtering: O(log n) via applied_at index
- Pagination: O(1) offset/limit with indexes

### Scale Targets

- Support 10,000+ future user mappings without degradation (NFR-002)
- <1KB per mapping storage (NFR-003)
- <2 seconds mapping application during user creation (NFR-001)

## Summary of Changes

| Component | Change Type | Description |
|-----------|-------------|-------------|
| UserMapping entity | MODIFIED | Add appliedAt field, make user nullable |
| user_mapping table | SCHEMA CHANGE | Add applied_at column, modify user_id to nullable, add unique constraint on email |
| UserMappingRepository | MODIFIED | Add query methods for future users and applied history |
| Database indexes | NEW | Add index on applied_at |
| User entity | NO CHANGE | No structural changes |

**Migration Risk**: LOW - Purely additive schema changes, no data loss, backward compatible with existing queries.
