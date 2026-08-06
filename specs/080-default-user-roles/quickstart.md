# Quickstart: Default User Roles on Creation

**Feature**: 080-default-user-roles
**Branch**: `080-default-user-roles`

## What This Feature Does

Changes the default roles assigned to newly created users from {USER, VULN} to {USER, VULN, REQ}, so new users can immediately access requirements without admin intervention.

## Files to Modify

1. **`src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt`**
   - Method: `createNewOidcUser()`
   - Add `User.Role.REQ` to the default roles set
   - Update audit log string to include REQ

2. **`src/backendng/src/main/kotlin/com/secman/controller/UserController.kt`**
   - Method: `create()`
   - Change empty-roles default from `USER` to `USER, VULN, REQ`

## Verification

```bash
# Build to verify compilation
./gradlew build
```

After deploying:
- Create a new user via OIDC login → verify roles include USER, VULN, REQ
- Create a new user via admin API without roles → verify roles include USER, VULN, REQ
- Create a new user via admin API with explicit roles → verify only specified roles are assigned
- Verify existing users' roles are unchanged
