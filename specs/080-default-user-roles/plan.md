# Implementation Plan: Default User Roles on Creation

**Branch**: `080-default-user-roles` | **Date**: 2026-02-12 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/080-default-user-roles/spec.md`

## Summary

Change the default role set assigned to newly created users from {USER, VULN} (OIDC) / {USER} (manual) to {USER, VULN, REQ} across all creation paths. This is a two-location code change in the backend with no schema, API contract, or frontend modifications.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA
**Storage**: MariaDB 11.4 (existing `user_roles` table, no schema changes)
**Testing**: JUnit 5, Mockk (per constitution: tests only when user-requested)
**Target Platform**: Linux server (JVM)
**Project Type**: Web application (backend-only change)
**Performance Goals**: N/A (no performance impact - role assignment at user creation time)
**Constraints**: None - trivial change to default values
**Scale/Scope**: 2 files modified, ~4 lines changed

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | Expanding default permissions is an intentional policy decision per user request. No new attack surface. RBAC enforcement unchanged. |
| III. API-First | PASS | No API contract changes. Existing endpoints behave identically. |
| IV. User-Requested Testing | PASS | No tests planned unless user requests them. |
| V. RBAC | PASS | Role assignments still enforced via @Secured annotations. Adding REQ to defaults grants requirements access consistently. |
| VI. Schema Evolution | PASS | No schema changes. `user_roles` table already supports all Role enum values. No Flyway migration needed. |

**Gate result**: ALL PASS. No violations to justify.

## Project Structure

### Documentation (this feature)

```text
specs/080-default-user-roles/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── checklists/
    └── requirements.md  # Spec quality checklist
```

### Source Code (files to modify)

```text
src/backendng/src/main/kotlin/com/secman/
├── service/
│   └── OAuthService.kt          # Line ~969: OIDC default roles
└── controller/
    └── UserController.kt         # Lines ~137-138: Manual creation default roles
```

**Structure Decision**: Backend-only change. Two existing files modified in the existing project structure. No new files, no frontend changes, no new directories.

## Complexity Tracking

> No constitution violations. Table not needed.

## Changes Detail

### Change 1: OAuthService.kt — OIDC user creation (FR-001, FR-002)

**File**: `src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt`
**Method**: `createNewOidcUser()` (~line 969)

**Current**:
```kotlin
roles = mutableSetOf(User.Role.USER, User.Role.VULN), // FR-001, FR-002: Default roles
```

**Target**:
```kotlin
roles = mutableSetOf(User.Role.USER, User.Role.VULN, User.Role.REQ), // Default roles: USER, VULN, REQ
```

Also update the audit logging call (~line 978):
```kotlin
// Current:
auditRoleAssignment(savedUser, "USER,VULN", idpName)
// Target:
auditRoleAssignment(savedUser, "USER,VULN,REQ", idpName)
```

### Change 2: UserController.kt — Manual user creation (FR-001, FR-003)

**File**: `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt`
**Method**: `create()` (~lines 137-138)

**Current**:
```kotlin
if (roles.isEmpty()) {
    roles.add(User.Role.USER)
}
```

**Target**:
```kotlin
if (roles.isEmpty()) {
    roles.addAll(setOf(User.Role.USER, User.Role.VULN, User.Role.REQ))
}
```

### Requirement Traceability

| Requirement | Implementation |
|-------------|---------------|
| FR-001: Default roles USER, VULN, REQ | Both Change 1 and Change 2 |
| FR-002: OIDC creation path | Change 1 (OAuthService.kt) |
| FR-003: Manual creation path | Change 2 (UserController.kt) |
| FR-004: Admin explicit roles override | Already works - `roles.isEmpty()` check in UserController.kt |
| FR-005: Existing users unaffected | No code touches existing user lookup/re-auth paths |
| FR-006: Audit logging | Change 1 updates audit string; Change 2 already logs via admin notification |
