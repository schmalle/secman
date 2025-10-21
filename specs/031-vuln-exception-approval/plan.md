# Implementation Plan: Vulnerability Exception Request & Approval Workflow

**Branch**: `031-vuln-exception-approval` | **Date**: 2025-10-20 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/031-vuln-exception-approval/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

This feature implements a role-based exception request and approval workflow for overdue vulnerabilities. Regular users can request exceptions (PENDING status requiring approval), while ADMIN/SECCHAMPION users get auto-approved exceptions with audit trails. The system includes dedicated approval dashboards, real-time notification badges, comprehensive audit logging, and flexible exception scopes (single vulnerability or CVE pattern across assets).

**Technical Approach**: Extend existing vulnerability management system with new VulnerabilityExceptionRequest entity. Implement first-approver-wins concurrency control, server-side pagination (20/50/100 items), real-time badge updates via SSE/WebSocket with polling fallback, and comprehensive state transition logging for audit compliance.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, React 19, Bootstrap 5.3, Axios
**Storage**: MariaDB 11.4 via Hibernate JPA with auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Web application (desktop/tablet browsers, no mobile optimization)
**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)
**Performance Goals**:
- Exception request submission: <2 minutes user time
- Approval action: <1 minute per request
- Page loads: <3 seconds (up to 100 requests)
- Badge updates: <5 seconds after state change
**Constraints**:
- First-approver-wins atomic transactions (prevent duplicate approvals)
- Real-time updates required (SSE/WebSocket primary, 30s polling fallback)
- Permanent audit retention (no deletion of exception requests)
- Pagination required (20/50/100 items per page)
**Scale/Scope**:
- Expected load: 100-500 exception requests per month
- Concurrent approvers: 5-10 ADMIN/SECCHAMPION users
- Dashboard capacity: 100 pending requests displayable
- Single-page modals (no multi-step wizards)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅

- **File uploads**: Not applicable (no file uploads in this feature)
- **Input sanitization**: ✅ Exception reason field (50-2048 chars, plain text only, XSS prevention per Assumption 7)
- **RBAC enforcement**: ✅
  - API: @Secured annotations on all endpoints (FR-018, FR-032)
  - UI: Role-based rendering (ADMIN/SECCHAMPION see approval dashboard, all users see own requests)
  - Service layer: First-approver-wins prevents race conditions (FR-024b)
- **Sensitive data**: ✅ Exception reasons contain business justifications but no passwords/secrets (validated at input)
- **Token storage**: ✅ Existing JWT sessionStorage pattern (Dependency 4)

**Status**: PASS - All security requirements met

### II. Test-Driven Development (NON-NEGOTIABLE) ⚠️

- **Contract tests first**: Required for all new API endpoints
- **Integration tests**: Required for approval workflow, notification system
- **Unit tests**: Required for service layer business logic
- **TDD cycle**: Red-Green-Refactor enforced
- **Coverage target**: ≥80%

**Status**: REQUIRES USER REQUEST per Constitution IV - Test planning deferred until explicitly requested

### III. API-First ✅

- **RESTful design**: ✅ Standard REST patterns (POST for create, GET for list, DELETE for cancel)
- **OpenAPI documentation**: Required in Phase 1 (contracts/)
- **Consistent errors**: ✅ Standard HTTP codes (401/403/404/409 for conflicts)
- **Backward compatibility**: ✅ New endpoints only, no breaking changes to existing APIs

**Status**: PASS - API-first design followed

### IV. User-Requested Testing ✅

- **Test planning**: Will be marked OPTIONAL in tasks.md
- **Test frameworks**: JUnit 5 + MockK (backend), Playwright (frontend) available per TDD
- **User autonomy**: Tests only planned if user explicitly requests

**Status**: PASS - Compliant with user-driven testing principle

### V. Role-Based Access Control (RBAC) ✅

- **@Secured annotations**: ✅ All endpoints annotated (FR-018: ADMIN/SECCHAMPION only, others: IS_AUTHENTICATED)
- **Roles**: ✅ Using existing USER, ADMIN, SECCHAMPION, VULN roles
- **Frontend checks**: ✅ Role-based UI rendering (FR-030: approval menu visible to ADMIN/SECCHAMPION only)
- **Service layer**: ✅ Auto-approval logic checks roles (FR-005), first-approver-wins prevents privilege escalation (FR-024b)
- **Data filtering**: ✅ Users see only own requests (FR-010), ADMIN/SECCHAMPION see all (FR-018)

**Status**: PASS - Comprehensive RBAC enforcement

### VI. Schema Evolution ✅

- **Hibernate auto-migration**: ✅ New tables created via JPA annotations
- **Database constraints**: ✅ Foreign keys (User, Vulnerability), status enum, timestamps
- **Explicit relationships**: ✅ Many-to-one (Request → User, Vulnerability), one-to-one optional (Request → Exception)
- **Indexes**: Required for vulnerability_id, status, created_at, requester (high-cardinality queries)
- **No data loss**: ✅ New tables only, existing schema untouched

