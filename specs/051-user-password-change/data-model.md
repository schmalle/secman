# Data Model: User Password Change Feature (051)

**Date**: 2025-11-28
**Feature Branch**: `051-user-password-change`

## Overview

This document describes the data model changes required for the user password change feature. The primary change is adding an `authSource` field to the existing User entity to track authentication method.

---

## Entity Changes

### User Entity (Modification)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

#### New Enum: AuthSource

```kotlin
/**
 * Authentication source for user accounts
 * Feature: 051-user-password-change
 *
 * - LOCAL: User registered with username/password
 * - OAUTH: User created via OAuth/OIDC provider (no local password)
 * - HYBRID: User has both local password and linked OAuth (future)
 */
enum class AuthSource {
    LOCAL,
    OAUTH,
    HYBRID
}
```

#### New Field: authSource

| Attribute | Value |
|-----------|-------|
| Field Name | `authSource` |
| Column Name | `auth_source` |
| Type | `AuthSource` (enum) |
| Nullable | `false` |
| Default | `AuthSource.LOCAL` |
| Max Length | 20 |

**JPA Mapping**:
```kotlin
@Column(name = "auth_source", nullable = false, length = 20)
@Enumerated(EnumType.STRING)
var authSource: AuthSource = AuthSource.LOCAL
```

**Purpose**:
- Determines if user can change password via self-service
- LOCAL users: Can change password
- OAUTH users: Cannot change password (redirect to IdP)
- HYBRID users: Can change password (future enhancement)

---

## Database Migration

### Flyway Migration: V051__user_password_change.sql

```sql
-- Feature 051: User Password Change
-- Add auth_source column to track authentication method

ALTER TABLE users
ADD COLUMN auth_source VARCHAR(20) NOT NULL DEFAULT 'LOCAL';

-- Index for filtering users by auth source (admin queries)
CREATE INDEX idx_user_auth_source ON users(auth_source);

-- Comment for documentation
ALTER TABLE users
MODIFY COLUMN auth_source VARCHAR(20) NOT NULL
COMMENT 'Authentication source: LOCAL, OAUTH, or HYBRID';
```

**Migration Notes**:
- All existing users default to `LOCAL` (safe assumption)
- OAuthService will be updated to set `OAUTH` for new OAuth users
- Index added for potential future admin queries (filter users by auth type)

---

## Entity Relationships

```
┌─────────────────────────────────────────────────────┐
│                       User                           │
├─────────────────────────────────────────────────────┤
│ id: Long (PK)                                       │
│ username: String (unique)                           │
│ email: String (unique)                              │
│ passwordHash: String                                │
│ authSource: AuthSource (NEW)  ← Added by this feature│
│ roles: Set<Role>                                    │
│ workgroups: Set<Workgroup>                          │
│ mfaEnabled: Boolean                                 │
│ createdAt: Instant                                  │
│ updatedAt: Instant                                  │
└─────────────────────────────────────────────────────┘
```

---

## DTOs

### ChangePasswordRequest

**Location**: `src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt` (inline)

```kotlin
@Serdeable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
    val confirmPassword: String
)
```

| Field | Type | Validation |
|-------|------|------------|
| currentPassword | String | Required, max 200 chars |
| newPassword | String | Required, min 8 chars, max 200 chars |
| confirmPassword | String | Required, must match newPassword |

### ChangePasswordResponse

```kotlin
@Serdeable
data class ChangePasswordResponse(
    val success: Boolean,
    val message: String
)
```

| Field | Type | Description |
|-------|------|-------------|
| success | Boolean | True if password was changed |
| message | String | User-friendly success/error message |

### UserProfileDto (Modification)

**File**: `src/backendng/src/main/kotlin/com/secman/dto/UserProfileDto.kt`

Add new field to expose auth source to frontend:

```kotlin
@Serdeable
data class UserProfileDto(
    val username: String,
    val email: String,
    val roles: List<String>,
    val canChangePassword: Boolean  // NEW: true if authSource != OAUTH
) {
    companion object {
        fun fromUser(user: User): UserProfileDto {
            return UserProfileDto(
                username = user.username,
                email = user.email,
                roles = user.roles.map { it.name },
                canChangePassword = user.authSource != User.AuthSource.OAUTH
            )
        }
    }
}
```

---

## Validation Rules

### Password Validation

| Rule | Error Message |
|------|---------------|
| Current password required | "Current password is required" |
| New password required | "New password is required" |
| Min 8 characters | "Password must be at least 8 characters" |
| Max 200 characters | "Password exceeds maximum length" |
| Passwords must match | "New password and confirmation do not match" |
| Must differ from current | "New password must be different from current password" |
| Current password correct | "Current password is incorrect" |
| User is LOCAL auth | "Password change is not available for OAuth accounts" |

---

## State Transitions

### Password Change Flow

```
┌─────────────┐    ┌──────────────┐    ┌─────────────┐
│  Unchanged  │───▶│  Validating  │───▶│   Changed   │
└─────────────┘    └──────────────┘    └─────────────┘
                          │
                          │ (validation fails)
                          ▼
                   ┌──────────────┐
                   │    Error     │
                   └──────────────┘
```

**States**:
1. **Unchanged**: User's current password is active
2. **Validating**: Request received, validating inputs
3. **Changed**: New password hash stored, audit logged
4. **Error**: Validation failed, password unchanged

---

## Indexing Strategy

| Index Name | Column(s) | Purpose |
|------------|-----------|---------|
| idx_user_auth_source | auth_source | Filter users by authentication type |

**Note**: No additional indexes needed for password change operations - existing `idx_user_username` covers user lookup.

---

## Security Considerations

### Data Protection

- `passwordHash` field has `@JsonIgnore` annotation - never exposed in API responses
- `currentPassword` is never logged or stored - used only for verification
- Audit log records action type, user ID, and timestamp - never password content

### Audit Trail

Password change events logged with:
- Timestamp
- User ID (numeric)
- Action type: "PASSWORD_CHANGED"
- Entity type: "User"
- No password content in logs
