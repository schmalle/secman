# Research: Default User Roles on Creation

**Feature**: 080-default-user-roles
**Date**: 2026-02-12

## Research Summary

This feature requires no external research. All unknowns were resolved through codebase analysis.

## Findings

### Decision 1: User creation paths requiring changes

- **Decision**: Two creation paths need modification: OIDC (OAuthService.kt) and manual admin creation (UserController.kt)
- **Rationale**: Codebase analysis confirms these are the only two paths that create User entities. MCP uses delegation to existing users and does not create new accounts.
- **Alternatives considered**: Centralizing default roles into a shared constant or config — rejected as over-engineering for a 2-location change. If a third creation path is added in the future, a constant can be extracted then.

### Decision 2: Role name mapping

- **Decision**: "USERS" from the user request maps to the `User.Role.USER` enum value
- **Rationale**: The Role enum defines `USER` (not `USERS`). This is the basic authenticated user role. No other role matches the intent.
- **Alternatives considered**: None — unambiguous mapping.

### Decision 3: No schema migration needed

- **Decision**: No Flyway migration or schema change required
- **Rationale**: The `user_roles` table stores role values as strings via `@Enumerated(EnumType.STRING)`. The REQ enum value already exists and is used by other users. Adding REQ to default role sets only changes what gets inserted at creation time.
- **Alternatives considered**: None — the schema already supports this.

### Decision 4: Audit log string update

- **Decision**: Update the hardcoded audit string in OAuthService.kt from "USER,VULN" to "USER,VULN,REQ"
- **Rationale**: The `auditRoleAssignment` call logs which roles were assigned. It must reflect the new defaults for FR-006 compliance.
- **Alternatives considered**: Dynamically generating the audit string from the roles set — reasonable but out of scope for this change.
