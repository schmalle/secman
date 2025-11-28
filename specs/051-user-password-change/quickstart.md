# Quickstart: User Password Change Feature (051)

**Feature Branch**: `051-user-password-change`
**Date**: 2025-11-28

## Overview

This feature allows authenticated users with local accounts to change their own password from the user profile page.

---

## Key Files to Modify

### Backend

| File | Changes |
|------|---------|
| `src/backendng/src/main/kotlin/com/secman/domain/User.kt` | Add `AuthSource` enum and `authSource` field |
| `src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt` | Add `changePassword` endpoint |
| `src/backendng/src/main/kotlin/com/secman/dto/UserProfileDto.kt` | Add `canChangePassword` field |
| `src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt` | Set `authSource = OAUTH` for new OAuth users |
| `src/backendng/src/main/resources/db/migration/V051__user_password_change.sql` | Add `auth_source` column |

### Frontend

| File | Changes |
|------|---------|
| `src/frontend/src/components/UserProfile.tsx` | Add password change form in Security Settings |
| `src/frontend/src/services/userProfileService.ts` | Add `changePassword()` method |

---

## API Endpoint

```
PUT /api/users/profile/change-password
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "currentPassword": "oldPassword123",
  "newPassword": "newSecurePassword456",
  "confirmPassword": "newSecurePassword456"
}

Response (200 OK):
{
  "success": true,
  "message": "Password changed successfully"
}

Response (400 Bad Request):
{
  "error": "Current password is incorrect"
}
```

---

## Database Migration

```sql
-- V051__user_password_change.sql
ALTER TABLE users
ADD COLUMN auth_source VARCHAR(20) NOT NULL DEFAULT 'LOCAL';

CREATE INDEX idx_user_auth_source ON users(auth_source);
```

---

## Implementation Checklist

### Phase 1: Backend Core

- [ ] Add `AuthSource` enum to User.kt
- [ ] Add `authSource` field to User entity
- [ ] Create Flyway migration V051
- [ ] Update `UserProfileDto` with `canChangePassword`
- [ ] Add `ChangePasswordRequest` DTO
- [ ] Add `ChangePasswordResponse` DTO
- [ ] Implement `changePassword` endpoint in UserProfileController
- [ ] Add password validation logic
- [ ] Add audit logging for password changes

### Phase 2: OAuth Integration

- [ ] Update OAuthService to set `authSource = OAUTH` for new users
- [ ] Test OAuth user flow (should not see password change option)

### Phase 3: Frontend

- [ ] Update `UserProfileData` interface with `canChangePassword`
- [ ] Add `changePassword()` method to userProfileService
- [ ] Add password change form to UserProfile.tsx
- [ ] Implement client-side validation
- [ ] Handle success/error responses
- [ ] Hide form for OAuth-only users

### Phase 4: Testing & Verification

- [ ] Test local user password change flow
- [ ] Test OAuth user cannot access password change
- [ ] Test all validation error cases
- [ ] Verify audit log entries
- [ ] Test UI feedback (success/error messages)

---

## Common Patterns

### Password Hashing (BCrypt)

```kotlin
// In controller/service
private val passwordEncoder = BCryptPasswordEncoder()

// Verify current password
if (!passwordEncoder.matches(currentPassword, user.passwordHash)) {
    return HttpResponse.badRequest(mapOf("error" to "Current password is incorrect"))
}

// Hash new password
user.passwordHash = passwordEncoder.encode(newPassword)
```

### Audit Logging

```kotlin
// In service
auditLogService.logAction(
    authentication = authentication,
    action = "PASSWORD_CHANGED",
    entityType = "User",
    entityId = user.id,
    details = "Password changed via self-service"
)
```

### Frontend API Call

```typescript
// In userProfileService.ts
async changePassword(request: ChangePasswordRequest): Promise<ChangePasswordResponse> {
  const response = await axios.put<ChangePasswordResponse>(
    `${this.baseUrl}/profile/change-password`,
    request
  );
  return response.data;
}
```

---

## Validation Rules

| Rule | Error Message |
|------|---------------|
| Current password required | "Current password is required" |
| New password required | "New password is required" |
| Min 8 characters | "Password must be at least 8 characters" |
| Passwords must match | "New password and confirmation do not match" |
| Must differ from current | "New password must be different from current password" |
| Current password correct | "Current password is incorrect" |
| User is LOCAL auth | "Password change is not available for OAuth accounts" |

---

## Build & Test

```bash
# Backend build
cd src/backendng
./gradlew build

# Frontend build
cd src/frontend
npm run build

# Run backend locally
./gradlew run

# Run frontend dev server
npm run dev
```

---

## Related Documentation

- [Specification](./spec.md) - Feature requirements
- [Research](./research.md) - Technical decisions
- [Data Model](./data-model.md) - Entity changes
- [API Contract](./contracts/password-change-api.yaml) - OpenAPI specification
