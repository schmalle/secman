# Feature 014: Add CHAMPION and REQ Roles with UI Access Control

## Quick Summary
Added two new user roles (CHAMPION and REQ) with role-based access control for requirements management features. Norm Management, Standard Management, and UseCase Management are now restricted to ADMIN, CHAMPION, and REQ roles. Releases and Compare Releases are restricted to ADMIN role only.

## Access Control Matrix

| Feature | USER | ADMIN | VULN | RELEASE_MANAGER | CHAMPION | REQ |
|---------|------|-------|------|-----------------|----------|-----|
| **Requirements Overview** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Norm Management** | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| **Standard Management** | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| **UseCase Management** | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| **Releases (View/List)** | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Compare Releases** | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Create Release** | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ |
| **Delete Release** | ❌ | ✅ (any) | ❌ | ✅ (own) | ❌ | ❌ |

## Implementation Details

### Backend Changes
```kotlin
// User.kt - Added new roles
enum class Role {
    USER, ADMIN, VULN, RELEASE_MANAGER, CHAMPION, REQ
}

// NormController.kt, StandardController.kt, UseCaseController.kt
@Secured("ADMIN", "CHAMPION", "REQ")

// ReleaseController.kt
@Secured("ADMIN")
```

### Frontend Changes
```typescript
// permissions.ts - New permission functions
canAccessNormManagement(roles): boolean
canAccessStandardManagement(roles): boolean
canAccessUseCaseManagement(roles): boolean
canAccessReleases(roles): boolean
canAccessCompareReleases(roles): boolean

// Sidebar.tsx - Conditional rendering
{canAccessNormManagement(userRoles) && (
  <li><a href="/norms">Norm Management</a></li>
)}
```

## Role Descriptions

### CHAMPION
**Purpose**: Requirements champions who manage security standards and norms  
**Access**: Can create/edit/delete Norms, Standards, and UseCases  
**Typical Users**: Security architects, requirements managers, compliance officers

### REQ
**Purpose**: Requirements engineers who maintain requirements documentation  
**Access**: Can create/edit/delete Norms, Standards, and UseCases  
**Typical Users**: Requirements engineers, technical writers, BA/QA leads

## Migration Path

### Before Deployment
1. Identify users who need CHAMPION or REQ roles
2. Document current access patterns (who uses Norm/Standard/UseCase features)
3. Prepare communication for users who will lose access

### After Deployment
1. Login as ADMIN user
2. Go to User Management UI
3. Assign CHAMPION or REQ roles to appropriate users
4. Verify users can access required features
5. Monitor logs for 403 Forbidden errors

## Testing Scenarios

### Test Case 1: USER Role (Restricted Access)
- ✅ Can access Requirements Overview
- ❌ Cannot see Norm Management in sidebar
- ❌ Cannot see Standard Management in sidebar
- ❌ Cannot see UseCase Management in sidebar
- ❌ Cannot see Releases in sidebar
- ❌ Direct API access returns 403 Forbidden

### Test Case 2: CHAMPION Role
- ✅ Can access Requirements Overview
- ✅ Can see and use Norm Management
- ✅ Can see and use Standard Management
- ✅ Can see and use UseCase Management
- ❌ Cannot see Releases in sidebar
- ❌ Direct release API access returns 403 Forbidden

### Test Case 3: REQ Role
- ✅ Can access Requirements Overview
- ✅ Can see and use Norm Management
- ✅ Can see and use Standard Management
- ✅ Can see and use UseCase Management
- ❌ Cannot see Releases in sidebar
- ❌ Direct release API access returns 403 Forbidden

### Test Case 4: ADMIN Role (Full Access)
- ✅ Can access all features
- ✅ Can see all menu items
- ✅ Can access all API endpoints

## Security Considerations

### Defense in Depth
- **Backend**: `@Secured` annotations enforce role checks on API endpoints
- **Frontend**: UI hides unauthorized menu items (reduces attack surface)
- **Result**: Even if users guess URLs, backend returns 403 Forbidden

### Attack Vectors Mitigated
1. **Direct URL access**: Backend validates roles on every request
2. **API enumeration**: Unauthorized endpoints return 403 (not 200/404)
3. **JWT tampering**: Roles embedded in signed JWT tokens
4. **Privilege escalation**: Only ADMIN can assign CHAMPION/REQ roles

## Rollback Plan
If issues arise, rollback by:
1. Revert backend changes: `git revert <commit-hash>`
2. Revert frontend changes: `git revert <commit-hash>`
3. Redeploy previous version
4. All users regain previous access levels

## Documentation
- **Full Specification**: [spec.md](spec.md)
- **Quick Start Guide**: [quickstart.md](quickstart.md)
- **Change Log**: [CHANGELOG.md](CHANGELOG.md)

## Related Features
- **Feature 001**: Admin Role Management (RBAC foundation)
- **Feature 004**: VULN Role & Vulnerability Management UI
- **Feature 008**: Workgroup-Based Access Control
- **Feature 011**: Release-Based Requirement Version Management

---
**Status**: ✅ Implementation Complete  
**Date**: 2025-01-09  
**Author**: System  
**Reviewed**: Pending
