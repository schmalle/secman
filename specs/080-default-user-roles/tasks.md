# Tasks: Default User Roles on Creation

**Feature**: 080-default-user-roles
**Date**: 2026-02-12

## Phase 1: Core Implementation

- [x] **T-001**: Update OIDC default roles in OAuthService.kt — Add `User.Role.REQ` to the default role set in `createNewOidcUser()` and update the audit log string from "USER,VULN" to "USER,VULN,REQ"
- [x] **T-002**: Update manual creation default roles in UserController.kt — Change the empty-roles default from `{USER}` to `{USER, VULN, REQ}` in the `create()` method

## Phase 2: Validation

- [x] **T-003**: Build verification — Run `./gradlew build` to confirm compilation and existing tests pass
