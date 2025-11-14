# Data Model: OIDC Default Roles

**Date**: 2025-11-14
**Feature**: 046-oidc-default-roles
**Phase**: 1 - Design & Contracts

## Overview

This feature does NOT introduce new entities. It modifies the behavior of existing User entity creation during OIDC auto-provisioning to initialize the `roles` collection with default values ("USER", "VULN").

## Entities

### User (Existing - Behavior Modified)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

**Purpose**: Represents a system user account with authentication credentials and role-based permissions.

**Fields**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | Primary Key, Auto-generated | Unique identifier |
| username | String | NOT NULL, UNIQUE, max 255 chars | User login name |
| email | String | NOT NULL, UNIQUE, max 255 chars | Email address for notifications |
| passwordHash | String? | NULLABLE, max 255 chars | Bcrypt hash (null for OIDC users) |
| roles | MutableSet<String> | NOT NULL, ElementCollection | Set of role names (USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION) |
| createdAt | LocalDateTime | NOT NULL, auto-set | User creation timestamp |
| updatedAt | LocalDateTime | NOT NULL, auto-updated | Last modification timestamp |

**Relationships**:
- Many-to-Many with Workgroup (via `workgroups` field)
- One-to-Many with UserMapping (via `userMappings` field)
- One-to-Many with Asset (via `manuallyCreatedAssets` field)

**Validation Rules**:
- Email must be valid format (validated by IdentityProvider claims)
- Roles must be one of: USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION
- Username derived from email (substringBefore("@")) for OIDC users
- PasswordHash must be null for OIDC-created users

**State Transitions**:

```text
[OIDC Authentication] → [Auto-provision check]
                              ↓
                        [Check existing user]
                              ↓
                    ┌─────────┴─────────┐
                    │                   │
            [User exists]        [User not found]
                    │                   │
                    │                   ↓
                    │         [Create new User with roles = {"USER", "VULN"}]
                    │                   ↓
                    │            [Audit log event]
                    │                   ↓
                    │         [Async email to admins]
                    │                   │
                    └───────────┬───────┘
                                ↓
                        [Return User entity]
```

**Modified Behavior**:
- **BEFORE this feature**: New OIDC users created with `roles = mutableSetOf()` (empty)
- **AFTER this feature**: New OIDC users created with `roles = mutableSetOf("USER", "VULN")`
- **Constraint**: Existing users are NEVER modified (FR-006)
- **Constraint**: Only applies when `identityProvider.autoProvision == true` (FR-007)

**JPA Annotations** (Expected):
```kotlin
@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    val username: String,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = true) // Null for OIDC users
    var passwordHash: String? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")])
    @Column(name = "role")
    val roles: MutableSet<String> = mutableSetOf(), // MODIFIED: Initialize with {"USER", "VULN"} for OIDC

    // ... other fields (workgroups, createdAt, etc.)
)
```

---

### IdentityProvider (Existing - No Changes)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/IdentityProvider.kt`

**Purpose**: Stores OIDC/SAML identity provider configuration.

**Relevant Fields** (for this feature):

| Field | Type | Description |
|-------|------|-------------|
| id | Long | Primary Key |
| name | String | Identity provider display name (e.g., "Google Workspace") |
| type | String | "OIDC" or "SAML" |
| autoProvision | Boolean | **If true, create new users automatically on first login** |
| enabled | Boolean | If false, provider cannot be used |

**Usage in Feature**:
- `autoProvision` flag checked before creating new user (FR-007)
- `name` field included in audit log and admin notification
- No modifications required to this entity

---

## Database Schema Impact

**Schema Changes**: NONE

**Rationale**:
- `User.roles` field already exists as `ElementCollection` (maps to `user_roles` table)
- No new columns, tables, or constraints required
- Only change is initialization value in application code
- Hibernate auto-migration not triggered (no schema diff)

**Existing Schema** (no changes):
```sql
-- users table (existing)
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

-- user_roles table (existing)
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role)
);
```

**Data Migration**: Not required (existing users retain current roles)

---

## Validation Rules

### User Creation (New OIDC Users)

**Pre-conditions**:
1. Identity provider exists and `enabled = true`
2. Identity provider has `autoProvision = true`
3. User with matching email does NOT exist (checked via `UserRepository.findByEmail()`)

**Invariants**:
1. New OIDC user MUST have exactly 2 roles: "USER" and "VULN" (FR-001, FR-002)
2. `passwordHash` MUST be null for OIDC users
3. Username MUST be derived from email (e.g., "john.doe@example.com" → "john.doe")
4. Email MUST match identity provider userInfo claim

**Post-conditions**:
1. User entity saved to database (within transaction)
2. Audit log entry created (security.audit logger)
3. Admin notification email queued (async, best-effort)

### Role Assignment Atomicity

**Transaction Boundary**:
```
BEGIN TRANSACTION
  INSERT INTO users (username, email, password_hash, created_at, updated_at)
  INSERT INTO user_roles (user_id, role) VALUES (?, 'USER')
  INSERT INTO user_roles (user_id, role) VALUES (?, 'VULN')
COMMIT
```

**Rollback Scenarios**:
- Database constraint violation (e.g., duplicate email)
- Foreign key constraint failure (should not occur)
- Any `RuntimeException` during transaction
- **Result**: No partial user created, no orphaned roles (FR-009)

---

## Access Patterns

### Queries (Existing - No Changes)

```kotlin
// Find user by email (used in OIDC callback)
interface UserRepository : CrudRepository<User, Long> {
    fun findByEmail(email: String): User?

    // For admin notifications (FR-011)
    fun findByRolesContaining(role: String): List<User>
}
```

**Performance Considerations**:
- `findByEmail()`: Uses unique index on email column (fast lookup)
- `findByRolesContaining("ADMIN")`: Joins with user_roles table; acceptable for small admin count (<100)
- No N+1 query issues (roles eagerly fetched)

### Indexes (Existing)

```sql
-- Already present, no changes required
CREATE UNIQUE INDEX idx_users_email ON users(email);
CREATE UNIQUE INDEX idx_users_username ON users(username);
CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);
```

---

## Summary

| Aspect | Status | Details |
|--------|--------|---------|
| New Entities | None | Uses existing User and IdentityProvider |
| Schema Changes | None | No DDL required |
| Data Migration | None | Existing users unaffected |
| Transaction Scope | Modified | User creation + role assignment now atomic |
| Validation Rules | Enhanced | Default roles enforced for OIDC users |
| Performance Impact | Minimal | 2 additional INSERTs per new OIDC user (<1ms) |

**Entity Diagram**:

```text
┌──────────────────────┐         ┌──────────────────────┐
│  IdentityProvider    │         │       User           │
├──────────────────────┤         ├──────────────────────┤
│ id: Long             │         │ id: Long             │
│ name: String         │         │ username: String     │
│ autoProvision: Bool  │◄────────│ email: String        │
│ enabled: Boolean     │ (N:1)   │ passwordHash: String?│
└──────────────────────┘         │ roles: Set<String>   │
                                  │   ↳ {"USER", "VULN"} │ ← NEW DEFAULT
                                  └──────────────────────┘
                                           │
                                           │ ElementCollection
                                           ↓
                                  ┌──────────────────────┐
                                  │    user_roles        │
                                  ├──────────────────────┤
                                  │ user_id: Long (FK)   │
                                  │ role: String         │
                                  └──────────────────────┘
```
