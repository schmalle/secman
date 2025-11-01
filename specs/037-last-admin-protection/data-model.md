# Data Model: Last Admin Protection

**Feature**: 037-last-admin-protection
**Date**: 2025-10-31

## Overview

This feature requires **NO schema changes** - it extends existing User entity validation logic. All data structures already exist in the system.

## Existing Entities (No Changes)

### User (Existing)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

**Attributes**:
- `id`: Long (Primary Key)
- `username`: String (Unique, Not Null)
- `email`: String (Unique, Not Null, Email format)
- `passwordHash`: String (Not Null)
- `roles`: MutableSet<Role> (EAGER fetch, stored in user_roles table)
- `workgroups`: MutableSet<Workgroup> (EAGER fetch, many-to-many)
- `createdAt`: Instant
- `updatedAt`: Instant

**Relevant Methods**:
- `hasRole(role: Role): Boolean` - Check if user has specific role
- `isAdmin(): Boolean` - Check if user has ADMIN role

**Enum: User.Role**:
- USER
- ADMIN ← **Critical for this feature**
- VULN
- RELEASE_MANAGER
- REQ
- RISK
- SECCHAMPION

**Relationships**:
- One user has many roles (stored in `user_roles` table via @ElementCollection)
- Many users to many workgroups (stored in `user_workgroups` table)

**Database Tables** (Existing):
```sql
-- Main user table (already exists)
users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
)

-- User roles junction table (already exists)
user_roles (
    user_id BIGINT NOT NULL,
    role_name VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role_name),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
)
```

## Enhanced Service-Layer Data Structures

### ValidationResult (Extending Existing)

**Location**: `src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt`

**Current Structure** (lines 17-21):
```kotlin
@Serdeable
data class ValidationResult(
    val canDelete: Boolean,
    val blockingReferences: List<BlockingReference>,
    val message: String
)
```

**Enhancement**: Add new blocking reference type for last admin constraint

**New Blocking Reference Example**:
```kotlin
BlockingReference(
    entityType = "SystemConstraint",
    count = 1,
    role = "last_admin",
    details = "Cannot delete the last administrator. At least one ADMIN user must remain in the system."
)
```

**No schema changes required** - this is a service-layer DTO returned in API responses.

### BlockingReference (Extending Existing)

**Location**: `src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt`

**Current Structure** (lines 23-29):
```kotlin
@Serdeable
data class BlockingReference(
    val entityType: String,
    val count: Int,
    val role: String,
    val details: String
)
```

**Enhancement**: Support new entity type "SystemConstraint" for system-level constraints

**Example Values for Last Admin Protection**:
- `entityType`: "SystemConstraint"
- `count`: 1
- `role`: "last_admin"
- `details`: "Cannot delete the last administrator. At least one ADMIN user must remain in the system."

## Query Patterns

### Count Admin Users

**Purpose**: Determine if user is the last ADMIN

**Implementation** (in UserService):
```kotlin
fun countAdminUsers(): Int {
    return userRepository.findAll().count { user ->
        user.roles.contains(User.Role.ADMIN)
    }
}
```

**Complexity**: O(n) where n = total users
**Performance**: ~5-10ms for 1000 users (EAGER roles loaded in single query)

**SQL Generated** (by Hibernate):
```sql
SELECT u.*, ur.role_name
FROM users u
LEFT JOIN user_roles ur ON u.id = ur.user_id
```

**In-Memory Filtering**:
- Filter by `role_name = 'ADMIN'`
- Count matching users

### Find Users by IDs (for Bulk Operations)

**Purpose**: Validate bulk deletion before execution

**Implementation** (in UserService):
```kotlin
fun findUsersByIds(ids: List<Long>): List<User> {
    return ids.mapNotNull { id ->
        userRepository.findById(id).orElse(null)
    }
}
```

**Alternative** (if performance becomes issue):
```kotlin
// Custom repository method (not implemented initially)
fun findByIdIn(ids: List<Long>): List<User>
```

## State Transitions

### User Deletion State Machine

```
[User with ADMIN role exists]
    |
    v
[Admin count check]
    |
    +---> If admin_count > 1 --> [Allow deletion] --> [User deleted]
    |
    +---> If admin_count = 1 --> [Block deletion] --> [Return 409 error]
```

### Role Update State Machine

```
[User update request with roles]
    |
    v
[Check if ADMIN role being removed]
    |
    +---> If ADMIN role retained --> [Allow update] --> [User updated]
    |
    +---> If ADMIN role removed:
            |
            v
          [Admin count check]
            |
            +---> If admin_count > 1 --> [Allow update] --> [User updated]
            |
            +---> If admin_count = 1 --> [Block update] --> [Return 409 error]
```

