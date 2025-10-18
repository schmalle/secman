# Data Model: Role-Based Access Control

**Feature**: 025-role-based-access-control | **Date**: 2025-10-18
**Related**: [spec.md](./spec.md) | [plan.md](./plan.md) | [research.md](./research.md)

## Overview

This document defines the data model changes required for implementing RISK, REQ, and SECCHAMPION roles. The primary change is to the User entity's Role enum, with no new entities required.

---

## Entity Changes

### User Entity (MODIFIED)

**Location**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/domain/User.kt`

**Change Type**: Enum modification + migration script

#### Current State

```kotlin
@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(unique = true, nullable = false)
    var username: String,

    @Column(unique = true, nullable = false)
    @Email
    var email: String,

    @Column(name = "password_hash", nullable = false)
    @JsonIgnore
    var passwordHash: String,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "role_name")
    var roles: MutableSet<Role> = mutableSetOf(Role.USER),

    // ... other fields
) {
    enum class Role {
        USER, ADMIN, VULN, RELEASE_MANAGER, CHAMPION, REQ  // CURRENT
    }
}
```

#### Updated State

```kotlin
@Entity
@Table(name = "users")
data class User(
    // ... same fields as before ...

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "role_name")
    var roles: MutableSet<Role> = mutableSetOf(Role.USER),

    // ... other fields unchanged ...
) {
    /**
     * User roles for RBAC
     * Feature: 025-role-based-access-control
     *
     * Roles:
     * - USER: Default role with basic access
     * - ADMIN: Full system administration (super-user)
     * - VULN: Vulnerability management permissions
     * - RELEASE_MANAGER: Release creation and management
     * - SECCHAMPION: Security Champion - access to Risk, Requirements, and Vulnerabilities (no Admin)
     * - REQ: Requirements management permissions
     * - RISK: Risk management permissions
     */
    enum class Role {
        USER,
        ADMIN,
        VULN,
        RELEASE_MANAGER,
        SECCHAMPION,  // RENAMED from CHAMPION
        REQ,          // EXISTING - no change
        RISK          // NEW
    }

    /**
     * Check if user has Risk Management access
     * Feature: 025-role-based-access-control
     */
    fun hasRiskAccess(): Boolean =
        roles.contains(Role.ADMIN) ||
        roles.contains(Role.RISK) ||
        roles.contains(Role.SECCHAMPION)

    /**
     * Check if user has Requirements access
     * Feature: 025-role-based-access-control
     */
    fun hasReqAccess(): Boolean =
        roles.contains(Role.ADMIN) ||
        roles.contains(Role.REQ) ||
        roles.contains(Role.SECCHAMPION)

    /**
     * Check if user has Vulnerability access
     * Feature: 025-role-based-access-control
     */
    fun hasVulnAccess(): Boolean =
        roles.contains(Role.ADMIN) ||
        roles.contains(Role.VULN) ||
        roles.contains(Role.SECCHAMPION)

    // Existing methods remain unchanged
    fun hasRole(role: Role): Boolean = roles.contains(role)
    fun isAdmin(): Boolean = hasRole(Role.ADMIN)
}
```

#### Database Schema Impact

**Affected Table**: `user_roles`

**Current Schema**:
```sql
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_name VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY (user_id, role_name)
);
```

**Schema After Migration**: No structural changes (VARCHAR column accepts new enum values)

**Data Migration Required**: YES

**Migration Script**:
```sql
-- File: src/backendng/src/main/resources/db/migration/V2__rename_champion_to_secchampion.sql
-- Purpose: Rename CHAMPION role to SECCHAMPION for existing users
-- Feature: 025-role-based-access-control
-- Date: 2025-10-18

-- Update existing CHAMPION roles to SECCHAMPION
UPDATE user_roles
SET role_name = 'SECCHAMPION'
WHERE role_name = 'CHAMPION';

