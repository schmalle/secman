# Data Model: Default User Roles on Creation

**Feature**: 080-default-user-roles
**Date**: 2026-02-12

## No Data Model Changes

This feature modifies default values at the application layer only. No database schema, entity, or relationship changes are needed.

### Existing Entities (unchanged)

**User** (`users` table)
- id: Long (PK)
- username: String (unique)
- email: String (unique)
- passwordHash: String
- roles: Set<Role> (ElementCollection → `user_roles` join table, EnumType.STRING)
- authSource: AuthSource (LOCAL, OAUTH, HYBRID)

**Role** (enum, stored as strings in `user_roles`)
- USER, ADMIN, VULN, RELEASE_MANAGER, REQ, RISK, SECCHAMPION, REQADMIN

### What Changes

| Aspect | Before | After |
|--------|--------|-------|
| OIDC user default roles | USER, VULN | USER, VULN, REQ |
| Manual user default roles (no roles specified) | USER | USER, VULN, REQ |
| Manual user with explicit roles | As specified | As specified (unchanged) |
| Existing users | Unaffected | Unaffected |
