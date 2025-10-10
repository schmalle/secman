# Feature 014 Changelog

## [2025-01-09] - CHAMPION and REQ Roles Implementation

### Added
- Two new user roles: `CHAMPION` and `REQ`
- Role-based access control for Norm Management (ADMIN, CHAMPION, REQ only)
- Role-based access control for Standard Management (ADMIN, CHAMPION, REQ only)  
- Role-based access control for UseCase Management (ADMIN, CHAMPION, REQ only)
- Role-based access control for Releases (ADMIN only)
- Role-based access control for Compare Releases (ADMIN only)
- Six new permission utility functions in `permissions.ts`
- Conditional rendering of navigation menu items based on user roles
- Comprehensive specification document in `specs/014-add-champion-req-roles/`

### Changed
- `User.kt` Role enum: Added CHAMPION and REQ
- `NormController.kt`: Changed from IS_AUTHENTICATED to role-specific security
- `StandardController.kt`: Changed from IS_AUTHENTICATED to role-specific security
- `UseCaseController.kt`: Changed from IS_AUTHENTICATED to role-specific security
- `ReleaseController.kt`: Changed from IS_AUTHENTICATED to ADMIN-only security
- `Sidebar.tsx`: Implemented conditional menu item rendering based on roles
- `permissions.ts`: Extended with new role check and access control functions

### Security Improvements
- Defense in depth: Both backend and frontend enforce role-based restrictions
- Direct URL access protection via backend `@Secured` annotations
- UI menu items hidden for unauthorized roles (reduces attack surface)
- All API endpoints now explicitly declare required roles

### Breaking Changes
- **Users with only USER role**: Will no longer see Norm/Standard/UseCase/Releases menu items
- **Non-ADMIN users**: Will no longer see Releases and Compare Releases menu items
- **API access**: Endpoints `/api/norms`, `/api/standards`, `/api/usecases`, `/api/releases` now return 403 Forbidden for unauthorized roles

### Migration Notes
- No database migration required (roles stored as strings in existing table)
- Existing users retain their current roles
- New CHAMPION and REQ roles can be assigned immediately via User Management UI
- Recommend assigning CHAMPION or REQ roles to requirement managers before deployment

### Testing
- ✅ Backend compilation successful
- ✅ Frontend build successful
- ⏳ Manual testing pending (post-deployment)
- ⏳ E2E tests pending (optional - existing tests cover admin scenarios)

### Files Modified (10 files)
**Backend (5 files)**:
1. src/backendng/src/main/kotlin/com/secman/domain/User.kt
2. src/backendng/src/main/kotlin/com/secman/controller/NormController.kt
3. src/backendng/src/main/kotlin/com/secman/controller/StandardController.kt
4. src/backendng/src/main/kotlin/com/secman/controller/UseCaseController.kt
5. src/backendng/src/main/kotlin/com/secman/controller/ReleaseController.kt

**Frontend (2 files)**:
6. src/frontend/src/utils/permissions.ts
7. src/frontend/src/components/Sidebar.tsx

**Documentation (3 files)**:
8. specs/014-add-champion-req-roles/spec.md
9. specs/014-add-champion-req-roles/quickstart.md
10. specs/014-add-champion-req-roles/CHANGELOG.md

### Lines Changed
- Backend: 5 lines changed (1 line per file)
- Frontend: ~130 lines added (permissions.ts: ~100 lines, Sidebar.tsx: ~30 lines)
- Documentation: ~350 lines (spec.md: ~220 lines, quickstart.md: ~90 lines, CHANGELOG.md: ~90 lines)
- **Total**: ~485 lines

### Related Issues
- None (proactive security enhancement)

### References
- Feature 001: Admin Role Management (foundation)
- Feature 004: VULN Role & Vulnerability Management UI
- Feature 011: Release-Based Requirement Version Management

---
**Implementation Status**: ✅ Complete  
**Deployment Status**: ⏳ Pending  
**Testing Status**: ⏳ Pending