-- Verify migration (should return 0 rows)
-- SELECT * FROM user_roles WHERE role_name = 'CHAMPION';

-- Add comment for audit trail
-- Migration completed: CHAMPION → SECCHAMPION
```

**Rollback Script** (if needed):
```sql
-- File: db/rollback/V2__rollback_secchampion_to_champion.sql
-- Purpose: Rollback SECCHAMPION to CHAMPION
-- WARNING: Only use if migration needs to be reverted

UPDATE user_roles
SET role_name = 'CHAMPION'
WHERE role_name = 'SECCHAMPION';
```

---

## Permission Mapping

### Role-to-Resource Matrix

This is the authoritative mapping of roles to resources. All @Secured annotations must align with this matrix.

| Resource Area               | ADMIN | RISK | REQ | SECCHAMPION | VULN | RELEASE_MGR | USER |
|-----------------------------|-------|------|-----|-------------|------|-------------|------|
| **Risk Management**         |       |      |     |             |      |             |      |
| `/api/risk-assessments/*`   | ✅    | ✅   | ❌  | ✅          | ❌   | ❌          | ❌   |
| `/api/risks/*`              | ✅    | ✅   | ❌  | ✅          | ❌   | ❌          | ❌   |
| **Requirements**            |       |      |     |             |      |             |      |
| `/api/requirements/*`       | ✅    | ❌   | ✅  | ✅          | ❌   | ❌          | ❌   |
| `/api/norms/*`              | ✅    | ❌   | ✅  | ✅          | ❌   | ❌          | ❌   |
| `/api/usecases/*`           | ✅    | ❌   | ✅  | ✅          | ❌   | ❌          | ❌   |
| `/api/standards/*`          | ✅    | ❌   | ✅  | ✅          | ❌   | ❌          | ❌   |
| **Vulnerabilities**         |       |      |     |             |      |             |      |
| `/api/vulnerabilities/*`    | ✅    | ❌   | ❌  | ✅          | ✅   | ❌          | ❌   |
| `/api/vulnerability-exceptions/*` | ✅ | ❌ | ❌ | ✅        | ✅   | ❌          | ❌   |
| **Releases**                |       |      |     |             |      |             |      |
| `/api/releases/*` (read)    | ✅    | ❌   | ❌  | ❌          | ❌   | ✅          | ✅   |
| `/api/releases/*` (write)   | ✅    | ❌   | ❌  | ❌          | ❌   | ✅          | ❌   |
| **Admin Area**              |       |      |     |             |      |             |      |
| `/api/admin/*`              | ✅    | ❌   | ❌  | ❌          | ❌   | ❌          | ❌   |
| `/api/workgroups/*`         | ✅    | ❌   | ❌  | ❌          | ❌   | ❌          | ❌   |
| `/api/users/*`              | ✅    | ❌   | ❌  | ❌          | ❌   | ❌          | ❌   |
| **Assets & Scans**          |       |      |     |             |      |             |      |
| `/api/assets/*`             | ✅    | ❌   | ❌  | ❌          | ❌   | ❌          | ✅ (workgroup) |
| `/api/scans/*`              | ✅    | ❌   | ❌  | ❌          | ❌   | ❌          | ✅ (workgroup) |
| **Demands**                 |       |      |     |             |      |             |      |
| `/api/demands/*`            | ✅    | ❌   | ❌  | ❌          | ❌   | ❌          | ✅   |

**Notes**:
- ✅ = Full access (read + write unless otherwise specified)
- ❌ = No access (403 Forbidden)
- ADMIN has super-user access to all resources
- SECCHAMPION has broad access but NO admin privileges
- Workgroup-scoped access for USER role on assets/scans (existing behavior)

### Role Descriptions

#### USER (Existing)
- Default role assigned to all new users
- Access to workgroup-assigned resources
- Can view demands, classification tool
- No special permissions

#### ADMIN (Existing)
- Super-user role with access to all areas
- User management, system configuration
- Workgroup administration
- Bypasses all workgroup restrictions

#### VULN (Existing)
- Vulnerability management permissions
- View and manage vulnerability exceptions
- Access to vulnerability scanning data
- Respects workgroup boundaries (non-admin users)

#### RELEASE_MANAGER (Existing)
- Create and manage requirement releases
- Publish/archive releases
- Compare releases
- Cannot delete releases created by other users (unless ADMIN)

#### SECCHAMPION (New - renamed from CHAMPION)
- **"Power User" role for security champions**
- Access to Risk Management (like RISK)
- Access to Requirements (like REQ)
- Access to Vulnerabilities (like VULN)
- **NO admin access** (cannot manage users, workgroups, or system settings)
- Purpose: Security coordinators who need visibility across security domains

#### REQ (Existing - no change)
- Requirements management permissions
- Create/edit/delete requirements
- Manage norms, use cases, standards
- Export requirements
- No access to risks or vulnerabilities

#### RISK (New)
- Risk management permissions
- Create/edit/delete risk assessments
- View risk reports
- No access to requirements or vulnerabilities

---

## Access Denial Logging

### Log Entry Structure

**Implementation**: SLF4J with MDC context (see Decision 2 in research.md)

**Service Class**: `AccessDenialLogger`

**Location**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/AccessDenialLogger.kt`

#### Entity Definition

```kotlin
package com.secman.service

import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant

/**
 * Service for logging access denials for security audit purposes
 * Feature: 025-role-based-access-control
 *
 * Logs are written to a dedicated logger (ACCESS_DENIAL_AUDIT) with structured MDC context.
 * This enables easy aggregation in log management systems (Splunk, ELK, Datadog).
 *
 * Constitutional Compliance: Principle I (Security-First) - FR-014
 */
