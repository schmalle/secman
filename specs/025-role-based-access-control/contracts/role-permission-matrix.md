# Contract: Role Permission Matrix - Endpoint Authorization

**Feature**: 025-role-based-access-control | **Date**: 2025-10-18
**Related**: [../spec.md](../spec.md) | [../data-model.md](../data-model.md)

## Overview

This contract defines the exact @Secured annotations required for each backend controller endpoint to enforce the role permission matrix defined in [../data-model.md](../data-model.md). This is the authoritative source for which roles can access which endpoints.

**Constitutional Reference**: Principle V (RBAC) - "@Secured annotations required on all endpoints"

---

## Risk Management Endpoints

### RiskAssessmentController

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt`

**Current State**: `@Secured(SecurityRule.IS_AUTHENTICATED)` (allows all authenticated users)

**Required Change**: Update to restrict to ADMIN, RISK, and SECCHAMPION roles

#### Endpoint Authorization Table

| Method | Endpoint | Current | Updated | Allowed Roles |
|--------|----------|---------|---------|---------------|
| GET | `/api/risk-assessments` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| GET | `/api/risk-assessments/{id}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| GET | `/api/risk-assessments/demand/{demandId}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| GET | `/api/risk-assessments/asset/{assetId}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| GET | `/api/risk-assessments/basis/{basisType}/{basisId}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| POST | `/api/risk-assessments` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| POST | `/api/risk-assessments/demand-based` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| POST | `/api/risk-assessments/asset-based` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| PUT | `/api/risk-assessments/{id}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| DELETE | `/api/risk-assessments/{id}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| POST | `/api/risk-assessments/{id}/token` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| POST | `/api/risk-assessments/{id}/notify` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |
| POST | `/api/risk-assessments/{id}/remind` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, RISK, SECCHAMPION |

#### Implementation

**Change Type**: Controller-level annotation

**Before**:
```kotlin
@Controller("/api/risk-assessments")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class RiskAssessmentController(...)
```

**After**:
```kotlin
@Controller("/api/risk-assessments")
@Secured("ADMIN", "RISK", "SECCHAMPION")  // CHANGED
@ExecuteOn(TaskExecutors.BLOCKING)
open class RiskAssessmentController(...)
```

**Rationale**: Controller-level annotation applies to all endpoints. No method-level overrides needed since all risk assessment operations require the same permissions.

**Test Coverage**: Contract test in `RoleAuthorizationContractTest.kt` (see Test Requirements section)

---

### RiskController

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/RiskController.kt`

**Analysis**: This controller likely handles risk-related operations (need to inspect file to confirm endpoints)

**Required Annotation**: `@Secured("ADMIN", "RISK", "SECCHAMPION")`

**Implementation**: Same pattern as RiskAssessmentController

---

## Requirements Endpoints

### RequirementController

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt`

**Current State**: `@Secured(SecurityRule.IS_AUTHENTICATED)` (line 36)

**Required Change**: Update to restrict to ADMIN, REQ, and SECCHAMPION roles

#### Endpoint Authorization Table

| Method | Endpoint | Current | Updated | Allowed Roles |
|--------|----------|---------|---------|---------------|
| GET | `/api/requirements` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| GET | `/api/requirements/{id}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| POST | `/api/requirements` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| PUT | `/api/requirements/{id}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| DELETE | `/api/requirements/{id}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| DELETE | `/api/requirements/all` | ADMIN (line 343) | ✅ No change | ADMIN |
| GET | `/api/requirements/export/docx` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| GET | `/api/requirements/export/xlsx` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| GET | `/api/requirements/export/docx/usecase/{id}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| GET | `/api/requirements/export/xlsx/usecase/{id}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| GET | `/api/requirements/export/docx/translated/{lang}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| GET | `/api/requirements/export/xlsx/translated/{lang}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| GET | `/api/requirements/export/docx/usecase/{id}/translated/{lang}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |
| GET | `/api/requirements/export/xlsx/usecase/{id}/translated/{lang}` | IS_AUTHENTICATED | ✅ Role-specific | ADMIN, REQ, SECCHAMPION |

#### Implementation

**Change Type**: Controller-level annotation with one method-level override

**Before**:
```kotlin
@Controller("/api/requirements")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class RequirementController(...)
```

**After**:
```kotlin
@Controller("/api/requirements")
@Secured("ADMIN", "REQ", "SECCHAMPION")  // CHANGED
open class RequirementController(...) {

