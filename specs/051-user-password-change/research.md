# Research: User Password Change Feature (051)

**Date**: 2025-11-28
**Feature Branch**: `051-user-password-change`

## Executive Summary

This document consolidates research findings for implementing the user password change feature. All technical decisions are based on existing codebase patterns and the project's technology stack.

---

## 1. Password Hashing Mechanism

**Decision**: Use existing `BCryptPasswordEncoder` from Spring Security

**Rationale**:
- Already used consistently across AuthController, AuthenticationProviderUserPassword, UserController, and OAuthService
- Industry standard for password hashing (bcrypt with salt)
- No additional dependencies required

**Code Reference**:
- `AuthController.kt:26`: `private val passwordEncoder = BCryptPasswordEncoder()`
- `UserController.kt:29`: `private val passwordEncoder = BCryptPasswordEncoder()`

**Alternatives Considered**:
- Argon2 - More modern but would require additional dependencies
- PBKDF2 - Already available but BCrypt is preferred and consistent with codebase

---

## 2. OAuth/OIDC User Detection

**Decision**: Add `authSource` field to User entity

**Rationale**:
- Current User entity has no explicit field to track authentication source
- OAuth users receive random UUID passwords (OAuthService.kt:760): `passwordEncoder.encode(UUID.randomUUID().toString())`
- Need explicit tracking to hide password change UI for OAuth-only users
- Flyway migration will set existing users to "LOCAL" (conservative default)

**Implementation**:
```kotlin
// User.kt - New field
@Column(name = "auth_source", nullable = false, length = 20)
@Enumerated(EnumType.STRING)
var authSource: AuthSource = AuthSource.LOCAL

enum class AuthSource {
    LOCAL,      // Username/password registration
    OAUTH,      // OAuth/OIDC provider
    HYBRID      // Has local password + linked OAuth (future)
}
```

**Migration Strategy**:
- New flyway migration: `V051__user_password_change.sql`
- Set all existing users to `LOCAL` (safe default)
- Update OAuthService to set `OAUTH` for new OAuth users

**Alternatives Considered**:
- Check if password matches UUID pattern - fragile, unreliable
- Store linked provider IDs in separate table - overengineered for this use case
- Always show password change option - violates spec requirement FR-009

---

## 3. Security Audit Logging

**Decision**: Use existing AuditLogService pattern + security.audit logger

**Rationale**:
- AuditLogService (AuditLogService.kt) provides structured logging
- OAuthService uses `LoggerFactory.getLogger("security.audit")` for security events
- Consistent with existing security event logging (role assignments, user creation)

**Implementation**:
```kotlin
// In PasswordChangeService
auditLogService.logAction(
    authentication = authentication,
    action = "PASSWORD_CHANGED",
    entityType = "User",
    entityId = userId,
    details = "Password changed successfully"
)
```

**Alternatives Considered**:
- Database-backed audit log - overkill for password changes
- Separate security event table - would require new infrastructure

---

## 4. API Endpoint Design

**Decision**: Add `PUT /api/users/profile/change-password` to UserProfileController

**Rationale**:
- UserProfileController already handles authenticated user's profile operations
- Uses `@Secured(SecurityRule.IS_AUTHENTICATED)` - correct security level
- Follows existing pattern: `/profile/mfa-status`, `/profile/mfa-toggle`
- Keeps password change separate from admin user management in UserController

**Request/Response**:
```kotlin
@Serdeable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
    val confirmPassword: String
)

@Serdeable
data class ChangePasswordResponse(
    val success: Boolean,
    val message: String
)
```

**Alternatives Considered**:
- POST /api/auth/change-password - AuthController is for login/logout, not profile operations
- PUT /api/users/{id}/password - Would require ADMIN role per UserController

---

## 5. Frontend Integration Point

**Decision**: Add password change section to UserProfile.tsx Security Settings card

**Rationale**:
- UserProfile.tsx already has "Security Settings" card with MFA toggle
- Logical grouping with other security-related settings
- Consistent UX pattern

**UI Design**:
- Collapsible form below MFA settings in Security Settings card
- Fields: Current Password, New Password, Confirm New Password
- Client-side validation before submission
- Hide entire section for OAuth-only users (check `authSource` in profile data)

**Alternatives Considered**:
- Separate page - unnecessary complexity
- Modal dialog - poor UX for password entry

---

## 6. Session Invalidation

**Decision**: No automatic session invalidation (current user continues working)

**Rationale**:
- Current JWT system has no server-side session tracking
- Existing tokens remain valid until expiration (8 hours per RefreshResponse)
- Forcing re-login after password change would interrupt user's work
- Other sessions naturally expire - acceptable trade-off for this system

**Future Enhancement**:
- Could implement token blacklist or version tracking if needed
- Would require additional infrastructure (Redis cache or DB table)

**Alternatives Considered**:
- Immediate logout - poor UX
- Token blacklist - requires additional infrastructure

---

## 7. Password Validation Rules

**Decision**: Minimum 8 characters, must differ from current password

**Rationale**:
- Spec requirement FR-005: minimum 8 characters
- Spec requirement FR-006: must differ from current password
- Consistent with industry standards
- No additional complexity rules (uppercase, special chars) per spec

**Implementation**:
```kotlin
// Validation in service layer
when {
    newPassword.length < 8 ->
        return ChangePasswordResult.Error("Password must be at least 8 characters")
    passwordEncoder.matches(newPassword, user.passwordHash) ->
        return ChangePasswordResult.Error("New password must be different from current password")
}
```

---

## 8. Error Handling

**Decision**: Specific error messages for each validation failure

**Rationale**:
- Spec requirement FR-007: clear, user-friendly error messages
- Helps users understand what to fix
- Does not reveal security-sensitive information

**Error Messages**:
| Scenario | Error Message |
|----------|---------------|
| Wrong current password | "Current password is incorrect" |
| Passwords don't match | "New password and confirmation do not match" |
| Too short | "Password must be at least 8 characters" |
| Same as current | "New password must be different from current password" |
| OAuth user attempt | "Password change is not available for OAuth accounts" |

---

## Dependencies

No new dependencies required. All functionality can be implemented with:
- Existing `spring-security-crypto` for BCryptPasswordEncoder
- Existing `micronaut-security` for authentication
- Existing logging infrastructure

---

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| OAuth user tries to change password | Check `authSource` field before processing |
| Concurrent password change | Natural protection via BCrypt comparison - second request fails |
| Brute force current password | Rate limiting at controller level (future enhancement) |
| Weak password chosen | Enforce minimum 8 characters per spec |

---

## Open Questions (Resolved)

1. **Q**: Should we invalidate all sessions after password change?
   **A**: No - current JWT architecture doesn't support this without additional infrastructure. Tokens expire naturally.

2. **Q**: Should OAuth users be able to set a local password?
   **A**: No per spec - OAuth users should manage credentials with their identity provider.

3. **Q**: What password complexity rules?
   **A**: Minimum 8 characters only per spec requirements.
