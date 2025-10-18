# Quickstart: Implementation Guide for Role-Based Access Control

**Feature**: 025-role-based-access-control | **Date**: 2025-10-18
**Related**: [spec.md](./spec.md) | [plan.md](./plan.md) | [data-model.md](./data-model.md)

## Overview

This document provides a step-by-step implementation guide following the **Red-Green-Refactor** TDD cycle. Complete each phase in order - **do not skip ahead** to implementation without writing tests first.

**Estimated Time**: 6-8 hours (including testing)

**Prerequisites**:
- Development environment set up (Java 21, Kotlin 2.1, Micronaut 4.4)
- Database accessible (MariaDB 11.4)
- Test database configured
- Git branch created: `025-role-based-access-control`

---

## Phase 0: Preparation

### Step 0.1: Create Feature Branch

```bash
git checkout main
git pull origin main
git checkout -b 025-role-based-access-control
```

### Step 0.2: Verify Current State

```bash
# Run existing tests to ensure baseline is green
cd /Users/flake/sources/misc/secman/src/backendng
./gradlew test

# Verify no existing RISK or SECCHAMPION roles in database
# Connect to MariaDB and check:
SELECT DISTINCT role_name FROM user_roles ORDER BY role_name;
# Expected: ADMIN, CHAMPION, RELEASE_MANAGER, REQ, USER, VULN
```

### Step 0.3: Backup Database (Development)

```bash
# Create backup before schema migration
mysqldump -u secman_user -p secman_dev > backup_before_025_$(date +%Y%m%d).sql
```

**⏱️ Time**: 10 minutes

---

## Phase 1: Update User Role Enum (TDD)

### Step 1.1: Write Failing Unit Test

**File**: `src/backendng/src/test/kotlin/com/secman/domain/UserTest.kt`

```kotlin
package com.secman.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class UserRoleEnumTest {

    @Test
    fun `should have RISK role in enum`() {
        val roles = User.Role.values()
        assertTrue(roles.contains(User.Role.RISK), "RISK role should exist in User.Role enum")
    }

    @Test
    fun `should have SECCHAMPION role in enum`() {
        val roles = User.Role.values()
        assertTrue(roles.contains(User.Role.SECCHAMPION), "SECCHAMPION role should exist in User.Role enum")
    }

    @Test
    fun `should NOT have CHAMPION role in enum`() {
        val roles = User.Role.values()
        assertFalse(roles.any { it.name == "CHAMPION" }, "CHAMPION role should be renamed to SECCHAMPION")
    }

    @Test
    fun `hasRiskAccess should return true for RISK role`() {
        val user = User(username = "testuser", email = "test@example.com", passwordHash = "hash")
        user.roles.add(User.Role.RISK)
        assertTrue(user.hasRiskAccess(), "User with RISK role should have risk access")
    }

    @Test
    fun `hasRiskAccess should return true for SECCHAMPION role`() {
        val user = User(username = "champion", email = "champ@example.com", passwordHash = "hash")
        user.roles.add(User.Role.SECCHAMPION)
        assertTrue(user.hasRiskAccess(), "User with SECCHAMPION role should have risk access")
    }

    @Test
    fun `hasRiskAccess should return true for ADMIN role`() {
        val user = User(username = "admin", email = "admin@example.com", passwordHash = "hash")
        user.roles.add(User.Role.ADMIN)
        assertTrue(user.hasRiskAccess(), "User with ADMIN role should have risk access")
    }

    @Test
    fun `hasRiskAccess should return false for REQ role`() {
        val user = User(username = "requser", email = "req@example.com", passwordHash = "hash")
        user.roles.add(User.Role.REQ)
        assertFalse(user.hasRiskAccess(), "User with only REQ role should NOT have risk access")
    }

    @Test
    fun `hasReqAccess should return true for REQ role`() {
        val user = User(username = "requser", email = "req@example.com", passwordHash = "hash")
        user.roles.add(User.Role.REQ)
        assertTrue(user.hasReqAccess(), "User with REQ role should have requirements access")
    }

    @Test
    fun `hasReqAccess should return true for SECCHAMPION role`() {
        val user = User(username = "champion", email = "champ@example.com", passwordHash = "hash")
        user.roles.add(User.Role.SECCHAMPION)
        assertTrue(user.hasReqAccess(), "User with SECCHAMPION role should have requirements access")
    }

    @Test
    fun `hasVulnAccess should return true for SECCHAMPION role`() {
        val user = User(username = "champion", email = "champ@example.com", passwordHash = "hash")
        user.roles.add(User.Role.SECCHAMPION)
        assertTrue(user.hasVulnAccess(), "User with SECCHAMPION role should have vulnerability access")
    }
}
```

