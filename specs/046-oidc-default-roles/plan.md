# Implementation Plan: OIDC Default Roles

**Branch**: `046-oidc-default-roles` | **Date**: 2025-11-14 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/046-oidc-default-roles/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Automatically assign USER and VULN roles to all newly created users during OIDC auto-provisioning. This enhancement builds on Feature 041 (Identity Provider OIDC/SAML support) by ensuring new users have immediate access to view assets and vulnerabilities without manual administrator intervention. The implementation includes transaction atomicity, audit logging, and email notifications to administrators.

**Technical Approach**: Modify the existing OAuth callback handler in `OAuthService` to inject default roles (USER, VULN) into the user entity during auto-provisioning. Wrap user creation and role assignment in a database transaction to ensure atomicity. Add audit logging via SLF4J and async email notifications via `EmailSender` service.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, JavaMail API (SMTP)
**Storage**: MariaDB 12 with Hibernate auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (E2E)
**Target Platform**: JVM server (backend), Modern browsers (frontend)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: <5 seconds total time from OIDC authentication to asset/vulnerability access
**Constraints**: Transaction must complete <200ms; email delivery must not block user creation
**Scale/Scope**: Affects all new OIDC user creations; existing 5 roles (USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Principle I: Security-First ✅ PASS
- **File uploads**: N/A - No file uploads in this feature
- **Input sanitization**: OIDC claims validated by existing Feature 041 implementation
- **RBAC enforcement**: Notification endpoint requires ADMIN role; default roles (USER, VULN) correctly assigned
- **Sensitive data**: No sensitive data logged (only user identifier, roles, timestamp)
- **Authentication tokens**: Uses existing sessionStorage JWT mechanism (no changes)
- **Justification**: Feature enhances security posture by ensuring consistent role assignment

### Principle II: Test-Driven Development ⚠️ DEFERRED
- **Contract tests**: Will be written first for role assignment logic
- **Integration tests**: Will be written for OIDC callback + role assignment flow
- **Unit tests**: Will be written for business logic in service layer
- **TDD cycle**: Red-Green-Refactor enforced
- **Coverage target**: ≥80%
- **Status**: Test planning deferred per Principle IV (User-Requested Testing)
- **Note**: Test infrastructure ready (JUnit 5, MockK, Playwright); test cases written only when user explicitly requests

### Principle III: API-First ✅ PASS
- **RESTful design**: No new API endpoints; modification to existing OAuth callback
- **OpenAPI docs**: Will update existing `/oauth/callback` documentation
- **Backward compatibility**: No breaking changes; purely additive behavior (default roles)
- **Error formats**: Uses existing Micronaut exception handling
- **HTTP status codes**: Existing 200 (success), 401 (unauthorized), 500 (server error)
- **Justification**: Enhancement is transparent to API consumers

### Principle IV: User-Requested Testing ✅ PASS
- **Proactive testing**: NOT preparing test cases unless explicitly requested
- **Test tasks**: Will mark as OPTIONAL in tasks.md when generated
- **Testing frameworks**: JUnit 5, MockK, Playwright remain required per TDD
- **Status**: Compliant - awaiting user request for test planning

### Principle V: RBAC ✅ PASS
- **@Secured annotations**: Notification endpoint uses @Secured("ADMIN")
- **Roles**: Uses existing USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION
- **Frontend checks**: No frontend changes required
- **Workgroup filtering**: Not applicable - role assignment happens at user creation
- **Service layer**: Authorization checks in OAuthService (auto-provisioning enabled check)
- **Justification**: Correctly implements RBAC for new feature behavior

### Principle VI: Schema Evolution ✅ PASS
- **Auto-migration**: No schema changes required (uses existing User.roles collection)
- **Database constraints**: Existing @ManyToMany relationship between User and Role
- **Foreign keys**: Not applicable
- **Indexes**: Not required (no new queries)
- **Migration testing**: Not applicable
- **Data loss**: Zero risk (purely additive behavior)
- **Justification**: Leverages existing schema; no migration needed

**Overall Gate Status**: ✅ **PASS** (1 principle deferred per constitution)

**Violations Requiring Justification**: None

---

## Post-Phase 1 Constitution Re-check

*Date*: 2025-11-14
*Phase*: After design & contracts completion

### Re-evaluation Results

All principles remain compliant after Phase 1 design:

- ✅ **Security-First**: Email template uses HTML sanitization; no XSS vectors; RBAC maintained
- ⚠️ **TDD**: Deferred per Principle IV (awaiting user request for test planning)
- ✅ **API-First**: OAuth callback contract documented in OpenAPI 3.0; backward compatible
- ✅ **User-Requested Testing**: Compliant - no proactive test case generation
- ✅ **RBAC**: Admin notification restricted to ADMIN role users; default roles correctly assigned
- ✅ **Schema Evolution**: Confirmed no schema changes required (uses existing User.roles)

**New Risks Identified**: None

**Mitigation Actions Required**: None

**Gate Status**: ✅ **CONFIRMED PASS** - Ready for Phase 2 (/speckit.tasks)

## Project Structure

### Documentation (this feature)

```text
specs/046-oidc-default-roles/
├── spec.md              # Feature specification (/speckit.specify output)
├── plan.md              # This file (/speckit.plan output)
├── research.md          # Phase 0 output (generated below)
├── data-model.md        # Phase 1 output (generated below)
├── quickstart.md        # Phase 1 output (generated below)
├── contracts/           # Phase 1 output (generated below)
│   └── oauth-callback.yaml
├── checklists/          # Quality validation
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT YET CREATED)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   ├── User.kt                     # Existing entity (roles: MutableSet<String>)
│   │   └── IdentityProvider.kt         # Existing entity (autoProvision: Boolean)
│   ├── service/
│   │   ├── OAuthService.kt             # MODIFY: Add default role assignment
│   │   ├── UserService.kt              # MODIFY: Add audit logging for role events
│   │   └── EmailSender.kt              # USE: Send admin notifications
│   ├── controller/
│   │   └── OAuthController.kt          # Existing: /oauth/callback endpoint
│   └── repository/
│       ├── UserRepository.kt           # Existing
│       └── IdentityProviderRepository.kt # Existing
└── src/test/kotlin/com/secman/
    ├── service/
    │   ├── OAuthServiceTest.kt         # ADD: Unit tests for role assignment
    │   └── UserServiceTest.kt          # MODIFY: Add audit logging tests
    └── integration/
        └── OAuthIntegrationTest.kt     # ADD: E2E test for OIDC + roles

src/frontend/
├── src/
│   ├── services/api/
│   │   └── auth.ts                     # No changes required
│   └── pages/
│       └── login.astro                 # No changes required
└── tests/
    └── e2e/
        └── oidc-login.spec.ts          # ADD: Playwright test for first-time login

src/backendng/src/main/resources/
└── email-templates/
    └── admin-new-user.html             # ADD: Email template for admin notifications
```

**Structure Decision**: Web application structure with Kotlin/Micronaut backend and Astro/React frontend. Modifications concentrated in backend OAuth service layer. No frontend changes required - feature is transparent to users (roles applied automatically). Email template added for administrator notifications.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations requiring justification. All constitutional principles satisfied.
