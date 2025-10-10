# Feature 014: Add CHAMPION and REQ Roles with UI Access Control

## Overview
Add two new user roles (CHAMPION and REQ) to the system and implement role-based access control (RBAC) for specific management features. Norm Management, Standard Management, and UseCase Management will only be visible to users with ADMIN, CHAMPION, or REQ roles. Releases and Compare Releases features will only be visible to users with ADMIN role.

## User Story
As a system administrator, I want to introduce CHAMPION and REQ roles so that I can grant specific users access to requirements-related management features (Norms, Standards, UseCases) without giving them full admin privileges, while keeping release management restricted to administrators only.

## Requirements

### Backend Changes

#### 1. User Role Enum Extension
- **Location**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`
- **Change**: Add `CHAMPION` and `REQ` to the `Role` enum
- **Before**: `enum class Role { USER, ADMIN, VULN, RELEASE_MANAGER }`
- **After**: `enum class Role { USER, ADMIN, VULN, RELEASE_MANAGER, CHAMPION, REQ }`

#### 2. Controller Security Annotations

##### NormController
- **Location**: `src/backendng/src/main/kotlin/com/secman/controller/NormController.kt`
- **Change**: Update `@Secured` annotation from `IS_AUTHENTICATED` to specific roles
- **New annotation**: `@Secured("ADMIN", "CHAMPION", "REQ")`
- **Affects**: All endpoints in `/api/norms`

##### StandardController
- **Location**: `src/backendng/src/main/kotlin/com/secman/controller/StandardController.kt`
- **Change**: Update `@Secured` annotation from `IS_AUTHENTICATED` to specific roles
- **New annotation**: `@Secured("ADMIN", "CHAMPION", "REQ")`
- **Affects**: All endpoints in `/api/standards`

##### UseCaseController
- **Location**: `src/backendng/src/main/kotlin/com/secman/controller/UseCaseController.kt`
- **Change**: Update `@Secured` annotation from `IS_AUTHENTICATED` to specific roles
- **New annotation**: `@Secured("ADMIN", "CHAMPION", "REQ")`
- **Affects**: All endpoints in `/api/usecases`

##### ReleaseController
- **Location**: `src/backendng/src/main/kotlin/com/secman/controller/ReleaseController.kt`
- **Change**: Update `@Secured` annotation from `IS_AUTHENTICATED` to ADMIN only
- **New annotation**: `@Secured("ADMIN")`
- **Affects**: All endpoints in `/api/releases`
- **Note**: Individual methods like `createRelease` and `deleteRelease` already have `@Secured("ADMIN", "RELEASE_MANAGER")` which takes precedence

### Frontend Changes

#### 1. Permission Utility Functions
- **Location**: `src/frontend/src/utils/permissions.ts`
- **New Functions**:
  - `isChampion(roles)`: Check if user has CHAMPION role
  - `isReq(roles)`: Check if user has REQ role
  - `canAccessNormManagement(roles)`: Returns true if user is ADMIN, CHAMPION, or REQ
  - `canAccessStandardManagement(roles)`: Returns true if user is ADMIN, CHAMPION, or REQ
  - `canAccessUseCaseManagement(roles)`: Returns true if user is ADMIN, CHAMPION, or REQ
  - `canAccessReleases(roles)`: Returns true if user is ADMIN only
  - `canAccessCompareReleases(roles)`: Returns true if user is ADMIN only

#### 2. Sidebar Navigation Component
- **Location**: `src/frontend/src/components/Sidebar.tsx`
- **Changes**:
  - Import new permission functions from `utils/permissions`
  - Track `userRoles` state in addition to `isAdmin` and `hasVuln`
  - Conditionally render navigation items based on role checks:
    - **Norm Management**: Show if `canAccessNormManagement(userRoles)`
    - **Standard Management**: Show if `canAccessStandardManagement(userRoles)`
    - **UseCase Management**: Show if `canAccessUseCaseManagement(userRoles)`
    - **Releases**: Show if `canAccessReleases(userRoles)`
    - **Compare Releases**: Show if `canAccessCompareReleases(userRoles)`

## Access Control Matrix

| Feature | USER | ADMIN | VULN | RELEASE_MANAGER | CHAMPION | REQ |
|---------|------|-------|------|-----------------|----------|-----|
| Norm Management | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Standard Management | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| UseCase Management | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Releases (View/List) | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| Compare Releases | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| Create Release | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ |
| Delete Release | ❌ | ✅ (any) | ❌ | ✅ (own only) | ❌ | ❌ |

## Technical Implementation

### Backend Security Flow
1. User makes API request to protected endpoint (e.g., `/api/norms`)
2. Micronaut Security intercepts request and checks JWT token
3. Extracts user roles from authentication context
4. Validates against `@Secured` annotation on controller
5. If user has at least one of the required roles → proceed
6. Otherwise → 403 Forbidden response

### Frontend UI Flow
1. User data loaded on page load (includes roles array)
2. `userLoaded` event triggers role check in Sidebar component
3. Component state updated with `userRoles` array
4. Navigation items rendered conditionally using permission utility functions
5. If user lacks required role → menu item hidden from sidebar
6. Users attempting direct URL access are blocked by backend (403 Forbidden)

## Database Migration
**No database migration required.** The Role enum is stored as strings in the `user_roles` table via JPA's `@ElementCollection`. Existing rows are not affected, and new roles can be assigned immediately via the User Management UI.

## Backward Compatibility
- **Existing users**: No impact. Current roles (USER, ADMIN, VULN, RELEASE_MANAGER) continue to work as before.
- **Existing functionality**: All existing endpoints remain accessible to previously authorized roles.
- **New restrictions**: 
  - Users with only USER role will lose access to Norm/Standard/UseCase management (previously accessible to all authenticated users)
  - Non-admin users will lose access to Release pages (previously accessible to all authenticated users)

## Testing Strategy

### Manual Testing
1. Create test users with different role combinations:
   - User with only USER role
   - User with CHAMPION role
   - User with REQ role
   - User with ADMIN role
2. Verify sidebar visibility for each role
3. Verify API access (403 vs 200) for each role
4. Test direct URL access (should redirect/error if unauthorized)

### Automated Testing
- **Unit tests**: Not required (role enum is straightforward)
- **Integration tests**: Backend controllers already have security tests
- **E2E tests**: Existing Playwright tests for releases cover admin-only access

## Deployment Notes
1. Deploy backend changes first (new roles defined, but not yet enforced in UI)
2. Deploy frontend changes (UI enforces new role restrictions)
3. Assign CHAMPION/REQ roles to appropriate users via User Management UI
4. Monitor logs for 403 Forbidden errors (indicates users attempting unauthorized access)

## Security Considerations
- **Defense in depth**: Both backend (@Secured annotations) and frontend (UI hiding) enforce restrictions
- **Direct URL access**: Backend validation prevents unauthorized access even if users guess URLs
- **Role elevation**: Only ADMIN users can assign CHAMPION/REQ roles via User Management UI
- **JWT tokens**: Roles are embedded in JWT tokens and validated on every request

## Future Enhancements
- Add CHAMPION and REQ role assignment UI in User Management page
- Implement audit logging for role-based access attempts
- Consider more granular permissions (e.g., read-only vs. read-write for CHAMPION/REQ)

## Related Features
- **Feature 001**: Admin Role Management (foundation for RBAC)
- **Feature 004**: VULN Role & Vulnerability Management UI
- **Feature 011**: Release-Based Requirement Version Management (Release UI restrictions)

## References
- Micronaut Security Annotations: https://micronaut-projects.github.io/micronaut-security/latest/guide/
- User Role Enum: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`
- Permission Utilities: `src/frontend/src/utils/permissions.ts`

---
**Author**: System  
**Date**: 2025-01-09  
**Status**: Implemented ✅
