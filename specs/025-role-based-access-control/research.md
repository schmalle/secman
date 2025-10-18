# Research Findings: Role-Based Access Control - RISK, REQ, and SECCHAMPION Roles

**Feature**: 025-role-based-access-control | **Date**: 2025-10-18
**Related**: [spec.md](./spec.md) | [plan.md](./plan.md)

## Overview

This document contains research findings for 5 key technical decisions identified in the implementation plan. Each decision is backed by codebase analysis, architectural considerations, and constitutional compliance verification.

---

## Decision 1: Role Enum Migration Strategy

**Question**: How should we migrate the existing CHAMPION role to SECCHAMPION while maintaining backward compatibility?

### Research Findings

**Current State Analysis**:
- Examined `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/domain/User.kt`
- Current `User.Role` enum values: `USER, ADMIN, VULN, RELEASE_MANAGER, CHAMPION, REQ`
- REQ role already exists (no change needed)
- CHAMPION role exists and needs renaming to SECCHAMPION
- RISK role needs to be added

**Database Storage**:
```kotlin
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")])
@Enumerated(EnumType.STRING)
@Column(name = "role_name")
var roles: MutableSet<Role> = mutableSetOf(Role.USER)
```

Roles are stored as STRING values in the `user_roles` table, making migration more complex than numeric enums.

**Migration Options Evaluated**:

1. **Option A: Direct Rename + SQL Migration Script** (SELECTED)
   - Update enum: `CHAMPION` → `SECCHAMPION`
   - SQL script: `UPDATE user_roles SET role_name = 'SECCHAMPION' WHERE role_name = 'CHAMPION'`
   - Pros: Clean migration, no duplicate roles, single source of truth
   - Cons: Requires SQL migration script, brief service interruption
   - Constitutional Compliance: ✅ Schema Evolution (automated migration via Hibernate)

2. **Option B: Add SECCHAMPION, Deprecate CHAMPION**
   - Keep both enum values temporarily
   - Add `@Deprecated` annotation to CHAMPION
   - Pros: Zero-downtime migration, gradual transition
   - Cons: Duplicate permissions, increased complexity, confusing RBAC matrix
   - Constitutional Compliance: ⚠️ Violates RBAC principle (duplicate role semantics)

3. **Option C: Hibernate Auto-Migration Only**
   - Rely on Hibernate ddl-auto to handle enum changes
   - Pros: No manual SQL scripts
   - Cons: **Does NOT work for existing data** - Hibernate won't update enum string values in existing rows
   - Constitutional Compliance: ❌ Violates Schema Evolution (data loss risk)

**Decision: Option A - Direct Rename + SQL Migration Script**

**Rationale**:
- Aligns with Constitution Principle VI (Schema Evolution): "Hibernate auto-migration MUST be used with appropriate constraints"
- Prevents duplicate role semantics (Constitution Principle V - RBAC)
- Existing enum STRING storage requires explicit data migration
- One-time migration script can be executed during deployment window

**Implementation Details**:
```kotlin
// User.kt - Updated enum
enum class Role {
    USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION, REQ, RISK
}
```

```sql
-- Migration script: V1__rename_champion_to_secchampion.sql
UPDATE user_roles SET role_name = 'SECCHAMPION' WHERE role_name = 'CHAMPION';
```

**Testing Strategy**:
- Unit test: Verify enum contains SECCHAMPION, not CHAMPION
- Integration test: Create user with SECCHAMPION role, verify persistence
- Migration test: Insert CHAMPION role, run migration, verify converted to SECCHAMPION

**Risk Mitigation**:
- Backup database before migration
- Test migration on dev/staging environments first
- Monitor for any hardcoded "CHAMPION" string references in codebase

---

## Decision 2: Access Denial Logging Implementation

**Question**: What logging approach should we use for access denials - dedicated audit table or structured logging?

### Research Findings

**Current Logging Patterns Analysis**:
- Examined existing controllers: All use SLF4J `LoggerFactory.getLogger()`
- Pattern: `private val log = LoggerFactory.getLogger(ClassName::class.java)`
- Example from VulnerabilityManagementController.kt:
```kotlin
private val log = LoggerFactory.getLogger(VulnerabilityManagementController::class.java)
log.error("Error fetching current vulnerabilities for user: {}", authentication.name, e)
```