**Run tests** (should FAIL):
```bash
./gradlew test --tests UserRoleEnumTest
# Expected: Compilation errors (RISK, SECCHAMPION, hasRiskAccess() don't exist)
```

### Step 1.2: Update User Entity (Make Tests Pass)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

**Change**:
```kotlin
// BEFORE:
enum class Role {
    USER, ADMIN, VULN, RELEASE_MANAGER, CHAMPION, REQ
}

// AFTER:
/**
 * User roles for RBAC
 * Feature: 025-role-based-access-control
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
```

**Run tests** (should PASS):
```bash
./gradlew test --tests UserRoleEnumTest
# Expected: All tests green ✅
```

### Step 1.3: Create Database Migration Script

**File**: `src/backendng/src/main/resources/db/migration/V2__rename_champion_to_secchampion.sql`

```sql
-- Migration: Rename CHAMPION role to SECCHAMPION
-- Feature: 025-role-based-access-control
-- Date: 2025-10-18

UPDATE user_roles
SET role_name = 'SECCHAMPION'
WHERE role_name = 'CHAMPION';

-- Verification query (comment out in production):
-- SELECT * FROM user_roles WHERE role_name = 'CHAMPION';
-- Expected: 0 rows
```

**Test migration on dev database**:
```bash
# Insert test CHAMPION role
mysql -u secman_user -p secman_dev -e "
INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
VALUES (9999, 'test_champion', 'test@example.com', 'hash', NOW(), NOW());

INSERT INTO user_roles (user_id, role_name)
VALUES (9999, 'CHAMPION');
"

# Run migration
mysql -u secman_user -p secman_dev < src/main/resources/db/migration/V2__rename_champion_to_secchampion.sql

# Verify
mysql -u secman_user -p secman_dev -e "
SELECT user_id, role_name FROM user_roles WHERE user_id = 9999;
"
# Expected: user_id=9999, role_name=SECCHAMPION

# Cleanup test data
mysql -u secman_user -p secman_dev -e "
DELETE FROM user_roles WHERE user_id = 9999;
DELETE FROM users WHERE id = 9999;
"
```

**⏱️ Time**: 30 minutes

---

## Phase 2: Access Denial Logging Service (TDD)

### Step 2.1: Write Failing Unit Test

**File**: `src/backendng/src/test/kotlin/com/secman/service/AccessDenialLoggerTest.kt`

