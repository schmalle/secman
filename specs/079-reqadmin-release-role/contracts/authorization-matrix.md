# Authorization Contracts: Release Management

**Feature**: 079-reqadmin-release-role
**Date**: 2026-02-06

## REST API Endpoint Authorization

### Changed Endpoints

| Endpoint | Method | Before | After |
|----------|--------|--------|-------|
| `/api/releases` | POST | `@Secured("ADMIN", "RELEASE_MANAGER")` | `@Secured("ADMIN", "REQADMIN")` |
| `/api/releases/{id}` | DELETE | `@Secured("ADMIN", "RELEASE_MANAGER")` | `@Secured("ADMIN", "REQADMIN")` |

### Unchanged Endpoints

| Endpoint | Method | Authorization |
|----------|--------|---------------|
| `/api/releases` | GET | `@Secured(IS_AUTHENTICATED)` |
| `/api/releases/{id}` | GET | `@Secured(IS_AUTHENTICATED)` |
| `/api/releases/{id}/status` | PUT | `@Secured("ADMIN", "RELEASE_MANAGER")` |
| `/api/releases/{id}/requirements` | GET | `@Secured(IS_AUTHENTICATED)` |

## MCP Tool Authorization

### Changed Tools

| Tool | Before | After |
|------|--------|-------|
| `create_release` | ADMIN or RELEASE_MANAGER + Delegation | ADMIN or REQADMIN + Delegation |
| `delete_release` | ADMIN or RELEASE_MANAGER + Delegation | ADMIN or REQADMIN + Delegation |

### Unchanged Tools

| Tool | Authorization |
|------|---------------|
| `list_releases` | ADMIN or RELEASE_MANAGER + Delegation |
| `get_release` | ADMIN or RELEASE_MANAGER + Delegation |
| `set_release_status` | ADMIN or RELEASE_MANAGER + Delegation |
| `compare_releases` | ADMIN or RELEASE_MANAGER + Delegation |

## Frontend Permission Functions

### Changed Functions

| Function | Before | After |
|----------|--------|-------|
| `canCreateRelease(roles)` | `isAdmin(roles) \|\| isReleaseManager(roles)` | `isAdmin(roles) \|\| isReqAdmin(roles)` |
| `canDeleteRelease(release, user, roles)` | ADMIN (any) or RELEASE_MANAGER (own) | ADMIN (any) or REQADMIN (own) |

### New Function

| Function | Logic |
|----------|-------|
| `isReqAdmin(roles)` | `roles.includes('REQADMIN')` |

### Unchanged Functions

| Function | Logic |
|----------|-------|
| `canUpdateReleaseStatus(release, user, roles)` | ADMIN (any) or RELEASE_MANAGER (own) |
| `canViewReleases(roles)` | Always true |
| `canAccessReleases(roles)` | hasReqAccess (ADMIN, REQ, SECCHAMPION) |

## Error Responses

No changes to error response format. Unauthorized requests continue to return:
- REST: HTTP 403 Forbidden
- MCP: `AUTHORIZATION_ERROR` with descriptive message