**Logging Infrastructure**:
- Framework: Micronaut 4.4 with built-in SLF4J support
- Log aggregation: Assumed to be external (not found in codebase)
- No existing MDC usage found in controllers (searched for "MDC")

**Options Evaluated**:

1. **Option A: SLF4J with MDC Context** (SELECTED)
   - Use `org.slf4j.MDC` to add context to log entries
   - Log level: WARN for access denials
   - Pros: Consistent with existing logging, easy aggregation, no schema changes
   - Cons: Requires log aggregation setup for querying/reporting
   - Constitutional Compliance: ✅ No new infrastructure, fits API-First principle

2. **Option B: Dedicated Audit Table**
   - Create `AccessDenialLog` entity with JPA
   - Store: userId, roles, resource, timestamp, ipAddress
   - Pros: Queryable via SQL, long-term retention, relational analysis
   - Cons: Database overhead, schema coupling, overkill for read-only audit
   - Constitutional Compliance: ⚠️ Schema Evolution burden (new table + indexes)

3. **Option C: Event Publishing + Async Listener**
   - Publish `AccessDeniedEvent` using Micronaut events
   - Listener logs to file/external service
   - Pros: Decoupled, extensible
   - Cons: Over-engineered for simple logging, added complexity
   - Constitutional Compliance: ⚠️ TDD burden (event infrastructure tests)

**Decision: Option A - SLF4J with MDC Context**

**Rationale**:
- Aligns with existing logging patterns (LoggerFactory in all controllers)
- Supports Constitution Principle III (API-First): No database schema changes
- Structured logging enables aggregation in Splunk/ELK/Datadog
- Satisfies FR-014: "Log all denials with full context"
- Log level WARN is appropriate for security events (not ERROR - system functioning correctly)

**Implementation Details**:

```kotlin
package com.secman.service

import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.slf4j.MDC

@Singleton
class AccessDenialLogger {
    private val log = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT")

    fun logAccessDenial(
        authentication: Authentication,
        resource: String,
        requiredRoles: List<String>
    ) {
        try {
            // Add contextual information to MDC
            MDC.put("user_id", authentication.name)
            MDC.put("user_roles", authentication.roles.joinToString(","))
            MDC.put("resource", resource)
            MDC.put("required_roles", requiredRoles.joinToString(","))
            MDC.put("event_type", "access_denied")

            log.warn(
                "Access denied: user={}, roles=[{}], resource={}, required=[{}]",
                authentication.name,
                authentication.roles.joinToString(","),
                resource,
                requiredRoles.joinToString(",")
            )
        } finally {
            MDC.clear()
        }
    }
}
```

**Log Format Example**:
```
2025-10-18 14:23:45.123 WARN [ACCESS_DENIAL_AUDIT] Access denied: user=john.doe, roles=[USER,REQ], resource=/api/risk-assessments, required=[ADMIN,RISK,SECCHAMPION] user_id=john.doe user_roles=USER,REQ resource=/api/risk-assessments required_roles=ADMIN,RISK,SECCHAMPION event_type=access_denied
```

**Testing Strategy**:
- Unit test: Verify MDC context is set and cleared
- Integration test: Trigger 403 error, verify log entry in test appender
- Contract test: Ensure all @Secured endpoints log denials

---

## Decision 3: Per-Request Role Validation Approach

**Question**: How do we enforce per-request role checking without session-level caching?

### Research Findings

**Micronaut Security Architecture Analysis**:
- Framework: Micronaut 4.4 with built-in security
- Current pattern: `@Secured(SecurityRule.IS_AUTHENTICATED)` on controllers
- Example from RequirementController.kt:
```kotlin
@Controller("/api/requirements")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class RequirementController(...)
```

**Authentication Flow**:
1. JWT token sent in `Authorization: Bearer <token>` header
2. Micronaut `JwtAuthenticationProvider` validates token
3. `Authentication` object created with user details + roles
4. `@Secured` annotation checked **per request** by `SecurityFilter`
5. No caching found in codebase

**Options Evaluated**:

1. **Option A: Micronaut @Secured Annotations** (SELECTED)
   - Update existing `@Secured` annotations with new roles
   - Micronaut SecurityFilter validates on every request
   - Pros: Built-in, no custom code, per-request enforcement guaranteed
   - Cons: None (framework-standard approach)
   - Constitutional Compliance: ✅ RBAC (Principle V), no new infrastructure

2. **Option B: Custom SecurityInterceptor**
   - Implement `HttpServerFilter` with manual role checks
   - Intercept requests, validate roles before controller execution
   - Pros: Full control over validation logic
   - Cons: Reinvents framework capabilities, more test burden
   - Constitutional Compliance: ⚠️ TDD burden (custom interceptor tests)

3. **Option C: Service-Layer Role Checks**
   - Move role validation to service methods
   - Controllers call services, services check `authentication.roles`
   - Pros: Fine-grained control
   - Cons: Repetitive code, easy to miss, not controller-level security
   - Constitutional Compliance: ❌ Violates RBAC principle (not enforced at API layer)

**Decision: Option A - Micronaut @Secured Annotations**

**Rationale**:
- Micronaut SecurityFilter executes on **every request** (no caching)
- Satisfies FR-008 and Clarification #3: "Role check happens on every request, no caching"
- Aligns with Constitution Principle V (RBAC): "@Secured on endpoints"
- Existing pattern in codebase (VulnerabilityManagementController uses `@Secured("ADMIN", "VULN")`)
- No session-level caching found in Micronaut 4.4 security implementation

**Implementation Details**:

```kotlin
// Risk Management endpoints
@Controller("/api/risk-assessments")
@Secured("ADMIN", "RISK", "SECCHAMPION")
open class RiskAssessmentController(...)

// Requirements endpoints
@Controller("/api/requirements")
@Secured("ADMIN", "REQ", "SECCHAMPION")
open class RequirementController(...)

// Vulnerabilities endpoints (update existing)
@Controller
@Secured("ADMIN", "VULN", "SECCHAMPION")
open class VulnerabilityManagementController(...)
```

**Verification**:
- Micronaut SecurityFilter source code inspection confirms per-request execution
- JWT tokens do NOT cache roles in session - roles extracted from token on each request
- Role changes in database reflected on next request (when new JWT issued or existing JWT re-validated)

**Testing Strategy**:
- Contract test: Send request with insufficient role, verify 403
- Integration test: Update user role mid-session, verify next request reflects change
- Load test: Verify no performance degradation from per-request checks

---

## Decision 4: Frontend Role Checking Strategy

**Question**: How should the frontend obtain and check user roles for navigation visibility?

### Research Findings

**Current Frontend Architecture Analysis**:
- Examined `/Users/flake/sources/misc/secman/src/frontend/src/components/Sidebar.tsx`
- Current pattern:
```typescript
const [isAdmin, setIsAdmin] = useState(false);
const user = (window as any).currentUser;
const roles = user?.roles || [];
const hasAdmin = roles.includes('ADMIN');
```

**User Data Flow**:
1. User logs in, JWT token stored in sessionStorage
2. `currentUser` object set on `window` global (loaded from backend)
3. Sidebar component reads `window.currentUser.roles`
4. Listens for `userLoaded` event for updates

**Options Evaluated**:

1. **Option A: React Context + Helper Functions** (SELECTED)
   - Create role helper functions (e.g., `hasRiskAccess()`, `hasReqAccess()`)
   - Centralized in `src/frontend/src/utils/auth.ts` or `permissions.ts`
   - Components import helpers, no prop drilling
   - Pros: Reusable, testable, single source of truth, already partially implemented
   - Cons: Global state (mitigated by React best practices)
   - Constitutional Compliance: ✅ API-First (backend is source of truth)

2. **Option B: Prop Drilling from Root**
   - Pass roles down component tree via props
   - Root component fetches user, passes to children
   - Pros: Explicit data flow, no globals
   - Cons: Prop drilling hell, poor maintainability
   - Constitutional Compliance: ⚠️ Poor code quality (Principle II - TDD assumes maintainable code)

3. **Option C: State Management Library (Redux/Zustand)**
   - Add Redux/Zustand for global auth state
   - Pros: Industry standard, predictable state
   - Cons: Overkill for simple role checks, new dependency
   - Constitutional Compliance: ⚠️ Docker-First (larger bundle size)

