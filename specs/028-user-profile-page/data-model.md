# Data Model: User Profile Page

**Feature**: 028-user-profile-page
**Date**: 2025-10-19

## Overview

This feature leverages the existing `User` entity without any schema changes. A new DTO (`UserProfileDto`) provides a read-only view of user data for the profile page.

## Entities

### User (Existing - No Changes)

**Source**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

**Description**: Represents an authenticated user account in the system.

**Fields**:
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | Primary Key, Auto-generated | Unique user identifier |
| username | String | Unique, Not Null, 1-100 chars | User's login name |
| email | String | Not Null, Valid email format | User's email address |
| passwordHash | String | Not Null | Hashed password (NEVER exposed in API) |
| roles | Set<String> | ElementCollection, Not Null | User roles (USER, ADMIN, VULN, RELEASE_MANAGER) |
| createdAt | Instant | Not Null, Auto-generated | Account creation timestamp |
| updatedAt | Instant | Not Null, Auto-updated | Last modification timestamp |
| workgroups | Set<Workgroup> | ManyToMany, Nullable | User's assigned workgroups (Feature 008) |

**Relationships**:
- **ManyToMany** with `Workgroup` (bidirectional, Feature 008)

**Validation Rules**:
- Username must be unique (database constraint + repository check)
- Email must be valid format (validation annotation)
- Roles collection must not be empty (at minimum contains USER)
- PasswordHash must never be null or empty

**State Transitions**: N/A (no workflow states for User entity)

**Notes**:
- This entity is NOT modified for Feature 028
- Profile page reads username, email, and roles only
- PasswordHash and internal timestamps are excluded from API responses

---

## DTOs

### UserProfileDto (New)

**Source**: `src/backendng/src/main/kotlin/com/secman/dto/UserProfileDto.kt`

**Description**: Data Transfer Object for user profile API responses. Exposes only safe, user-visible fields.

**Fields**:
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| username | String | Not Null | User's display name |
| email | String | Not Null | User's email address |
| roles | Set<String> | Not Null, Not Empty | User's assigned roles |

**Factory Method**:
```kotlin
companion object {
    fun fromUser(user: User): UserProfileDto {
        return UserProfileDto(
            username = user.username,
            email = user.email ?: "Not set",
            roles = user.roles.toSet()
        )
    }
}
```

**Validation Rules**:
- All fields required (null checks enforced)
- Email defaults to "Not set" if somehow null (defensive programming)
- Roles converted to immutable Set

**Security Considerations**:
- **EXCLUDES** passwordHash (security-first principle)
- **EXCLUDES** id (no internal DB identifiers in API)
- **EXCLUDES** createdAt, updatedAt (not relevant to user)
- **EXCLUDES** workgroups (out of scope for profile page)

**Usage**:
- Returned by GET /api/users/profile endpoint
- Created via `UserProfileDto.fromUser(user)` in controller

---

## Data Flow

### Profile Page Load Flow

```
1. User clicks "Profile" in navigation menu
   ↓
2. Frontend: Navigate to /profile route (profile.astro)
   ↓
3. Frontend: UserProfile.tsx component mounts
   ↓
4. Frontend: useEffect triggers userProfileService.getProfile()
   ↓
5. Frontend: Axios GET /api/users/profile (JWT in Authorization header)
   ↓
6. Backend: UserProfileController.getCurrentUserProfile()
   ↓
7. Backend: Extract username from Authentication.name
   ↓
8. Backend: userRepository.findByUsername(username)
   ↓
9. Backend: User entity retrieved from database
   ↓
10. Backend: UserProfileDto.fromUser(user)
   ↓
11. Backend: Return UserProfileDto as JSON (200 OK)
   ↓
12. Frontend: Parse response, update state
   ↓
13. Frontend: Render profile UI with username, email, role badges
```

### Error Flow (User Not Found)

```
7. Backend: Extract username from Authentication.name
   ↓
8. Backend: userRepository.findByUsername(username) returns null
   ↓
9. Backend: Throw NotFoundException("User not found")
   ↓
10. Backend: Return error response (404 Not Found)
   ↓
11. Frontend: Catch error, set error state
   ↓
12. Frontend: Render error message with retry button
```

### Error Flow (Unauthenticated)

```
5. Frontend: Axios GET /api/users/profile (no JWT or invalid JWT)
   ↓
6. Backend: Micronaut Security intercepts request
   ↓
7. Backend: @Secured(IS_AUTHENTICATED) check fails
   ↓
8. Backend: Return 401 Unauthorized
   ↓
9. Frontend: Axios intercepts 401
   ↓
10. Frontend: Redirect to login page (existing auth middleware)
```

---

## Database Schema

### No Changes Required

This feature does NOT require any database migrations. The `User` table already contains all necessary fields:

**Existing User Table** (MariaDB 11.4):
```sql
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_email (email)
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role)
);
```

**Indexes Used**:
- `idx_username`: Used by `userRepository.findByUsername()` for efficient profile lookup
- No new indexes required

---

## Validation Matrix

| Requirement | Entity/DTO | Validation Rule | Enforced By |
|-------------|------------|-----------------|-------------|
| FR-002: Display email | UserProfileDto | email field not null | DTO factory method (defaults to "Not set") |
| FR-003: Display roles | UserProfileDto | roles set not empty | DTO factory method (direct copy from User entity) |
| FR-004: Display username | UserProfileDto | username not null | DTO factory method (User.username is not null) |
| FR-007: Own data only | User | Username matches authentication.name | Controller logic (repository query) |
| FR-008: Authentication required | N/A | @Secured annotation | Micronaut Security framework |

---

## API Response Examples

### Success Response (200 OK)

```json
{
  "username": "adminuser",
  "email": "secmanadmin@schmall.io",
  "roles": ["ADMIN", "USER"]
}
```

### Success Response (Single Role)

```json
{
  "username": "johndoe",
  "email": "john.doe@example.com",
  "roles": ["USER"]
}
```

### Success Response (OAuth User)

```json
{
  "username": "github_user123",
  "email": "user@github.com",
  "roles": ["USER", "VULN"]
}
```

### Error Response (401 Unauthorized)

```json
{
  "message": "Unauthorized",
  "_links": {
    "self": {
      "href": "/api/users/profile"
    }
  }
}
```

### Error Response (404 Not Found)

```json
{
  "message": "User not found",
  "_links": {
    "self": {
      "href": "/api/users/profile"
    }
  }
}
```

### Error Response (500 Internal Server Error)

```json
{
  "message": "An error occurred while processing your request",
  "_links": {
    "self": {
      "href": "/api/users/profile"
    }
  }
}
```

---

## Summary

- **Entities Modified**: 0 (no changes to User entity)
- **DTOs Created**: 1 (UserProfileDto)
- **Database Migrations**: 0 (no schema changes)
- **New Tables**: 0
- **New Indexes**: 0
- **Security Level**: High (no sensitive data exposed, authentication required)