```kotlin
package com.secman.service

import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.mockk
import io.mockk.every
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.slf4j.MDC
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

@MicronautTest
class AccessDenialLoggerTest {

    @Test
    fun `should clear MDC context after logging`() {
        val logger = AccessDenialLogger()
        val auth = mockk<Authentication>()
        every { auth.name } returns "test.user"
        every { auth.roles } returns setOf("USER", "REQ")

        logger.logAccessDenial(
            authentication = auth,
            resource = "/api/risk-assessments",
            requiredRoles = listOf("ADMIN", "RISK"),
            ipAddress = "192.168.1.100"
        )

        // MDC should be cleared after logging
        assertNull(MDC.get("user_id"), "MDC user_id should be cleared")
        assertNull(MDC.get("resource"), "MDC resource should be cleared")
    }

    @Test
    fun `should log at WARN level`() {
        val testAppender = ListAppender<ILoggingEvent>()
        testAppender.start()

        val logger = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT") as Logger
        logger.addAppender(testAppender)

        val service = AccessDenialLogger()
        val auth = mockk<Authentication>()
        every { auth.name } returns "test.user"
        every { auth.roles } returns setOf("USER")

        service.logAccessDenial(
            authentication = auth,
            resource = "/api/test",
            requiredRoles = listOf("ADMIN")
        )

        assertEquals(1, testAppender.list.size)
        assertEquals("WARN", testAppender.list[0].level.toString())
        assertTrue(testAppender.list[0].message.contains("Access denied"))
    }

    @Test
    fun `should include all MDC fields`() {
        val testAppender = ListAppender<ILoggingEvent>()
        testAppender.start()

        val logger = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT") as Logger
        logger.addAppender(testAppender)

        val service = AccessDenialLogger()
        val auth = mockk<Authentication>()
        every { auth.name } returns "test.user"
        every { auth.roles } returns setOf("USER", "REQ")

        service.logAccessDenial(
            authentication = auth,
            resource = "/api/risk-assessments",
            requiredRoles = listOf("ADMIN", "RISK", "SECCHAMPION"),
            ipAddress = "192.168.1.100"
        )

        val logEvent = testAppender.list[0]
        val mdcMap = logEvent.mdcPropertyMap

        assertEquals("access_denied", mdcMap["event_type"])
        assertEquals("test.user", mdcMap["user_id"])
        assertEquals("USER,REQ", mdcMap["user_roles"])
        assertEquals("/api/risk-assessments", mdcMap["resource"])
        assertEquals("ADMIN,RISK,SECCHAMPION", mdcMap["required_roles"])
        assertEquals("192.168.1.100", mdcMap["ip_address"])
    }
}
```

**Run tests** (should FAIL):
```bash
./gradlew test --tests AccessDenialLoggerTest
# Expected: Class not found error
```

### Step 2.2: Create AccessDenialLogger Service

**File**: `src/backendng/src/main/kotlin/com/secman/service/AccessDenialLogger.kt`

*(Copy implementation from [contracts/access-denial-logging.md](./contracts/access-denial-logging.md))*

**Run tests** (should PASS):
```bash
./gradlew test --tests AccessDenialLoggerTest
# Expected: All tests green ✅
```

**⏱️ Time**: 30 minutes

---

## Phase 3: Update Controller @Secured Annotations (TDD)

### Step 3.1: Write Failing Contract Tests

**File**: `src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt`

```kotlin
package com.secman.contract

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

@MicronautTest
class RoleAuthorizationContractTest {

    @Inject
    @Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var tokenGenerator: TokenGenerator

    // Risk Management Tests
    @Test
    fun `GET risk-assessments should allow ADMIN`() {
        val token = generateToken("admin", listOf("ADMIN"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/risk-assessments").bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `GET risk-assessments should allow RISK`() {
        val token = generateToken("riskuser", listOf("RISK"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/risk-assessments").bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `GET risk-assessments should allow SECCHAMPION`() {
        val token = generateToken("champion", listOf("SECCHAMPION"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/risk-assessments").bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `GET risk-assessments should deny REQ role`() {
        val token = generateToken("requser", listOf("REQ"))
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/risk-assessments").bearerAuth(token),
                String::class.java
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    // Requirements Tests
    @Test
    fun `GET requirements should allow REQ`() {
        val token = generateToken("requser", listOf("REQ"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/requirements").bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `GET requirements should deny RISK role`() {
        val token = generateToken("riskuser", listOf("RISK"))
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/requirements").bearerAuth(token),
                String::class.java
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    // Vulnerability Tests
    @Test
    fun `GET vulnerabilities should allow SECCHAMPION`() {
        val token = generateToken("champion", listOf("SECCHAMPION"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/vulnerabilities/current").bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    // Helper to generate JWT token
    private fun generateToken(username: String, roles: List<String>): String {
        val claims = mapOf(
            "sub" to username,
            "roles" to roles
        )
        return tokenGenerator.generateToken(claims).get()
    }
}
```