    // ... other methods ...

    @Delete("/all")
    @Transactional
    @Secured("ADMIN")  // KEEP - more restrictive than controller level
    open fun deleteAllRequirements(): HttpResponse<*> {
        // ...
    }
}
```

**Rationale**:
- Controller-level annotation applies to most endpoints
- `deleteAllRequirements()` already has method-level `@Secured("ADMIN")` - keep as-is
- Method-level annotations override controller-level (Micronaut security precedence)

---

### NormController

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/NormController.kt`

**Required Annotation**: `@Secured("ADMIN", "REQ", "SECCHAMPION")`

**Endpoints**: All norm management operations

---

### UseCaseController

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/UseCaseController.kt`

**Required Annotation**: `@Secured("ADMIN", "REQ", "SECCHAMPION")`

**Endpoints**: All use case management operations

---

### StandardController

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/StandardController.kt`

**Required Annotation**: `@Secured("ADMIN", "REQ", "SECCHAMPION")`

**Endpoints**: All standard management operations

---

## Vulnerability Endpoints

### VulnerabilityManagementController

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityManagementController.kt`

**Current State**: `@Secured("ADMIN", "VULN")` (line 40)

**Required Change**: Add SECCHAMPION to allowed roles

#### Endpoint Authorization Table

| Method | Endpoint | Current | Updated | Allowed Roles |
|--------|----------|---------|---------|---------------|
| GET | `/api/vulnerabilities/current` | ADMIN, VULN | ✅ Add SECCHAMPION | ADMIN, VULN, SECCHAMPION |
| GET | `/api/vulnerability-exceptions` | ADMIN, VULN | ✅ Add SECCHAMPION | ADMIN, VULN, SECCHAMPION |
| POST | `/api/vulnerability-exceptions` | ADMIN, VULN | ✅ Add SECCHAMPION | ADMIN, VULN, SECCHAMPION |
| PUT | `/api/vulnerability-exceptions/{id}` | ADMIN, VULN | ✅ Add SECCHAMPION | ADMIN, VULN, SECCHAMPION |
| DELETE | `/api/vulnerability-exceptions/{id}` | ADMIN, VULN | ✅ Add SECCHAMPION | ADMIN, VULN, SECCHAMPION |
| GET | `/api/vulnerability-products` | ADMIN, VULN | ✅ Add SECCHAMPION | ADMIN, VULN, SECCHAMPION |
| POST | `/api/vulnerability-exceptions/preview` | ADMIN, VULN | ✅ Add SECCHAMPION | ADMIN, VULN, SECCHAMPION |

#### Implementation

**Change Type**: Controller-level annotation update

**Before**:
```kotlin
@Controller
@Secured("ADMIN", "VULN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class VulnerabilityManagementController(...)
```

**After**:
```kotlin
@Controller
@Secured("ADMIN", "VULN", "SECCHAMPION")  // CHANGED - added SECCHAMPION
@ExecuteOn(TaskExecutors.BLOCKING)
open class VulnerabilityManagementController(...)
```

**Rationale**: SECCHAMPION role should have same vulnerability access as VULN role (per FR-004 in spec.md)

---

## Other Controllers (No Changes Required)

These controllers already have appropriate RBAC and do not need changes for this feature:

### AssetController
- Current: `@Secured(SecurityRule.IS_AUTHENTICATED)` with workgroup filtering
- Status: ✅ No change - assets accessible to all authenticated users (workgroup-scoped)

### DemandController
- Current: `@Secured(SecurityRule.IS_AUTHENTICATED)`
- Status: ✅ No change - demands accessible to all authenticated users

### WorkgroupController
- Current: `@Secured("ADMIN")` (assumed based on pattern)
- Status: ✅ No change - admin-only access

### UserController
- Current: `@Secured("ADMIN")` (assumed based on pattern)
- Status: ✅ No change - admin-only access

### ReleaseController
- Current: `@Secured("ADMIN", "RELEASE_MANAGER")` or similar
- Status: ✅ No change - releases accessible to specific roles already defined

### ImportController
- Current: Mixed (@Secured annotations per endpoint)
- Status: ✅ No change - import operations have specific role requirements

---

## Error Response Contract

All endpoints with @Secured annotations must return consistent 403 responses for unauthorized access.

**HTTP Status Code**: 403 Forbidden

**Response Body**:
```json
{
    "message": "You don't have permission to access this resource. Contact your administrator."
}
```

**Headers**:
```
Content-Type: application/json
WWW-Authenticate: Bearer realm="secman"
```

**Constraints**:
- ❌ DO NOT reveal which roles are required in error message (security: prevents role enumeration)
- ❌ DO NOT expose internal role names or system details
- ✅ DO provide generic message directing user to administrator
- ✅ DO log access denial with full context (see access-denial-logging.md)

**Micronaut Default Behavior**:
Micronaut's `@Secured` annotation automatically returns 403 with a generic message. No custom error handling required unless we want to customize the message further.

**Custom Error Handler** (optional enhancement):
```kotlin
@Singleton
@Replaces(DefaultUnauthorizedRejectionUriProvider::class)
class CustomUnauthorizedHandler : UnauthorizedRejectionUriProvider {
    override fun getUnauthorizedRedirectUri(request: HttpRequest<*>): Optional<URI> {
        // Return custom error response for API requests
        return Optional.empty()
    }
}
```

---

## Test Requirements

### Contract Tests

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt`

**Purpose**: Verify @Secured annotations enforce correct role access

#### Test Structure

```kotlin
@MicronautTest
class RoleAuthorizationContractTest {

    @Inject
    @Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var tokenGenerator: JwtTokenGenerator

    // Risk Management Tests
    @Test
    fun `GET risk-assessments - should allow ADMIN`() {
        val token = tokenGenerator.generateToken("admin", listOf("ADMIN"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/risk-assessments")
                .bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `GET risk-assessments - should allow RISK`() {
        val token = tokenGenerator.generateToken("riskuser", listOf("RISK"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/risk-assessments")
                .bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `GET risk-assessments - should allow SECCHAMPION`() {
        val token = tokenGenerator.generateToken("champion", listOf("SECCHAMPION"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/risk-assessments")
                .bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `GET risk-assessments - should deny REQ role`() {
        val token = tokenGenerator.generateToken("requser", listOf("REQ"))
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/risk-assessments")
                    .bearerAuth(token),
                String::class.java
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `GET risk-assessments - should deny USER role`() {
        val token = tokenGenerator.generateToken("user", listOf("USER"))
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/risk-assessments")
                    .bearerAuth(token),
                String::class.java
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    // Requirements Tests
    @Test
    fun `GET requirements - should allow ADMIN`() {
        val token = tokenGenerator.generateToken("admin", listOf("ADMIN"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/requirements")
                .bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `GET requirements - should allow REQ`() {
        val token = tokenGenerator.generateToken("requser", listOf("REQ"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/requirements")
                .bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `GET requirements - should allow SECCHAMPION`() {
        val token = tokenGenerator.generateToken("champion", listOf("SECCHAMPION"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/requirements")
                .bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `GET requirements - should deny RISK role`() {
        val token = tokenGenerator.generateToken("riskuser", listOf("RISK"))
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/requirements")
                    .bearerAuth(token),
                String::class.java
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    // Vulnerability Tests
    @Test
    fun `GET vulnerabilities current - should allow SECCHAMPION`() {
        val token = tokenGenerator.generateToken("champion", listOf("SECCHAMPION"))
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/vulnerabilities/current")
                .bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    // Multi-role tests
    @Test
    fun `SECCHAMPION should have combined access to Risk, Req, and Vuln`() {
        val token = tokenGenerator.generateToken("champion", listOf("SECCHAMPION"))

        // Should access risks
        var response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/risk-assessments").bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)

        // Should access requirements
        response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/requirements").bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)

        // Should access vulnerabilities
        response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/vulnerabilities/current").bearerAuth(token),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `SECCHAMPION should NOT access admin endpoints`() {
        val token = tokenGenerator.generateToken("champion", listOf("SECCHAMPION"))
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/workgroups").bearerAuth(token),
                String::class.java
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    // Access denial logging test
    @Test
    fun `should log access denial when unauthorized`() {
        // This test would use LogCaptor or similar to verify logging
        // See access-denial-logging.md for full test implementation
    }
}
```

**Test Coverage Requirements**:
- ✅ Every role + endpoint combination in permission matrix
- ✅ Multi-role users (e.g., user with ADMIN + RISK)
- ✅ SECCHAMPION access to Risk, Req, Vuln
- ✅ SECCHAMPION denied access to Admin
- ✅ Access denial logging (integration test)

**Estimated Test Count**: ~40-50 contract tests

---

## Implementation Checklist

**Risk Management Controllers**:
- [ ] Update RiskAssessmentController: `@Secured("ADMIN", "RISK", "SECCHAMPION")`
- [ ] Update RiskController: `@Secured("ADMIN", "RISK", "SECCHAMPION")`
- [ ] Write contract tests for risk endpoints

**Requirements Controllers**:
- [ ] Update RequirementController: `@Secured("ADMIN", "REQ", "SECCHAMPION")`
- [ ] Update NormController: `@Secured("ADMIN", "REQ", "SECCHAMPION")`
- [ ] Update UseCaseController: `@Secured("ADMIN", "REQ", "SECCHAMPION")`
- [ ] Update StandardController: `@Secured("ADMIN", "REQ", "SECCHAMPION")`
- [ ] Write contract tests for requirements endpoints

**Vulnerability Controllers**:
- [ ] Update VulnerabilityManagementController: `@Secured("ADMIN", "VULN", "SECCHAMPION")`
- [ ] Write contract tests for vulnerability endpoints

**Testing**:
- [ ] Run all contract tests - verify green
- [ ] Manual test with Postman/Insomnia - verify 403 responses
- [ ] Check access denial logs - verify context present
- [ ] Integration test - verify workgroup filtering still works

**Documentation**:
- [ ] Update README.md with new roles (see quickstart.md)
- [ ] Update API documentation (Swagger/OpenAPI if applicable)

---

## Security Considerations

**Principle: Defense in Depth**
- ✅ Backend @Secured annotations are PRIMARY defense (cannot be bypassed)
- ✅ Frontend role checks are SECONDARY (UX convenience only)
- ✅ Never rely solely on frontend for access control

**Attack Scenarios**:
1. **Scenario**: User modifies JWT token to add RISK role
   - **Mitigation**: JWT signature verification prevents tampering

2. **Scenario**: User sends direct API request bypassing frontend
   - **Mitigation**: @Secured annotations on backend enforce access

3. **Scenario**: User guesses endpoint URLs
   - **Mitigation**: Generic 403 response doesn't reveal role requirements

4. **Scenario**: Admin role renamed in database
   - **Mitigation**: Enum-based roles (not strings) prevent role injection

**Compliance**:
- ✅ FR-008: Role-based access control at API endpoint level
- ✅ FR-013: Generic error messages
- ✅ FR-014: Access denials logged with full context
- ✅ Constitution Principle I: Security-First
- ✅ Constitution Principle V: RBAC (@Secured on endpoints)

---

## Related Documents

- [../data-model.md](../data-model.md) - Role enum definition and permission matrix
- [./access-denial-logging.md](./access-denial-logging.md) - Logging specifications
- [../quickstart.md](../quickstart.md) - Implementation guide
- [../spec.md](../spec.md) - Feature specification

**Questions/Issues**: Contact security team for @Secured annotation review before merge.