## Validation Rules

### Deletion Validation Rules

1. **FR-001**: User deletion MUST be blocked if user is sole ADMIN
   - Query: `countAdminUsers() <= 1 AND user.isAdmin()`
   - Result: Return ValidationResult(canDelete = false)

2. **FR-002**: Admin count MUST be accurate
   - Query: Count all users where roles contains ADMIN
   - Must use EAGER-loaded roles collection

3. **FR-009**: Self-deletion MUST follow same rules
   - No special case for self-deletion
   - Same admin count check applies

### Role Update Validation Rules

1. **FR-008**: Removing ADMIN role from last admin MUST be blocked
   - Check: `user.isAdmin() AND !newRoles.contains(ADMIN) AND countAdminUsers() <= 1`
   - Result: Return ValidationResult(canDelete = false)

### Bulk Deletion Validation Rules

1. **FR-010**: Bulk operation MUST NOT remove all admins
   - Query: Count admins in deletion set vs total admins
   - Validation: `adminsToDelete < totalAdmins` OR `totalAdmins - adminsToDelete >= 1`
   - Result: Fail entire bulk operation if validation fails (atomic)

## Concurrency Considerations

### Transaction Isolation

**Default**: READ_COMMITTED (Micronaut/Hibernate default)

**Scenario**: Two concurrent requests to delete last two admins
```
Time  | Transaction A (Delete Admin 1) | Transaction B (Delete Admin 2)
------|--------------------------------|--------------------------------
T0    | BEGIN                          | BEGIN
T1    | Count admins: 2                | Count admins: 2
T2    | Validation: OK (count > 1)     | Validation: OK (count > 1)
T3    | DELETE admin 1                 | (waiting for lock)
T4    | COMMIT                         | DELETE admin 2
T5    | (success)                      | COMMIT
T6    |                                | (success)
T7    | Result: 0 admins (BAD!)        |
```

**Mitigation**: This scenario is EXTREMELY rare in practice (requires exact timing), but if needed:
1. Use SERIALIZABLE isolation for user deletion transactions
2. Or perform admin count check AFTER acquiring write lock on user table

**Decision**: Accept low-probability edge case, document in deployment notes. Can be addressed post-MVP if observed in production.

## Performance Implications

### Admin Count Query Performance

**Best Case**: 100 users, 5 admins
- Query time: ~2-5ms
- Memory: ~100KB (user objects)

**Typical Case**: 1000 users, 20 admins
- Query time: ~5-10ms
- Memory: ~1MB (user objects)

**Worst Case**: 10,000 users, 100 admins (unlikely for foreseeable future)
- Query time: ~50-100ms
- Memory: ~10MB (user objects)

**Optimization Strategy**: Defer optimization until scale requires it. Current approach sufficient for <5000 users.

## Index Analysis

### Existing Indexes (Sufficient)

```sql
-- Primary key index (already exists)
PRIMARY KEY (id) ON users

-- Unique indexes (already exists)
UNIQUE INDEX (username) ON users
UNIQUE INDEX (email) ON users

-- Foreign key index (already exists)
INDEX (user_id) ON user_roles
```

### No New Indexes Required

Admin count query uses EAGER fetch with LEFT JOIN, which is already optimized. No additional indexes improve performance for this feature.

## Data Integrity Constraints

### Application-Level Constraints (New)

1. **Last Admin Protection Constraint**
   - Type: Business rule (not database constraint)
   - Enforced: Service layer (UserDeletionValidator)
   - Validation: Before DELETE or UPDATE operations
   - Error: HTTP 409 Conflict with structured error response

### Why Not Database Constraint?

**Option Considered**: CHECK constraint `(SELECT COUNT(*) FROM users u JOIN user_roles r ON u.id = r.user_id WHERE r.role_name = 'ADMIN') >= 1`

**Rejected Because**:
- Not portable (MariaDB/MySQL don't support subqueries in CHECK constraints)
- Complex to maintain
- Application-level validation provides better error messages
- Database constraint doesn't distinguish between "last admin deletion" and "other blocking references"

## Schema Migration Plan

**NONE REQUIRED** - This feature uses existing schema only.

## Rollback Plan

If feature needs to be rolled back:
1. Remove validation logic from UserDeletionValidator
2. Remove admin count method from UserService
3. No database rollback needed (no schema changes)

## Summary

- ✅ **Zero schema changes required**
- ✅ Uses existing User entity and role system
- ✅ Extends existing ValidationResult pattern
- ✅ Query performance acceptable for current scale
- ✅ No new database tables, columns, or indexes
- ✅ Application-level constraint enforcement
- ✅ Consistent with existing architecture