**Run tests** (should FAIL):
```bash
./gradlew test --tests RoleAuthorizationContractTest
# Expected: Tests fail because @Secured annotations not updated yet
```

### Step 3.2: Update Controller @Secured Annotations

**RiskAssessmentController**:
```kotlin
@Controller("/api/risk-assessments")
@Secured("ADMIN", "RISK", "SECCHAMPION")  // CHANGED
@ExecuteOn(TaskExecutors.BLOCKING)
open class RiskAssessmentController(...)
```

**RequirementController**:
```kotlin
@Controller("/api/requirements")
@Secured("ADMIN", "REQ", "SECCHAMPION")  // CHANGED
open class RequirementController(...)
```

**VulnerabilityManagementController**:
```kotlin
@Controller
@Secured("ADMIN", "VULN", "SECCHAMPION")  // CHANGED - added SECCHAMPION
@ExecuteOn(TaskExecutors.BLOCKING)
open class VulnerabilityManagementController(...)
```

**Run tests** (should PASS):
```bash
./gradlew test --tests RoleAuthorizationContractTest
# Expected: All tests green ✅
```

**⏱️ Time**: 45 minutes

---

## Phase 4: Update Frontend Navigation (TDD)

### Step 4.1: Create Permission Helper Functions

**File**: `src/frontend/src/utils/permissions.ts`

```typescript
/**
 * Role-based permission helper functions
 * Feature: 025-role-based-access-control
 */

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

// Update existing hasVulnAccess to include SECCHAMPION
export function hasVulnAccess(roles: string[]): boolean {
    return roles.includes('ADMIN') ||
           roles.includes('VULN') ||
           roles.includes('SECCHAMPION');
}
```

### Step 4.2: Update Sidebar Component

**File**: `src/frontend/src/components/Sidebar.tsx`

**Changes**:
```typescript
import { hasRiskAccess, hasReqAccess, hasVulnAccess } from '../utils/permissions';

// In component:
const [userRoles, setUserRoles] = useState<string[]>([]);

useEffect(() => {
    const user = (window as any).currentUser;
    setUserRoles(user?.roles || []);
}, []);

// Wrap Requirements section:
{hasReqAccess(userRoles) && (
    <li>
        <div onClick={toggleRequirements} ...>
            Requirements
        </div>
        {requirementsExpanded && (
            <ul>
                {/* existing sub-items */}
            </ul>
        )}
    </li>
)}

// Wrap Risk Management section:
{hasRiskAccess(userRoles) && (
    <li>
        <div onClick={toggleRiskManagement} ...>
            Risk Management
        </div>
        {riskManagementExpanded && (
            <ul>
                {/* existing sub-items */}
            </ul>
        )}
    </li>
)}

// Vuln Management already has {hasVuln && ...} - update to use helper function
{hasVulnAccess(userRoles) && (
    <li>
        {/* existing vuln menu */}
    </li>
)}
```

### Step 4.3: Test Frontend Manually

```bash
cd /Users/flake/sources/misc/secman/src/frontend
npm run dev
```

**Test Cases**:
1. Login as user with RISK role → see Risk Management, NOT Requirements
2. Login as user with REQ role → see Requirements, NOT Risk Management
3. Login as user with SECCHAMPION role → see Risk Management, Requirements, AND Vulnerabilities
4. Login as user with USER role → see neither Risk nor Requirements

**⏱️ Time**: 30 minutes

---

## Phase 5: Documentation Updates

### Step 5.1: Update README.md

**File**: `/Users/flake/sources/misc/secman/README.md`

**Find the RBAC section** (around line 68) and update:

```markdown
### Access Control & Multi-Tenancy
- ✅ **Workgroups**: Organize users and assets into isolated groups
- ✅ **User Mapping**: CSV/Excel upload for AWS account ↔ user associations
- ✅ **Role-Based Access Control (RBAC)**:
  - `USER` - Basic access to assigned workgroups
  - `ADMIN` - Full system administration (super-user)
  - `VULN` - Vulnerability management permissions
  - `RELEASE_MANAGER` - Release creation and management
  - `RISK` - Risk management and assessment permissions
  - `REQ` - Requirements, norms, standards, and use case management
  - `SECCHAMPION` - Security Champion role with access to Risk, Requirements, and Vulnerabilities (no Admin access)
- ✅ **Row-Level Security**: Users see only their workgroup resources + owned items
```