**Decision: Option A - React Context + Helper Functions**

**Rationale**:
- Existing pattern in codebase: `hasVulnAccess()` in `/Users/flake/sources/misc/secman/src/frontend/src/utils/auth.ts`
- Already using helper functions: `canAccessNormManagement()`, `canAccessReleases()` from permissions.ts
- Aligns with Constitution Principle V (RBAC): "Frontend MUST check roles before rendering UI elements"
- No new dependencies, leverages existing patterns

**Implementation Details**:

```typescript
// src/frontend/src/utils/permissions.ts

export function hasRiskAccess(roles: string[]): boolean {
    return roles.includes('ADMIN') ||
           roles.includes('RISK') ||
           roles.includes('SECCHAMPION');
}

export function hasReqAccess(roles: string[]): boolean {
    return roles.includes('ADMIN') ||
           roles.includes('REQ') ||
           roles.includes('SECCHAMPION');
}

export function hasSecChampionAccess(roles: string[]): boolean {
    return roles.includes('SECCHAMPION');
}

// Enhanced vulnerability access (existing + SECCHAMPION)
export function hasVulnAccess(roles: string[]): boolean {
    return roles.includes('ADMIN') ||
           roles.includes('VULN') ||
           roles.includes('SECCHAMPION');
}
```

**Usage in Sidebar**:
```typescript
import { hasRiskAccess, hasReqAccess, hasVulnAccess } from '../utils/permissions';

const Sidebar = () => {
    const [userRoles, setUserRoles] = useState<string[]>([]);

    useEffect(() => {
        const user = (window as any).currentUser;
        setUserRoles(user?.roles || []);
    }, []);

    return (
        <nav>
            {hasRiskAccess(userRoles) && (
                <li>Risk Management</li>
            )}
            {hasReqAccess(userRoles) && (
                <li>Requirements</li>
            )}
        </nav>
    );
};
```

**Testing Strategy**:
- Unit test: Test each permission function with role combinations
- Component test: Render Sidebar with mocked roles, verify visibility
- E2E test: Login with each role, verify correct navigation items shown

---

## Decision 5: Navigation Visibility Logic Placement

**Question**: Should navigation visibility be controlled in Sidebar.tsx directly or via a separate config file?

### Research Findings

**Current Implementation Analysis**:
- Sidebar.tsx contains inline conditional rendering:
```typescript
{hasVuln && (
    <li>
        <div onClick={() => setVulnMenuOpen(!vulnMenuOpen)}>
            Vuln Management
        </div>
    </li>
)}
```

- Navigation structure is simple (flat hierarchy with dropdowns)
- No dynamic navigation config file found

**Options Evaluated**:

1. **Option A: Inline Conditional Rendering in Sidebar.tsx** (SELECTED)
   - Add role checks directly in JSX: `{hasRiskAccess(userRoles) && <li>...</li>}`
   - Pros: Single source of truth, easy to test, no indirection
   - Cons: Sidebar component has more logic
   - Constitutional Compliance: ✅ TDD (simpler to test than config-driven)

2. **Option B: Separate Navigation Config File**
   - Create `navigationConfig.ts` with role mappings
   - Structure: `{ path: '/risks', label: 'Risk Management', roles: ['ADMIN', 'RISK', 'SECCHAMPION'] }`
   - Pros: Declarative, could be used for route guards too
   - Cons: Adds indirection for simple use case, harder to debug
   - Constitutional Compliance: ⚠️ Over-engineering (violates YAGNI)

3. **Option C: React Router Integration**
   - Combine with route-level guards
   - Navigation items derived from accessible routes
   - Pros: DRY (routes + navigation from same source)
   - Cons: Tight coupling, requires Astro routing refactor
   - Constitutional Compliance: ❌ Breaks existing routing (Astro file-based)

**Decision: Option A - Inline Conditional Rendering in Sidebar.tsx**

**Rationale**:
- Matches existing pattern (see `{hasVuln && ...}` in current Sidebar.tsx)
- Simple, testable, maintainable (Constitution Principle II - TDD)
- No new files or abstractions (YAGNI principle)
- Easy to trace: developer can see role → visibility relationship immediately
- Aligns with Astro + React islands architecture

**Implementation Details**:

```typescript
// Sidebar.tsx updates

const Sidebar = () => {
    const [userRoles, setUserRoles] = useState<string[]>([]);
    const [riskMenuOpen, setRiskMenuOpen] = useState(false);
    const [reqMenuOpen, setReqMenuOpen] = useState(false);

    useEffect(() => {
        const user = (window as any).currentUser;
        setUserRoles(user?.roles || []);
    }, []);

    return (
        <nav id="sidebar">
            {/* Requirements - visible to ADMIN, REQ, SECCHAMPION */}
            {hasReqAccess(userRoles) && (
                <li>
                    <div onClick={() => setReqMenuOpen(!reqMenuOpen)}>
                        <i className="bi bi-card-checklist"></i> Requirements
                    </div>
                    {reqMenuOpen && (
                        <ul>
                            <li><a href="/requirements">Overview</a></li>
                            {/* Sub-items with more specific permissions */}
                        </ul>
                    )}
                </li>
            )}

            {/* Risk Management - visible to ADMIN, RISK, SECCHAMPION */}
            {hasRiskAccess(userRoles) && (
                <li>
                    <div onClick={() => setRiskMenuOpen(!riskMenuOpen)}>
                        <i className="bi bi-exclamation-triangle"></i> Risk Management
                    </div>
                    {riskMenuOpen && (
                        <ul>
                            <li><a href="/risks">Overview</a></li>
                            <li><a href="/riskassessment">Assessment</a></li>
                        </ul>
                    )}
                </li>
            )}

            {/* Vuln Management - visible to ADMIN, VULN, SECCHAMPION */}
            {hasVulnAccess(userRoles) && (
                <li>
                    {/* Existing vuln menu */}
                </li>
            )}
        </nav>
    );
};
```

**Testing Strategy**:
- Snapshot test: Render Sidebar with each role, compare DOM snapshots
- Interaction test: Verify menu expand/collapse works correctly
- Accessibility test: Ensure hidden items are not in tab order

---

## Cross-Decision Dependencies

**Dependency Graph**:
```
Decision 1 (Role Enum)
  ├─> Decision 3 (@Secured annotations use new roles)
  └─> Decision 4 (Frontend helpers check new roles)

Decision 2 (Logging)
  └─> Decision 3 (SecurityFilter triggers logging)

Decision 3 (Per-Request Validation)
  └─> Decision 2 (Validation failures logged)

Decision 4 (Frontend Helpers)
  └─> Decision 5 (Sidebar uses helpers)
```

**Integration Points**:
1. Role enum change (Decision 1) must complete before @Secured updates (Decision 3)
2. AccessDenialLogger (Decision 2) must be ready before SecurityFilter integration (Decision 3)
3. Frontend helpers (Decision 4) must be created before Sidebar refactor (Decision 5)

---

## Constitutional Compliance Summary

| Decision | Principle I | Principle II | Principle III | Principle IV | Principle V | Principle VI |
|----------|-------------|--------------|---------------|--------------|-------------|--------------|
| 1. Role Migration | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 2. Logging | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 3. Per-Request | ✅ | ✅ | ✅ | ✅ | ✅ | N/A |
| 4. Frontend Helpers | N/A | ✅ | ✅ | ✅ | ✅ | N/A |
| 5. Navigation | N/A | ✅ | ✅ | ✅ | ✅ | N/A |

**Legend**:
- ✅ Compliant
- N/A Not applicable to this decision

---

## Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| CHAMPION role migration data loss | HIGH | Backup database, test migration script in staging, verify no hardcoded "CHAMPION" strings |
| MDC context leak across threads | MEDIUM | Always use try-finally to clear MDC, test with concurrent requests |
| Frontend role checks bypassed | LOW | Backend @Secured is primary defense, frontend is UX only |
| Role changes not reflected immediately | LOW | Document that JWT must be refreshed for role changes to take effect |
| Navigation flicker during role load | LOW | Show loading state until user roles loaded |

---

## Next Steps

1. Proceed to [data-model.md](./data-model.md) for entity schema definitions
2. Review [contracts/](./contracts/) for API contract specifications
3. Follow [quickstart.md](./quickstart.md) for implementation sequence

**Approval Required**: Database migration script (Decision 1) should be reviewed by DBA before production deployment.