**Status**: PASS - Automated migration with proper constraints

**GATE RESULT**: ✅ PASS - All constitutional requirements met. No violations requiring justification.

## Project Structure

### Documentation (this feature)

```
specs/031-vuln-exception-approval/
├── spec.md              # Feature specification (completed)
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (to be generated)
├── data-model.md        # Phase 1 output (to be generated)
├── quickstart.md        # Phase 1 output (to be generated)
├── contracts/           # Phase 1 output (to be generated)
│   ├── exception-request-api.yaml
│   └── notification-events.yaml
├── checklists/
│   └── requirements.md  # Quality validation (completed)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
src/
├── backendng/
│   └── src/main/kotlin/com/secman/
│       ├── domain/
│       │   └── VulnerabilityExceptionRequest.kt          # NEW: Main request entity
│       ├── repository/
│       │   └── VulnerabilityExceptionRequestRepository.kt # NEW: JPA repository
│       ├── service/
│       │   ├── VulnerabilityExceptionRequestService.kt   # NEW: Business logic
│       │   ├── ExceptionRequestAuditService.kt           # NEW: Audit logging (FR-026b)
│       │   └── ExceptionRequestNotificationService.kt    # NEW: Email notifications (P3)
│       ├── controller/
│       │   └── VulnerabilityExceptionRequestController.kt # NEW: REST API
│       ├── dto/
│       │   ├── VulnerabilityExceptionRequestDto.kt       # NEW: Response DTO
│       │   ├── CreateExceptionRequestDto.kt              # NEW: Request DTO
│       │   └── ReviewExceptionRequestDto.kt              # NEW: Review DTO
│       └── websocket/
│           └── ExceptionBadgeUpdateHandler.kt            # NEW: Real-time badge (FR-031)
│
└── frontend/
    └── src/
        ├── components/
        │   ├── ExceptionRequestModal.tsx                  # NEW: Request form modal
        │   ├── ExceptionStatusBadge.tsx                   # NEW: Status badge component
        │   ├── MyExceptionRequests.tsx                    # NEW: User's request list
        │   ├── ExceptionApprovalDashboard.tsx             # NEW: ADMIN/SECCHAMPION dashboard
        │   ├── ExceptionRequestDetailModal.tsx            # NEW: Detail view modal
        │   └── CurrentVulnerabilitiesTable.tsx            # MODIFIED: Add "Request Exception" button
        ├── services/
        │   ├── exceptionRequestService.ts                 # NEW: API client
        │   └── exceptionBadgeService.ts                   # NEW: SSE/WebSocket client (FR-031)
        ├── pages/
        │   ├── my-exception-requests.astro                # NEW: User request page
        │   └── exception-approvals.astro                  # NEW: Approval dashboard page
        └── hooks/
            └── useExceptionBadgeCount.ts                  # NEW: Real-time badge hook

tests/
├── backendng/
│   └── src/test/kotlin/com/secman/
│       ├── contract/
│       │   └── VulnerabilityExceptionRequestContractTest.kt # NEW: API contract tests
│       ├── service/
│       │   ├── VulnerabilityExceptionRequestServiceTest.kt  # NEW: Service unit tests
│       │   └── ExceptionRequestAuditServiceTest.kt          # NEW: Audit logging tests
│       └── integration/
│           └── ExceptionRequestConcurrencyTest.kt           # NEW: First-approver-wins test
└── frontend/
    └── tests/e2e/
        ├── exception-request-workflow.spec.ts           # NEW: End-to-end workflow test
        └── exception-approval-dashboard.spec.ts         # NEW: Approval dashboard E2E
```

**Structure Decision**: Web application structure (Option 2) selected. Feature adds new entities, services, and controllers to existing backend (`src/backendng/`), and new React components/pages to existing frontend (`src/frontend/`). No new projects required. Real-time updates add WebSocket handler to backend and SSE/WebSocket service to frontend.

## Complexity Tracking

*No constitutional violations requiring justification.*

**Architecture Decisions**:

1. **First-Approver-Wins Concurrency**: Database-level optimistic locking with version field on VulnerabilityExceptionRequest prevents race conditions. Simpler than pessimistic locks (no timeout management) and aligns with web-scale patterns.

2. **Real-Time Badge Updates**: SSE (Server-Sent Events) primary, WebSocket as alternative, 30-second polling fallback. More complex than polling-only but provides better UX (SC-008: <5 second updates). Simpler than full bidirectional WebSocket (badge updates are unidirectional).

3. **Separate Request Entity**: VulnerabilityExceptionRequest distinct from VulnerabilityException enables workflow tracking (PENDING → APPROVED/REJECTED) and audit trail. Alternative of status flags on VulnerabilityException rejected because it conflates technical exceptions with approval workflow.

4. **Server-Side Pagination**: Required for scalability beyond 100 requests. Client-side pagination rejected due to memory constraints and network efficiency. Selected page sizes (20/50/100) align with existing patterns (Feature 012: releases, Feature 004: vulnerabilities).

**No Violations**: All decisions align with constitutional principles. No complexity justification required.