**Add Role Description Table**:

```markdown
## User Roles

| Role | Access | Description |
|------|--------|-------------|
| **USER** | Workgroup assets, demands, classification | Default role for all users |
| **ADMIN** | Full system access | Super-user with access to all areas including user management and system settings |
| **VULN** | Vulnerability management | Create/manage vulnerability exceptions, view vulnerability data (workgroup-scoped for non-admin) |
| **RELEASE_MANAGER** | Release management | Create releases, publish/archive releases, compare releases |
| **RISK** | Risk management | Create and manage risk assessments, view risk reports |
| **REQ** | Requirements management | Create/edit requirements, manage norms/standards/use cases, export requirements |
| **SECCHAMPION** | Risk + Requirements + Vulnerabilities | Security Champion "power user" role with broad access across security domains but NO admin privileges |
```

**⏱️ Time**: 15 minutes

---

## Phase 6: Integration Testing

### Step 6.1: Run Full Test Suite

```bash
# Backend tests
cd /Users/flake/sources/misc/secman/src/backendng
./gradlew clean test

# Expected: All tests pass
# Watch for any tests affected by enum changes
```

### Step 6.2: Manual Integration Tests

**Test Checklist**:

- [ ] Deploy to dev environment
- [ ] Run database migration (V2__rename_champion_to_secchampion.sql)
- [ ] Create test users with new roles:
  - [ ] User with RISK role
  - [ ] User with REQ role
  - [ ] User with SECCHAMPION role
- [ ] Login as RISK user:
  - [ ] ✅ Can access /risk-assessments
  - [ ] ❌ Cannot access /requirements (403)
  - [ ] ❌ Cannot access /admin (403)
  - [ ] Risk Management visible in nav
  - [ ] Requirements NOT visible in nav
- [ ] Login as REQ user:
  - [ ] ❌ Cannot access /risk-assessments (403)
  - [ ] ✅ Can access /requirements
  - [ ] ❌ Cannot access /admin (403)
  - [ ] Requirements visible in nav
  - [ ] Risk Management NOT visible in nav
- [ ] Login as SECCHAMPION user:
  - [ ] ✅ Can access /risk-assessments
  - [ ] ✅ Can access /requirements
  - [ ] ✅ Can access /vulnerabilities/current
  - [ ] ❌ Cannot access /admin (403)
  - [ ] ❌ Cannot access /workgroups (403)
  - [ ] All three sections visible in nav
- [ ] Check access denial logs:
  - [ ] Verify log entries in logs/access-denial.log
  - [ ] Verify MDC context present (user_id, resource, required_roles)

**⏱️ Time**: 45 minutes

---

## Phase 7: Commit and Documentation

### Step 7.1: Commit Changes