@Singleton
class AccessDenialLogger {

    companion object {
        // Dedicated logger for access denial events
        // Allows separate log routing/filtering in log aggregation systems
        private val log = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT")
    }

    /**
     * Log an access denial event with full context
     *
     * @param authentication Authenticated user attempting access
     * @param resource Resource path attempted (e.g., "/api/risk-assessments")
     * @param requiredRoles Roles required to access the resource
     * @param ipAddress Optional IP address of the request
     */
    fun logAccessDenial(
        authentication: Authentication,
        resource: String,
        requiredRoles: List<String>,
        ipAddress: String? = null
    ) {
        try {
            // Add structured context to MDC for log aggregation
            MDC.put("event_type", "access_denied")
            MDC.put("user_id", authentication.name)
            MDC.put("user_roles", authentication.roles.joinToString(","))
            MDC.put("resource", resource)
            MDC.put("required_roles", requiredRoles.joinToString(","))
            MDC.put("timestamp", Instant.now().toString())
            ipAddress?.let { MDC.put("ip_address", it) }

            // Log at WARN level (not ERROR - this is expected behavior for unauthorized access)
            log.warn(
                "Access denied: user='{}', roles=[{}], resource='{}', required=[{}], ip='{}'",
                authentication.name,
                authentication.roles.joinToString(","),
                resource,
                requiredRoles.joinToString(","),
                ipAddress ?: "unknown"
            )
        } finally {
            // CRITICAL: Always clear MDC to prevent context leakage across threads
            MDC.clear()
        }
    }

