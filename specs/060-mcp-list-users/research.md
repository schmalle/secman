# Research: MCP List Users Tool

**Feature**: 060-mcp-list-users
**Date**: 2026-01-04

## Research Summary

No significant unknowns - feature follows established patterns in the codebase.

## Decisions

### 1. Admin-Only Access Pattern

**Decision**: Use `context.isAdmin` check in tool execute() method

**Rationale**: This is the established pattern used by `DeleteAllRequirementsTool.kt` (line 51). The `isAdmin` flag in `McpExecutionContext` is populated from the delegated user's roles and is the canonical way to check admin status.

**Alternatives Considered**:
- Check for ADMIN role in `delegatedUserRoles` set - More verbose, less semantic
- Add new permission `USERS_READ` - Over-engineering for admin-only feature

### 2. User Delegation Requirement

**Decision**: Require User Delegation (return error if `!context.hasDelegation()`)

**Rationale**: Per spec FR-002 and FR-004, the tool cannot verify admin role without knowing who the actual user is. Service account mode (no delegation) cannot be trusted for admin operations.

**Alternatives Considered**:
- Allow service accounts with special permission - Would bypass RBAC principle
- Use API key's owner as implicit user - Not how MCP delegation is designed

### 3. Response Data Structure

**Decision**: Inline map in tool response (no separate DTO)

**Rationale**: Simple data projection with 8 fields. Following pattern of `GetRequirementsTool.kt` which returns inline maps. Creating a separate DTO adds unnecessary complexity for a read-only response.

**Alternatives Considered**:
- Create `UserListDto` class - Over-engineering for this use case
- Reuse existing `User` entity - Would expose password hash

### 4. User Data Retrieval

**Decision**: Use `UserRepository.findAll()` (inherited from JpaRepository)

**Rationale**: Standard JPA method, well-tested, returns all entities. No filtering needed since this is admin-only.

**Alternatives Considered**:
- Custom query with projection - Unnecessary complexity
- Pagination support - Explicitly rejected in clarification (no pagination)

### 5. Tool Registration

**Decision**: Add `list_users` to McpToolRegistry with new `USERS_READ` permission (or leverage `USER_ACTIVITY` existing permission)

**Rationale**: Need to decide on permission. Looking at existing permissions, `USER_ACTIVITY` already exists and `requiresAdmin()` returns true. However, semantically `USER_ACTIVITY` is for monitoring sessions, not listing users.

**Final Decision**: Use `USER_ACTIVITY` permission since it already requires admin and is conceptually related to user management. Adding a new permission would require database migration for existing API keys.

**Alternatives Considered**:
- New `USERS_READ` permission - Requires enum change and migration
- No permission check (rely only on isAdmin) - Inconsistent with other tools

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Micronaut | 4.10 | Framework (existing) |
| JpaRepository | - | findAll() method (existing) |
| McpExecutionContext | - | hasDelegation(), isAdmin (existing) |

## Integration Points

| Integration | Pattern | Notes |
|-------------|---------|-------|
| McpToolRegistry | Constructor injection + registration | Add to tools list |
| UserRepository | Constructor injection | Use findAll() |
| McpExecutionContext | Parameter to execute() | Check delegation and admin |

## No Unknowns Remaining

All technical decisions made based on existing patterns in codebase.