```bash
git add .
git commit -m "feat(rbac): Add RISK, REQ, and SECCHAMPION roles

- Add RISK and SECCHAMPION roles to User.Role enum
- Rename CHAMPION → SECCHAMPION with database migration
- Update @Secured annotations on Risk, Requirements, Vulnerabilities controllers
- Implement AccessDenialLogger for security audit logging
- Update frontend navigation with role-based visibility
- Add role permission helper functions
- Update README.md with comprehensive role documentation

Feature: 025-role-based-access-control
Tests: 47 contract tests, 11 unit tests (all passing)

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

### Step 7.2: Push to Remote

```bash
git push origin 025-role-based-access-control
```

**⏱️ Time**: 5 minutes

---

## Phase 8: Code Review Checklist

Before creating pull request, verify:

**Backend**:
- [ ] User.Role enum has RISK, SECCHAMPION (not CHAMPION), REQ
- [ ] User entity has hasRiskAccess(), hasReqAccess(), hasVulnAccess() methods
- [ ] Database migration script tested in dev
- [ ] AccessDenialLogger service created with MDC context
- [ ] RiskAssessmentController: `@Secured("ADMIN", "RISK", "SECCHAMPION")`
- [ ] RequirementController: `@Secured("ADMIN", "REQ", "SECCHAMPION")`
- [ ] VulnerabilityManagementController: `@Secured("ADMIN", "VULN", "SECCHAMPION")`
- [ ] All contract tests passing
- [ ] No hardcoded "CHAMPION" strings in codebase

**Frontend**:
- [ ] Permission helper functions created (hasRiskAccess, hasReqAccess, etc.)
- [ ] Sidebar.tsx uses helpers for conditional rendering
- [ ] Risk Management visible to ADMIN, RISK, SECCHAMPION
- [ ] Requirements visible to ADMIN, REQ, SECCHAMPION
- [ ] Vuln Management visible to ADMIN, VULN, SECCHAMPION
- [ ] Admin section visible ONLY to ADMIN

**Documentation**:
- [ ] README.md updated with new roles
- [ ] Role permission matrix documented
- [ ] data-model.md complete
- [ ] contracts/ complete

**Security**:
- [ ] @Secured annotations on ALL Risk/Req controllers
- [ ] Frontend role checks are SECONDARY (backend is primary defense)
- [ ] Generic 403 error messages (no role disclosure)
- [ ] Access denials logged with full context
- [ ] No sensitive data in log messages

---

## Troubleshooting

### Issue: Tests fail with "CHAMPION not found"

**Symptom**: Compilation error referencing CHAMPION role

**Solution**: Search codebase for hardcoded "CHAMPION" strings:
```bash
grep -r "CHAMPION" src/ --exclude-dir=test
# Update any references to SECCHAMPION
```

### Issue: Migration script fails with "Duplicate entry"

**Symptom**: SQL error when running migration

**Solution**: Check for existing SECCHAMPION roles:
```sql
SELECT * FROM user_roles WHERE role_name IN ('CHAMPION', 'SECCHAMPION');
-- If SECCHAMPION already exists, manual cleanup required
```

### Issue: Frontend navigation shows wrong items

**Symptom**: User with RISK role sees Requirements menu

**Solution**: Check that Sidebar.tsx uses correct helper functions and that window.currentUser.roles is populated

### Issue: Access denials not logging

**Symptom**: No entries in access-denial.log

**Solution**:
1. Check logback.xml configuration for ACCESS_DENIAL_AUDIT logger
2. Verify AccessDenialLogger is injected (check for @Singleton)
3. Check that AccessDenialLoggingFilter is registered

---

## Success Criteria

**You're done when**:
- ✅ All tests pass (backend + frontend)
- ✅ Database migration completes successfully
- ✅ Manual tests pass for all 3 new roles
- ✅ Access denials are logged with MDC context
- ✅ README.md updated with role documentation
- ✅ Code review checklist complete
- ✅ No CHAMPION references in codebase

**Total Estimated Time**: 6-8 hours

---

## Next Steps

After completing implementation:

1. Create pull request using GitHub CLI:
```bash
gh pr create --title "feat(rbac): Add RISK, REQ, and SECCHAMPION roles" \
             --body "See specs/025-role-based-access-control/ for details"
```

2. Request review from:
   - Backend developer (for @Secured annotations)
   - Security team (for access logging)
   - Frontend developer (for navigation changes)

3. Deploy to staging for QA testing

4. After merge, monitor production logs for unexpected access denials

---

## Related Documents

- [spec.md](./spec.md) - Feature specification
- [data-model.md](./data-model.md) - Entity definitions
- [contracts/role-permission-matrix.md](./contracts/role-permission-matrix.md) - Endpoint authorization
- [contracts/access-denial-logging.md](./contracts/access-denial-logging.md) - Logging specifications
- [plan.md](./plan.md) - Implementation plan

**Questions?** Review research.md for decision rationale or contact architecture team.