    /**
     * Log an access denial with HTTP method context
     *
     * @param authentication Authenticated user
     * @param httpMethod HTTP method (GET, POST, PUT, DELETE)
     * @param resource Resource path
     * @param requiredRoles Required roles
     * @param ipAddress Optional IP address
     */
    fun logAccessDenialWithMethod(
        authentication: Authentication,
        httpMethod: String,
        resource: String,
        requiredRoles: List<String>,
        ipAddress: String? = null
    ) {
        try {
            MDC.put("event_type", "access_denied")
            MDC.put("user_id", authentication.name)
            MDC.put("user_roles", authentication.roles.joinToString(","))
            MDC.put("http_method", httpMethod)
            MDC.put("resource", resource)
            MDC.put("required_roles", requiredRoles.joinToString(","))
            MDC.put("timestamp", Instant.now().toString())
            ipAddress?.let { MDC.put("ip_address", it) }

            log.warn(
                "Access denied: user='{}', method={}, resource='{}', roles=[{}], required=[{}]",
                authentication.name,
                httpMethod,
                resource,
                authentication.roles.joinToString(","),
                requiredRoles.joinToString(",")
            )
        } finally {
            MDC.clear()
        }
    }
}
```

#### Log Format Specification

**Log Level**: WARN

**Logger Name**: `ACCESS_DENIAL_AUDIT`

**Log Message Format**:
```
Access denied: user='<username>', roles=[<comma-separated-roles>], resource='<path>', required=[<comma-separated-required-roles>], ip='<ip-address>'
```

**MDC Context Fields**:
| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `event_type` | String | Always "access_denied" for filtering | `access_denied` |
| `user_id` | String | Username or email from authentication | `john.doe@example.com` |
| `user_roles` | String | Comma-separated roles user has | `USER,REQ` |
| `resource` | String | Resource path attempted | `/api/risk-assessments` |
| `required_roles` | String | Comma-separated roles required | `ADMIN,RISK,SECCHAMPION` |
| `timestamp` | ISO-8601 | Event timestamp | `2025-10-18T14:23:45.123Z` |
| `ip_address` | String (optional) | Client IP address | `192.168.1.100` |
| `http_method` | String (optional) | HTTP method | `POST` |

**Example Log Entry** (full line with MDC):
```
2025-10-18 14:23:45.123 WARN [http-nio-8080-exec-5] ACCESS_DENIAL_AUDIT - Access denied: user='john.doe@example.com', roles=[USER,REQ], resource='/api/risk-assessments', required=[ADMIN,RISK,SECCHAMPION], ip='192.168.1.100' event_type=access_denied user_id=john.doe@example.com user_roles=USER,REQ resource=/api/risk-assessments required_roles=ADMIN,RISK,SECCHAMPION timestamp=2025-10-18T14:23:45.123Z ip_address=192.168.1.100
```

**Log Aggregation Query Examples**:

Splunk:
```
index=secman logger_name=ACCESS_DENIAL_AUDIT event_type=access_denied
| stats count by user_id, resource
```

ELK (Elasticsearch Query DSL):
```json
{
  "query": {
    "bool": {
      "must": [
        { "term": { "logger_name": "ACCESS_DENIAL_AUDIT" }},
        { "term": { "mdc.event_type": "access_denied" }}
      ]
    }
  },
  "aggs": {
    "by_user": {
      "terms": { "field": "mdc.user_id" }
    }
  }
}
```

---

## No New Entities

This feature does NOT require new database entities. All changes are to existing User entity and its Role enum.

**Verification**:
- ✅ No new tables required
- ✅ No new foreign keys
- ✅ No new indexes (user_roles table already has unique key on user_id + role_name)
- ✅ Logging uses SLF4J (no database storage)

---

## Migration Checklist

**Pre-Migration**:
- [ ] Backup production database
- [ ] Test migration script on dev environment
- [ ] Test migration script on staging environment
- [ ] Verify no hardcoded "CHAMPION" strings in codebase (grep search)
- [ ] Document rollback procedure

**Migration Execution**:
- [ ] Run migration script: `V2__rename_champion_to_secchampion.sql`
- [ ] Verify migration: `SELECT COUNT(*) FROM user_roles WHERE role_name = 'CHAMPION'` (should be 0)
- [ ] Verify data integrity: `SELECT COUNT(*) FROM user_roles WHERE role_name = 'SECCHAMPION'`
- [ ] Deploy updated User.kt enum
- [ ] Restart application

**Post-Migration Verification**:
- [ ] Log in with SECCHAMPION user, verify access to Risk Management
- [ ] Log in with SECCHAMPION user, verify access to Requirements
- [ ] Log in with SECCHAMPION user, verify access to Vulnerabilities
- [ ] Log in with SECCHAMPION user, verify NO access to Admin area (expect 403)
- [ ] Check application logs for enum errors
- [ ] Monitor AccessDenialLogger for unexpected denials

**Rollback Procedure** (if needed):
1. Stop application
2. Run rollback script: `V2__rollback_secchampion_to_champion.sql`
3. Deploy previous version of User.kt (with CHAMPION enum)
4. Restart application
5. Investigate root cause

---

## Testing Requirements

### Unit Tests

**File**: `src/backendng/src/test/kotlin/com/secman/domain/UserTest.kt`

```kotlin
class UserTest {
    @Test
    fun `should have RISK role in enum`() {
        val roles = User.Role.values()
        assertTrue(roles.contains(User.Role.RISK))
    }

    @Test
    fun `should have SECCHAMPION role in enum`() {
        val roles = User.Role.values()
        assertTrue(roles.contains(User.Role.SECCHAMPION))
    }

    @Test
    fun `should NOT have CHAMPION role in enum`() {
        val roles = User.Role.values()
        assertFalse(roles.any { it.name == "CHAMPION" })
    }

    @Test
    fun `hasRiskAccess should return true for RISK role`() {
        val user = User(username = "test", email = "test@example.com", passwordHash = "hash")
        user.roles.add(User.Role.RISK)
        assertTrue(user.hasRiskAccess())
    }

    @Test
    fun `hasRiskAccess should return true for SECCHAMPION role`() {
        val user = User(username = "test", email = "test@example.com", passwordHash = "hash")
        user.roles.add(User.Role.SECCHAMPION)
        assertTrue(user.hasRiskAccess())
    }

    @Test
    fun `hasReqAccess should return true for REQ role`() {
        val user = User(username = "test", email = "test@example.com", passwordHash = "hash")
        user.roles.add(User.Role.REQ)
        assertTrue(user.hasReqAccess())
    }
}
```

### Integration Tests

**File**: `src/backendng/src/test/kotlin/com/secman/integration/RoleEnumPersistenceTest.kt`

```kotlin
@MicronautTest
class RoleEnumPersistenceTest {
    @Inject
    lateinit var userRepository: UserRepository

    @Test
    fun `should persist and retrieve SECCHAMPION role`() {
        val user = User(username = "champion", email = "champion@example.com", passwordHash = "hash")
        user.roles.add(User.Role.SECCHAMPION)

        val saved = userRepository.save(user)
        entityManager.flush()
        entityManager.clear()

        val retrieved = userRepository.findById(saved.id!!).get()
        assertTrue(retrieved.roles.contains(User.Role.SECCHAMPION))
    }

    @Test
    fun `should persist and retrieve RISK role`() {
        val user = User(username = "riskman", email = "risk@example.com", passwordHash = "hash")
        user.roles.add(User.Role.RISK)

        val saved = userRepository.save(user)
        entityManager.flush()
        entityManager.clear()

        val retrieved = userRepository.findById(saved.id!!).get()
        assertTrue(retrieved.roles.contains(User.Role.RISK))
    }
}
```

### Contract Tests

Covered in [contracts/role-permission-matrix.md](./contracts/role-permission-matrix.md)

---

## Next Steps

1. Review [contracts/role-permission-matrix.md](./contracts/role-permission-matrix.md) for API endpoint updates
2. Review [contracts/access-denial-logging.md](./contracts/access-denial-logging.md) for logging specifications
3. Follow [quickstart.md](./quickstart.md) for implementation sequence
4. Execute migration script in staging before production

**Questions/Concerns**: Contact architecture team for review of migration strategy.
